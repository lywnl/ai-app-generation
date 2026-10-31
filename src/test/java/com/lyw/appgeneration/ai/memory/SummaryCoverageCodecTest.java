package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SummaryCoverageCodecTest {

    @Test
    void 旧覆盖为空且新覆盖往返不丢失来源和证据修订() {
        assertNull(SummaryCoverageCodec.decode(1, null));
        var coverage = partial();
        assertEquals(coverage, SummaryCoverageCodec.decode(2, SummaryCoverageCodec.encode(coverage)));
        var ended = coverage.withTerminal(20L, ChatMemoryOutcome.FAILED);
        assertFalse(ended.terminalIncluded(), "登记结束不能冒充已摘要终态");
        assertEquals(2L, ended.evidenceRevision());
        assertEquals(ended, SummaryCoverageCodec.decode(2, SummaryCoverageCodec.encode(ended)));
    }

    @Test
    void 损坏版本身份指纹和终态都不能提供覆盖权限() {
        String json = SummaryCoverageCodec.encode(partial());
        assertThrows(IllegalArgumentException.class, () -> SummaryCoverageCodec.decode(1, json));
        assertThrows(IllegalArgumentException.class, () -> SummaryCoverageCodec.decode(3, null));
        for (String invalid : new String[]{"", "null", "{}", json + "{}",
                json.replace("\"userHistoryId\":10", "\"userHistoryId\":0"),
                json.replace("\"evidenceRevision\":2,", ""),
                json.replace("\"terminalHistoryId\":null", "\"terminalHistoryId\":20")}) {
            assertThrows(IllegalArgumentException.class, () -> SummaryCoverageCodec.decode(2, invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> partial().withTerminal(20, ChatMemoryOutcome.FAILED)
                        .withTerminal(21, ChatMemoryOutcome.SUCCEEDED));
    }

    private SummaryCoverage partial() {
        return new SummaryCoverage(10L, "回合一", "epoch-1", 40L,
                "a".repeat(64), 2L, null, null, false);
    }
}
