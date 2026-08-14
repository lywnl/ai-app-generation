package com.lyw.appgeneration.ai.parser;

import com.lyw.appgeneration.ai.parser.ToolRequestStreamParser.ArgEvent;
import com.lyw.appgeneration.ai.parser.ToolRequestStreamParser.ArgEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 不依赖 Spring 的纯单元测试,只测状态机。
 */
class ToolRequestStreamParserTest {

    /** 流式代码只以增量事件对外暴露，避免解析器额外保留无界完整副本。 */
    @Test
    void 流式代码字段只产生增量且不累积完整值() {
        List<ArgEvent> writeEvents = ToolRequestStreamParser.collect(
                "writeFile",
                "{\"relativeFilePath\":\"src/a.vue\",\"content\":\"hello\\nworld\"}");
        List<ArgEvent> modifyEvents = ToolRequestStreamParser.collect(
                "modifyFile",
                "{\"relativeFilePath\":\"src/a.vue\","
                        + "\"oldContent\":\"A\\uD83D\\uDE00B\","
                        + "\"newContent\":\"C\\nD\"}");

        assertLastValueReady(writeEvents, "relativeFilePath", "src/a.vue");
        assertDeltaContent(writeEvents, "content", "hello\nworld");
        assertNoValueReady(writeEvents, "content");

        assertLastValueReady(modifyEvents, "relativeFilePath", "src/a.vue");
        assertDeltaContent(modifyEvents, "oldContent", "A😀B");
        assertDeltaContent(modifyEvents, "newContent", "C\nD");
        assertNoValueReady(modifyEvents, "oldContent");
        assertNoValueReady(modifyEvents, "newContent");
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
        assertNoValueReady(events, "content");
    }

    /** 转义字符落在 chunk 边界:反斜杠在前一片末尾,被转义字符在后一片开头 */
    @Test
    void escape_across_chunk_boundary_preserved() {
        List<ArgEvent> events = feedChunks("writeFile", List.of(
                "{\"content\":\"a\\", "\"b\"}"));
        // value 应为 a"b
        assertDeltaContent(events, "content", "a\"b");
        assertNoValueReady(events, "content");
    }

    @Test
    void unicode_escape_and_surrogate_pair_across_chunks_are_decoded() {
        List<ArgEvent> events = feedChunks("writeFile", List.of(
                "{\"content\":\"A\\uD83D", "\\uDE00B\"}"));

        assertDeltaContent(events, "content", "A😀B");
        assertNoValueReady(events, "content");
    }

    @Test
    void finish_waits_for_in_progress_feed_and_emits_each_value_once() throws Exception {
        List<ArgEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch keyCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseKeyCallback = new CountDownLatch(1);
        CountDownLatch finishTaskStarted = new CountDownLatch(1);
        ToolRequestStreamParser parser = new ToolRequestStreamParser("writeFile", event -> {
            events.add(event);
            if (event.type == ArgEventType.KEY_READY && "content".equals(event.key)) {
                keyCallbackEntered.countDown();
                awaitLatch(releaseKeyCallback);
            }
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> feedFuture = executor.submit(
                    () -> parser.feed("{\"content\":\"A\\uD83D\\uDE00B\"}"));
            assertTrue(keyCallbackEntered.await(1, TimeUnit.SECONDS));

            Future<?> finishFuture = executor.submit(() -> {
                finishTaskStarted.countDown();
                parser.finish();
            });
            assertTrue(finishTaskStarted.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> finishFuture.get(100, TimeUnit.MILLISECONDS));

            releaseKeyCallback.countDown();
            feedFuture.get(1, TimeUnit.SECONDS);
            finishFuture.get(1, TimeUnit.SECONDS);
        }

        assertEquals(0, events.stream()
                .filter(event -> event.type == ArgEventType.VALUE_READY)
                .count());
        assertDeltaContent(events, "content", "A😀B");
    }

    @Test
    void feed_after_finish_does_not_change_output() {
        List<ArgEvent> events = new ArrayList<>();
        ToolRequestStreamParser parser = new ToolRequestStreamParser("writeFile", events::add);
        parser.feed("{\"content\":\"before");
        parser.finish();
        List<ArgEvent> snapshot = List.copyOf(events);

        parser.feed(" after finish\"}");
        parser.finish();

        assertEquals(snapshot, events);
        assertEquals(0, events.stream()
                .filter(event -> event.type == ArgEventType.VALUE_READY)
                .count());
    }

    /** 多参数顺序随意，路径保留完整值，代码字段只保留增量。 */
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
                "relativeFilePath=x.vue"
        ), keyValPairs);
        assertDeltaContent(events, "oldContent", "old");
        assertDeltaContent(events, "newContent", "new");
        assertNoValueReady(events, "oldContent");
        assertNoValueReady(events, "newContent");
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

    private static void assertNoValueReady(List<ArgEvent> events, String key) {
        assertTrue(events.stream().noneMatch(event ->
                event.type == ArgEventType.VALUE_READY && key.equals(event.key)));
    }

    private static void assertDeltaContent(
            List<ArgEvent> events, String key, String expected) {
        String actual = events.stream()
                .filter(event -> event.type == ArgEventType.DELTA
                        && key.equals(event.key))
                .map(event -> event.payload)
                .reduce("", String::concat);
        assertEquals(expected, actual);
    }

    private static List<String> filterNonDelta(List<ArgEvent> events) {
        return events.stream()
                .filter(e -> e.type != ArgEventType.DELTA)
                .map(e -> e.type + "|" + e.key + "|" + e.payload)
                .toList();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("等待并发测试屏障超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发测试屏障时被中断", exception);
        }
    }
}
