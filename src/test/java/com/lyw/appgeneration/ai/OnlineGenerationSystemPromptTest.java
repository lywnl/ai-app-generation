package com.lyw.appgeneration.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineGenerationSystemPromptTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "prompt/codegen-html-system-prompt.txt",
            "prompt/codegen-multi-file-system-prompt.txt",
            "prompt/codegen-vue-project-system-prompt.txt"
    })
    void onlinePromptDeclaresReservedServerNamespace(String resource)
            throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            assertNotNull(input, "在线系统提示资源必须存在：" + resource);
            String prompt = new String(input.readAllBytes(),
                    StandardCharsets.UTF_8);

            assertTrue(prompt.contains("[[server.*]]"),
                    "必须声明服务端保留命名空间：" + resource);
            assertTrue(prompt.contains("不得") && prompt.contains("复述"),
                    "必须禁止复述服务端保留标记：" + resource);
        }
    }
}
