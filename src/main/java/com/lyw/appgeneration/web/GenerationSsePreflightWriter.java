package com.lyw.appgeneration.web;

import com.lyw.appgeneration.exception.GenerationPreflightException;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在 Controller 调用前把生成入口错误写成唯一一组安全 SSE 事件。 */
@Component
public final class GenerationSsePreflightWriter {

    public static final String GENERATION_PATH = "/app/chat/gen/code";
    public static final String WRITTEN_ATTRIBUTE =
            GenerationSsePreflightWriter.class.getName() + ".WRITTEN";

    private final AppLifecycleMetricsCollector metricsCollector;

    public GenerationSsePreflightWriter(
            AppLifecycleMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    public boolean writeIfApplicable(
            HttpServletRequest request,
            HttpServletResponse response,
            GenerationPreflightException error) throws IOException {
        if (!isApplicable(request, response)) {
            return false;
        }
        request.setAttribute(WRITTEN_ATTRIBUTE, Boolean.TRUE);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        metricsCollector.startSseProtocolObservation().complete(
                AppLifecycleMetricsCollector.SseProtocolResult.BUSINESS_ERROR,
                error.kind() == GenerationPreflightException.Kind.BUSINESS
                        ? AppLifecycleMetricsCollector.SseErrorKind.BUSINESS
                        : AppLifecycleMetricsCollector.SseErrorKind.SYSTEM);
        response.getWriter().write(encode(error));
        response.getWriter().flush();
        return true;
    }

    public boolean isGenerationRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().equals(
                request.getContextPath() + GENERATION_PATH);
    }

    public boolean shouldSkipDuplicateWrite(
            HttpServletRequest request, HttpServletResponse response) {
        return isGenerationRequest(request)
                && (response.isCommitted() || Boolean.TRUE.equals(
                request.getAttribute(WRITTEN_ATTRIBUTE)));
    }

    private boolean isApplicable(
            HttpServletRequest request, HttpServletResponse response) {
        if (!isGenerationRequest(request)
                || response.isCommitted()
                || Boolean.TRUE.equals(request.getAttribute(
                WRITTEN_ATTRIBUTE))) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept == null) {
            return false;
        }
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(type -> MediaType.TEXT_EVENT_STREAM.getType()
                            .equals(type.getType())
                            && MediaType.TEXT_EVENT_STREAM.getSubtype()
                            .equals(type.getSubtype()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String encode(GenerationPreflightException error) {
        return new GenerationSseEncoder().preflightWire(error);
    }
}
