package com.lyw.appgeneration.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** 在指定 Micrometer 边界注入异常，验证指标收集始终是业务旁路。 */
public final class ThrowingMeterRegistry extends SimpleMeterRegistry {

    private final FailurePoint failurePoint;
    private final AtomicBoolean failureTriggered = new AtomicBoolean();

    public ThrowingMeterRegistry(FailurePoint failurePoint) {
        this.failurePoint = Objects.requireNonNull(failurePoint, "故障点不能为空");
    }

    public boolean failureTriggered() {
        return failureTriggered.get();
    }

    @Override
    protected Counter newCounter(Meter.Id id) {
        if (failurePoint == FailurePoint.COUNTER_REGISTRATION) {
            throw failure();
        }
        Counter counter = super.newCounter(id);
        if (failurePoint != FailurePoint.COUNTER_INCREMENT) {
            return counter;
        }
        Counter proxy = mock(Counter.class, delegatesTo(counter));
        doAnswer(ignored -> {
            throw failure();
        }).when(proxy).increment();
        return proxy;
    }

    @Override
    protected Timer newTimer(
            Meter.Id id,
            DistributionStatisticConfig distributionStatisticConfig,
            PauseDetector pauseDetector) {
        if (failurePoint == FailurePoint.TIMER_REGISTRATION) {
            throw failure();
        }
        Timer timer = super.newTimer(id, distributionStatisticConfig, pauseDetector);
        if (failurePoint != FailurePoint.TIMER_RECORD) {
            return timer;
        }
        Timer proxy = mock(Timer.class, delegatesTo(timer));
        doAnswer(ignored -> {
            throw failure();
        }).when(proxy).record(any(Duration.class));
        return proxy;
    }

    @Override
    protected DistributionSummary newDistributionSummary(
            Meter.Id id,
            DistributionStatisticConfig distributionStatisticConfig,
            double scale) {
        if (failurePoint == FailurePoint.SUMMARY_REGISTRATION) {
            throw failure();
        }
        DistributionSummary summary = super.newDistributionSummary(
                id, distributionStatisticConfig, scale);
        if (failurePoint != FailurePoint.SUMMARY_RECORD) {
            return summary;
        }
        DistributionSummary proxy = mock(
                DistributionSummary.class, delegatesTo(summary));
        doAnswer(ignored -> {
            throw failure();
        }).when(proxy).record(anyDouble());
        return proxy;
    }

    private IllegalStateException failure() {
        failureTriggered.set(true);
        return new IllegalStateException("指标故障注入: " + failurePoint);
    }

    public enum FailurePoint {
        COUNTER_REGISTRATION,
        COUNTER_INCREMENT,
        TIMER_REGISTRATION,
        TIMER_RECORD,
        SUMMARY_REGISTRATION,
        SUMMARY_RECORD
    }
}
