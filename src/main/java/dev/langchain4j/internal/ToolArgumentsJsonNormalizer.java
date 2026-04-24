package dev.langchain4j.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.Internal;

import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.langchain4j.internal.Utils.isNullOrBlank;

@Internal
public final class ToolArgumentsJsonNormalizer {

    private static final Type MAP_TYPE = new TypeReference<Map<String, Object>>() {
    }.getType();
    private static final Pattern TRAILING_COMMA_PATTERN = Pattern.compile(",(\\s*[}\\]])");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("(?is)^```(?:json)?\\s*(.*?)\\s*```$");

    public enum Status {
        VALID,
        REPAIRED,
        INVALID
    }

    public record Result(Status status, String normalizedArguments, String reason) {

        public boolean isValid() {
            return status != Status.INVALID;
        }

        public boolean repaired() {
            return status == Status.REPAIRED;
        }
    }

    private ToolArgumentsJsonNormalizer() {
    }

    public static Result normalize(String rawArguments) {
        if (isNullOrBlank(rawArguments)) {
            return new Result(Status.VALID, "{}", "blank_arguments");
        }

        String trimmed = rawArguments.trim();
        Result direct = tryParseToResult(trimmed, Status.VALID, "already_valid");
        if (direct != null) {
            return direct;
        }

        Set<String> attempts = new LinkedHashSet<>();
        String stripped = stripCodeFence(trimmed);
        attempts.add(stripped);
        attempts.add(unwrapJsonString(stripped));
        attempts.add(removeTrailingCommas(stripped));
        attempts.add(fixMissingCommasBeforeKeys(removeTrailingCommas(stripped)));
        attempts.add(balanceObjectBraces(fixMissingCommasBeforeKeys(removeTrailingCommas(stripped))));
        attempts.add(balanceObjectBraces(fixMissingCommasBeforeKeys(removeTrailingCommas(unwrapJsonString(stripped)))));

        for (String candidate : attempts) {
            Result repaired = tryParseToResult(candidate, Status.REPAIRED, "auto_repaired");
            if (repaired != null) {
                return repaired;
            }
        }

        return new Result(Status.INVALID, null, "invalid_json_arguments");
    }

    private static Result tryParseToResult(String candidate, Status status, String reason) {
        if (isNullOrBlank(candidate)) {
            return null;
        }
        String normalizedCandidate = candidate.trim();
        if (!normalizedCandidate.startsWith("{") || !normalizedCandidate.endsWith("}")) {
            return null;
        }
        try {
            Map<String, Object> asMap = Json.fromJson(normalizedCandidate, MAP_TYPE);
            return new Result(status, Json.toJson(asMap), reason);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stripCodeFence(String value) {
        if (isNullOrBlank(value)) {
            return value;
        }
        Matcher matcher = CODE_FENCE_PATTERN.matcher(value.trim());
        return matcher.matches() ? matcher.group(1).trim() : value;
    }

    private static String removeTrailingCommas(String value) {
        if (isNullOrBlank(value)) {
            return value;
        }
        return TRAILING_COMMA_PATTERN.matcher(value).replaceAll("$1");
    }

    private static String unwrapJsonString(String value) {
        if (isNullOrBlank(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            return trimmed;
        }
        String unwrapped = trimmed.substring(1, trimmed.length() - 1);
        return unwrapped
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .trim();
    }

    /**
     * Repairs common malformed payloads like:
     * {"a":"1" "b":"2"} -> {"a":"1","b":"2"}
     */
    private static String fixMissingCommasBeforeKeys(String raw) {
        if (isNullOrBlank(raw)) {
            return raw;
        }

        StringBuilder out = new StringBuilder(raw.length() + 8);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (!inString && c == '"' && looksLikeObjectKeyStart(raw, i)) {
                char previous = lastNonWhitespace(out);
                if (previous != 0 && previous != '{' && previous != ',' && previous != '[' && previous != ':') {
                    out.append(',');
                }
            }

            out.append(c);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            }
        }

        return out.toString();
    }

    private static boolean looksLikeObjectKeyStart(String text, int quoteIndex) {
        if (quoteIndex < 0 || quoteIndex >= text.length() || text.charAt(quoteIndex) != '"') {
            return false;
        }

        boolean escaped = false;
        int i = quoteIndex + 1;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                break;
            }
        }

        if (i >= text.length()) {
            return false;
        }

        i++;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == ':';
    }

    private static char lastNonWhitespace(StringBuilder value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return 0;
    }

    private static String balanceObjectBraces(String raw) {
        if (isNullOrBlank(raw)) {
            return raw;
        }

        String value = raw.trim();
        int firstBrace = value.indexOf('{');
        if (firstBrace > 0) {
            value = value.substring(firstBrace);
        }

        int lastBrace = value.lastIndexOf('}');
        if (lastBrace >= 0 && lastBrace < value.length() - 1) {
            value = value.substring(0, lastBrace + 1);
        }

        int balance = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
            }
        }

        StringBuilder out = new StringBuilder(value);
        if (inString) {
            out.append('"');
        }
        while (balance > 0) {
            out.append('}');
            balance--;
        }

        return out.toString();
    }
}
