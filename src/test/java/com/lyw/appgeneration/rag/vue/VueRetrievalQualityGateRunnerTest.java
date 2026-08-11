package com.lyw.appgeneration.rag.vue;

import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VueRetrievalQualityGateRunnerTest {

    @Test
    void 物理核验失败时检索保持短路() {
        AtomicInteger retrievalCalls = new AtomicInteger();
        VueIngestionVerification verification = new VueIngestionVerification(
                false, "catalog", 23, 22, 0, Set.of(1024), List.of("缺少知识块"));

        VueRetrievalEvaluationReport report = new VueRetrievalQualityGateRunner()
                .evaluateWhenIngested(() -> verification, () -> {
                    retrievalCalls.incrementAndGet();
                    throw new AssertionError("不得执行检索");
                });

        assertFalse(report.executed());
        assertEquals(0, retrievalCalls.get());
    }
}
