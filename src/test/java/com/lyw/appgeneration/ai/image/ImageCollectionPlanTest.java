package com.lyw.appgeneration.ai.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ImageCollectionPlanTest {
    @Test
    void 规划格式和提示词只提供照片与Logo任务() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var plan = mapper.readValue("""
                {"contentImageTasks":[{"query":"咖啡店"}],"logoTasks":[{"description":"简洁品牌"}]}
                """, ImageCollectionPlan.class);
        var serialized = mapper.valueToTree(plan);
        assertEquals(2, serialized.size());
        assertEquals("咖啡店", serialized.path("contentImageTasks").get(0).path("query").asText());
        assertEquals("简洁品牌", serialized.path("logoTasks").get(0).path("description").asText());
        try (var input = getClass().getResourceAsStream("/prompt/image-collection-plan-system-prompt.txt")) {
            assertNotNull(input);
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(prompt.contains("contentImageTasks"));
            assertTrue(prompt.contains("logoTasks"));
            assertTrue(prompt.contains("最多 6 个不同的搜索主题"));
            assertTrue(prompt.contains("每个关键词最多返回 3 张"));
            assertTrue(prompt.contains("合计最多 18 张"));
            assertTrue(prompt.contains("最多 1 个生成任务"));
            assertTrue(prompt.contains("相同或近似主题合并"));
            assertTrue(prompt.contains("不要求凑满"));
            assertFalse(prompt.contains("illustrationTasks"));
            assertFalse(prompt.contains("Undraw"));
        }
    }
}
