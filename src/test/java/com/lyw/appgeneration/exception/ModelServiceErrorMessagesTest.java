package com.lyw.appgeneration.exception;

import com.lyw.appgeneration.service.VueTurnModeRoutingException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.internal.ExceptionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ModelServiceErrorMessagesTest {

    @Test
    void DeepSeek余额错误经过真实SDK和异步分类包装后仍可识别() {
        HttpException original = new HttpException(402, """
                {"error":{"message":"Insufficient Balance","type":"unknown_error","code":"invalid_request_error"},
                 "request_id":"private-request","api_key":"private-test-key"}
                """);
        RuntimeException mapped = new ExceptionMapper.DefaultExceptionMapper().mapException(original);
        var wrapped = new CompletionException(new ExecutionException(
                new VueTurnModeRoutingException("分类模型调用失败", mapped)));

        assertEquals("InvalidRequestException", mapped.getClass().getSimpleName());
        assertTrue(ModelServiceErrorMessages.isInsufficientBalance(wrapped));
        assertEquals("模型服务余额不足，请充值后重试。",
                ModelServiceErrorMessages.messageOrDefault(wrapped, "系统内部错误"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":{\"code\":\"insufficient_balance\",\"message\":\"PRIVATE\"}}",
            "{\"error\":{\"type\":\"InsufficientBalance\"}}",
            "{\"error\":{\"message\":\"Insufficient Balance (request_id: test-request)\",\"type\":\"insufficient_balance_error\",\"param\":null,\"code\":\"invalid_request_error\"}}",
            "{\"code\":\"Arrearage\",\"message\":\"PRIVATE\"}",
            "{\"error\":{\"message\":\"Insufficient Balance\"}}",
            "status code: 400; body: {\"error\":{\"message\":\"余额不足。\"}}",
            "Insufficient Balance"
    })
    void 只接受SDK中明确的余额错误字段或消息(String message) {
        assertTrue(ModelServiceErrorMessages.isInsufficientBalance(new LangChain4jException(message)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 408, 429, 500, 503})
    void 普通HTTP错误不会仅因状态码被识别为余额不足(int status) {
        var error = new HttpException(status, "{\"error\":{\"message\":\"PRIVATE\",\"code\":\"rate_limit_exceeded\"}}");
        assertFalse(ModelServiceErrorMessages.isInsufficientBalance(error));
        assertEquals("系统错误", ModelServiceErrorMessages.messageOrDefault(error, "系统错误"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":{\"code\":\"insufficient_quota\"}}",
            "{\"error\":{\"code\":\"context_length_exceeded\"}}",
            "{\"prompt\":\"余额不足\",\"error\":{\"message\":\"bad request\"}}",
            "{\"error\":{\"message\":\"The user asked about insufficient balance\"}}",
            "{\"choices\":[{\"message\":{\"content\":\"Insufficient Balance\"}}]}",
            "invalid JSON", "{\"error\":", ""
    })
    void 模糊配额错误及非错误文本保持未知(String message) {
        assertFalse(ModelServiceErrorMessages.isInsufficientBalance(new LangChain4jException(message)));
    }

    @Test
    void 非模型异常中的余额字样和被抑制异常不改变提示() {
        var error = new IllegalStateException("Insufficient Balance {\"error\":{\"code\":\"insufficient_balance\"}}");
        error.addSuppressed(new HttpException(402, "Insufficient Balance"));
        assertEquals("系统内部错误", ModelServiceErrorMessages.messageOrDefault(error, "系统内部错误"));
        assertFalse(ModelServiceErrorMessages.isInsufficientBalance(null));
        assertFalse(ModelServiceErrorMessages.isInsufficientBalance(new LangChain4jException((String) null)));
    }

    @Test
    void 循环异常链和过深或过长输入有界处理() {
        RuntimeException first = new RuntimeException();
        RuntimeException second = new RuntimeException();
        first.initCause(second);
        second.initCause(first);
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertFalse(ModelServiceErrorMessages.isInsufficientBalance(first)));
        RuntimeException deep = new HttpException(402, "Insufficient Balance");
        for (int i = 0; i < 40; i++) {
            deep = new RuntimeException(deep);
        }
        assertFalse(ModelServiceErrorMessages.isInsufficientBalance(deep));
        assertFalse(ModelServiceErrorMessages.isInsufficientBalance(new LangChain4jException(
                "x".repeat(20_000) + "{\"error\":{\"code\":\"insufficient_balance\"}}")));
        assertTrue(ModelServiceErrorMessages.isInsufficientBalance(new HttpException(402, "x".repeat(20_000))));
    }
}
