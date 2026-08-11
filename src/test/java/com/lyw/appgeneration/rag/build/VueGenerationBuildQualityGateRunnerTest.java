package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import com.lyw.appgeneration.rag.vue.VueRetrievalEvaluationReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VueGenerationBuildQualityGateRunnerTest {

    private final VueGenerationBuildQualityGateRunner runner =
            new VueGenerationBuildQualityGateRunner();

    @Test
    void 物理核验失败时不调用检索和生成() {
        AtomicInteger retrievalCalls = new AtomicInteger();
        AtomicInteger generationCalls = new AtomicInteger();

        VueGenerationBuildReport report = runner.evaluate(
                () -> failedIngestion(),
                () -> {
                    retrievalCalls.incrementAndGet();
                    return passingRetrieval();
                },
                () -> {
                    generationCalls.incrementAndGet();
                    return VueGenerationBuildReport.executed(List.of());
                });

        assertFalse(report.executed());
        assertEquals(0, retrievalCalls.get());
        assertEquals(0, generationCalls.get());
    }

    @Test
    void 检索未执行或未达标时不调用生成() {
        AtomicInteger generationCalls = new AtomicInteger();

        VueGenerationBuildReport notExecuted = runner.evaluate(
                this::passingIngestion,
                () -> VueRetrievalEvaluationReport.notExecuted(List.of("检索未执行")),
                () -> invokedGeneration(generationCalls));
        VueGenerationBuildReport failed = runner.evaluate(
                this::passingIngestion,
                this::failedRetrieval,
                () -> invokedGeneration(generationCalls));

        assertFalse(notExecuted.executed());
        assertFalse(failed.executed());
        assertEquals(0, generationCalls.get());
    }

    @Test
    void 检索指标完美但不足三十条时不调用生成() {
        AtomicInteger generationCalls = new AtomicInteger();
        VueRetrievalEvaluationReport incomplete =
                VueRetrievalEvaluationReport.executed(List.of(), List.of());

        VueGenerationBuildReport report = runner.evaluate(
                this::passingIngestion,
                () -> incomplete,
                () -> invokedGeneration(generationCalls));

        assertFalse(report.executed());
        assertEquals(0, generationCalls.get());
    }

    @Test
    void 两项前置通过后才按固定顺序调用生成() {
        List<String> calls = new ArrayList<>();
        VueGenerationBuildReport expected = VueGenerationBuildReport.executed(List.of());

        VueGenerationBuildReport actual = runner.evaluate(
                () -> {
                    calls.add("物理核验");
                    return passingIngestion();
                },
                () -> {
                    calls.add("真实检索");
                    return passingRetrieval();
                },
                () -> {
                    calls.add("启动生成");
                    return expected;
                });

        assertSame(expected, actual);
        assertEquals(List.of("物理核验", "真实检索", "启动生成"), calls);
    }

    @Test
    void 三个Supplier异常均原样传播且后续步骤不执行() {
        IllegalStateException ingestionFailure = new IllegalStateException("核验异常");
        assertSame(ingestionFailure, assertThrows(IllegalStateException.class,
                () -> runner.evaluate(
                        () -> { throw ingestionFailure; },
                        () -> { throw new AssertionError("不得检索"); },
                        () -> { throw new AssertionError("不得生成"); })));

        IllegalArgumentException retrievalFailure = new IllegalArgumentException("检索异常");
        assertSame(retrievalFailure, assertThrows(IllegalArgumentException.class,
                () -> runner.evaluate(
                        this::passingIngestion,
                        () -> { throw retrievalFailure; },
                        () -> { throw new AssertionError("不得生成"); })));

        RuntimeException generationFailure = new RuntimeException("生成异常");
        assertSame(generationFailure, assertThrows(RuntimeException.class,
                () -> runner.evaluate(
                        this::passingIngestion,
                        this::passingRetrieval,
                        () -> { throw generationFailure; })));
    }

    private VueGenerationBuildReport invokedGeneration(AtomicInteger calls) {
        calls.incrementAndGet();
        throw new AssertionError("不得生成");
    }

    private VueIngestionVerification failedIngestion() {
        return new VueIngestionVerification(
                false, "catalog", 23, 22, 0, Set.of(1024), List.of("行数不足"));
    }

    private VueIngestionVerification passingIngestion() {
        return new VueIngestionVerification(
                true, "catalog", 23, 23, 0, Set.of(1024), List.of());
    }

    private VueRetrievalEvaluationReport passingRetrieval() {
        List<com.lyw.appgeneration.rag.vue.VueRetrievalObservation> rows =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(index -> new com.lyw.appgeneration.rag.vue.VueRetrievalObservation(
                                new com.lyw.appgeneration.rag.vue.VueEvalCase(
                                        "q-" + index, "需求", "精确技术词",
                                        List.of("s"), List.of()),
                                "s", List.of(), null))
                        .toList();
        return VueRetrievalEvaluationReport.executed(rows, rows);
    }

    private VueRetrievalEvaluationReport failedRetrieval() {
        List<com.lyw.appgeneration.rag.vue.VueRetrievalObservation> rows =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(index -> new com.lyw.appgeneration.rag.vue.VueRetrievalObservation(
                                new com.lyw.appgeneration.rag.vue.VueEvalCase(
                                        "q-" + index, "需求", "精确技术词",
                                        List.of("expected"), List.of()),
                                "wrong", List.of(), null))
                        .toList();
        return VueRetrievalEvaluationReport.executed(rows, rows);
    }
}
