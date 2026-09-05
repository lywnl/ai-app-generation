package com.lyw.appgeneration.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillToolProtocolSupportTest {

    @Test
    void 成功协议保留正文且字段固定() {
        String raw = SkillToolProtocolSupport.json(SkillToolResult.applied(
                "vue-frontend-design", "正文哨兵"));

        SkillToolResult result = SkillToolProtocolSupport.parse(
                raw, "readSkill", "vue-frontend-design");

        assertEquals(SkillToolResult.Status.APPLIED, result.status());
        assertEquals("正文哨兵", result.content());
    }

    @Test
    void 客户端副本强制丢弃正文且拒绝未知字段() {
        String raw = SkillToolProtocolSupport.json(SkillToolResult.applied(
                "vue-frontend-design", "正文哨兵"));

        String safe = SkillToolProtocolSupport.clientSafeResult(raw);

        assertEquals(null, SkillToolProtocolSupport.parse(
                safe, "readSkill", "vue-frontend-design").content());
        assertThrows(IllegalArgumentException.class, () ->
                SkillToolProtocolSupport.parse(
                        raw.replace("}", ",\"extra\":true}"),
                        "readSkill", "vue-frontend-design"));
    }

    @Test
    void 失败协议不得携带正文() {
        String raw = SkillToolProtocolSupport.json(SkillToolResult.rejected(
                "vue-frontend-design", "RESOURCE_LIMIT_EXCEEDED"));

        assertNull(SkillToolProtocolSupport.parse(
                raw, "readSkill", "vue-frontend-design").content());
    }
}
