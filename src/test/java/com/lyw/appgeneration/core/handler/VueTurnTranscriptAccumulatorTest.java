package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTurnTranscriptAccumulatorTest {

    @Test
    void 展示与回答记忆必须从同一有序片段派生() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);

        transcript.appendAiText(1L, "第一段");
        transcript.appendTrustedToolDisplay(
                1L, "tool-1", "\n\n已修改 src/App.vue\n\n");
        transcript.appendAiText(2L, "第二段");

        VueTurnTranscriptAccumulator.Snapshot snapshot =
                transcript.snapshot();
        assertEquals("第一段\n\n已修改 src/App.vue\n\n第二段",
                snapshot.displayText());
        assertEquals("第一段第二段", snapshot.answerMemoryText());
        assertEquals(List.of(
                new VueTurnTranscriptAccumulator.Fragment(
                        VueTurnTranscriptAccumulator.FragmentSource.AI_TEXT,
                        1L, null, "第一段"),
                new VueTurnTranscriptAccumulator.Fragment(
                        VueTurnTranscriptAccumulator.FragmentSource
                                .TRUSTED_TOOL_DISPLAY,
                        1L, "tool-1", "\n\n已修改 src/App.vue\n\n"),
                new VueTurnTranscriptAccumulator.Fragment(
                        VueTurnTranscriptAccumulator.FragmentSource.AI_TEXT,
                        2L, null, "第二段")), snapshot.fragments());
    }

    @Test
    void 回滚必须按Unicode码点逆序删除指定代次正文() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);
        transcript.appendAiText(1L, "甲😀乙");
        transcript.appendAiText(1L, "丙");

        VueTurnTranscriptAccumulator.RollbackDecision decision =
                transcript.rollbackAiText(1L, 3);

        assertEquals(3, decision.removedCodePoints());
        assertEquals("甲", decision.snapshot().displayText());
        assertEquals("甲", decision.snapshot().answerMemoryText());
        assertEquals(1, decision.snapshot().fragments().size());
        assertEquals("甲", decision.snapshot().fragments().getFirst().text());
    }

    @Test
    void 回滚正文必须保留同代已执行工具展示和其他代正文() {
        VueTurnTranscriptAccumulator transcript = transcript(128, 8);
        transcript.appendAiText(1L, "失败正文");
        transcript.appendTrustedToolDisplay(
                1L, "write-1", "\n\n文件已经落盘\n\n");
        transcript.appendAiText(2L, "恢复正文");

        transcript.rollbackAiText(1L, 4);

        assertEquals("\n\n文件已经落盘\n\n恢复正文",
                transcript.displayText());
        assertEquals("恢复正文", transcript.answerMemoryText());
        assertEquals(List.of(
                VueTurnTranscriptAccumulator.FragmentSource
                        .TRUSTED_TOOL_DISPLAY,
                VueTurnTranscriptAccumulator.FragmentSource.AI_TEXT),
                transcript.snapshot().fragments().stream()
                        .map(VueTurnTranscriptAccumulator.Fragment::source)
                        .toList());
    }

    @Test
    void 回滚后必须归还展示预算供恢复代正文使用() {
        VueTurnTranscriptAccumulator transcript = transcript(6, 1);
        VueTurnTranscriptAccumulator.AppendDecision first =
                transcript.appendAiText(1L, "ABCDE");
        VueTurnTranscriptAccumulator.AppendDecision overflow =
                transcript.appendAiText(1L, "F");

        assertTrue(first.accepted());
        assertTrue(overflow.resourceLimitExceeded());
        assertEquals("", overflow.acceptedPrefix());

        transcript.rollbackAiText(1L, 5);
        VueTurnTranscriptAccumulator.AppendDecision recovered =
                transcript.appendAiText(2L, "12345");

        assertTrue(recovered.accepted());
        assertFalse(recovered.resourceLimitExceeded());
        assertEquals("12345", transcript.displayText());
        assertEquals("12345", transcript.answerMemoryText());
    }

    @Test
    void 回滚码点超过该代已接收正文时必须拒绝且不改变快照() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);
        transcript.appendAiText(1L, "正文");
        VueTurnTranscriptAccumulator.Snapshot before = transcript.snapshot();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transcript.rollbackAiText(1L, 3));

        assertEquals("回滚码点超过指定 generation 的已接收正文",
                exception.getMessage());
        assertEquals(before, transcript.snapshot());
    }

    @Test
    void 零码点回滚不得改变正文且非法代次必须拒绝() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);
        transcript.appendAiText(1L, "正文");

        VueTurnTranscriptAccumulator.RollbackDecision decision =
                transcript.rollbackAiText(1L, 0);

        assertEquals(0, decision.removedCodePoints());
        assertEquals("正文", decision.snapshot().displayText());
        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendAiText(-1L, "非法"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendAiText(0L, "未初始化代次"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendTrustedToolDisplay(
                        0L, "tool-0", "展示"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.rollbackAiText(0L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VueTurnTranscriptAccumulator.Fragment(
                        VueTurnTranscriptAccumulator.FragmentSource.AI_TEXT,
                        0L, null, "未初始化代次"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendTrustedToolDisplay(
                        1L, " ", "展示"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.rollbackAiText(1L, -1));
    }

    @Test
    void 跨分片代理对必须保持来源且零码点回滚要清除旧代待决项() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);
        String emoji = "😀";

        VueTurnTranscriptAccumulator.AppendDecision high =
                transcript.appendAiText(1L, emoji.substring(0, 1));
        assertEquals("", high.acceptedPrefix());
        assertEquals("", transcript.displayText());

        transcript.rollbackAiText(1L, 0);
        VueTurnTranscriptAccumulator.AppendDecision low =
                transcript.appendAiText(2L, emoji.substring(1));

        assertEquals("�", low.acceptedPrefix());
        assertEquals("�", transcript.displayText());
        assertEquals(2L,
                transcript.snapshot().fragments().getFirst().generation());
    }

    @Test
    void 同一来源跨分片代理对必须合并为一个Unicode码点() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);
        String emoji = "😀";

        transcript.appendAiText(1L, emoji.substring(0, 1));
        VueTurnTranscriptAccumulator.AppendDecision low =
                transcript.appendAiText(1L, emoji.substring(1));

        assertEquals(emoji, low.acceptedPrefix());
        assertEquals(emoji, transcript.displayText());
        assertEquals(1,
                FileToolBudgetGuard.codePointCount(transcript.displayText()));
    }

    private VueTurnTranscriptAccumulator transcript(
            int canonicalMaximum, int terminalReserve) {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(1);
        guard.setMaxCumulativeMutationCodePoints(1);
        guard.setMaxCanonicalAiTextCodePoints(canonicalMaximum);
        guard.setMaxReadFileCodePoints(1);
        guard.setMaxReadDirCodePoints(1);
        return new VueTurnTranscriptAccumulator(
                guard.newSession(), terminalReserve);
    }
}
