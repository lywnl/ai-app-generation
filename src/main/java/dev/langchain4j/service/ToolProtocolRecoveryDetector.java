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
 * 在单个模型 generation 内增量识别连续重复的纯文本工具调用。
 *
 * <p>首个完整候选会被隔离；只有紧随其后的规范指纹完全相同，才返回
 * {@link Duplicate}。任何无法确认的内容都会按原始顺序释放。</p>
 */
public final class ToolProtocolRecoveryDetector {

    private static final String MARKER = "[工具调用]";
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Set<String> registeredTools;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder separator = new StringBuilder();

    private Candidate heldCandidate;
    private boolean structuredToolCallObserved;
    private boolean duplicateDetected;

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
        if (chunk.isEmpty()) {
            return duplicateDetected ? new Duplicate("")
                    : pendingState();
        }
        if (structuredToolCallObserved) {
            return new Text(chunk);
        }
        if (duplicateDetected) {
            return new Duplicate("");
        }
        pending.append(chunk);
        return drain();
    }

    /**
     * 通知检测器本 generation 已观察到真实结构化工具调用。
     * 此后不再做正文协议判定，并立即释放此前隔离的候选。
     */
    public Result observeStructuredToolCall() {
        if (duplicateDetected) {
            return new Duplicate("");
        }
        if (structuredToolCallObserved) {
            return new Text("");
        }
        structuredToolCallObserved = true;
        String released = releaseAll();
        return new Text(released);
    }

    /** 流结束时原样释放所有未确认内容。 */
    public Result finish() {
        if (duplicateDetected) {
            pending.setLength(0);
            return new Text("");
        }
        return new Text(releaseAll());
    }

    private Result drain() {
        StringBuilder output = new StringBuilder();
        while (!pending.isEmpty()) {
            int markerIndex = pending.indexOf(MARKER);
            if (markerIndex < 0) {
                drainWithoutCompleteMarker(output);
                break;
            }
            if (markerIndex > 0) {
                consumeOrdinaryPrefix(markerIndex, output);
                continue;
            }

            ParseResult parsed = parseCandidate(pending.toString());
            if (parsed instanceof Incomplete) {
                break;
            }
            if (parsed instanceof Invalid) {
                consumeOrdinaryPrefix(1, output);
                continue;
            }
            Complete complete = (Complete) parsed;
            pending.delete(0, complete.length());
            if (acceptCandidate(complete.candidate(), output)) {
                pending.setLength(0);
                return new Duplicate(output.toString());
            }
        }
        return output.isEmpty() ? pendingState() : new Text(output.toString());
    }

    private void drainWithoutCompleteMarker(StringBuilder output) {
        int retainedPrefixLength = longestMarkerPrefixSuffix(pending);
        int safeLength = pending.length() - retainedPrefixLength;
        if (safeLength > 0) {
            consumeOrdinaryPrefix(safeLength, output);
        }
    }

    private void consumeOrdinaryPrefix(int length, StringBuilder output) {
        String ordinary = pending.substring(0, length);
        pending.delete(0, length);
        if (heldCandidate == null) {
            output.append(ordinary);
            return;
        }
        if (isWhitespaceOnly(ordinary)) {
            separator.append(ordinary);
            return;
        }
        output.append(heldCandidate.raw()).append(separator).append(ordinary);
        heldCandidate = null;
        separator.setLength(0);
    }

    private boolean acceptCandidate(
            Candidate candidate, StringBuilder output) {
        if (heldCandidate == null) {
            heldCandidate = candidate;
            return false;
        }
        if (heldCandidate.fingerprint().equals(candidate.fingerprint())) {
            heldCandidate = null;
            separator.setLength(0);
            duplicateDetected = true;
            return true;
        }
        output.append(heldCandidate.raw()).append(separator);
        heldCandidate = candidate;
        separator.setLength(0);
        return false;
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
        String raw = source.substring(0, jsonEnd);
        return new Complete(new Candidate(
                raw, toolName + "\n" + canonicalJson), jsonEnd);
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

    private String releaseAll() {
        StringBuilder released = new StringBuilder();
        if (heldCandidate != null) {
            released.append(heldCandidate.raw()).append(separator);
        }
        released.append(pending);
        heldCandidate = null;
        separator.setLength(0);
        pending.setLength(0);
        return released.toString();
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

    private static boolean isWhitespaceOnly(String value) {
        return !value.isEmpty() && value.codePoints()
                .allMatch(Character::isWhitespace);
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

    public sealed interface Result permits Text, Buffering, Duplicate {
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

    /** 已确认两个连续候选的规范指纹完全相同。 */
    public record Duplicate(String text) implements Result {

        public Duplicate {
            text = Objects.requireNonNull(text, "重复前可下发文本不能为空");
        }
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

    private record Candidate(String raw, String fingerprint) {
    }
}
