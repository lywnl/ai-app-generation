package com.lyw.appgeneration.service.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueProjectSystemPromptTest {

    private static final String PROMPT_RESOURCE = "prompt/codegen-vue-project-system-prompt.txt";

    @Test
    void requiresCorrectNodeUrlImportAndComponentLibrarySetup() throws IOException {
        String prompt = readPrompt();

        assertTrue(prompt.contains("import { fileURLToPath, URL } from 'node:url'"));
        assertTrue(prompt.contains("组件库"));
        assertTrue(prompt.contains("package.json"));
        assertTrue(prompt.contains("精确依赖"));
        assertTrue(prompt.contains("main.js"));
        assertTrue(prompt.contains("注册/导入方式"));
        assertTrue(prompt.contains("骨架"));
        assertTrue(prompt.contains("功能片段"));
    }

    private String readPrompt() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(PROMPT_RESOURCE)) {
            assertNotNull(input, "Vue 工程系统提示词必须存在");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
