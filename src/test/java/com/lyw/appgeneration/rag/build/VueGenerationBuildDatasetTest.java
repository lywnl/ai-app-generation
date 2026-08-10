package com.lyw.appgeneration.rag.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueGenerationBuildDatasetTest {

    private static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "基础站点",
            "登录注册",
            "管理后台",
            "表格设置",
            "电商列表详情",
            "内容门户文章",
            "ECharts看板",
            "文件上传",
            "聊天界面",
            "复合长需求");

    @Test
    void providesExactlyTenIndependentRealGenerationCases() throws Exception {
        VueGenerationBuildDataset dataset = VueGenerationBuildDataset.load(
                "rag/vue-generation-build-cases.json", new ObjectMapper());

        assertEquals(10, dataset.cases().size());
        assertEquals(10, dataset.cases().stream()
                .map(VueGenerationBuildCase::caseId).distinct().count());
        assertEquals(10, dataset.cases().stream()
                .map(VueGenerationBuildCase::appId).distinct().count());
        assertEquals(REQUIRED_CATEGORIES, dataset.cases().stream()
                .map(VueGenerationBuildCase::category).collect(Collectors.toSet()));
        assertTrue(dataset.cases().stream().allMatch(testCase -> !testCase.prompt().isBlank()));
        assertTrue(dataset.cases().stream()
                .filter(testCase -> "复合长需求".equals(testCase.category()))
                .allMatch(testCase -> testCase.prompt().length() >= 100));
    }
}
