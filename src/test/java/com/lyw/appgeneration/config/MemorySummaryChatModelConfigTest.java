package com.lyw.appgeneration.config;

import com.lyw.appgeneration.monitor.AiModelMonitorListener;
import com.lyw.appgeneration.service.impl.MemorySummaryDraftEngine;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySummaryChatModelConfigTest {

    @Test
    @DisplayName("L1 摘要草稿引擎使用专用模型")
    void l1SummaryDraftEngineUsesDedicatedModel() {
        Constructor<?> productionConstructor = Arrays.stream(
                        MemorySummaryDraftEngine.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(
                        Autowired.class))
                .findFirst()
                .orElseThrow();
        Qualifier qualifier = productionConstructor.getParameters()[1]
                .getAnnotation(Qualifier.class);

        assertNotNull(qualifier, "摘要模型参数必须使用显式 Qualifier");
        assertEquals("memorySummaryChatModel", qualifier.value(),
                "L1 不能复用带 SDK 自动重试的全局模型");
    }

    @Test
    @DisplayName("L1 专用模型禁用 SDK 重试并保留统一监控")
    void dedicatedModelDisablesSdkRetriesAndKeepsMonitor() {
        MemorySummaryChatModelConfig config =
                new MemorySummaryChatModelConfig();
        config.setBaseUrl("http://127.0.0.1:19091/v1");
        config.setApiKey("test-key");
        config.setModelName("memory-summary-test");
        config.setMaxTokens(8_192);
        AiModelMonitorListener listener = mock(AiModelMonitorListener.class);
        ReflectionTestUtils.setField(
                config, "aiModelMonitorListener", listener);
        @SuppressWarnings("unchecked")
        ObjectProvider<HttpClientBuilder> httpClientBuilderProvider =
                mock(ObjectProvider.class);
        when(httpClientBuilderProvider.getIfAvailable()).thenReturn(null);

        OpenAiChatModel model = (OpenAiChatModel)
                config.memorySummaryChatModel(httpClientBuilderProvider);

        assertEquals(0, ReflectionTestUtils.getField(model, "maxRetries"),
                "门禁已有应用级退避，SDK 不得在 60 秒截止后再次请求模型");
        assertEquals(List.of(listener), model.listeners(),
                "专用模型仍需保留统一模型监控");
    }
}
