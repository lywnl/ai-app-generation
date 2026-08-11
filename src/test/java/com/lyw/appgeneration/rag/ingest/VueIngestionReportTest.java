package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueIngestionReportTest {

    @Test
    void 未执行失败和通过状态不会互相混淆() {
        String notExecuted = VueIngestionReport.notExecuted(
                List.of("缺少环境变量 DASHSCOPE_API_KEY")).renderMarkdown();
        String failed = VueIngestionReport.failed(null, List.of("缺少表 templates_vue"))
                .renderMarkdown();
        VueIngestionExpectedSnapshot expected = expectedSnapshot();
        String passed = VueIngestionReport.verified(
                expected, verification("a".repeat(64), true, 23, 23, 4)).renderMarkdown();
        VueIngestionReport verificationFailed = VueIngestionReport.verified(
                expected, verification("a".repeat(64), false, 23, 22, 4));

        assertTrue(notExecuted.contains("状态：未执行"));
        assertTrue(failed.contains("状态：未通过"));
        assertTrue(notExecuted.contains("目标：PGVector/templates_vue"));
        assertFalse(failed.contains("目录版本："));
        assertTrue(passed.contains("状态：通过"));
        assertTrue(passed.contains("当前版本行数：23/23"));
        assertTrue(passed.contains("历史版本行数：4"));
        assertTrue(verificationFailed.renderMarkdown().contains("状态：未通过"));
        assertFalse(verificationFailed.passed());
    }

    @Test
    void 报告不泄漏凭据源码检索文本或向量() {
        String targetSecret = "sk-secret:5432/rag";
        String catalogSecret = "a".repeat(64);
        String failed = VueIngestionReport.failed(
                null,
                List.of("password=database-secret Authorization: Bearer token-secret",
                        "<template>私有源码</template>", "[0.1, 0.2, 0.3]",
                        "缺少环境变量 <template>源码</template>",
                        "真实摄取依赖失败: [0.1, 0.2]",
                        targetSecret, catalogSecret))
                .renderMarkdown();
        String notExecuted = VueIngestionReport.notExecuted(
                List.of("PGVector 端口不可达: <template>源码</template>:5432"))
                .renderMarkdown();

        assertFalse(failed.contains("database-secret"));
        assertFalse(failed.contains("token-secret"));
        assertFalse(failed.contains("<template>"));
        assertFalse(failed.contains("[0.1,"));
        assertFalse(failed.contains(targetSecret));
        assertFalse(failed.contains(catalogSecret));
        assertFalse(notExecuted.contains("database-secret"));
        assertFalse(notExecuted.contains("<template>"));
        assertFalse(notExecuted.contains("[0.1,"));
    }

    @Test
    void 公开报告工厂不接受可回显的目标或目录版本字符串() {
        boolean acceptsUntrustedString = Arrays.stream(VueIngestionReport.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("notExecuted")
                        || method.getName().equals("failed")
                        || method.getName().equals("verified"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(String.class::equals);

        assertFalse(acceptsUntrustedString);
    }

    private VueIngestionExpectedSnapshot expectedSnapshot() {
        return VueIngestionExpectedSnapshot.from(new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper()));
    }

    private VueIngestionVerification verification(
            String untrustedCatalogVersion,
            boolean passed,
            int expectedCount,
            int actualCount,
            long historicalCount) {
        return new VueIngestionVerification(
                passed, untrustedCatalogVersion, expectedCount, actualCount, historicalCount,
                Set.of(1024), List.of());
    }

}
