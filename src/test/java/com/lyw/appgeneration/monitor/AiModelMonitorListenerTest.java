package com.lyw.appgeneration.monitor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelMonitorListenerTest {

    @Mock
    private ChatTokenEstimator tokenEstimator;

    private PrometheusMeterRegistry registry;
    private AiModelMetricsCollector metricsCollector;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metricsCollector = new AiModelMetricsCollector(registry);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void exposesConstructorInjectionForMetricsAndEstimator() {
        assertDoesNotThrow(() -> AiModelMonitorListener.class.getConstructor(
                AiModelMetricsCollector.class,
                ChatTokenEstimator.class));
    }

    @Test
    void onRequestReestimatesFinalMessagesAndToolsAndStoresOpaqueObservation() {
        AiModelMonitorListener listener = newListener();
        ChatModelRequestContext requestContext =
                org.mockito.Mockito.mock(ChatModelRequestContext.class);
        Map<Object, Object> attributes = new HashMap<>();
        ChatRequest request = request("qwen-max", "敏感用户正文");
        when(requestContext.chatRequest()).thenReturn(request);
        when(requestContext.attributes()).thenReturn(attributes);
        when(tokenEstimator.estimateRequest(
                request.messages(), request.toolSpecifications()))
                .thenReturn(200);

        listener.onRequest(requestContext);

        assertEquals(1, attributes.size(),
                "一次请求只能写入一个不透明观察状态");
        assertFalse(attributes.containsKey("request_start_time"));
        assertFalse(attributes.containsKey("request_estimated_tokens"));
        assertFalse(attributes.containsKey("request_model_family"));
        verify(tokenEstimator).estimateRequest(
                request.messages(), request.toolSpecifications());
        assertEquals(1D, counter("ai_model_requests_total",
                "model_family", "qwen", "status", "started").count());
        String scrape = registry.scrape();
        assertFalse(scrape.contains("qwen-max"));
        assertFalse(scrape.contains("敏感用户正文"));
        assertFalse(scrape.contains("敏感工具正文"));
    }

    @Test
    void onResponseRecordsActualToEstimatedInputRatio() {
        AiModelMonitorListener listener = newListener();
        Map<Object, Object> attributes = new HashMap<>();
        ChatRequest request = request("qwen-max", "敏感用户正文");
        when(tokenEstimator.estimateRequest(anyList(), anyList()))
                .thenReturn(200);
        listener.onRequest(requestContext(request, attributes));

        listener.onResponse(responseContext(attributes, 300, 50, 350));

        assertEquals(1D, counter("ai_model_requests_total",
                "model_family", "qwen", "status", "success").count());
        assertEquals(300D, counter("ai_model_tokens_total",
                "model_family", "qwen", "token_type", "input").count());
        assertEquals(1L, timer("ai_model_response_duration_seconds",
                "model_family", "qwen", "outcome", "success").count());
        assertEquals(1.5D, summary("memory_token_estimation_ratio",
                "model_family", "qwen").totalAmount());
    }

    @Test
    void onResponseDoesNotInventRatioWhenEstimateIsZero() {
        AiModelMonitorListener listener = newListener();
        Map<Object, Object> attributes = new HashMap<>();
        ChatRequest request = request("gpt-5", "正文");
        when(tokenEstimator.estimateRequest(anyList(), anyList()))
                .thenReturn(0);
        listener.onRequest(requestContext(request, attributes));

        listener.onResponse(responseContext(attributes, 300, 50, 350));

        assertTrue(registry.find("memory_token_estimation_ratio")
                .summaries().isEmpty());
    }

    @Test
    void onErrorUsesFixedFamilyAndErrorTypeFromOpaqueObservation() {
        AiModelMonitorListener listener = newListener();
        Map<Object, Object> attributes = new HashMap<>();
        ChatRequest request = request("deepseek-chat", "错误回调正文");
        when(tokenEstimator.estimateRequest(anyList(), anyList()))
                .thenReturn(100);
        listener.onRequest(requestContext(request, attributes));
        ChatModelErrorContext errorContext =
                org.mockito.Mockito.mock(ChatModelErrorContext.class);
        when(errorContext.attributes()).thenReturn(attributes);
        when(errorContext.error()).thenReturn(
                new IllegalStateException("敏感原始异常消息"));

        assertDoesNotThrow(() -> listener.onError(errorContext));

        assertEquals(1D, counter("ai_model_requests_total",
                "model_family", "deepseek", "status", "error").count());
        assertEquals(1D, counter("ai_model_errors_total",
                "model_family", "deepseek", "error_type", "unknown")
                .count());
        assertEquals(1L, timer("ai_model_response_duration_seconds",
                "model_family", "deepseek", "outcome", "error").count());
        assertFalse(registry.scrape().contains("敏感原始异常消息"));
    }

    @Test
    void legacyStringAttributesWithWrongTypesCannotEscapeCallbacks() {
        AiModelMonitorListener listener = newListener();
        Map<Object, Object> attributes = new HashMap<>();
        attributes.put("request_start_time", "错误时间类型");
        attributes.put("request_estimated_tokens", new Object());
        attributes.put("request_model_family", 42);
        ChatModelErrorContext errorContext =
                org.mockito.Mockito.mock(ChatModelErrorContext.class);
        when(errorContext.attributes()).thenReturn(attributes);
        when(errorContext.error()).thenReturn(
                new IllegalStateException("旧属性污染"));

        assertAll(
                () -> assertDoesNotThrow(() -> listener.onResponse(
                        responseContext(attributes, 300, 50, 350))),
                () -> assertDoesNotThrow(() ->
                        listener.onError(errorContext)));
    }

    @Test
    void onResponseIgnoresMissingResponseOrMetadata() {
        AiModelMonitorListener listener = newListener();
        Map<Object, Object> attributes = new HashMap<>();
        ChatRequest request = request("qwen-max", "空响应正文");
        when(tokenEstimator.estimateRequest(anyList(), anyList()))
                .thenReturn(100);
        listener.onRequest(requestContext(request, attributes));
        ChatModelResponseContext missingResponse =
                org.mockito.Mockito.mock(ChatModelResponseContext.class);
        when(missingResponse.attributes()).thenReturn(attributes);
        ChatModelResponseContext missingMetadata =
                org.mockito.Mockito.mock(ChatModelResponseContext.class);
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        when(missingMetadata.attributes()).thenReturn(attributes);
        when(missingMetadata.chatResponse()).thenReturn(response);
        when(response.metadata()).thenReturn(null);

        assertAll(
                () -> assertDoesNotThrow(() ->
                        listener.onResponse(missingResponse)),
                () -> assertDoesNotThrow(() ->
                        listener.onResponse(missingMetadata)));
    }

    @Test
    void observationDependencyFailuresCannotEscapeAnyCallback() {
        AiModelMonitorListener listener = newListener();
        ChatModelRequestContext requestContext =
                org.mockito.Mockito.mock(ChatModelRequestContext.class);
        ChatModelResponseContext responseContext =
                org.mockito.Mockito.mock(ChatModelResponseContext.class);
        ChatModelErrorContext errorContext =
                org.mockito.Mockito.mock(ChatModelErrorContext.class);
        when(requestContext.attributes()).thenThrow(
                new IllegalStateException("请求属性敏感异常"));
        when(responseContext.attributes()).thenThrow(
                new IllegalStateException("响应属性敏感异常"));
        when(errorContext.attributes()).thenThrow(
                new IllegalStateException("错误属性敏感异常"));

        assertAll(
                () -> assertDoesNotThrow(() ->
                        listener.onRequest(requestContext)),
                () -> assertDoesNotThrow(() ->
                        listener.onResponse(responseContext)),
                () -> assertDoesNotThrow(() ->
                        listener.onError(errorContext)));
    }

    @Test
    void estimationFailureLogContainsOnlyExceptionType() {
        AiModelMonitorListener listener = newListener();
        ChatRequest request = request("deepseek-chat", "模型原始正文");
        when(tokenEstimator.estimateRequest(anyList(), anyList()))
                .thenThrow(new IllegalStateException("指标敏感异常正文"));
        Logger logger = (Logger) LoggerFactory.getLogger(
                AiModelMonitorListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.onRequest(requestContext(request, new HashMap<>()));
        } finally {
            logger.detachAppender(appender);
        }
        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertTrue(messages.stream()
                .anyMatch(message -> message.contains("IllegalStateException")));
        assertFalse(messages.stream()
                .anyMatch(message -> message.contains("指标敏感异常正文")
                        || message.contains("模型原始正文")));
    }

    private AiModelMonitorListener newListener() {
        return assertDoesNotThrow(() -> AiModelMonitorListener.class
                .getConstructor(
                        AiModelMetricsCollector.class,
                        ChatTokenEstimator.class)
                .newInstance(metricsCollector, tokenEstimator));
    }

    private Counter counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertNotNull(counter, () -> "缺少 Counter：" + name);
        return counter;
    }

    private Timer timer(String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        assertNotNull(timer, () -> "缺少 Timer：" + name);
        return timer;
    }

    private DistributionSummary summary(String name, String... tags) {
        DistributionSummary summary = registry.find(name)
                .tags(tags)
                .summary();
        assertNotNull(summary, () -> "缺少 DistributionSummary：" + name);
        return summary;
    }

    private ChatModelRequestContext requestContext(
            ChatRequest request, Map<Object, Object> attributes) {
        ChatModelRequestContext context =
                org.mockito.Mockito.mock(ChatModelRequestContext.class);
        when(context.chatRequest()).thenReturn(request);
        when(context.attributes()).thenReturn(attributes);
        return context;
    }

    private ChatModelResponseContext responseContext(
            Map<Object, Object> attributes,
            int inputTokens,
            int outputTokens,
            int totalTokens) {
        ChatModelResponseContext context =
                org.mockito.Mockito.mock(ChatModelResponseContext.class);
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        ChatResponseMetadata metadata =
                org.mockito.Mockito.mock(ChatResponseMetadata.class);
        TokenUsage usage = org.mockito.Mockito.mock(TokenUsage.class);
        when(context.attributes()).thenReturn(attributes);
        when(context.chatResponse()).thenReturn(response);
        when(response.metadata()).thenReturn(metadata);
        when(metadata.tokenUsage()).thenReturn(usage);
        when(usage.inputTokenCount()).thenReturn(inputTokens);
        when(usage.outputTokenCount()).thenReturn(outputTokens);
        when(usage.totalTokenCount()).thenReturn(totalTokens);
        return context;
    }

    private ChatRequest request(String modelName, String userText) {
        ToolSpecification tool = ToolSpecification.builder()
                .name("sensitiveTool")
                .description("敏感工具正文")
                .build();
        return ChatRequest.builder()
                .messages(UserMessage.from(userText))
                .parameters(ChatRequestParameters.builder()
                        .modelName(modelName)
                        .toolSpecifications(List.of(tool))
                        .build())
                .build();
    }
}
