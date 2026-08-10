package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueGenerationBuildReportTest {

    @Test
    void unexecutedReportNeverClaimsTenOfTen() {
        String markdown = VueGenerationBuildReport.notExecuted(
                List.of("缺少环境变量 DEEPSEEK_API_KEY")).renderMarkdown();

        assertTrue(markdown.contains("状态：未执行"));
        assertTrue(markdown.contains("DEEPSEEK_API_KEY"));
        assertFalse(markdown.contains("10/10"));
        assertFalse(markdown.contains("状态：通过"));
    }

    @Test
    void executedReportRendersEveryDiagnosticAndSanitizesSecretsAndUserPaths() {
        VueGenerationBuildCase testCase = new VueGenerationBuildCase(
                "case-01", "基础站点", "需求");
        String sensitiveOutput = """
                /Users/example/private/project
                api-key=abcd1234
                token: bearer-value
                password=plain-text
                build failed
                """;
        BuildResult buildResult = new BuildResult(
                false, BuildStage.NPM_BUILD, 2, false, sensitiveOutput, 250L);
        VueGenerationBuildRow row = new VueGenerationBuildRow(
                testCase,
                7001L,
                true,
                "vue-skeleton-basic-001",
                List.of("vue-login-form-001"),
                buildResult,
                null);

        String markdown = VueGenerationBuildReport.executed(List.of(row)).renderMarkdown();

        assertTrue(markdown.contains("状态：未通过"));
        assertTrue(markdown.contains("case-01"));
        assertTrue(markdown.contains("7001"));
        assertTrue(markdown.contains("vue-skeleton-basic-001"));
        assertTrue(markdown.contains("vue-login-form-001"));
        assertTrue(markdown.contains("NPM_BUILD"));
        assertTrue(markdown.contains("2"));
        assertTrue(markdown.contains("false"));
        assertTrue(markdown.contains("build failed"));
        assertTrue(markdown.contains("<用户路径>"));
        assertTrue(markdown.contains("<已脱敏>"));
        assertFalse(markdown.contains("/Users/example"));
        assertFalse(markdown.contains("abcd1234"));
        assertFalse(markdown.contains("bearer-value"), markdown);
        assertFalse(markdown.contains("plain-text"));
    }

    @Test
    void sanitizesEveryFinalReportFieldIncludingReasonAndIdentifiers() {
        String notExecuted = VueGenerationBuildReport.notExecuted(List.of(
                "检查 /home/alice/private 失败，Authorization: Bearer reason-secret"))
                .renderMarkdown();
        VueGenerationBuildCase testCase = new VueGenerationBuildCase(
                "case-token=case-secret", "password: category-secret", "需求");
        VueGenerationBuildRow row = new VueGenerationBuildRow(
                testCase,
                7002L,
                false,
                "secret=skeleton-secret",
                List.of("api-key=feature-secret"),
                null,
                "Bearer row-secret");

        String executed = VueGenerationBuildReport.executed(List.of(row)).renderMarkdown();

        assertFalse(notExecuted.contains("alice"));
        assertFalse(notExecuted.contains("reason-secret"));
        for (String secret : new String[]{
                "case-secret", "category-secret", "skeleton-secret", "feature-secret", "row-secret"}) {
            assertFalse(executed.contains(secret), secret + " 不得从任何报告字段泄漏");
        }
    }
}
