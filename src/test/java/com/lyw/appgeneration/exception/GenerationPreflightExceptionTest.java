package com.lyw.appgeneration.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationPreflightExceptionTest {

    @Test
    void businessPreflightPreservesSafeCodeMessageAndCause() {
        BusinessException cause = new BusinessException(
                ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");

        GenerationPreflightException exception =
                GenerationPreflightException.business(
                        ErrorCode.NO_AUTH_ERROR.getCode(),
                        "无权限访问该应用", cause);

        assertEquals(GenerationPreflightException.Kind.BUSINESS,
                exception.kind());
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.code());
        assertEquals("无权限访问该应用", exception.safeMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void systemPreflightNeverExposesCauseMessage() {
        IllegalStateException cause = new IllegalStateException(
                "数据库密码和内部堆栈");

        GenerationPreflightException exception =
                GenerationPreflightException.system(cause);

        assertEquals(GenerationPreflightException.Kind.SYSTEM, exception.kind());
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.code());
        assertEquals("生成服务暂时不可用，请稍后重试。",
                exception.safeMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void invalidBusinessContractIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                GenerationPreflightException.business(0, "有效文案", null));
        assertThrows(IllegalArgumentException.class, () ->
                GenerationPreflightException.business(1, "  ", null));
    }
}
