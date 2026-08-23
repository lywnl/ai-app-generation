package dev.langchain4j.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 单一文本通道的增量内部标记检测器。 */
public final class InternalOutputLeakDetector {

    public enum Status {
        SAFE, BUFFERING, VIOLATION
    }

    public record DetectionResult(Status status, String safeText) {
    }

    private final List<String> forbiddenPatterns;
    private String pending = "";
    private boolean violated;

    public InternalOutputLeakDetector(String reservedPrefix, Set<String> exactMarkers) {
        forbiddenPatterns = new ArrayList<>();
        forbiddenPatterns.add(InternalOutputRecoveryPolicy.validateReservedPrefix(reservedPrefix));
        forbiddenPatterns.addAll(InternalOutputRecoveryPolicy.copyMarkers(exactMarkers));
        forbiddenPatterns.sort(Comparator.comparingInt(String::length).reversed());
    }

    public DetectionResult accept(String text) {
        if (violated) {
            return violation();
        }
        if (text == null || text.isEmpty()) {
            return result("");
        }
        pending += text;
        if (containsForbiddenPattern(pending)) {
            violated = true;
            pending = "";
            return violation();
        }
        int suffixLength = possiblePrefixSuffixLength(pending);
        if (pending.length() > 0 && Character.isHighSurrogate(pending.charAt(pending.length() - 1))) {
            suffixLength = Math.max(suffixLength, 1);
        }
        String safeText = pending.substring(0, pending.length() - suffixLength);
        pending = pending.substring(pending.length() - suffixLength);
        return result(safeText);
    }

    public DetectionResult finish() {
        if (violated) {
            return violation();
        }
        String safeText = pending;
        pending = "";
        return new DetectionResult(Status.SAFE, safeText);
    }

    private boolean containsForbiddenPattern(String text) {
        return forbiddenPatterns.stream().anyMatch(text::contains);
    }

    private int possiblePrefixSuffixLength(String text) {
        int maximum = 0;
        for (String pattern : forbiddenPatterns) {
            for (int length = 1; length < pattern.length() && length <= text.length(); length++) {
                if (text.endsWith(pattern.substring(0, length))) {
                    maximum = Math.max(maximum, length);
                }
            }
        }
        return maximum;
    }

    private DetectionResult result(String safeText) {
        return new DetectionResult(pending.isEmpty() ? Status.SAFE : Status.BUFFERING, safeText);
    }

    private DetectionResult violation() {
        return new DetectionResult(Status.VIOLATION, "");
    }
}
