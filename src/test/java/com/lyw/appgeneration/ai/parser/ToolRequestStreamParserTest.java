package com.lyw.appgeneration.ai.parser;

import com.lyw.appgeneration.ai.parser.ToolRequestStreamParser.ArgEvent;
import com.lyw.appgeneration.ai.parser.ToolRequestStreamParser.ArgEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 不依赖 Spring 的纯单元测试,只测状态机。
 */
class ToolRequestStreamParserTest {

    /** 整包喂入:writeFile 的 content 为 streaming 字段,应既有 DELTA 又有 VALUE_READY */
    @Test
    void writeFile_full_json_emits_keyReady_delta_and_valueReady() {
        List<ArgEvent> events = ToolRequestStreamParser.collect(
                "writeFile",
                "{\"relativeFilePath\":\"src/a.vue\",\"content\":\"hello\\nworld\"}");

        // 期望:KEY relativeFilePath -> VALUE src/a.vue -> KEY content -> DELTA(hello\nworld) -> VALUE hello\nworld
        assertEvent(events.get(0), ArgEventType.KEY_READY,   "relativeFilePath", null);
        assertEvent(events.get(1), ArgEventType.VALUE_READY, "relativeFilePath", "src/a.vue");
        assertEvent(events.get(2), ArgEventType.KEY_READY,   "content",          null);
        assertEvent(events.get(3), ArgEventType.DELTA,       "content",          "hello\nworld");
        assertEvent(events.get(4), ArgEventType.VALUE_READY, "content",          "hello\nworld");
        assertEquals(5, events.size());
    }

    /** 非 streaming 字段(readFile.relativeFilePath)不应产生 DELTA */
    @Test
    void nonStreaming_field_emits_no_delta_even_when_chunked() {
        List<ArgEvent> events = feedChunks("readFile", List.of(
                "{\"", "relativeFilePath", "\":", " \"", "src", "/", "b.vue\"}"));

        long deltaCount = events.stream().filter(e -> e.type == ArgEventType.DELTA).count();
        assertEquals(0, deltaCount);
        assertLastValueReady(events, "relativeFilePath", "src/b.vue");
    }

    /** 模拟用户给出的真实 chunk 序列:逐字符/逐片段喂入,结果必须与整包等价 */
    @Test
    void chunked_feed_equivalent_to_full_feed() {
        String full = "{\"relativeFilePath\": \"src/pages/Resume.vue\",\"oldContent\":\"A\",\"newContent\":\"B\"}";
        List<ArgEvent> fullEvents  = ToolRequestStreamParser.collect("modifyFile", full);

        // 逐字符
        List<ArgEvent> charEvents = new ArrayList<>();
        ToolRequestStreamParser p = new ToolRequestStreamParser("modifyFile", charEvents::add);
        for (int i = 0; i < full.length(); i++) p.feed(String.valueOf(full.charAt(i)));
        p.finish();

        // 两者的 KEY_READY / VALUE_READY 序列应该完全一致(DELTA 数量可能因缓冲策略不同)
        assertEquals(filterNonDelta(fullEvents), filterNonDelta(charEvents));
    }

    /** streaming 字段跨 chunk 时,DELTA 应合起来等于完整 value */
    @Test
    void streaming_delta_concatenation_equals_final_value() {
        List<ArgEvent> events = feedChunks("writeFile", List.of(
                "{\"content\":\"", "line1\\n", "line2\\n", "line3\"}"));

        String joinedDelta = events.stream()
                .filter(e -> e.type == ArgEventType.DELTA)
                .map(e -> e.payload).reduce("", String::concat);
        assertEquals("line1\nline2\nline3", joinedDelta);
        assertLastValueReady(events, "content", "line1\nline2\nline3");
    }

    /** 转义字符落在 chunk 边界:反斜杠在前一片末尾,被转义字符在后一片开头 */
    @Test
    void escape_across_chunk_boundary_preserved() {
        List<ArgEvent> events = feedChunks("writeFile", List.of(
                "{\"content\":\"a\\", "\"b\"}"));
        // value 应为 a"b
        assertLastValueReady(events, "content", "a\"b");
    }

    /** 多参数顺序随意,全部字符串 value 都要被正确识别 */
    @Test
    void multiple_fields_in_order() {
        List<ArgEvent> events = ToolRequestStreamParser.collect(
                "modifyFile",
                "{\"relativeFilePath\":\"x.vue\",\"oldContent\":\"old\",\"newContent\":\"new\"}");

        List<String> keyValPairs = new ArrayList<>();
        for (ArgEvent e : events) {
            if (e.type == ArgEventType.VALUE_READY) keyValPairs.add(e.key + "=" + e.payload);
        }
        assertEquals(List.of(
                "relativeFilePath=x.vue",
                "oldContent=old",
                "newContent=new"
        ), keyValPairs);
    }

    // ---------- helpers ----------

    private static List<ArgEvent> feedChunks(String tool, List<String> chunks) {
        List<ArgEvent> events = new ArrayList<>();
        ToolRequestStreamParser p = new ToolRequestStreamParser(tool, events::add);
        chunks.forEach(p::feed);
        p.finish();
        return events;
    }

    private static void assertEvent(ArgEvent e, ArgEventType t, String k, String v) {
        assertEquals(t, e.type, "type");
        assertEquals(k, e.key,  "key");
        assertEquals(v, e.payload, "payload");
    }

    private static void assertLastValueReady(List<ArgEvent> events, String key, String expected) {
        ArgEvent last = events.stream()
                .filter(e -> e.type == ArgEventType.VALUE_READY && key.equals(e.key))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals(expected, last.payload);
    }

    private static List<String> filterNonDelta(List<ArgEvent> events) {
        return events.stream()
                .filter(e -> e.type != ArgEventType.DELTA)
                .map(e -> e.type + "|" + e.key + "|" + e.payload)
                .toList();
    }
}
