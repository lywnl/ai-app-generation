package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalOutputLeakDetectorTest {

    private static final String PREFIX = "[[internal.";
    private static final String MARKER = "<internal-ack>";

    @Test
    void 保留前缀和精确标记在首段或正文后均触发违规() {
        assertViolation(PREFIX + "内容");
        assertViolation("正文" + PREFIX + "内容");
        assertViolation(MARKER);
        assertViolation("正文" + MARKER);
    }

    @Test
    void 逐字符输入与一次输入对违规文本结果等价() {
        String text = "正文" + MARKER + "尾部";
        InternalOutputLeakDetector once = detector();
        InternalOutputLeakDetector.DetectionResult onceResult = once.accept(text);

        InternalOutputLeakDetector characterByCharacter = detector();
        InternalOutputLeakDetector.DetectionResult chunkResult = null;
        for (int index = 0; index < text.length(); index++) {
            chunkResult = characterByCharacter.accept(text.substring(index, index + 1));
        }

        assertEquals(onceResult.status(), chunkResult.status());
        assertEquals(InternalOutputLeakDetector.Status.VIOLATION, chunkResult.status());
    }

    @Test
    void 相似前缀失配立即释放且完成时释放未决候选() {
        InternalOutputLeakDetector detector = detector();

        assertEquals(InternalOutputLeakDetector.Status.BUFFERING, detector.accept("正文[[inte").status());
        InternalOutputLeakDetector.DetectionResult mismatch = detector.accept("rx");
        assertEquals(InternalOutputLeakDetector.Status.SAFE, mismatch.status());
        assertEquals("[[interx", mismatch.safeText());

        assertEquals(InternalOutputLeakDetector.Status.BUFFERING, detector.accept("[[inte").status());
        assertEquals("[[inte", detector.finish().safeText());
    }

    @Test
    void 空分块命中后调用和跨分块代理对具有确定行为() {
        InternalOutputLeakDetector detector = detector();
        assertEquals(InternalOutputLeakDetector.Status.SAFE, detector.accept("").status());

        InternalOutputLeakDetector emojiDetector = new InternalOutputLeakDetector(PREFIX, Set.of(MARKER));
        String emoji = "🙂";
        assertEquals(InternalOutputLeakDetector.Status.BUFFERING,
                emojiDetector.accept(emoji.substring(0, 1)).status());
        assertEquals(emoji, emojiDetector.accept(emoji.substring(1)).safeText());

        assertEquals(InternalOutputLeakDetector.Status.VIOLATION, detector.accept(PREFIX).status());
        assertEquals(InternalOutputLeakDetector.Status.VIOLATION, detector.accept("安全正文").status());
        assertEquals(InternalOutputLeakDetector.Status.VIOLATION, detector.finish().status());
        assertEquals(InternalOutputLeakDetector.Status.VIOLATION, detector.finish().status());
    }

    @Test
    void 恢复策略保存不可变规则并将规则注入检测器和扫描器() {
        Set<String> markers = new java.util.LinkedHashSet<>(Set.of(MARKER));
        InternalOutputRecoveryPolicy policy = new InternalOutputRecoveryPolicy(
                InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, PREFIX, markers);
        markers.add("<later>");

        assertEquals(InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE, policy.mode());
        assertEquals(PREFIX, policy.reservedPrefix());
        assertEquals(Set.of(MARKER), policy.exactMarkers());
        assertThrows(UnsupportedOperationException.class, () -> policy.exactMarkers().add("<invalid>"));
        assertEquals(InternalOutputLeakDetector.Status.VIOLATION,
                policy.newLeakDetector().accept(MARKER).status());
        assertEquals(ToolArgumentLeakScanner.Status.VIOLATION,
                policy.newToolArgumentLeakScanner().complete("request", "{\"text\":\"<internal-ack>\"}").status());

        assertEquals(InternalOutputRecoveryPolicy.Mode.FAIL_FAST,
                new InternalOutputRecoveryPolicy(InternalOutputRecoveryPolicy.Mode.FAIL_FAST, PREFIX, Set.of()).mode());
    }

    @Test
    void 恢复策略拒绝无效规则() {
        assertThrows(NullPointerException.class,
                () -> new InternalOutputRecoveryPolicy(null, PREFIX, Set.of(MARKER)));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRecoveryPolicy(InternalOutputRecoveryPolicy.Mode.FAIL_FAST, null, Set.of(MARKER)));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRecoveryPolicy(InternalOutputRecoveryPolicy.Mode.FAIL_FAST, " ", Set.of(MARKER)));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRecoveryPolicy(InternalOutputRecoveryPolicy.Mode.FAIL_FAST, PREFIX, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRecoveryPolicy(InternalOutputRecoveryPolicy.Mode.FAIL_FAST, PREFIX, Set.of(" ")));
        java.util.HashSet<String> nullMarker = new java.util.HashSet<>();
        nullMarker.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRecoveryPolicy(InternalOutputRecoveryPolicy.Mode.FAIL_FAST, PREFIX, nullMarker));
    }

    private void assertViolation(String text) {
        assertEquals(InternalOutputLeakDetector.Status.VIOLATION, detector().accept(text).status());
    }

    private InternalOutputLeakDetector detector() {
        return new InternalOutputLeakDetector(PREFIX, Set.of(MARKER));
    }
}
