package com.lyw.appgeneration.rag.eval;

import java.util.regex.Pattern;

/**
 * 高成本评测报告的统一最终输出脱敏器。
 */
public final class EvaluationReportSanitizer {

    private static final String REDACTED = "<已脱敏>";
    private static final Pattern UNIX_USER_PATH = Pattern.compile(
            "/(?:Users|home)/[^/<>\\s|]+(?:/[^<>\\s|]*)?");
    private static final Pattern WINDOWS_USER_PATH = Pattern.compile(
            "(?i)[A-Z]:\\\\Users\\\\[^<>\\\\\\s|]+(?:\\\\[^<>\\s|]*)?");
    private static final Pattern AUTHORIZATION_BEARER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^<>\\s|&,}]+");
    private static final Pattern STANDALONE_BEARER = Pattern.compile(
            "(?i)(?<![\\p{Alnum}_])bearer\\s+[A-Z0-9][A-Z0-9._~+/=-]{2,}");
    private static final String SECRET_NAME =
            "[\\\"']?(?:api[-_]?key|token|password|secret)[\\\"']?";
    private static final Pattern DOUBLE_QUOTED_NAMED_SECRET = Pattern.compile(
            "(?i)(" + SECRET_NAME + "\\s*[:=]\\s*)\\\"(?:\\\\.|[^\\\"\\\\\\r\\n])*\\\"");
    private static final Pattern SINGLE_QUOTED_NAMED_SECRET = Pattern.compile(
            "(?i)(" + SECRET_NAME + "\\s*[:=]\\s*)'(?:\\\\.|[^'\\\\\\r\\n])*'");
    private static final Pattern UNCLOSED_DOUBLE_QUOTED_NAMED_SECRET = Pattern.compile(
            "(?i)(" + SECRET_NAME + "\\s*[:=]\\s*)\\\"(?:\\\\.|[^\\\"\\r\\n])*(?=\\r?\\n|$)");
    private static final Pattern UNCLOSED_SINGLE_QUOTED_NAMED_SECRET = Pattern.compile(
            "(?i)(" + SECRET_NAME + "\\s*[:=]\\s*)'(?:\\\\.|[^'\\r\\n])*(?=\\r?\\n|$)");
    private static final Pattern UNQUOTED_NAMED_SECRET = Pattern.compile(
            "(?i)(" + SECRET_NAME + "\\s*[:=]\\s*)(?![\\\"'])([^<>\\r\\n|&,}\\]]+)");

    private EvaluationReportSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = UNIX_USER_PATH.matcher(value).replaceAll("<用户路径>");
        sanitized = WINDOWS_USER_PATH.matcher(sanitized).replaceAll("<用户路径>");
        sanitized = AUTHORIZATION_BEARER.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = STANDALONE_BEARER.matcher(sanitized).replaceAll("Bearer " + REDACTED);
        sanitized = DOUBLE_QUOTED_NAMED_SECRET.matcher(sanitized)
                .replaceAll(match -> match.group(1) + "\"" + REDACTED + "\"");
        sanitized = SINGLE_QUOTED_NAMED_SECRET.matcher(sanitized)
                .replaceAll(match -> match.group(1) + "'" + REDACTED + "'");
        sanitized = UNCLOSED_DOUBLE_QUOTED_NAMED_SECRET.matcher(sanitized)
                .replaceAll(match -> match.group(1) + "\"" + REDACTED + "\"");
        sanitized = UNCLOSED_SINGLE_QUOTED_NAMED_SECRET.matcher(sanitized)
                .replaceAll(match -> match.group(1) + "'" + REDACTED + "'");
        return UNQUOTED_NAMED_SECRET.matcher(sanitized)
                .replaceAll(match -> match.group(1) + REDACTED);
    }
}
