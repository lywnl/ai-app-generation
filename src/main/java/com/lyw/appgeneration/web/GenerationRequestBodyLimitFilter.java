package com.lyw.appgeneration.web;

import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在 Jackson 反序列化前限制精确生成 POST 的完整请求体字节数。 */
@Component
public final class GenerationRequestBodyLimitFilter
        extends OncePerRequestFilter {

    public static final int MAX_REQUEST_BYTES = 262_144;

    private static final int READ_BUFFER_BYTES = 8_192;

    private final GenerationSsePreflightWriter preflightWriter;

    public GenerationRequestBodyLimitFilter(
            GenerationSsePreflightWriter preflightWriter) {
        this.preflightWriter = preflightWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !preflightWriter.isGenerationRequest(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isJson(request)) {
            rejectBusiness(request, response,
                    "请求必须使用application/json");
            return;
        }
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            rejectBusiness(request, response, "请求内容过大");
            return;
        }
        byte[] body;
        try {
            body = readBounded(request);
        } catch (RequestBodyTooLargeException exception) {
            rejectBusiness(request, response, "请求内容过大");
            return;
        } catch (IOException exception) {
            rejectSystem(request, response, exception);
            return;
        }
        filterChain.doFilter(
                new CachedGenerationRequestWrapper(request, body), response);
    }

    private boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.getType().equals(
                    mediaType.getType())
                    && MediaType.APPLICATION_JSON.getSubtype().equals(
                    mediaType.getSubtype())
                    && (mediaType.getCharset() == null
                    || StandardCharsets.UTF_8.equals(mediaType.getCharset()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] readBounded(HttpServletRequest request)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(Math.max(request.getContentLength(), 0),
                        MAX_REQUEST_BYTES));
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        try (var input = request.getInputStream()) {
            int read;
            int total = 0;
            while ((read = input.read(buffer, 0, Math.min(buffer.length,
                    MAX_REQUEST_BYTES + 1 - total))) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new RequestBodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private void rejectBusiness(
            HttpServletRequest request,
            HttpServletResponse response,
            String message) throws IOException {
        writeOrRejectHttp(request, response,
                GenerationPreflightException.business(
                        ErrorCode.PARAMS_ERROR.getCode(), message, null));
    }

    private void rejectSystem(
            HttpServletRequest request,
            HttpServletResponse response,
            IOException cause) throws IOException {
        writeOrRejectHttp(request, response,
                GenerationPreflightException.system(cause));
    }

    private void writeOrRejectHttp(
            HttpServletRequest request,
            HttpServletResponse response,
            GenerationPreflightException error) throws IOException {
        if (!preflightWriter.writeIfApplicable(request, response, error)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private static final class RequestBodyTooLargeException
            extends IOException {
    }
}
