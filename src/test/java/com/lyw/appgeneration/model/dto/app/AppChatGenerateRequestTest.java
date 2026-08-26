package com.lyw.appgeneration.model.dto.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppChatGenerateRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appId只接受Json字符串形式的Long范围内正整数() throws Exception {
        AppChatGenerateRequest request = objectMapper.readValue(
                "{\"appId\":\"9223372036854775807\",\"message\":\"需求\",\"generationId\":\"00000000-0000-4000-8000-000000000007\"}",
                AppChatGenerateRequest.class);

        assertEquals(Long.MAX_VALUE, request.requireAppId());
        assertEquals(7L, new AppChatGenerateRequest("0007", "需求")
                .requireAppId());
        assertParamsError(() -> objectMapper.readValue(
                "{\"appId\":9223372036854775807,\"message\":\"需求\"}",
                AppChatGenerateRequest.class));
        assertParamsError(() -> new AppChatGenerateRequest("-1", "需求")
                .requireAppId());
        assertParamsError(() -> new AppChatGenerateRequest("+1", "需求")
                .requireAppId());
        assertParamsError(() -> new AppChatGenerateRequest(" 1", "需求")
                .requireAppId());
        assertParamsError(() -> new AppChatGenerateRequest("1.0", "需求")
                .requireAppId());
        assertParamsError(() -> new AppChatGenerateRequest("1e2", "需求")
                .requireAppId());
        assertParamsError(() -> new AppChatGenerateRequest(
                "9223372036854775808", "需求").requireAppId());
        assertParamsError(() -> new AppChatGenerateRequest("0", "需求")
                .requireAppId());
        assertParamsError(() -> new AppChatGenerateRequest("", "需求")
                .requireAppId());
    }

    @Test
    void message按UnicodeCodePoint限制且保留原始正文() {
        String supplementary = "\uD83D\uDE00";
        String maximum = supplementary.repeat(
                AppChatGenerateRequest.MAX_MESSAGE_CODE_POINTS);
        AppChatGenerateRequest accepted = new AppChatGenerateRequest(
                "7", maximum);

        assertEquals(maximum, accepted.requireMessage());
        assertParamsError(() -> new AppChatGenerateRequest(
                "7", maximum + supplementary).requireMessage());
        assertParamsError(() -> new AppChatGenerateRequest("7", null)
                .requireMessage());
        assertParamsError(() -> new AppChatGenerateRequest("7", " \n\t")
                .requireMessage());
    }

    private void assertParamsError(ThrowingAction action) {
        Exception exception = assertThrows(Exception.class, action::run);
        Throwable actual = exception;
        while (actual.getCause() != null
                && !(actual instanceof BusinessException)) {
            actual = actual.getCause();
        }
        BusinessException businessException = assertInstanceOf(
                BusinessException.class, actual);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),
                businessException.getCode());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
