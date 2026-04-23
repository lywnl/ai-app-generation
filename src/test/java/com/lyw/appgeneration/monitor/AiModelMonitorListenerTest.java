package com.lyw.appgeneration.monitor;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelMonitorListenerTest {

    @Mock
    private AiModelMetricsCollector metricsCollector;

    @Mock
    private ChatModelErrorContext errorContext;

    @InjectMocks
    private AiModelMonitorListener listener;

    @AfterEach
    void tearDown() {
        MonitorContextHolder.clearContext();
    }

    @Test
    void onError_shouldNotThrow_whenThreadLocalContextIsMissing() {
        MonitorContextHolder.clearContext();

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .parameters(ChatRequestParameters.builder().modelName("test-model").build())
                .build();
        Map<Object, Object> attributes = new HashMap<>();
        attributes.put("request_start_time", Instant.now());

        when(errorContext.chatRequest()).thenReturn(request);
        when(errorContext.attributes()).thenReturn(attributes);
        when(errorContext.error()).thenReturn(new RuntimeException("boom"));
        doNothing().when(metricsCollector).recordRequest(anyString(), anyString(), anyString(), anyString());
        doNothing().when(metricsCollector).recordError(anyString(), anyString(), anyString(), anyString());
        doNothing().when(metricsCollector).recordResponseTime(anyString(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> listener.onError(errorContext));
    }
}
