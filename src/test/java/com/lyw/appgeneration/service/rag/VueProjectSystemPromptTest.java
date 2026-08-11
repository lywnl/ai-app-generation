package com.lyw.appgeneration.service.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void requiresEveryApprovedDependencyToUseItsExactVersion() throws IOException {
        String prompt = readPrompt();
        String dependencyRules = prompt.substring(
                prompt.indexOf("3）package.json 文件参考："),
                prompt.indexOf("5）用户消息中若提供"));
        Map<String, String> approvedDependencies = Map.of(
                "vue", "3.3.4",
                "vue-router", "4.2.4",
                "vite", "4.4.5",
                "@vitejs/plugin-vue", "4.2.3",
                "element-plus", "2.8.8",
                "@element-plus/icons-vue", "2.3.1",
                "echarts", "5.5.1");

        approvedDependencies.forEach((name, version) ->
                assertTrue(dependencyRules.contains("\"" + name + "\": \"" + version + "\"")
                                || dependencyRules.contains(name + " " + version),
                        () -> "提示词必须声明批准依赖的精确版本: " + name + " " + version));
        assertFalse(dependencyRules.contains("^"), "依赖规则不得引导模型使用 ^ 版本范围");
        assertFalse(dependencyRules.contains("~"), "依赖规则不得引导模型使用 ~ 版本范围");
    }

    private String readPrompt() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(PROMPT_RESOURCE)) {
            assertNotNull(input, "Vue 工程系统提示词必须存在");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
