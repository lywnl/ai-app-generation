package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class AiModelMonitorListener implements ChatModelListener {

    private static final String REQUEST_START_TIME_KEY = "request_start_time";
    private static final String REQUEST_ESTIMATED_TOKENS_KEY =
            "request_estimated_tokens";
    private static final String REQUEST_MODEL_FAMILY_KEY =
            "request_model_family";

    private final AiModelMetricsCollector aiModelMetricsCollector;
    private final ChatTokenEstimator tokenEstimator;

    public AiModelMonitorListener(
            AiModelMetricsCollector aiModelMetricsCollector,
            ChatTokenEstimator tokenEstimator) {
        this.aiModelMetricsCollector = Objects.requireNonNull(
                aiModelMetricsCollector, "AI 模型指标收集器不能为空");
        this.tokenEstimator = Objects.requireNonNull(
                tokenEstimator, "Token 估算器不能为空");
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        Map<Object, Object> attributes = requestContext.attributes();
        attributes.put(REQUEST_START_TIME_KEY, Instant.now());
        ChatRequest chatRequest = requestContext.chatRequest();
        AiModelMetricsCollector.ModelFamily modelFamily =
                AiModelMetricsCollector.ModelFamily.fromModelName(
                        chatRequest.modelName());
        attributes.put(REQUEST_MODEL_FAMILY_KEY, modelFamily);
        estimateRequestTokens(chatRequest, attributes);
        safeRecord(() -> aiModelMetricsCollector.recordRequest(
                modelFamily,
                AiModelMetricsCollector.RequestStatus.STARTED));
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Map<Object, Object> attributes = responseContext.attributes();
        AiModelMetricsCollector.ModelFamily modelFamily =
                requestModelFamily(attributes);
        safeRecord(() -> aiModelMetricsCollector.recordRequest(
                modelFamily,
                AiModelMetricsCollector.RequestStatus.SUCCESS));
        recordResponseTime(
                attributes,
                modelFamily,
                AiModelMetricsCollector.ResponseOutcome.SUCCESS);
        recordTokenUsage(responseContext, attributes, modelFamily);
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Map<Object, Object> attributes = errorContext.attributes();
        AiModelMetricsCollector.ModelFamily modelFamily =
                requestModelFamily(attributes);
        AiModelMetricsCollector.ErrorType errorType =
                AiModelMetricsCollector.ErrorType.fromThrowable(
                        errorContext.error());
        safeRecord(() -> aiModelMetricsCollector.recordRequest(
                modelFamily,
                AiModelMetricsCollector.RequestStatus.ERROR));
        safeRecord(() -> aiModelMetricsCollector.recordError(
                modelFamily, errorType));
        recordResponseTime(
                attributes,
                modelFamily,
                AiModelMetricsCollector.ResponseOutcome.ERROR);
    }

    private void estimateRequestTokens(
            ChatRequest chatRequest, Map<Object, Object> attributes) {
        try {
            int estimatedTokens = tokenEstimator.estimateRequest(
                    chatRequest.messages(), chatRequest.toolSpecifications());
            attributes.put(REQUEST_ESTIMATED_TOKENS_KEY, estimatedTokens);
        } catch (RuntimeException exception) {
            log.warn("AI 请求 Token 估算失败，exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void recordResponseTime(
            Map<Object, Object> attributes,
            AiModelMetricsCollector.ModelFamily modelFamily,
            AiModelMetricsCollector.ResponseOutcome outcome) {
        Instant startTime = (Instant) attributes.get(REQUEST_START_TIME_KEY);
        if (startTime == null) {
            return;
        }
        Duration responseTime = Duration.between(startTime, Instant.now());
        safeRecord(() -> aiModelMetricsCollector.recordResponseTime(
                modelFamily, outcome, responseTime));
    }

    private void recordTokenUsage(
            ChatModelResponseContext responseContext,
            Map<Object, Object> attributes,
            AiModelMetricsCollector.ModelFamily modelFamily) {
        ChatResponseMetadata metadata =
                responseContext.chatResponse().metadata();
        TokenUsage tokenUsage = metadata == null ? null : metadata.tokenUsage();
        if (tokenUsage == null) {
            return;
        }
        Integer inputTokens = tokenUsage.inputTokenCount();
        Integer outputTokens = tokenUsage.outputTokenCount();
        Integer totalTokens = tokenUsage.totalTokenCount();
        recordTokenCount(
                modelFamily, AiModelMetricsCollector.TokenType.INPUT,
                inputTokens);
        recordTokenCount(
                modelFamily, AiModelMetricsCollector.TokenType.OUTPUT,
                outputTokens);
        recordTokenCount(
                modelFamily, AiModelMetricsCollector.TokenType.TOTAL,
                totalTokens);
        recordEstimationRatio(attributes, modelFamily, inputTokens);
    }

    private void recordTokenCount(
            AiModelMetricsCollector.ModelFamily modelFamily,
            AiModelMetricsCollector.TokenType tokenType,
            Integer tokenCount) {
        if (tokenCount == null) {
            return;
        }
        safeRecord(() -> aiModelMetricsCollector.recordTokenUsage(
                modelFamily, tokenType, tokenCount));
    }

    private void recordEstimationRatio(
            Map<Object, Object> attributes,
            AiModelMetricsCollector.ModelFamily modelFamily,
            Integer actualInputTokens) {
        Object estimatedValue = attributes.get(REQUEST_ESTIMATED_TOKENS_KEY);
        if (actualInputTokens == null || !(estimatedValue instanceof Number)) {
            return;
        }
        int estimatedTokens = ((Number) estimatedValue).intValue();
        if (estimatedTokens <= 0) {
            return;
        }
        double ratio = actualInputTokens.doubleValue() / estimatedTokens;
        safeRecord(() -> aiModelMetricsCollector.recordTokenEstimationRatio(
                modelFamily, ratio));
    }

    private AiModelMetricsCollector.ModelFamily requestModelFamily(
            Map<Object, Object> attributes) {
        Object value = attributes.get(REQUEST_MODEL_FAMILY_KEY);
        if (value instanceof AiModelMetricsCollector.ModelFamily modelFamily) {
            return modelFamily;
        }
        return AiModelMetricsCollector.ModelFamily.UNKNOWN;
    }

    private void safeRecord(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("AI 模型指标记录失败，exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
