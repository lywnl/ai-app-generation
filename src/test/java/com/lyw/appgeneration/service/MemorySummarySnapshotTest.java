package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.memory.SummaryCoverage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemorySummarySnapshotTest {

    @Test
    void 空快照没有正文和覆盖范围() {
        assertEquals(new MemorySummarySnapshot("", 0L), MemorySummarySnapshot.empty());
    }

    @Test
    void 快照拒绝正文与覆盖边界不一致() {
        assertThrows(NullPointerException.class, () -> new MemorySummarySnapshot(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> new MemorySummarySnapshot("摘要", -1L));
        assertThrows(IllegalArgumentException.class, () -> new MemorySummarySnapshot("摘要", 0L));
        assertThrows(IllegalArgumentException.class, () -> new MemorySummarySnapshot("", 2L));
        assertThrows(IllegalArgumentException.class, () -> new MemorySummarySnapshot(" ", 2L));
    }

    @Test
    void 组合覆盖允许首轮游标为零且保留旧构造的校验阶段() {
        var coverage = partialCoverage();
        var snapshot = new MemorySummarySnapshot("摘要", 0L, 1L, 2, coverage);
        assertEquals(coverage, snapshot.coverageState());
        assertEquals(1L, snapshot.summaryRevision());
        assertEquals("无效摘要", new MemorySummarySnapshot("无效摘要", 2L).summary());
    }

    @Test
    void 拒绝新格式半状态与越过部分前沿的历史游标() {
        var coverage = partialCoverage();
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySummarySnapshot("摘要", 0L, 1L, 2, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySummarySnapshot("", 0L, 1L, 2, coverage));
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySummarySnapshot("摘要", 0L, 0L, 2, coverage));
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySummarySnapshot("摘要", 0L, 1L, 1, coverage));
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySummarySnapshot("摘要", 20L, 1L, 2, coverage));
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySummarySnapshot("摘要", 2L, 1L, 3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySummarySnapshot("摘要", 2L, -1L, 2, null));
    }

    private SummaryCoverage partialCoverage() {
        return new SummaryCoverage(10L, "turn-1", "epoch-1", 40L,
                "a".repeat(64), 2L, null, null, false);
    }
}
