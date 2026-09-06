package com.lyw.appgeneration.service;

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
}
