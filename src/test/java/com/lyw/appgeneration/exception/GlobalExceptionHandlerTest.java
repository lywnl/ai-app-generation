package com.lyw.appgeneration.exception;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class GlobalExceptionHandlerTest {

    @Test
    void runtimeExceptionHandler_shouldNotThrow_whenSseOutputStreamAlreadyUsed() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/app/chat/gen/code");
        request.addHeader("Accept", "text/event-stream");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("occupied".getBytes());
        response.getOutputStream().flush();

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        try {
            assertDoesNotThrow(() -> handler.runtimeExceptionHandler(new RuntimeException("boom")));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
