package com.lyw.appgeneration.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void runtimeExceptionHandlerNeverWritesSseResponseDirectly()
            throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
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
}
