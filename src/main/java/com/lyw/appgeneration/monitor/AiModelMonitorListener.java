package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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

    private static final Object REQUEST_OBSERVATION_KEY = new Object();

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
        observeSafely("request", () -> observeRequest(requestContext));
    }

    private void observeRequest(ChatModelRequestContext requestContext) {
        Map<Object, Object> attributes = requestContext.attributes();
        Instant startedAt = Instant.now();
        ChatRequest chatRequest = requestContext.chatRequest();
        AiModelMetricsCollector.ModelFamily modelFamily =
                AiModelMetricsCollector.ModelFamily.fromModelName(
                        chatRequest.modelName());
        Integer estimatedTokens = estimateRequestTokens(chatRequest);
        attributes.put(REQUEST_OBSERVATION_KEY, new RequestObservation(
                startedAt, modelFamily, estimatedTokens));
        safeRecord(() -> aiModelMetricsCollector.recordRequest(
                modelFamily,
                AiModelMetricsCollector.RequestStatus.STARTED));
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        observeSafely("response", () -> observeResponse(responseContext));
    }

    private void observeResponse(ChatModelResponseContext responseContext) {
        Map<Object, Object> attributes = responseContext.attributes();
        RequestObservation observation = requestObservation(attributes);
        AiModelMetricsCollector.ModelFamily modelFamily = requestModelFamily(
                observation);
        safeRecord(() -> aiModelMetricsCollector.recordRequest(
                modelFamily,
                AiModelMetricsCollector.RequestStatus.SUCCESS));
        recordResponseTime(
                observation,
                modelFamily,
                AiModelMetricsCollector.ResponseOutcome.SUCCESS);
        recordTokenUsage(responseContext, observation, modelFamily);
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        observeSafely("error", () -> observeError(errorContext));
    }

    private void observeError(ChatModelErrorContext errorContext) {
        Map<Object, Object> attributes = errorContext.attributes();
        RequestObservation observation = requestObservation(attributes);
        AiModelMetricsCollector.ModelFamily modelFamily = requestModelFamily(
                observation);
        AiModelMetricsCollector.ErrorType errorType =
                AiModelMetricsCollector.ErrorType.fromThrowable(
                        errorContext.error());
        safeRecord(() -> aiModelMetricsCollector.recordRequest(
                modelFamily,
                AiModelMetricsCollector.RequestStatus.ERROR));
        safeRecord(() -> aiModelMetricsCollector.recordError(
                modelFamily, errorType));
        recordResponseTime(
                observation,
                modelFamily,
                AiModelMetricsCollector.ResponseOutcome.ERROR);
    }

    private Integer estimateRequestTokens(ChatRequest chatRequest) {
        try {
            return tokenEstimator.estimateRequest(
                    chatRequest.messages(), chatRequest.toolSpecifications());
        } catch (RuntimeException exception) {
            log.warn("AI 请求 Token 估算失败，exceptionType={}",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private void recordResponseTime(
            RequestObservation observation,
            AiModelMetricsCollector.ModelFamily modelFamily,
            AiModelMetricsCollector.ResponseOutcome outcome) {
        Object startedAt = observation == null
                ? null : observation.startedAt();
        if (!(startedAt instanceof Instant startTime)) {
            return;
        }
        Duration responseTime = Duration.between(startTime, Instant.now());
        safeRecord(() -> aiModelMetricsCollector.recordResponseTime(
                modelFamily, outcome, responseTime));
    }

    private void recordTokenUsage(
            ChatModelResponseContext responseContext,
            RequestObservation observation,
            AiModelMetricsCollector.ModelFamily modelFamily) {
        ChatResponse chatResponse = responseContext.chatResponse();
        if (chatResponse == null) {
            return;
        }
        ChatResponseMetadata metadata = chatResponse.metadata();
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
        recordEstimationRatio(observation, modelFamily, inputTokens);
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
            RequestObservation observation,
            AiModelMetricsCollector.ModelFamily modelFamily,
            Integer actualInputTokens) {
        Object estimatedValue = observation == null
                ? null : observation.estimatedTokens();
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
            RequestObservation observation) {
        Object value = observation == null
                ? null : observation.modelFamily();
        if (value instanceof AiModelMetricsCollector.ModelFamily modelFamily) {
            return modelFamily;
        }
        return AiModelMetricsCollector.ModelFamily.UNKNOWN;
    }

    private RequestObservation requestObservation(
            Map<Object, Object> attributes) {
        Object value = attributes.get(REQUEST_OBSERVATION_KEY);
        if (value instanceof RequestObservation observation) {
            return observation;
        }
        return null;
    }

    private void observeSafely(String phase, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("AI 模型观测回调失败，phase={}，exceptionType={}",
                    phase, exception.getClass().getSimpleName());
        }
    }

    private void safeRecord(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("AI 模型指标记录失败，exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private record RequestObservation(
            Instant startedAt,
            AiModelMetricsCollector.ModelFamily modelFamily,
            Integer estimatedTokens) {
    }
}
