package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ToolProtocolRecoveryDetectorTest {

    private static final String MARKER = "[工具调用]";
    private static final Set<String> REGISTERED_TOOLS =
            Set.of("readFile", "writeFile");

    @Test
    void detectsEquivalentNestedBlocksAcrossEveryCharacterChunk() {
        String first = MARKER
                + " writeFile {\"path\":\"src/App.vue\","
                + "\"body\":{\"enabled\":true,\"items\":[1,\"x\",null]}}";
        String second = MARKER
                + " writeFile {\"body\":{\"items\":[1,\"x\",null],"
                + "\"enabled\":true},\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = detector();

        FeedOutcome outcome = feedByCharacter(detector, first + second);

        ToolProtocolRecoveryDetector.Violation violation = violationOf(
                outcome.lastResult());
        assertEquals(
                ToolProtocolRecoveryDetector.ViolationReason.DUPLICATE_BLOCK,
                violation.reason());
        assertEquals("", outcome.forwardedText());
    }

    @Test
    void keepsDifferentFingerprintsQuarantinedUntilStreamFinishes() {
        String first = MARKER
                + " writeFile {\"nested\":{\"b\":2,\"a\":1},"
                + "\"items\":[1,2],\"decimal\":1.0}";
        String second = MARKER
                + " writeFile {\"decimal\":1,\"items\":[2,1],"
                + "\"nested\":{\"a\":1,\"b\":2}}";
        ToolProtocolRecoveryDetector detector = detector();

        ToolProtocolRecoveryDetector.Result accepted =
                detector.accept(first + "未受信后缀" + second);
        ToolProtocolRecoveryDetector.Violation finished =
                violationOf(detector.finish());

        assertInstanceOf(
                ToolProtocolRecoveryDetector.Buffering.class, accepted);
        assertEquals(
                ToolProtocolRecoveryDetector.ViolationReason.STREAM_FINISHED,
                finished.reason());
    }

    @Test
    void quarantinesSingleCandidateAndOnlyForwardsTrustedPrefix() {
        ToolProtocolRecoveryDetector detector = detector();

        FeedOutcome outcome = feedByCharacter(
                detector,
                "可信前缀" + MARKER
                        + " readFile {\"path\":\"src/App.vue\"}");
        ToolProtocolRecoveryDetector.Violation finished =
                violationOf(detector.finish());

        assertEquals("可信前缀", outcome.forwardedText());
        assertEquals(
                ToolProtocolRecoveryDetector.ViolationReason.STREAM_FINISHED,
                finished.reason());
    }

    @Test
    void returnsTrustedPrefixWithImmediateDuplicateViolation() {
        String candidate = MARKER
                + " readFile {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = detector();

        ToolProtocolRecoveryDetector.Violation violation = violationOf(
                detector.accept(
                        "应保留正文" + candidate + candidate
                                + "应丢弃污染"));

        assertEquals("应保留正文", violation.trustedText());
        assertEquals(
                ToolProtocolRecoveryDetector.ViolationReason.DUPLICATE_BLOCK,
                violation.reason());
    }

    @Test
    void quarantinesMarkedUnknownMalformedDuplicateAndIncompleteCalls() {
        List<String> inputs = List.of(
                MARKER + " deleteFile {\"path\":\"x\"}",
                MARKER + " writeFile [\"not-object\"]",
                MARKER + " writeFile {\"path\":\"x\",\"path\":\"y\"}",
                MARKER + " writeFile {\"path\":\"src/App.vue\"");

        for (String input : inputs) {
            ToolProtocolRecoveryDetector detector = detector();
            FeedOutcome outcome = feedByCharacter(detector, input);
            ToolProtocolRecoveryDetector.Violation finished =
                    violationOf(detector.finish());

            assertEquals("", outcome.forwardedText(), input);
            assertEquals(
                    ToolProtocolRecoveryDetector
                            .ViolationReason.STREAM_FINISHED,
                    finished.reason(), input);
        }
    }

    @Test
    void forwardsOrdinaryMarkdownJsonAndToolCallsExplanation() {
        String ordinary = "普通说明：tool_calls 是结构化字段。\n"
                + "```json\n{\"path\":\"src/App.vue\"}\n```";
        ToolProtocolRecoveryDetector detector = detector();

        FeedOutcome outcome = feedByCharacter(detector, ordinary);
        ToolProtocolRecoveryDetector.Text finished = assertInstanceOf(
                ToolProtocolRecoveryDetector.Text.class,
                detector.finish());

        assertEquals(
                ordinary, outcome.forwardedText() + finished.text());
    }

    @Test
    void preservesMarkerPrefixesAcrossChunksWithoutLeakingThem() {
        ToolProtocolRecoveryDetector detector = detector();

        assertEquals("前缀", textOf(detector.accept("前缀[工具调")));
        assertInstanceOf(
                ToolProtocolRecoveryDetector.Buffering.class,
                detector.accept("用] readFile {\"path\":\"src/App.vue\"}"));
        assertEquals(
                ToolProtocolRecoveryDetector.ViolationReason.STREAM_FINISHED,
                violationOf(detector.finish()).reason());
    }

    @Test
    void forwardsFalseMarkerPrefixesVerbatim() {
        String ordinary = "前缀[工具调X用] readFile {\"path\":\"x\"}";
        ToolProtocolRecoveryDetector detector = detector();

        FeedOutcome outcome = feedByCharacter(detector, ordinary);

        assertEquals(
                ordinary,
                outcome.forwardedText() + textOf(detector.finish()));
    }

    @Test
    void realStructuredToolCallDropsEarlierCandidateWithoutRecovery() {
        String candidate = MARKER
                + " readFile {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = detector();

        assertInstanceOf(
                ToolProtocolRecoveryDetector.Buffering.class,
                detector.accept(candidate));
        assertInstanceOf(
                ToolProtocolRecoveryDetector.Buffering.class,
                detector.observeStructuredToolCall());
        ToolProtocolRecoveryDetector.Text finished = assertInstanceOf(
                ToolProtocolRecoveryDetector.Text.class,
                detector.finish());

        assertEquals("", finished.text());
    }

    @Test
    void keepsDetectingPseudoToolTextAfterStructuredToolCall() {
        String candidate = MARKER
                + " readFile {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = detector();

        detector.observeStructuredToolCall();
        FeedOutcome outcome = feedByCharacter(detector, candidate);
        ToolProtocolRecoveryDetector.Text finished = assertInstanceOf(
                ToolProtocolRecoveryDetector.Text.class,
                detector.finish());

        assertEquals("", outcome.forwardedText());
        assertEquals("", finished.text());
    }

    @Test
    void duplicateViolationRemainsTerminalAfterStructuredNotification() {
        String candidate = MARKER
                + " readFile {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = detector();

        ToolProtocolRecoveryDetector.Violation first = violationOf(
                detector.accept(candidate + candidate));
        ToolProtocolRecoveryDetector.Violation observed = violationOf(
                detector.observeStructuredToolCall());
        ToolProtocolRecoveryDetector.Violation late = violationOf(
                detector.accept("迟到污染"));

        assertEquals(first.reason(), observed.reason());
        assertEquals(first.reason(), late.reason());
        assertEquals(first.reason(), violationOf(detector.finish()).reason());
    }

    @Test
    void reachesQuarantineLimitAtExactBoundary() {
        ToolProtocolRecoveryDetector detector = detector();
        String quarantined = MARKER + "x".repeat(
                ToolProtocolRecoveryDetector.QUARANTINE_LIMIT
                        - MARKER.length());

        ToolProtocolRecoveryDetector.Violation violation = violationOf(
                detector.accept(quarantined));

        assertEquals(
                ToolProtocolRecoveryDetector
                        .ViolationReason.QUARANTINE_LIMIT,
                violation.reason());
    }

    @Test
    void doesNotReachQuarantineLimitOneCharacterEarly() {
        ToolProtocolRecoveryDetector detector = detector();
        String quarantined = MARKER + "x".repeat(
                ToolProtocolRecoveryDetector.QUARANTINE_LIMIT
                        - MARKER.length() - 1);

        assertInstanceOf(
                ToolProtocolRecoveryDetector.Buffering.class,
                detector.accept(quarantined));
        assertEquals(
                ToolProtocolRecoveryDetector
                        .ViolationReason.QUARANTINE_LIMIT,
                violationOf(detector.accept("x")).reason());
    }

    private ToolProtocolRecoveryDetector detector() {
        return new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);
    }

    private FeedOutcome feedByCharacter(
            ToolProtocolRecoveryDetector detector, String input) {
        ToolProtocolRecoveryDetector.Result last =
                new ToolProtocolRecoveryDetector.Text("");
        StringBuilder forwarded = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            last = detector.accept(input.substring(index, index + 1));
            if (last instanceof ToolProtocolRecoveryDetector.Text text) {
                forwarded.append(text.text());
            } else if (last instanceof ToolProtocolRecoveryDetector.Violation
                    violation) {
                forwarded.append(violation.trustedText());
            }
        }
        return new FeedOutcome(forwarded.toString(), last);
    }

    private String textOf(ToolProtocolRecoveryDetector.Result result) {
        return result instanceof ToolProtocolRecoveryDetector.Text text
                ? text.text() : "";
    }

    private ToolProtocolRecoveryDetector.Violation violationOf(
            ToolProtocolRecoveryDetector.Result result) {
        return assertInstanceOf(
                ToolProtocolRecoveryDetector.Violation.class, result);
    }

    private record FeedOutcome(
            String forwardedText,
            ToolProtocolRecoveryDetector.Result lastResult) {
    }
}
