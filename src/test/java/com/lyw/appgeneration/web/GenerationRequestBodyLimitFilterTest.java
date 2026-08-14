package com.lyw.appgeneration.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRequestBodyLimitFilterTest {

    private GenerationRequestBodyLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GenerationRequestBodyLimitFilter(
                new GenerationSsePreflightWriter(
                        new AppLifecycleMetricsCollector(
                                new SimpleMeterRegistry())));
    }

    @Test
    void 精确上限被完整缓存并继续过滤链() throws Exception {
        byte[] body = new byte[GenerationRequestBodyLimitFilter
                .MAX_REQUEST_BYTES];
        MockHttpServletRequest request = generationRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        CachedGenerationRequestWrapper cached = assertInstanceOf(
                CachedGenerationRequestWrapper.class, chain.getRequest());
        assertArrayEquals(body, cached.getInputStream().readAllBytes());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void 多一字节和声明过大的正文均在进入业务前拒绝() throws Exception {
        byte[] oversized = new byte[GenerationRequestBodyLimitFilter
                .MAX_REQUEST_BYTES + 1];
        assertRejected(generationRequest(oversized));

        MockHttpServletRequest declaredOversized = generationRequest(
                "{}".getBytes(StandardCharsets.UTF_8));
        declaredOversized = new DeclaredLengthRequest(
                GenerationRequestBodyLimitFilter.MAX_REQUEST_BYTES + 1L);
        configureGenerationRequest(declaredOversized);
        assertRejected(declaredOversized);
    }

    @Test
    void 缺失长度和chunked请求仍按真实完整字节拒绝() throws Exception {
        byte[] oversized = new byte[GenerationRequestBodyLimitFilter
                .MAX_REQUEST_BYTES + 1];
        UnknownLengthRequest request = new UnknownLengthRequest(oversized);
        configureGenerationRequest(request);
        request.addHeader("Transfer-Encoding", "chunked");

        assertRejected(request);
    }

    @Test
    void 超大流最多只预读上限加一字节() throws Exception {
        CountingRequest request = new CountingRequest(
                GenerationRequestBodyLimitFilter.MAX_REQUEST_BYTES * 2);
        configureGenerationRequest(request);

        assertRejected(request);

        assertEquals(GenerationRequestBodyLimitFilter.MAX_REQUEST_BYTES + 1,
                request.readBytes());
    }

    @Test
    void 合法Json后的超限尾随空白不能绕过完整预读() throws Exception {
        byte[] prefix = "{\"appId\":\"7\",\"message\":\"需求\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[GenerationRequestBodyLimitFilter
                .MAX_REQUEST_BYTES + 1];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        java.util.Arrays.fill(body, prefix.length, body.length, (byte) ' ');

        assertRejected(generationRequest(body));
    }

    @Test
    void 多字节Utf8按字节而不是Java字符限制() throws Exception {
        String jsonPrefix = "{\"appId\":\"7\",\"message\":\"";
        String jsonSuffix = "\"}";
        String body = jsonPrefix + "中".repeat(90_000) + jsonSuffix;
        assertTrue(body.length()
                < GenerationRequestBodyLimitFilter.MAX_REQUEST_BYTES);
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length
                > GenerationRequestBodyLimitFilter.MAX_REQUEST_BYTES);

        assertRejected(generationRequest(
                body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 非Json与读取异常只输出一组安全前置事件() throws Exception {
        MockHttpServletRequest wrongType = generationRequest(
                "{}".getBytes(StandardCharsets.UTF_8));
        wrongType.setContentType(MediaType.TEXT_PLAIN_VALUE);
        assertRejected(wrongType);

        MockHttpServletRequest readFailure = new FailingReadRequest();
        configureGenerationRequest(readFailure);
        MockHttpServletResponse response = assertRejected(readFailure);
        assertFalse(response.getContentAsString().contains("磁盘密钥"));
    }

    @Test
    void 只接受标准Json媒体类型和Utf8字符集() throws Exception {
        MockHttpServletRequest vendorJson = generationRequest(
                "{}".getBytes(StandardCharsets.UTF_8));
        vendorJson.setContentType("application/problem+json");
        assertRejected(vendorJson);

        MockHttpServletRequest nonUtf8 = generationRequest(
                "{}".getBytes(StandardCharsets.UTF_8));
        nonUtf8.setContentType("application/json;charset=ISO-8859-1");
        assertRejected(nonUtf8);

        MockHttpServletRequest noCharset = generationRequest(
                "{}".getBytes(StandardCharsets.UTF_8));
        noCharset.setContentType("application/json");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(noCharset, new MockHttpServletResponse(), chain);
        assertInstanceOf(CachedGenerationRequestWrapper.class,
                chain.getRequest());
    }

    @Test
    void 畸形Accept头不得让安全Writer再次抛错() throws Exception {
        MockHttpServletRequest request = generationRequest(
                new byte[GenerationRequestBodyLimitFilter
                        .MAX_REQUEST_BYTES + 1]);
        request.removeHeader("Accept");
        request.addHeader("Accept", "text/event-stream;bad==value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void 通配Accept不能冒充明确的Sse响应契约() throws Exception {
        MockHttpServletRequest request = generationRequest(
                new byte[GenerationRequestBodyLimitFilter
                        .MAX_REQUEST_BYTES + 1]);
        request.removeHeader("Accept");
        request.addHeader("Accept", MediaType.ALL_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void 普通Rest接口和非Post请求不被正文门禁误拦截() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockHttpServletRequest ordinary = generationRequest(
                new byte[GenerationRequestBodyLimitFilter.MAX_REQUEST_BYTES + 1]);
        ordinary.setRequestURI("/api/app/update");
        FilterChain chain = (request, response) -> calls.incrementAndGet();

        filter.doFilter(ordinary, new MockHttpServletResponse(), chain);
        assertEquals(1, calls.get());

        MockHttpServletRequest get = generationRequest(new byte[0]);
        get.setMethod("GET");
        FilterChain getChain = (request, response) -> calls.incrementAndGet();
        filter.doFilter(get, new MockHttpServletResponse(), getChain);
        assertEquals(2, calls.get());
    }

    @Test
    void 缓存包装器的输入流和Reader均可重复读取同一Utf8正文()
            throws Exception {
        byte[] body = "{\"message\":\"中文😀\"}"
                .getBytes(StandardCharsets.UTF_8);
        CachedGenerationRequestWrapper wrapper =
                new CachedGenerationRequestWrapper(
                        generationRequest(body), body);

        assertArrayEquals(body, wrapper.getInputStream().readAllBytes());
        assertArrayEquals(body, wrapper.getInputStream().readAllBytes());
        assertEquals(new String(body, StandardCharsets.UTF_8),
                wrapper.getReader().readLine());
        assertEquals(body.length, wrapper.getContentLength());
        assertEquals(body.length, wrapper.getContentLengthLong());
    }

    private MockHttpServletResponse assertRejected(
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "拒绝后不得进入 Controller/Service");
        assertEquals(200, response.getStatus());
        assertEquals("text/event-stream;charset=UTF-8",
                response.getContentType());
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        String content = response.getContentAsString();
        assertEquals(1, count(content, "event: business-error"));
        assertEquals(1, count(content, "event: done"));
        assertTrue(content.indexOf("event: business-error")
                < content.indexOf("event: done"));
        return response;
    }

    private MockHttpServletRequest generationRequest(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        configureGenerationRequest(request);
        request.setContent(content);
        return request;
    }

    private void configureGenerationRequest(MockHttpServletRequest request) {
        request.setMethod("POST");
        request.setContextPath("/api");
        request.setRequestURI("/api/app/chat/gen/code");
        request.setContentType("application/json;charset=UTF-8");
        request.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private int count(String source, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }

    private static final class UnknownLengthRequest
            extends MockHttpServletRequest {

        private final byte[] content;

        private UnknownLengthRequest(byte[] content) {
            this.content = content;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream(content, null);
        }
    }

    private static final class DeclaredLengthRequest
            extends MockHttpServletRequest {

        private final long contentLength;

        private DeclaredLengthRequest(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override
        public int getContentLength() {
            return Math.toIntExact(contentLength);
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            throw new AssertionError("声明长度超限时不得读取正文");
        }
    }

    private static final class FailingReadRequest
            extends MockHttpServletRequest {

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream(new byte[0], new IOException("磁盘密钥"));
        }
    }

    private static final class CountingRequest
            extends MockHttpServletRequest {

        private final int totalBytes;
        private final AtomicInteger readBytes = new AtomicInteger();

        private CountingRequest(int totalBytes) {
            this.totalBytes = totalBytes;
        }

        private int readBytes() {
            return readBytes.get();
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return readBytes.get() >= totalBytes;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    if (isFinished()) {
                        return -1;
                    }
                    readBytes.incrementAndGet();
                    return 'x';
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    if (isFinished()) {
                        return -1;
                    }
                    int read = Math.min(length,
                            totalBytes - readBytes.get());
                    java.util.Arrays.fill(
                            bytes, offset, offset + read, (byte) 'x');
                    readBytes.addAndGet(read);
                    return read;
                }
            };
        }
    }

    private static ServletInputStream stream(
            byte[] content, IOException failure) {
        ByteArrayInputStream input = new ByteArrayInputStream(content);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return input.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return input.read(bytes, offset, length);
            }
        };
    }
}
