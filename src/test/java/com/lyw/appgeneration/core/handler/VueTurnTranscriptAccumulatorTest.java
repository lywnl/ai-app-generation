package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTurnTranscriptAccumulatorTest {

    @Test
    void 保留标记与工具展示仍按来源分离且遵守预算() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);
        transcript.appendAiText(1L, "[[ser");
        transcript.appendTrustedToolDisplay(1L, "write-1", "工具展示");
        transcript.appendAiText(1L, "ver.test]]");
        assertEquals("[[server.test]]", transcript.answerMemoryText());
        assertEquals("[[ser工具展示ver.test]]", transcript.displayText());
        VueTurnTranscriptAccumulator limited = transcript(6, 1);
        assertTrue(limited.appendAiText(1L, "12345").accepted());
        assertTrue(limited.appendAiText(1L, "6").resourceLimitExceeded());
        assertEquals("12345", limited.displayText());
    }

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
    void 非法代次与空工具标识必须拒绝() {
        VueTurnTranscriptAccumulator transcript = transcript(64, 8);
        transcript.appendAiText(1L, "正文");

        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendAiText(-1L, "非法"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendAiText(0L, "未初始化代次"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendTrustedToolDisplay(
                        0L, "tool-0", "展示"));
        assertThrows(IllegalArgumentException.class,
                () -> new VueTurnTranscriptAccumulator.Fragment(
                        VueTurnTranscriptAccumulator.FragmentSource.AI_TEXT,
                        0L, null, "未初始化代次"));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.appendTrustedToolDisplay(
                        1L, " ", "展示"));
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
