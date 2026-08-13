package com.lyw.appgeneration.monitor;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager.AppOperationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTML、多文件与 Vue 共同生命周期边界的低基数旁路指标收集器。 */
@Component
public final class AppLifecycleMetricsCollector {

    private final MeterRegistry registry;

    public AppLifecycleMetricsCollector(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry 不能为空");
    }

    public void recordOperation(
            AppOperationType operation,
            OperationResult result,
            AppOperationType conflictWith) {
        safely(() -> counter("app_operations_total",
                "operation", tag(operation),
                "result", tag(result),
                "conflict_with", conflictWith == null ? "none" : tag(conflictWith))
                .increment());
    }

    public SseProtocolObservation startSseProtocolObservation() {
        return new SseProtocolObservation(this);
    }

    public SsePublisherObservation startSsePublisherObservation() {
        return new SsePublisherObservation(this);
    }

    public void recordOperationCancellation(
            OperationCancellationTrigger trigger,
            OperationCancellationResult result) {
        safely(() -> counter("app_operation_cancellations_total",
                "trigger", tag(trigger), "result", tag(result)).increment());
    }

    private void recordSseProtocol(SseProtocolResult result) {
        safely(() -> counter("generation_sse_protocol_results_total",
                "result", tag(result)).increment());
    }

    private void recordSsePublisher(SsePublisherResult result) {
        safely(() -> counter("generation_sse_publisher_terminations_total",
                "result", tag(result)).increment());
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 观测故障必须完全旁路，不能改变业务租约或 SSE 协议语义。
        }
    }

    private static String tag(Enum<?> value) {
        return Objects.requireNonNull(value, "指标枚举不能为空")
                .name().toLowerCase(Locale.ROOT);
    }

    /** 单次订阅中控制/协议结果的 CAS 单赢家观察句柄。 */
    public static final class SseProtocolObservation {

        private final AppLifecycleMetricsCollector collector;
        private final AtomicBoolean completed = new AtomicBoolean();

        private SseProtocolObservation(AppLifecycleMetricsCollector collector) {
            this.collector = collector;
        }

        public boolean complete(SseProtocolResult result) {
            Objects.requireNonNull(result, "SSE 控制结果不能为空");
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            collector.recordSseProtocol(result);
            return true;
        }
    }

    /** 单次订阅中 Reactor 发布流终止的 CAS 单赢家观察句柄。 */
    public static final class SsePublisherObservation {

        private final AppLifecycleMetricsCollector collector;
        private final AtomicBoolean completed = new AtomicBoolean();

        private SsePublisherObservation(AppLifecycleMetricsCollector collector) {
            this.collector = collector;
        }

        public boolean complete(SsePublisherResult result) {
            Objects.requireNonNull(result, "SSE 发布流终止结果不能为空");
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            collector.recordSsePublisher(result);
            return true;
        }
    }

    public enum OperationResult {
        ACQUIRED, REJECTED
    }

    public enum SseProtocolResult {
        DONE, BUSINESS_ERROR, SYSTEM_ERROR, PROTOCOL_ERROR
    }

    public enum SsePublisherResult {
        COMPLETED, SUBSCRIBER_CANCELLED, PUBLISHER_ERROR
    }

    public enum OperationCancellationTrigger {
        DELETE_TAKEOVER
    }

    public enum OperationCancellationResult {
        COMPLETED, TIMED_OUT, FAILED
    }
}
