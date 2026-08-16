package com.lyw.appgeneration.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** AI 模型调用的低基数旁路指标收集器。 */
@Component
public final class AiModelMetricsCollector {

    private final MeterRegistry registry;

    public AiModelMetricsCollector(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(
                registry, "MeterRegistry 不能为空");
    }

    public void recordRequest(ModelFamily modelFamily, RequestStatus status) {
        safely(() -> counter("ai_model_requests_total",
                "model_family", tag(modelFamily),
                "status", tag(status)).increment());
    }

    public void recordError(ModelFamily modelFamily, ErrorType errorType) {
        safely(() -> counter("ai_model_errors_total",
                "model_family", tag(modelFamily),
                "error_type", tag(errorType)).increment());
    }

    public void recordTokenUsage(
            ModelFamily modelFamily, TokenType tokenType, long tokenCount) {
        if (tokenCount < 0L) {
            return;
        }
        safely(() -> counter("ai_model_tokens_total",
                "model_family", tag(modelFamily),
                "token_type", tag(tokenType)).increment(tokenCount));
    }

    public void recordResponseTime(
            ModelFamily modelFamily,
            ResponseOutcome outcome,
            Duration duration) {
        safely(() -> Timer.builder("ai_model_response_duration_seconds")
                .description("AI 模型响应耗时")
                .publishPercentileHistogram()
                .tags("model_family", tag(modelFamily),
                        "outcome", tag(outcome))
                .register(registry)
                .record(duration));
    }

    public void recordTokenEstimationRatio(
            ModelFamily modelFamily, double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0D) {
            return;
        }
        safely(() -> DistributionSummary
                .builder("memory_token_estimation_ratio")
                .description("模型实际输入 Token 与统一估算 Token 的比值")
                .tag("model_family", tag(modelFamily))
                .register(registry)
                .record(ratio));
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 指标注册或记录失败不能改变模型调用结果。
        }
    }

    private static String tag(Enum<?> value) {
        return Objects.requireNonNull(value, "指标枚举不能为空")
                .name().toLowerCase(Locale.ROOT);
    }

    public enum ModelFamily {
        DEEPSEEK,
        QWEN,
        OPENAI,
        UNKNOWN;

        public static ModelFamily fromModelName(String modelName) {
            String normalized = Objects.toString(modelName, "")
                    .trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("deepseek")) {
                return DEEPSEEK;
            }
            if (normalized.contains("qwen")) {
                return QWEN;
            }
            if (normalized.contains("openai")
                    || normalized.contains("gpt")) {
                return OPENAI;
            }
            return UNKNOWN;
        }
    }

    public enum RequestStatus {
        STARTED, SUCCESS, ERROR
    }

    public enum ErrorType {
        TIMEOUT,
        RATE_LIMIT,
        AUTHENTICATION,
        NETWORK,
        CANCELLED,
        UNKNOWN;

        public static ErrorType fromThrowable(Throwable error) {
            Throwable current = error;
            for (int depth = 0; current != null && depth < 8; depth++) {
                if (current instanceof TimeoutException) {
                    return TIMEOUT;
                }
                if (current instanceof CancellationException
                        || current instanceof InterruptedException) {
                    return CANCELLED;
                }
                if (current instanceof IOException) {
                    return NETWORK;
                }
                String type = current.getClass().getSimpleName()
                        .toLowerCase(Locale.ROOT);
                if (type.contains("ratelimit")
                        || type.contains("toomanyrequests")) {
                    return RATE_LIMIT;
                }
                if (type.contains("authentication")
                        || type.contains("unauthorized")) {
                    return AUTHENTICATION;
                }
                current = current.getCause();
            }
            return UNKNOWN;
        }
    }

    public enum TokenType {
        INPUT, OUTPUT, TOTAL
    }

    public enum ResponseOutcome {
        SUCCESS, ERROR
    }
}
