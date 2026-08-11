package com.lyw.appgeneration.rag.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueIngestionReportTest {

    @Test
    void 未执行失败和通过状态不会互相混淆() {
        String notExecuted = VueIngestionReport.notExecuted(
                "127.0.0.1:5432/ai_codegen_rag",
                List.of("缺少环境变量 DASHSCOPE_API_KEY")).renderMarkdown();
        String failed = VueIngestionReport.failed(
                "127.0.0.1:5432/ai_codegen_rag", "catalog", List.of("缺少表 templates_vue"))
                .renderMarkdown();
        String passed = VueIngestionReport.verified(
                "127.0.0.1:5432/ai_codegen_rag",
                verification(true, 23, 23, 4)).renderMarkdown();
        VueIngestionReport verificationFailed = VueIngestionReport.verified(
                "127.0.0.1:5432/ai_codegen_rag",
                verification(false, 23, 22, 4));

        assertTrue(notExecuted.contains("状态：未执行"));
        assertTrue(failed.contains("状态：未通过"));
        assertTrue(passed.contains("状态：通过"));
        assertTrue(passed.contains("当前版本行数：23/23"));
        assertTrue(passed.contains("历史版本行数：4"));
        assertTrue(verificationFailed.renderMarkdown().contains("状态：未通过"));
        assertFalse(verificationFailed.passed());
    }

    @Test
    void 报告不泄漏凭据源码检索文本或向量() {
        String failed = VueIngestionReport.failed(
                "<template>私有目标</template>",
                "[0.1, 0.2, 0.3]",
                List.of("password=database-secret Authorization: Bearer token-secret",
                        "<template>私有源码</template>", "[0.1, 0.2, 0.3]",
                        "缺少环境变量 <template>源码</template>",
                        "真实摄取依赖失败: [0.1, 0.2]"))
                .renderMarkdown();
        String notExecuted = VueIngestionReport.notExecuted(
                "password=database-secret:<template>源码</template>/[0.1, 0.2]",
                List.of("PGVector 端口不可达: <template>源码</template>:5432"))
                .renderMarkdown();

        assertFalse(failed.contains("database-secret"));
        assertFalse(failed.contains("token-secret"));
        assertFalse(failed.contains("<template>"));
        assertFalse(failed.contains("[0.1,"));
        assertFalse(notExecuted.contains("database-secret"));
        assertFalse(notExecuted.contains("<template>"));
        assertFalse(notExecuted.contains("[0.1,"));
    }

    private VueIngestionVerification verification(
            boolean passed,
            int expectedCount,
            int actualCount,
            long historicalCount) {
        return new VueIngestionVerification(
                passed, "catalog", expectedCount, actualCount, historicalCount,
                Set.of(1024), List.of());
    }
}
