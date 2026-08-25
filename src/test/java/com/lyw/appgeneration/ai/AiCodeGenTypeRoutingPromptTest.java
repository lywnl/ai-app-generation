package com.lyw.appgeneration.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodeGenTypeRoutingPromptTest {

    private static final String PROMPT_RESOURCE = "prompt/codegen-routing-system-prompt.txt";

    @Test
    void definesSinglePageFileSeparationInsteadOfTreatingMultiplePagesAsMultiFile() throws IOException {
        String prompt = readPrompt();

        assertTrue(prompt.contains("HTML - 单文件单页"));
        assertTrue(prompt.contains("MULTI_FILE - 单页应用"));
        assertTrue(prompt.contains("index.html、style.css、script.js"));
        assertTrue(prompt.contains("VUE_PROJECT - 多页面、多路由"));
        assertFalse(prompt.contains("多个页面但不涉及复杂交互，选择 MULTI_FILE"));
    }

    private String readPrompt() throws IOException {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(PROMPT_RESOURCE)) {
            if (input == null) {
                throw new IOException("路由提示词资源不存在: " + PROMPT_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
