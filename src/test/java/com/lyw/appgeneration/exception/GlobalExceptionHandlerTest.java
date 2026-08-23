package com.lyw.appgeneration.exception;

import com.lyw.appgeneration.web.GenerationSsePreflightWriter;
import com.lyw.appgeneration.web.GenerationRequestBodyLimitFilter;
import com.lyw.appgeneration.controller.AppController;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class GlobalExceptionHandlerTest {

    @Test
    void runtimeExceptionHandlerNeverWritesSseResponseDirectly()
            throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                writer());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/app/chat/gen/code");
        request.addHeader("Accept", "text/event-stream");

        MockHttpServletResponse response = new MockHttpServletResponse();

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        try {
            var result = handler.runtimeExceptionHandler(
                    new RuntimeException("boom"));
            assertEquals("", response.getContentAsString());
            assertEquals(50000, result.getCode());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void malformed生成Json由唯一Writer归一为Sse前置错误()
            throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                writer(registry));
        MockHttpServletRequest request = generationRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = handler.httpMessageNotReadableExceptionHandler(
                new HttpMessageNotReadableException("不得泄漏的Jackson细节"),
                request, response);

        assertNull(result);
        assertEquals(200, response.getStatus());
        assertEquals("text/event-stream;charset=UTF-8",
                response.getContentType());
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        String body = response.getContentAsString();
        assertEquals(1, count(body, "event: business-error"));
        assertEquals(1, count(body, "event: done"));
        assertTrue(body.contains("\"protocol\":\"generation-error/v1\""));
        assertTrue(body.contains("\"kind\":\"BUSINESS\""));
        assertTrue(body.contains("\"code\":40000"));
        assertTrue(body.contains("event: done\n"
                + "data: {\"protocol\":\"generation-stream/v1\","
                + "\"sequence\":1}"));
        assertFalse(body.contains("Jackson"));

        var duplicate = handler.httpMessageNotReadableExceptionHandler(
                new HttpMessageNotReadableException("重复异常"),
                request, response);
        assertNull(duplicate);
        assertEquals(1, count(response.getContentAsString(),
                "event: business-error"));
        assertEquals(1.0, registry.get(
                        "generation_sse_protocol_results_total")
                .tags("result", "business_error",
                        "error_kind", "business").counter().count());
    }

    @Test
    void malformedJson经过真实Mvc反序列化链仍返回唯一Sse终态()
            throws Exception {
        GenerationSsePreflightWriter writer = writer(
                new SimpleMeterRegistry());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AppController())
                .setControllerAdvice(new GlobalExceptionHandler(writer))
                .addFilters(new GenerationRequestBodyLimitFilter(writer))
                .build();

        var response = mockMvc.perform(post(
                        "/api/app/chat/gen/code")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"appId\":\"7\",\"message\":"))
                .andReturn().getResponse();

        assertEquals(200, response.getStatus());
        assertEquals("text/event-stream;charset=UTF-8",
                response.getContentType());
        String body = response.getContentAsString();
        assertEquals(1, count(body, "event: business-error"));
        assertEquals(1, count(body, "event: done"));
        assertTrue(body.indexOf("event: business-error")
                < body.indexOf("event: done"));
        assertTrue(body.contains("event: done\n"
                + "data: {\"protocol\":\"generation-stream/v1\","
                + "\"sequence\":1}"));
    }

    @Test
    void 普通Rest的malformedJson仍返回BaseResponse且不直接写Sse()
            throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                writer());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContextPath("/api");
        request.setRequestURI("/api/app/update");
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = handler.httpMessageNotReadableExceptionHandler(
                new HttpMessageNotReadableException("malformed"),
                request, response);

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), result.getCode());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void 已提交的生成响应遇到malformed异常时不得二次写入()
            throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                writer());
        MockHttpServletRequest request = generationRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        var result = handler.httpMessageNotReadableExceptionHandler(
                new HttpMessageNotReadableException("迟到异常"),
                request, response);

        assertNull(result);
        assertEquals("", response.getContentAsString());
    }

    private MockHttpServletRequest generationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContextPath("/api");
        request.setRequestURI("/api/app/chat/gen/code");
        request.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        return request;
    }

    private GenerationSsePreflightWriter writer() {
        return writer(new SimpleMeterRegistry());
    }

    private GenerationSsePreflightWriter writer(
            SimpleMeterRegistry registry) {
        return new GenerationSsePreflightWriter(
                new AppLifecycleMetricsCollector(registry));
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
}
