package dev.langchain4j.model.openai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OpenAiStreamingChatModelTest {

    @Test
    void shouldDisableReasoningEffortForDeepSeekV4Flash() {
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-v4-flash")
                .reasoningEffort("high")
                .build();

        OpenAiChatRequestParameters compatible =
                OpenAiStreamingChatModel.disableReasoningEffortForDeepSeekV4Flash(parameters);

        assertEquals("deepseek-v4-flash", compatible.modelName());
        assertNull(compatible.reasoningEffort());
    }

    @Test
    void shouldKeepReasoningEffortForNonDeepSeekV4FlashModel() {
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-chat")
                .reasoningEffort("high")
                .build();

        OpenAiChatRequestParameters compatible =
                OpenAiStreamingChatModel.disableReasoningEffortForDeepSeekV4Flash(parameters);

        assertEquals("deepseek-chat", compatible.modelName());
        assertEquals("high", compatible.reasoningEffort());
    }

    @Test
    void shouldKeepParametersWhenReasoningEffortIsEmpty() {
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-v4-flash")
                .build();

        OpenAiChatRequestParameters compatible =
                OpenAiStreamingChatModel.disableReasoningEffortForDeepSeekV4Flash(parameters);

        assertSame(parameters, compatible);
    }
}
