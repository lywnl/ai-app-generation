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
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:api[-_]?key|token|password|secret)[\\\"']?"
                    + "\\s*[:=]\\s*[\\\"']?)([^\\\"'<>\\s|&,}\\]]+)([\\\"']?)");

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
        return NAMED_SECRET.matcher(sanitized)
                .replaceAll(match -> match.group(1) + REDACTED + match.group(3));
    }
}
