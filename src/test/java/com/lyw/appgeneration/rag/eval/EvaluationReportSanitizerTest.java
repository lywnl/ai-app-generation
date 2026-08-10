package com.lyw.appgeneration.rag.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvaluationReportSanitizerTest {

    @Test
    void sanitizesAbsoluteUserPathsAcrossSupportedOperatingSystems() {
        String input = """
                /home/alice/private/project/package.json
                /Users/bob/private/project/package.json
                C:\\Users\\carol\\private\\project\\package.json
                """;

        String sanitized = EvaluationReportSanitizer.sanitize(input);

        assertEquals(3, sanitized.lines().filter("<用户路径>"::equals).count());
        assertFalse(sanitized.contains("alice"));
        assertFalse(sanitized.contains("bob"));
        assertFalse(sanitized.contains("carol"));
    }

    @Test
    void sanitizesAuthorizationAndStandaloneBearerCredentials() {
        String input = """
                Authorization: Bearer authorization-secret<br>build failed
                请求失败 Bearer standalone-secret
                authorization=bearer query-secret
                """;

        String sanitized = EvaluationReportSanitizer.sanitize(input);

        assertFalse(sanitized.contains("authorization-secret"));
        assertFalse(sanitized.contains("standalone-secret"));
        assertFalse(sanitized.contains("query-secret"));
        assertFalse(sanitized.contains("authorization-secret"));
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("<br>build failed"));
        assertEquals(3, sanitized.lines().filter(line -> line.contains("<已脱敏>")).count());
    }

    @Test
    void sanitizesNamedSecretsInAssignmentsJsonAndUrlQueries() {
        String input = """
                api-key=plain-api-key
                token: plain-token
                password = plain-password
                secret: plain-secret
                {"api_key":"json-api-key","token":"json-token"}
                https://example.test/build?password=url-password&secret=url-secret&safe=visible
                """;

        String sanitized = EvaluationReportSanitizer.sanitize(input);

        for (String secret : new String[]{
                "plain-api-key", "plain-token", "plain-password", "plain-secret",
                "json-api-key", "json-token", "url-password", "url-secret"}) {
            assertFalse(sanitized.contains(secret), secret + " 不得进入报告");
        }
        assertFalse(sanitized.contains("api-key=plain"));
        assertEquals(8, sanitized.lines()
                .flatMap(line -> java.util.regex.Pattern.compile("<已脱敏>").matcher(line).results())
                .count());
        assertFalse(sanitized.contains("safe=<已脱敏>"));
    }

    @Test
    void preservesOrdinaryDiagnosticTextWithoutCredentialSyntax() {
        String ordinary = "token 计数为 128，password policy 校验通过，secret manager 不可用，Bearer 类型说明";

        assertEquals(ordinary, EvaluationReportSanitizer.sanitize(ordinary));
        assertEquals("", EvaluationReportSanitizer.sanitize(null));
        assertEquals("", EvaluationReportSanitizer.sanitize("   "));
    }
}
