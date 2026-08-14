package com.lyw.appgeneration.core.concurrency;

import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Vue 在线生成回合的全局非阻塞准入控制器。 */
@Component
public final class VueTurnAdmissionController {

    public static final int MAX_ACTIVE_TURNS = 64;
    private final Semaphore slots = new Semaphore(MAX_ACTIVE_TURNS);
    private final VueBuildRepairMetricsCollector metrics;

    public VueTurnAdmissionController(VueBuildRepairMetricsCollector metrics) {
        this.metrics = Objects.requireNonNull(
                metrics, "准入指标收集器不能为空");
    }

    public Optional<AdmissionPermit> tryAcquire() {
        if (!slots.tryAcquire()) {
            metrics.recordTurnAdmission(
                    VueBuildRepairMetricsCollector.AdmissionResult.REJECTED);
            return Optional.empty();
        }
        metrics.recordTurnAdmission(
                VueBuildRepairMetricsCollector.AdmissionResult.ACQUIRED);
        return Optional.of(new AdmissionPermit(slots,
                () -> metrics.recordTurnAdmission(
                        VueBuildRepairMetricsCollector.AdmissionResult.RELEASED)));
    }

    public static final class AdmissionPermit implements AutoCloseable {

        private final Semaphore slots;
        private final Runnable onReleased;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AdmissionPermit(Semaphore slots, Runnable onReleased) {
            this.slots = slots;
            this.onReleased = onReleased;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                slots.release();
                onReleased.run();
            }
        }
    }
}
