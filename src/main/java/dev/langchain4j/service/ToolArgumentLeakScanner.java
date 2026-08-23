package dev.langchain4j.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按 requestId 隔离地扫描工具参数 JSON 中的字符串 value。 */
public final class ToolArgumentLeakScanner {

    public enum Status {
        SAFE, BUFFERING, VIOLATION, INVALID, MISMATCH
    }

    public record ScanResult(Status status) {
    }

    private final String reservedPrefix;
    private final Set<String> exactMarkers;
    private final Map<String, StringBuilder> partialArguments = new HashMap<>();

    public ToolArgumentLeakScanner(String reservedPrefix, Set<String> exactMarkers) {
        this.reservedPrefix = InternalOutputRecoveryPolicy.validateReservedPrefix(reservedPrefix);
        this.exactMarkers = InternalOutputRecoveryPolicy.copyMarkers(exactMarkers);
    }

    public synchronized ScanResult accept(String requestId, String rawArgumentsDelta) {
        if (isInvalidRequest(requestId) || rawArgumentsDelta == null) {
            return new ScanResult(Status.INVALID);
        }
        StringBuilder arguments = partialArguments.computeIfAbsent(requestId, ignored -> new StringBuilder());
        arguments.append(rawArgumentsDelta);
        try {
            Status status = scan(new JsonValueParser(arguments.toString(), true).parse());
            if (status == Status.VIOLATION) {
                partialArguments.remove(requestId);
            }
            return new ScanResult(status);
        } catch (InvalidJsonException exception) {
            partialArguments.remove(requestId);
            return new ScanResult(Status.INVALID);
        }
    }

    public synchronized ScanResult complete(String requestId, String completeArguments) {
        if (isInvalidRequest(requestId)) {
            return new ScanResult(Status.INVALID);
        }
        StringBuilder partial = partialArguments.remove(requestId);
        if (completeArguments == null) {
            return new ScanResult(Status.INVALID);
        }
        if (partial != null && !partial.toString().equals(completeArguments)) {
            return new ScanResult(Status.MISMATCH);
        }
        try {
            return new ScanResult(scanCompletedValues(new JsonValueParser(completeArguments, false).parse().values()));
        } catch (InvalidJsonException exception) {
            return new ScanResult(Status.INVALID);
        }
    }

    public synchronized void discard(String requestId) {
        partialArguments.remove(requestId);
    }

    private boolean isInvalidRequest(String requestId) {
        return requestId == null || requestId.isBlank();
    }

    private Status scan(ParsedArguments parsed) {
        if (scanCompletedValues(parsed.values()) == Status.VIOLATION) {
            return Status.VIOLATION;
        }
        if (parsed.unfinishedValue() == null) {
            return Status.SAFE;
        }
        InternalOutputLeakDetector.Status status = newDetector().accept(parsed.unfinishedValue()).status();
        if (status == InternalOutputLeakDetector.Status.VIOLATION) {
            return Status.VIOLATION;
        }
        return hasForbiddenCandidateSuffix(parsed.unfinishedValue()) ? Status.BUFFERING : Status.SAFE;
    }

    private Status scanCompletedValues(List<String> values) {
        return values.stream().anyMatch(this::containsViolation) ? Status.VIOLATION : Status.SAFE;
    }

    private boolean containsViolation(String value) {
        return newDetector().accept(value).status() == InternalOutputLeakDetector.Status.VIOLATION;
    }

    private InternalOutputLeakDetector newDetector() {
        return new InternalOutputLeakDetector(reservedPrefix, exactMarkers);
    }

    private boolean hasForbiddenCandidateSuffix(String value) {
        if (hasCandidateSuffix(value, reservedPrefix)) {
            return true;
        }
        return exactMarkers.stream().anyMatch(marker -> hasCandidateSuffix(value, marker));
    }

    private boolean hasCandidateSuffix(String value, String pattern) {
        int maximumLength = Math.min(value.length(), pattern.length() - 1);
        for (int length = 1; length <= maximumLength; length++) {
            if (value.endsWith(pattern.substring(0, length))) {
                return true;
            }
        }
        return false;
    }

    private record ParsedArguments(List<String> values, String unfinishedValue) {
    }

    private static class InvalidJsonException extends RuntimeException {
    }

    private static final class IncompleteJsonException extends InvalidJsonException {
    }

    /** 严格 JSON 词法/结构解析器，仅收集字符串 value，不收集 object key。 */
    private static final class JsonValueParser {

        private final String source;
        private final boolean allowIncomplete;
        private final List<String> values = new ArrayList<>();
        private String unfinishedValue;
        private int index;

        private JsonValueParser(String source, boolean allowIncomplete) {
            this.source = source;
            this.allowIncomplete = allowIncomplete;
        }

        private ParsedArguments parse() {
            try {
                skipWhitespace();
                parseValue(true);
                skipWhitespace();
                if (index != source.length()) {
                    throw new InvalidJsonException();
                }
                return new ParsedArguments(List.copyOf(values), null);
            } catch (IncompleteJsonException exception) {
                if (allowIncomplete) {
                    return new ParsedArguments(List.copyOf(values), unfinishedValue);
                }
                throw new InvalidJsonException();
            }
        }

        private void parseValue(boolean collectString) {
            requireAvailable();
            char current = source.charAt(index);
            if (current == '"') {
                String value = parseString(collectString);
                if (collectString) {
                    values.add(value);
                }
            } else if (current == '{') {
                parseObject();
            } else if (current == '[') {
                parseArray();
            } else {
                parseLiteralOrNumber();
            }
        }

