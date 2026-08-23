package com.lyw.appgeneration.web;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.model.message.InternalOutputRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.InternalOutputRollbackMessage;
import com.lyw.appgeneration.ai.model.message.TrustedToolDisplayMessage;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import dev.langchain4j.service.GenerationStreamSignal;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSseEncoderTest {

    @Test
    void 正常业务帧与done必须共享连续响应序号() {
        GenerationSseEncoder encoder = new GenerationSseEncoder();

        ServerSentEvent<String> simple = encoder.business(
                GenerationStreamEvent.simpleText("简单正文"));
        ServerSentEvent<String> ai = encoder.business(
                GenerationStreamEvent.aiText(9L, "Vue正文"));
        ServerSentEvent<String> tool = encoder.business(
                GenerationStreamEvent.structuredToolEvent(
                        9L, "{\"type\":\"tool_request\"}"));
        ServerSentEvent<String> done = encoder.done();

        assertMessage(simple, 1L, "simple_text", "简单正文", null);
        assertMessage(ai, 2L, "ai_text", "Vue正文", "9");
        assertMessage(tool, 3L, "structured_tool_event",
                "{\"type\":\"tool_request\"}", "9");
        assertEquals("done", done.event());
        assertEquals("generation-stream/v1",
                JSONUtil.parseObj(done.data()).getStr("protocol"));
        assertEquals(4L, JSONUtil.parseObj(done.data()).getLong("sequence"));
    }

    @Test
    void 回滚恢复和可信工具展示必须编码受信字段与字符串代次() {
        GenerationSseEncoder encoder = new GenerationSseEncoder();
        var display = encoder.business(
                GenerationStreamEvent.trustedToolDisplay(
                        new TrustedToolDisplayMessage(
                                3L, "tool-1",
                                TrustedToolDisplayMessage.Stage.REQUESTED,
                                "正在选择工具")));
        var rollback = encoder.business(GenerationStreamEvent.rollback(
                new InternalOutputRollbackMessage(
                        3L, 2, Set.of("tool-1"))));
        var recovery = encoder.business(
                GenerationStreamEvent.internalRecovery(
                        new InternalOutputRecoveryMessage(
                                GenerationStreamSignal.Recovery.Phase.STARTED,
                                3L, 4L, null)));

        var displayData = JSONUtil.parseObj(display.data());
        assertEquals("trusted-tool-display", display.event());
        assertEquals("3", displayData.getStr("generation"));
        assertEquals("REQUESTED", displayData.getStr("stage"));
        assertEquals(1L, displayData.getLong("sequence"));

        var rollbackData = JSONUtil.parseObj(rollback.data());
        assertEquals("internal-output-rollback", rollback.event());
        assertEquals("3", rollbackData.getStr("failedGeneration"));
        assertEquals(2, rollbackData.getInt("codePoints"));
        assertEquals(List.of("tool-1"),
                rollbackData.getJSONArray("provisionalToolRequestIds")
                        .toList(String.class));
        assertEquals(2L, rollbackData.getLong("sequence"));

        var recoveryData = JSONUtil.parseObj(recovery.data());
        assertEquals("internal-output-recovery", recovery.event());
        assertEquals("3", recoveryData.getStr("originalFailedGeneration"));
        assertEquals("4", recoveryData.getStr("recoveryGeneration"));
        assertTrue(recoveryData.containsKey("failedGeneration"));
        assertTrue(JSONUtil.isNull(recoveryData.get("failedGeneration")));
        assertEquals("检测到生成状态异常，正在重新生成…",
                recoveryData.getStr("message"));
        assertEquals(3L, recoveryData.getLong("sequence"));
    }

    @Test
    void heartbeat和业务错误不编号且业务错误不重置done计数器() {
        GenerationSseEncoder encoder = new GenerationSseEncoder();
        encoder.business(GenerationStreamEvent.simpleText("第一段"));

        var heartbeat = encoder.heartbeat(123L);
        var error = encoder.businessError(
                GenerationPreflightException.business(
                        ErrorCode.OPERATION_ERROR.getCode(),
                        "请求被拒绝", null));
        var done = encoder.done();

        assertFalse(JSONUtil.parseObj(heartbeat.data())
                .containsKey("sequence"));
        assertFalse(JSONUtil.parseObj(error.data()).containsKey("sequence"));
        assertEquals(2L, JSONUtil.parseObj(done.data()).getLong("sequence"));
        assertThrows(IllegalStateException.class, encoder::done);
        assertThrows(IllegalStateException.class,
                () -> encoder.business(
                        GenerationStreamEvent.simpleText("迟到正文")));

        GenerationSseEncoder errorOnly = new GenerationSseEncoder();
        errorOnly.businessError(GenerationPreflightException.system(
                new IllegalStateException("前置失败")));
        assertThrows(IllegalStateException.class,
                () -> errorOnly.heartbeat(456L));
    }

    @Test
    void 前置错误线格式必须以done序号一闭合() {
        GenerationSseEncoder encoder = new GenerationSseEncoder();
        String wire = encoder.preflightWire(
                GenerationPreflightException.system(
                        new IllegalStateException("不得泄漏")));

        assertTrue(wire.startsWith("event: business-error\n"));
        assertTrue(wire.contains("\n\nevent: done\n"));
        assertTrue(wire.contains("\"protocol\":\"generation-stream/v1\""));
        assertTrue(wire.contains("\"sequence\":1"));
        assertFalse(wire.contains("不得泄漏"));
    }

    @Test
    void 业务帧编码失败不得消耗响应序号() {
        GenerationSseEncoder encoder = new GenerationSseEncoder();
        encoder.business(GenerationStreamEvent.simpleText("第一段"));

        assertThrows(NullPointerException.class,
                () -> encoder.business(null));

        var error = encoder.businessError(
                GenerationPreflightException.system(
                        new IllegalStateException("编码失败")));
        var done = encoder.done();

        assertFalse(JSONUtil.parseObj(error.data()).containsKey("sequence"));
        assertEquals(2L, JSONUtil.parseObj(done.data()).getLong("sequence"));
    }

    private void assertMessage(
            ServerSentEvent<String> event, long sequence,
            String kind, String data, String generation) {
        assertEquals("message", event.event());
        var payload = JSONUtil.parseObj(event.data());
        assertEquals("generation-stream/v1", payload.getStr("protocol"));
        assertEquals(sequence, payload.getLong("sequence"));
        assertEquals(kind, payload.getStr("kind"));
        assertEquals(data, payload.getStr("data"));
        if (generation == null) {
            assertFalse(payload.containsKey("generation"));
        } else {
            assertEquals(generation, payload.getStr("generation"));
        }
        assertFalse(payload.containsKey("d"));
    }
}
