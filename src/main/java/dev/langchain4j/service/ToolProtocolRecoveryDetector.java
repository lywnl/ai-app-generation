package dev.langchain4j.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 在单个模型 generation 内隔离普通正文中的伪工具调用。
 *
 * <p>明确的工具调用标记一旦出现，标记及其后的正文都不再可信。检测器只释放
 * 标记之前的普通文本；重复候选和隔离上限会提前确认协议退化，其余候选在流结束
 * 时统一确认，避免未知工具、坏 JSON 或残缺参数绕过隔离。</p>
 */
public final class ToolProtocolRecoveryDetector {

    private static final String MARKER = "[工具调用]";
    static final int QUARANTINE_LIMIT = 65_536;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Set<String> registeredTools;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder quarantine = new StringBuilder();

    private String previousFingerprint;
    private int quarantineScanOffset;
    private boolean quarantining;
    private boolean structuredToolCallObserved;
    private boolean violationObservedBeforeStructuredToolCall;
    private ViolationReason violationReason;

    public ToolProtocolRecoveryDetector(Set<String> registeredTools) {
        Objects.requireNonNull(registeredTools, "注册工具集合不能为空");
        this.registeredTools = Set.copyOf(registeredTools);
        if (this.registeredTools.stream().anyMatch(
                name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("注册工具名不能为空");
        }
    }

    /** 接收任意大小的正文分片。 */
    public Result accept(String chunk) {
        Objects.requireNonNull(chunk, "流式正文分片不能为空");
        if (violationReason != null) {
            return new Violation("", violationReason);
        }
        if (chunk.isEmpty()) {
            return pendingState();
        }
        if (quarantining) {
            return quarantine(chunk);
        }
        pending.append(chunk);
        return drainTrustedText();
    }

    /**
     * 通知检测器本 generation 已观察到真实结构化工具调用。
     * 真实工具调用不会让普通正文重新受信；混合响应中的伪工具正文仍须丢弃。
     */
    public Result observeStructuredToolCall() {
        structuredToolCallObserved = true;
        if (violationReason != null) {
            return new Violation("", violationReason);
        }
        return pendingState();
    }

    /** 当前 generation 是否已经收到真实结构化工具调用。 */
    boolean hasObservedStructuredToolCall() {
        return structuredToolCallObserved;
    }

    /** 当前 generation 的协议退化是否先于真实结构化工具调用被确认。 */
    boolean hasViolationObservedBeforeStructuredToolCall() {
        return violationObservedBeforeStructuredToolCall;
    }

    /** 流结束时释放普通文本，或确认仍被隔离的伪工具候选。 */
    public Result finish() {
        if (violationReason != null) {
            return new Violation("", violationReason);
        }
        if (quarantining) {
            quarantine.setLength(0);
            if (structuredToolCallObserved) {
                return new Text("");
            }
            confirmViolation(ViolationReason.STREAM_FINISHED);
            return new Violation("", violationReason);
        }
        String released = pending.toString();
        pending.setLength(0);
        return new Text(released);
    }

    private Result drainTrustedText() {
        int markerIndex = pending.indexOf(MARKER);
        if (markerIndex >= 0) {
            String trusted = pending.substring(0, markerIndex);
            String quarantined = pending.substring(markerIndex);
            pending.setLength(0);
            quarantining = true;
            Result result = quarantine(quarantined);
            if (result instanceof Violation violation) {
                return new Violation(trusted, violation.reason());
            }
            return trusted.isEmpty() ? pendingState() : new Text(trusted);
        }
        int retainedPrefixLength = longestMarkerPrefixSuffix(pending);
        int safeLength = pending.length() - retainedPrefixLength;
        if (safeLength == 0) {
            return pendingState();
        }
        String trusted = pending.substring(0, safeLength);
        pending.delete(0, safeLength);
        return new Text(trusted);
    }

    private Result quarantine(String text) {
        int remaining = QUARANTINE_LIMIT - quarantine.length();
        int acceptedLength = Math.min(text.length(), remaining);
        if (acceptedLength > 0) {
            quarantine.append(text, 0, acceptedLength);
        }
        scanQuarantine();
        if (violationReason == null
                && quarantine.length() >= QUARANTINE_LIMIT) {
            confirmViolation(ViolationReason.QUARANTINE_LIMIT);
        }
        if (violationReason != null) {
            quarantine.setLength(0);
            return new Violation("", violationReason);
        }
        return pendingState();
    }

    private void scanQuarantine() {
        while (quarantineScanOffset < quarantine.length()) {
            int markerIndex = quarantine.indexOf(
                    MARKER, quarantineScanOffset);
            if (markerIndex < 0) {
                quarantineScanOffset = Math.max(
                        quarantineScanOffset,
                        quarantine.length() - MARKER.length() + 1);
                return;
            }
            ParseResult parsed = parseCandidate(
                    quarantine.substring(markerIndex));
            if (parsed instanceof Incomplete) {
                return;
            }
            if (parsed instanceof Invalid) {
                quarantineScanOffset = markerIndex + MARKER.length();
                continue;
            }
            Complete complete = (Complete) parsed;
            String fingerprint = complete.candidate().fingerprint();
            if (fingerprint.equals(previousFingerprint)) {
                confirmViolation(ViolationReason.DUPLICATE_BLOCK);
                return;
            }
            previousFingerprint = fingerprint;
            quarantineScanOffset = markerIndex + complete.length();
        }
    }

    private void confirmViolation(ViolationReason reason) {
        if (violationReason != null) {
            return;
        }
        violationObservedBeforeStructuredToolCall =
                !structuredToolCallObserved;
        violationReason = reason;
    }

    private ParseResult parseCandidate(String source) {
        int index = MARKER.length();
        if (index == source.length()) {
            return Incomplete.INSTANCE;
        }
        if (!Character.isWhitespace(source.charAt(index))) {
            return Invalid.INSTANCE;
        }
        index = skipWhitespace(source, index);
        if (index == source.length()) {
            return Incomplete.INSTANCE;
        }
        int toolStart = index;
        while (index < source.length()
                && !Character.isWhitespace(source.charAt(index))) {
            index++;
        }
        if (index == source.length()) {
            return Incomplete.INSTANCE;
        }
        String toolName = source.substring(toolStart, index);
        if (!registeredTools.contains(toolName)) {
            return Invalid.INSTANCE;
        }
        index = skipWhitespace(source, index);
        if (index == source.length()) {
            return Incomplete.INSTANCE;
        }
        if (source.charAt(index) != '{') {
            return Invalid.INSTANCE;
        }
        int jsonEnd = findJsonObjectEnd(source, index);
        if (jsonEnd < 0) {
            return Incomplete.INSTANCE;
        }
        String json = source.substring(index, jsonEnd);
        String canonicalJson = canonicalJson(json);
        if (canonicalJson == null) {
            return Invalid.INSTANCE;
        }
        return new Complete(new Candidate(
                toolName + "\n" + canonicalJson), jsonEnd);
    }

    private int findJsonObjectEnd(String source, int objectStart) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = objectStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
                if (depth < 0) {
                    return index + 1;
                }
            }
        }
        return -1;
    }

    private String canonicalJson(String json) {
        try (JsonParser parser = STRICT_JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            String canonical = canonicalObject(parser);
            return parser.nextToken() == null ? canonical : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String canonicalObject(JsonParser parser) throws IOException {
        TreeMap<String, String> fields = new TreeMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw new IOException("JSON 对象字段无效");
            }
            String name = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw new IOException("JSON 对象值不完整");
            }
            fields.put(name, canonicalValue(parser, valueToken));
        }
        List<String> entries = new ArrayList<>(fields.size());
        for (var entry : fields.entrySet()) {
            entries.add(quote(entry.getKey()) + ":" + entry.getValue());
        }
        return "{" + String.join(",", entries) + "}";
    }

    private String canonicalArray(JsonParser parser) throws IOException {
        List<String> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw new IOException("JSON 数组不完整");
            }
            values.add(canonicalValue(parser, token));
        }
        return "[" + String.join(",", values) + "]";
    }

    private String canonicalValue(
            JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> canonicalObject(parser);
            case START_ARRAY -> canonicalArray(parser);
            case VALUE_STRING -> quote(parser.getText());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getText();
            case VALUE_TRUE -> "true";
            case VALUE_FALSE -> "false";
            case VALUE_NULL -> "null";
            default -> throw new IOException("JSON 值类型无效");
        };
    }

    private String quote(String value) throws IOException {
        return STRICT_JSON.writeValueAsString(value);
    }

    private Result pendingState() {
        return new Buffering();
    }

    private static int skipWhitespace(String source, int start) {
        int index = start;
        while (index < source.length()
                && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int longestMarkerPrefixSuffix(CharSequence value) {
        int maximum = Math.min(value.length(), MARKER.length() - 1);
        for (int length = maximum; length > 0; length--) {
            boolean matches = true;
            int offset = value.length() - length;
            for (int index = 0; index < length; index++) {
                if (value.charAt(offset + index) != MARKER.charAt(index)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return length;
            }
        }
        return 0;
    }

    public sealed interface Result permits Text, Buffering, Violation {
    }

    /** 可立即下发的可信普通文本。 */
    public record Text(String text) implements Result {

        public Text {
            text = Objects.requireNonNull(text, "下发文本不能为空");
        }
    }

    /** 当前仍有可能成为伪工具块的内容被隔离。 */
    public record Buffering() implements Result {
    }

    /** 已确认当前 generation 的普通正文违反工具调用协议。 */
    public record Violation(
            String trustedText, ViolationReason reason) implements Result {

        public Violation {
            trustedText = Objects.requireNonNull(
                    trustedText, "协议退化前可信文本不能为空");
            reason = Objects.requireNonNull(reason, "协议退化原因不能为空");
        }
    }

    public enum ViolationReason {
        DUPLICATE_BLOCK,
        STREAM_FINISHED,
        QUARANTINE_LIMIT
    }

    private sealed interface ParseResult
            permits Complete, Incomplete, Invalid {
    }

    private record Complete(Candidate candidate, int length)
            implements ParseResult {
    }

    private enum Incomplete implements ParseResult {
        INSTANCE
    }

    private enum Invalid implements ParseResult {
        INSTANCE
    }

    private record Candidate(String fingerprint) {
    }
}
