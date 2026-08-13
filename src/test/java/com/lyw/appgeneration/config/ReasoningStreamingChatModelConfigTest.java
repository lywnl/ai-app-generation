package com.lyw.appgeneration.config;

import com.lyw.appgeneration.monitor.AiModelMonitorListener;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ReasoningStreamingChatModelConfigTest {

    @Test
    void onlineAndEvaluationReasoningModelsDisableParallelToolCalls() {
        ReasoningStreamingChatModelConfig config = new ReasoningStreamingChatModelConfig();
        config.setApiKey("test-key");
        config.setBaseUrl("http://localhost");
        config.setModelName("test-model");
        config.setMaxTokens(1024);
        config.setTemperature(0.0);
        ReflectionTestUtils.setField(
                config, "aiModelMonitorListener", mock(AiModelMonitorListener.class));

        OpenAiStreamingChatModel online = (OpenAiStreamingChatModel)
                config.reasoningStreamingChatModelPrototype();
        OpenAiStreamingChatModel evaluation = (OpenAiStreamingChatModel)
                config.evaluationReasoningStreamingChatModelPrototype();

        assertEquals(Boolean.FALSE,
                online.defaultRequestParameters().parallelToolCalls());
        assertEquals(Boolean.FALSE,
                evaluation.defaultRequestParameters().parallelToolCalls());
    }
}
