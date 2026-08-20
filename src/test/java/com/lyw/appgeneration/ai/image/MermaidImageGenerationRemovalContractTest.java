package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MermaidImageGenerationRemovalContractTest {

    @Test
    void imageCollectionPlan_doesNotExposeDiagramTasks() {
        boolean exposesDiagramTasks = Arrays.stream(ImageCollectionPlan.class.getMethods())
                .anyMatch(method -> method.getName().equals("getDiagramTasks"));

        assertFalse(exposesDiagramTasks, "图片收集计划不应再暴露 diagramTasks");
    }

    @Test
    void imageCollectionPrompt_doesNotDeclareMermaidContract() throws IOException {
        String prompt = readImageCollectionPrompt();

        assertFalse(prompt.contains("diagramTasks"));
        assertFalse(prompt.contains("mermaidCode"));
        assertFalse(prompt.contains("Mermaid"));
    }

    private String readImageCollectionPrompt() throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(
                "/prompt/image-collection-plan-system-prompt.txt")) {
            if (inputStream == null) {
                throw new IOException("未找到图片收集规划提示词资源");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
