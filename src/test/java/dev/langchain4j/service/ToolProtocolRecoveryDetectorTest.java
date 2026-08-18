package dev.langchain4j.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolProtocolRecoveryDetectorTest {

    private static final Set<String> REGISTERED_TOOLS = Set.of("readFile", "writeFile");

    @Test
    void detectsEquivalentNestedBlocksAcrossEveryCharacterChunk() {
        String first = "[工具调用] writeFile {\"path\":\"src/App.vue\",\"body\":{\"enabled\":true,\"items\":[1,\"\\\\\\\"x\\\\\\\"\",null]}}";
        String second = "[工具调用] writeFile {\"body\":{\"items\":[1,\"\\\\\\\"x\\\\\\\"\",null],\"enabled\":true},\"path\":\"src/App.vue\"}";

        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        FeedOutcome outcome = feedByCharacter(detector, first + second);

        assertInstanceOf(ToolProtocolRecoveryDetector.Duplicate.class, outcome.lastResult());
        assertEquals("", outcome.forwardedText());
    }

    @Test
    void preservesArrayOrderAndNumberValueTypesInFingerprint() {
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        ToolProtocolRecoveryDetector.Result first = detector.accept(
                "[工具调用] writeFile {\"nested\":{\"b\":2,\"a\":1},\"items\":[1,2],\"decimal\":1.0}");
        ToolProtocolRecoveryDetector.Result second = detector.accept(
                "[工具调用] writeFile {\"decimal\":1,\"items\":[2,1],\"nested\":{\"a\":1,\"b\":2}}");

        assertInstanceOf(ToolProtocolRecoveryDetector.Buffering.class, first);
        assertEquals("[工具调用] writeFile {\"nested\":{\"b\":2,\"a\":1},\"items\":[1,2],\"decimal\":1.0}",
                textOf(second));
        assertEquals("[工具调用] writeFile {\"decimal\":1,\"items\":[2,1],\"nested\":{\"a\":1,\"b\":2}}",
                textOf(detector.finish()));
    }

    @Test
    void distinguishesIntegerFromDecimalWithoutChangingOtherValues() {
        String integer = "[工具调用] writeFile {\"value\":1}";
        String decimal = "[工具调用] writeFile {\"value\":1.0}";
        String exponent = "[工具调用] writeFile {\"value\":1e0}";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        assertInstanceOf(ToolProtocolRecoveryDetector.Buffering.class,
                detector.accept(integer));
        assertEquals(integer, textOf(detector.accept(decimal)));
        assertEquals(decimal, textOf(detector.accept(exponent)));
        assertEquals(exponent, textOf(detector.finish()));
    }

    @Test
    void forwardsUnknownToolsAndMalformedCandidatesVerbatim() {
        List<String> inputs = List.of(
                "[工具调用] deleteFile {\"path\":\"x\"}",
                "[工具调用] writeFile {\"path\":\"x\"}",
                "[工具调用] writeFile {\"path\":\"x\"} trailing",
                "[工具调用] writeFile {\"path\":\"x\"} true",
                "[工具调用] writeFile [\"not-object\"]",
                "[工具调用] writeFile {\"path\":\"x\",\"path\":\"y\"}");

        for (String input : inputs) {
            ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);
            FeedOutcome outcome = feedByCharacter(detector, input);
            ToolProtocolRecoveryDetector.Result finished = detector.finish();

            assertTrue(outcome.lastResult() instanceof ToolProtocolRecoveryDetector.Text
                            || outcome.lastResult() instanceof ToolProtocolRecoveryDetector.Buffering,
                    "候选尚未确定前允许暂存");
            assertEquals(input, outcome.forwardedText() + textOf(finished),
                    "必须原样释放：" + input);
        }
    }

    @Test
    void keepsNewestDifferentBlockAsRollingCandidate() {
        String first = "[工具调用] readFile {\"path\":\"src/App.vue\"}";
        String different = "[工具调用] readFile {\"path\":\"src/main.js\"}";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        ToolProtocolRecoveryDetector.Result held = detector.accept(first);
        ToolProtocolRecoveryDetector.Result released = detector.accept(different);
        ToolProtocolRecoveryDetector.Result duplicate = detector.accept(different);

        assertInstanceOf(ToolProtocolRecoveryDetector.Buffering.class, held);
        assertEquals(first, textOf(released));
        assertInstanceOf(ToolProtocolRecoveryDetector.Duplicate.class, duplicate);
        assertEquals("", textOf(detector.finish()));
    }

    @Test
    void releasesInterruptedCandidateButKeepsFollowingCandidateForRollingDetection() {
        String first = "[工具调用] readFile {\"path\":\"src/App.vue\"}";
        String following = "[工具调用] readFile {\"path\":\"src/main.js\"}";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        ToolProtocolRecoveryDetector.Result held = detector.accept(first);
        ToolProtocolRecoveryDetector.Result released = detector.accept("正文" + following);
        ToolProtocolRecoveryDetector.Result duplicate = detector.accept(following);

        assertInstanceOf(ToolProtocolRecoveryDetector.Buffering.class, held);
        assertEquals(first + "正文", textOf(released));
        assertInstanceOf(ToolProtocolRecoveryDetector.Duplicate.class, duplicate);
        assertEquals("", textOf(detector.finish()));
    }

    @Test
    void stopsDetectingAfterRealStructuredToolCallWasObserved() {
        String duplicate = "[工具调用] readFile {\"path\":\"src/App.vue\"}"
                + "[工具调用] readFile {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);
        detector.observeStructuredToolCall();

        FeedOutcome outcome = feedByCharacter(detector, duplicate);

        assertEquals(duplicate, outcome.forwardedText());
        assertEquals("", textOf(detector.finish()));
    }

    @Test
    void releasesHeldCandidateAndPermanentlyForwardsAfterStructuredToolCallArrives() {
        String candidate = "[工具调用] readFile {\"path\":\"src/App.vue\"}";
        String laterDuplicate = candidate + candidate;
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        assertInstanceOf(ToolProtocolRecoveryDetector.Buffering.class, detector.accept(candidate));
        ToolProtocolRecoveryDetector.Result released = detector.observeStructuredToolCall();
        FeedOutcome forwarded = feedByCharacter(detector, laterDuplicate);

        assertEquals(candidate, textOf(released));
        assertEquals(laterDuplicate, forwarded.forwardedText());
        assertEquals("", textOf(detector.finish()));
    }

    @Test
    void duplicateIsTerminalEvenIfStructuredNotificationArrivesLate() {
        String candidate = "[工具调用] readFile {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        assertInstanceOf(ToolProtocolRecoveryDetector.Duplicate.class,
                detector.accept(candidate + candidate));
        assertInstanceOf(ToolProtocolRecoveryDetector.Duplicate.class,
                detector.observeStructuredToolCall());
        assertInstanceOf(ToolProtocolRecoveryDetector.Duplicate.class,
                detector.accept("迟到污染"));
        assertEquals("", textOf(detector.finish()));
    }

    @Test
    void preservesOrdinaryTextBeforeDuplicateInTheSameChunk() {
        String candidate = "[工具调用] readFile {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        ToolProtocolRecoveryDetector.Result result = detector.accept(
                "应保留正文" + candidate + candidate + "应丢弃污染");

        ToolProtocolRecoveryDetector.Duplicate duplicate = assertInstanceOf(
                ToolProtocolRecoveryDetector.Duplicate.class, result);
        assertEquals("应保留正文", duplicate.text());
        assertEquals("", textOf(detector.finish()));
    }

    @Test
    void preservesMarkerPrefixesAndFalsePrefixesAcrossChunks() {
        String partialMarker = "前缀[工具调";
        String falseMarker = "用] readFileX {\"path\":\"src/App.vue\"}";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        FeedOutcome first = feedByCharacter(detector, partialMarker);
        FeedOutcome second = feedByCharacter(detector, falseMarker);

        assertEquals("前缀", first.forwardedText());
        assertEquals("[工具调用] readFileX {\"path\":\"src/App.vue\"}",
                second.forwardedText());
        assertEquals("", textOf(detector.finish()));
    }

    @Test
    void releasesIncompleteCandidateVerbatimWhenStreamFinishes() {
        String incomplete = "前缀[工具调用] writeFile {\"path\":\"src/App.vue\"";
        ToolProtocolRecoveryDetector detector = new ToolProtocolRecoveryDetector(REGISTERED_TOOLS);

        ToolProtocolRecoveryDetector.Result accepted = detector.accept(incomplete);
        ToolProtocolRecoveryDetector.Result finished = detector.finish();

        assertEquals("前缀", textOf(accepted));
        assertEquals("[工具调用] writeFile {\"path\":\"src/App.vue\"", textOf(finished));
    }

    private FeedOutcome feedByCharacter(
            ToolProtocolRecoveryDetector detector, String input) {
        ToolProtocolRecoveryDetector.Result last = new ToolProtocolRecoveryDetector.Text("");
        StringBuilder forwarded = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            last = detector.accept(input.substring(index, index + 1));
            if (last instanceof ToolProtocolRecoveryDetector.Text text) {
                forwarded.append(text.text());
            }
        }
        return new FeedOutcome(forwarded.toString(), last);
    }

    private String textOf(ToolProtocolRecoveryDetector.Result result) {
        return result instanceof ToolProtocolRecoveryDetector.Text text ? text.text() : "";
    }

    private record FeedOutcome(
            String forwardedText,
            ToolProtocolRecoveryDetector.Result lastResult) {
    }
}