        private void parseObject() {
            index++;
            skipWhitespace();
            if (consume('}')) {
                return;
            }
            while (true) {
                if (!peek('"')) {
                    if (index == source.length()) {
                        throw incomplete();
                    }
                    throw new InvalidJsonException();
                }
                parseString(false);
                skipWhitespace();
                require(':');
                skipWhitespace();
                parseValue(true);
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                require(',');
                skipWhitespace();
            }
        }

        private void parseArray() {
            index++;
            skipWhitespace();
            if (consume(']')) {
                return;
            }
            while (true) {
                parseValue(true);
                skipWhitespace();
                if (consume(']')) {
                    return;
                }
                require(',');
                skipWhitespace();
            }
        }

        private String parseString(boolean collectUnfinishedValue) {
            require('"');
            StringBuilder decoded = new StringBuilder();
            try {
                while (true) {
                    requireAvailable();
                    char current = source.charAt(index++);
                    if (current == '"') {
                        validateSurrogates(decoded);
                        return decoded.toString();
                    }
                    if (current < 0x20) {
                        throw new InvalidJsonException();
                    }
                    if (current != '\\') {
                        decoded.append(current);
                        continue;
                    }
                    requireAvailable();
                    switch (source.charAt(index++)) {
                        case '"' -> decoded.append('"');
                        case '\\' -> decoded.append('\\');
                        case '/' -> decoded.append('/');
                        case 'b' -> decoded.append('\b');
                        case 'f' -> decoded.append('\f');
                        case 'n' -> decoded.append('\n');
                        case 'r' -> decoded.append('\r');
                        case 't' -> decoded.append('\t');
                        case 'u' -> decoded.append(parseUnicodeEscape());
                        default -> throw new InvalidJsonException();
                    }
                }
            } catch (IncompleteJsonException exception) {
                if (collectUnfinishedValue) {
                    unfinishedValue = decoded.toString();
                }
                throw exception;
            }
        }

        private char parseUnicodeEscape() {
            if (source.length() - index < 4) {
                throw incomplete();
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = asciiHexDigit(source.charAt(index++));
                if (digit < 0) {
                    throw new InvalidJsonException();
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private int asciiHexDigit(char current) {
            if (current >= '0' && current <= '9') {
                return current - '0';
            }
            if (current >= 'A' && current <= 'F') {
                return current - 'A' + 10;
            }
            if (current >= 'a' && current <= 'f') {
                return current - 'a' + 10;
            }
            return -1;
        }

        private void parseLiteralOrNumber() {
            if (consumeWord("true") || consumeWord("false") || consumeWord("null")) {
                return;
            }
            parseNumber();
        }

        private void parseNumber() {
            consume('-');
            if (index == source.length()) {
                throw incomplete();
            }
            if (consume('0')) {
                if (hasAsciiDigit()) {
                    throw new InvalidJsonException();
                }
            } else {
                requireDigitOneToNine();
                while (hasAsciiDigit()) {
                    index++;
                }
            }
            if (consume('.')) {
                if (index == source.length()) {
                    throw incomplete();
                }
                requireAsciiDigit();
                while (hasAsciiDigit()) {
                    index++;
                }
            }
            if (consume('e') || consume('E')) {
                if (consume('+') || consume('-')) {
                    // 指数符号最多一个，随后必须是 ASCII 数字。
                }
                if (index == source.length()) {
                    throw incomplete();
                }
                requireAsciiDigit();
                while (hasAsciiDigit()) {
                    index++;
                }
            }
            if (!isValueBoundary()) {
                throw new InvalidJsonException();
            }
        }

        private boolean consumeWord(String word) {
            int remaining = source.length() - index;
            if (remaining < word.length() && word.startsWith(source.substring(index))) {
                index = source.length();
                throw incomplete();
            }
            if (!source.startsWith(word, index)) {
                return false;
            }
            index += word.length();
            if (!isValueBoundary()) {
                throw new InvalidJsonException();
            }
            return true;
        }

        private void validateSurrogates(StringBuilder decoded) {
            for (int offset = 0; offset < decoded.length(); offset++) {
                char current = decoded.charAt(offset);
                if (Character.isHighSurrogate(current)) {
                    if (offset + 1 >= decoded.length() || !Character.isLowSurrogate(decoded.charAt(++offset))) {
                        throw new InvalidJsonException();
                    }
                } else if (Character.isLowSurrogate(current)) {
                    throw new InvalidJsonException();
                }
            }
        }

        private boolean isValueBoundary() {
            return index == source.length() || ",]} \t\r\n".indexOf(source.charAt(index)) >= 0;
        }

        private void skipWhitespace() {
            while (index < source.length() && " \t\r\n".indexOf(source.charAt(index)) >= 0) {
                index++;
            }
        }

        private void require(char expected) {
            requireAvailable();
            if (source.charAt(index++) != expected) {
                throw new InvalidJsonException();
            }
        }

        private void requireAvailable() {
            if (index == source.length()) {
                throw incomplete();
            }
        }

        private void requireAsciiDigit() {
            requireAvailable();
            if (!hasAsciiDigit()) {
                throw new InvalidJsonException();
            }
            index++;
        }

        private void requireDigitOneToNine() {
            requireAvailable();
            char current = source.charAt(index);
            if (current < '1' || current > '9') {
                throw new InvalidJsonException();
            }
            index++;
        }

        private boolean hasAsciiDigit() {
            return index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9';
        }

        private boolean peek(char expected) {
            return index < source.length() && source.charAt(index) == expected;
        }

        private boolean consume(char expected) {
            if (peek(expected)) {
                index++;
                return true;
            }
            return false;
        }

        private IncompleteJsonException incomplete() {
            return new IncompleteJsonException();
        }
    }
}
