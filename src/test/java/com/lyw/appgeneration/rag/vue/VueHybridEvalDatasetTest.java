package com.lyw.appgeneration.rag.vue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueHybridEvalDatasetTest {

    private static final Set<String> STYLES = Set.of(
            "精确技术词", "同义", "长需求", "多功能", "陷阱");
    private static final Set<String> SKELETON_IDS = Set.of(
            "vue-skeleton-basic-001",
            "vue-skeleton-admin-001",
            "vue-skeleton-shop-001",
            "vue-skeleton-content-001",
            "vue-skeleton-dashboard-001");

    @Test
    void coversRequiredStylesSkeletonsQueriesAndCurrentParentIds() throws Exception {
        VueEvalDataset dataset = VueEvalDataset.load(
                "rag/vue-hybrid-eval-set.json", new ObjectMapper());
        List<VueEvalCase> cases = dataset.queries();

        assertTrue(cases.size() >= 30);
        assertEquals(cases.size(), cases.stream().map(VueEvalCase::queryId).distinct().count());
        assertEquals(STYLES, cases.stream().map(VueEvalCase::queryStyle).collect(Collectors.toSet()));
        assertTrue(cases.stream().allMatch(evalCase -> !evalCase.expectedSkeletonIds().isEmpty()));

        Map<String, Long> skeletonCoverage = cases.stream()
                .flatMap(evalCase -> evalCase.expectedSkeletonIds().stream())
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        SKELETON_IDS.forEach(id -> assertTrue(
                skeletonCoverage.getOrDefault(id, 0L) >= 4,
                id + " 的评测用例必须至少 4 条"));

        assertQueryExists(cases, query -> query.contains("package.json"));
        assertQueryExists(cases, query -> query.contains("vue-router hash"));
        assertQueryExists(cases, query -> query.contains("Element Plus"));
        assertQueryExists(cases, query -> query.contains("ECharts"));
        assertQueryExists(cases, query -> query.contains("登录") && query.contains("注册"));
        assertQueryExists(cases, query -> query.contains("管理后台")
                && query.contains("表格") && query.contains("设置"));
        assertQueryExists(cases, query -> query.contains("商品") && query.contains("订单"));
        assertQueryExists(cases, query -> query.contains("内容") && query.contains("文章"));
        assertQueryExists(cases, query -> query.contains("看板"));
        assertQueryExists(cases, query -> query.contains("数据展示"));
        assertQueryExists(cases, query -> query.contains("用户表单"));

        long longChineseRequirements = cases.stream()
                .filter(evalCase -> "长需求".equals(evalCase.queryStyle()))
                .filter(evalCase -> evalCase.query().length() >= 50)
                .count();
        assertTrue(longChineseRequirements >= 5);

        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        Set<String> currentParentIds = catalog.getDocuments().stream()
                .map(TemplateDoc::getId)
                .collect(Collectors.toSet());
        Set<String> annotatedIds = new HashSet<>();
        cases.forEach(evalCase -> {
            annotatedIds.addAll(evalCase.expectedSkeletonIds());
            annotatedIds.addAll(evalCase.expectedFeatureIds());
        });
        assertEquals(18, currentParentIds.size());
        assertTrue(currentParentIds.containsAll(annotatedIds));
        assertFalse(annotatedIds.isEmpty());
    }

    private void assertQueryExists(List<VueEvalCase> cases, Predicate<String> predicate) {
        assertTrue(cases.stream().map(VueEvalCase::query).anyMatch(predicate));
    }
}
