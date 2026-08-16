package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.config.MemoryTokenProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeChatTokenEstimatorTest {

    private final ConservativeChatTokenEstimator estimator = estimatorWithSafetyFactor(1.0D);

    @Test
    void estimatesTextByLanguageAndSymbolClass() {
        assertEquals(0, estimator.estimateText(null));
        assertEquals(0, estimator.estimateText(""));
        assertEquals(4, estimator.estimateText("你好世界"));
        assertEquals(3, estimator.estimateText("abcdefghijkl"));
        assertEquals(2, estimator.estimateText("😀"));
    }

    @Test
    void appliesConfiguredSafetyFactorWithUpwardRounding() {
        ConservativeChatTokenEstimator guarded = estimatorWithSafetyFactor(1.15D);

        assertEquals(5, guarded.estimateText("你好世界"));
    }

    @Test
    void 中英文交错时每个Ascii连续段独立向上取整() {
        assertEquals(8, estimator.estimateText("a你b好c世d界"));
    }

    @Test
    void 高频交错文本不会被低估到异步阈值附近() {
        ConservativeChatTokenEstimator guarded =
                estimatorWithSafetyFactor(1.15D);

        assertEquals(46_000,
                guarded.estimateText("a你".repeat(20_000)));
    }

    @Test
    void messageEstimateIncludesRolesNamesAndToolPayloads() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template/>\"}")
                .build();
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from("你是代码生成助手"),
                UserMessage.from("用户", "创建一个应用"),
                AiMessage.from("准备写入", List.of(request)),
                ToolExecutionResultMessage.from(request, "写入成功"));

        int plainTextTokens = estimator.estimateText("你是代码生成助手创建一个应用准备写入写入成功");

        assertTrue(estimator.estimateMessages(messages) > plainTextTokens);
    }

    @Test
    void requestEstimateIncludesToolSchemaAndProtocolOverhead() {
        ToolSpecification tool = ToolSpecification.builder()
                .name("writeFile")
                .description("写入指定相对路径的文件")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("relativeFilePath", "相对文件路径")
                        .addStringProperty("content", "UTF-8 文件正文")
                        .required("relativeFilePath", "content")
                        .additionalProperties(false)
                        .build())
                .build();
        List<dev.langchain4j.data.message.ChatMessage> messages =
                List.of(UserMessage.from("创建首页"));

        int messageTokens = estimator.estimateMessages(messages);
        int toolTokens = estimator.estimateToolSpecifications(List.of(tool));
        int requestTokens = estimator.estimateRequest(messages, List.of(tool));

        assertTrue(toolTokens > 0);
        assertTrue(requestTokens > messageTokens);
        assertTrue(requestTokens > toolTokens);
    }

    @Test
    void emptyCollectionsStillHaveStableRequestEnvelope() {
        assertEquals(0, estimator.estimateMessages(List.of()));
        assertEquals(0, estimator.estimateToolSpecifications(List.of()));
        assertTrue(estimator.estimateRequest(List.of(), List.of()) > 0);
    }

    private ConservativeChatTokenEstimator estimatorWithSafetyFactor(double safetyFactor) {
        MemoryTokenProperties properties = new MemoryTokenProperties();
        properties.setEstimationSafetyFactor(safetyFactor);
        return new ConservativeChatTokenEstimator(properties);
    }
}
