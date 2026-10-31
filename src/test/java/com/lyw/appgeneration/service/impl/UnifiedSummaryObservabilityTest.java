package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.AdmissionDeadline;
import com.lyw.appgeneration.service.MemoryCompressionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedSummaryObservabilityTest {
    @Test
    void 可靠提交之后复用相同覆盖不增加模型调用或成功摘要计数() {
        try (var f = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor())) {
            f.useSnapshotPersistence();
            when(f.model.chat(anyString())).thenReturn(UnifiedSummaryCoordinationTest.SUMMARY);
            assertEquals(MemoryCompressionResult.Status.COMPRESSED, f.service.compressUnified(f.request,
                    AdmissionDeadline.start(Duration.ofSeconds(5))).status());
            assertEquals(MemoryCompressionResult.Status.NOTHING_TO_COMPRESS, f.service.compressUnified(f.request,
                    AdmissionDeadline.start(Duration.ofSeconds(5))).status());
            assertEquals(1D, f.metrics.get("memory_summary_model_calls_total").counter().count());
            assertEquals(1L, f.metrics.get("memory_summary_tokens").summary().count());
            assertEquals(1D, f.metrics.get("memory_summary_reuse_total").tag("kind", "no_new_source").counter().count());
            verify(f.model, times(1)).chat(anyString());
        }
    }

    @Test
    void 草稿成功但提交失败不能计为已覆盖成功() {
        try (var f = new UnifiedSummaryCoordinationTest.Fixture(Executors.newSingleThreadExecutor())) {
            when(f.model.chat(anyString())).thenReturn(UnifiedSummaryCoordinationTest.SUMMARY);
            doThrow(new IllegalStateException("提交故障")).when(f.mapper).insert(any(com.lyw.appgeneration.model.entity.AppMemorySummary.class));
            assertEquals(MemoryCompressionResult.Status.MODEL_FAILED, f.service.compressUnified(f.request,
                    AdmissionDeadline.start(Duration.ofSeconds(5))).status());
            assertEquals(1D, f.metrics.get("memory_summary_model_calls_total").counter().count());
            assertTrue(f.metrics.find("memory_summary_tokens").summaries().isEmpty());
            assertEquals(0L, f.store.value.get().appliedSummaryRevision());
        }
    }
}
