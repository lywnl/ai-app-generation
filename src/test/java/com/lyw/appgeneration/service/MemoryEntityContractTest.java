package com.lyw.appgeneration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.mybatisflex.annotation.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEntityContractTest {

    @Test
    void 新持久化字段显式映射且历史响应隔离内部证据() throws Exception {
        for (String name : new String[]{"summaryRevision", "coverageVersion", "coverageState"}) {
            assertEquals(name, AppMemorySummary.class.getDeclaredField(name).getAnnotation(Column.class).value());
        }
        assertEquals("decisionEvidence", ChatHistory.class.getDeclaredField("decisionEvidence")
                .getAnnotation(Column.class).value());
        var history = ChatHistory.builder().id(10L).message("公开消息")
                .decisionEvidence("内部来源原文").build();
        var json = new ObjectMapper().valueToTree(history);
        assertFalse(json.has("decisionEvidence"));
        assertEquals("公开消息", json.get("message").asText());
        assertEquals("内部来源原文", history.getDecisionEvidence());
    }
}
