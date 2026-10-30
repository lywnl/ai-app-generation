package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Action;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserMemoryOperationParserTest {
    private final MemoryTokenProperties properties = new MemoryTokenProperties();
    private final UserMemoryOperationParser parser = new UserMemoryOperationParser(new UserPreferenceContract(
            new ConservativeChatTokenEstimator(properties), properties));
    private static final String ADD = """
            {"action":"ADD","category":"语言偏好","attribute":"页面默认语言","content":"默认日语",
             "scope":"USER","evidenceType":"EXPLICIT","turnIds":["11"]}
            """;

    @Test void 新增自由内容且不要求固定值代码() {
        var result = parser.parse(wrap(ADD), List.of(11L));
        assertNotNull(result);
        assertEquals("默认日语", result.getFirst().content());
        assertEquals(Action.ADD, result.getFirst().action());
    }

    @Test void 不限制同类别独立属性的存储条数() {
        String[] rows = new String[8];
        for (int i = 0; i < rows.length; i++) rows[i] = ADD.replace("页面默认语言", "属性" + i);
        assertEquals(8, parser.parse(wrap(String.join(",", rows)), List.of(11L)).size());
    }

    @Test void 空操作是合法批次() { assertEquals(List.of(), parser.parse(wrap(""), List.of(11L))); }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "null", "{\"operations\":null}", "{\"operations\":[],\"status\":\"ACTIVE\"}",
            "{\"operations\":[],\"operations\":[]}", "{\"operations\":[]} {}", "```json\n{\"operations\":[]}\n```"})
    void 非法结构整批拒绝(String raw) { assertNull(parser.parse(raw, List.of(11L))); }

    @ParameterizedTest
    @ValueSource(strings = {"11", "11.0", "\"11.0\"", "\"0\"", "\"-1\"", "\"011\"", "\"9223372036854775808\"", "null", "true"})
    void 证据ID严格使用正Long字符串(String id) {
        assertNull(parser.parse(wrap(ADD.replace("[\"11\"]", "[" + id + "]")), List.of(11L)));
    }

    @Test void 大Snowflake字符串不经过浮点数() {
        long id = 9223372036854775806L;
        assertEquals(List.of(id), parser.parse(wrap(ADD.replace("\"11\"", "\"" + id + "\"")), List.of(id))
                .getFirst().turnIds());
    }

    @Test void 非白名单证据即使其他操作合法也整批拒绝() {
        assertNull(parser.parse(wrap(ADD + "," + ADD.replace("\"11\"", "\"12\"")), List.of(11L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP", "THREAD"})
    void 应用和临时范围不写L2(String scope) {
        assertEquals(List.of(), parser.parse(wrap(ADD.replace("\"USER\"", "\"" + scope + "\"")), List.of(11L)));
    }

    @Test void 非法类别范围及模型越权字段拒绝() {
        assertNull(parser.parse(wrap(ADD.replace("语言偏好", "其他")), List.of(11L)));
        assertNull(parser.parse(wrap(ADD.replace("USER", "GLOBAL")), List.of(11L)));
        assertNull(parser.parse(wrap(ADD.replace("\"action\":", "\"userId\":\"7\",\"action\":")), List.of(11L)));
        assertNull(parser.parse(wrap(ADD.replace("\"action\":", "\"status\":\"ACTIVE\",\"action\":")), List.of(11L)));
    }

    @Test void 新增必须说明属性且不接受目标ID() {
        assertNull(parser.parse(wrap(ADD.replace("\"attribute\":\"页面默认语言\",", "")), List.of(11L)));
        assertNull(parser.parse(wrap(ADD.replace("\"action\":", "\"targetId\":\"123\",\"action\":")), List.of(11L)));
    }

    @Test void 替换需要目标类别和内容() {
        String replace = ADD.replace("\"ADD\"", "\"REPLACE\"")
                .replace("\"attribute\":\"页面默认语言\",", "\"targetId\":\"123\",");
        assertEquals(Action.REPLACE, parser.parse(wrap(replace), List.of(11L)).getFirst().action());
        assertNull(parser.parse(wrap(replace.replace("\"category\":\"语言偏好\",", "")), List.of(11L)));
    }

    @Test void 强化和撤销只指向目标且撤销必须明确() {
        String retract = """
                {"action":"RETRACT","targetId":"123","scope":"USER","evidenceType":"EXPLICIT","turnIds":["11"]}
                """;
        assertEquals(Action.RETRACT, parser.parse(wrap(retract), List.of(11L)).getFirst().action());
        assertNull(parser.parse(wrap(retract.replace("EXPLICIT", "IMPLICIT")), List.of(11L)));
        assertNull(parser.parse(wrap(retract.replace("\"action\":", "\"content\":\"中文\",\"action\":")), List.of(11L)));
    }

    @Test void 重复一致操作合并不同证据而冲突拒绝() {
        var result = parser.parse(wrap(ADD + "," + ADD.replace("\"11\"", "\"12\"")), List.of(11L, 12L));
        assertEquals(1, result.size());
        assertEquals(List.of(11L, 12L), result.getFirst().turnIds());
        assertNull(parser.parse(wrap(ADD + "," + ADD.replace("默认日语", "默认中文")), List.of(11L)));
    }

    @Test void 超限和不可见控制字符拒绝() {
        assertNull(parser.parse(wrap(ADD.replace("默认日语", "中文".repeat(2000))), List.of(11L)));
        assertNull(parser.parse(wrap(ADD.replace("默认日语", "默认\u202E日语")), List.of(11L)));
        properties.setMaxOutputTokens(1);
        assertNull(parser.parse(wrap(ADD), List.of(11L)));
    }

    private String wrap(String operations) { return "{\"operations\":[" + operations + "]}"; }
}
