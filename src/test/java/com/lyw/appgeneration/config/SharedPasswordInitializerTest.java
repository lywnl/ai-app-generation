package com.lyw.appgeneration.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class SharedPasswordInitializerTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Abc1234", "1234567"})
    void 空值或不足八位时拒绝启动(String password) {
        assertRejected(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcd123!", "Abcd123$", " Abcd1234", "Abcd1234 ",
            "Abcd123\n", "Abcd123\t", "密码Abcd1234", "Ａbcd1234", "Abcd123'", "Abcd123\\"})
    void 非法字符不被修剪或接受(String password) {
        assertRejected(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", "12345678", "Abcd1234", "Ab1_-.23", "________"})
    void 允许规定字符且不强制组合(String password) {
        try (var context = context(password)) {
            assertDoesNotThrow(() -> new SharedPasswordInitializer().initialize(context));
        }
    }

    private void assertRejected(String password) {
        try (var context = context(password)) {
            var error = assertThrows(IllegalStateException.class,
                    () -> new SharedPasswordInitializer().initialize(context));
            assertTrue(error.getMessage().contains("INFRA_SHARED_PASSWORD"));
            if (password != null && !password.isEmpty()) {
                assertFalse(error.getMessage().contains(password));
            }
            assertFalse(context.isActive());
        }
    }

    private GenericApplicationContext context(String password) {
        var environment = new MockEnvironment();
        if (password != null) {
            environment.setProperty("INFRA_SHARED_PASSWORD", password);
        }
        var context = new GenericApplicationContext();
        context.setEnvironment(environment);
        return context;
    }
}
