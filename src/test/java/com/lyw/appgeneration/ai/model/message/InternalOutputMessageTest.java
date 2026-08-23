package com.lyw.appgeneration.ai.model.message;

import dev.langchain4j.service.GenerationStreamSignal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalOutputMessageTest {

    @Test
    void 回滚消息必须完整保留受信信号且拒绝非法字段() {
        GenerationStreamSignal.Rollback signal =
                new GenerationStreamSignal.Rollback(
                        7L, 3, Set.of("tool-1"));

        InternalOutputRollbackMessage message =
                new InternalOutputRollbackMessage(signal);

        assertEquals(StreamMessageTypeEnum.INTERNAL_OUTPUT_ROLLBACK.getValue(),
                message.getType());
        assertEquals(7L, message.getFailedGeneration());
        assertEquals(3, message.getCodePoints());
        assertEquals(Set.of("tool-1"),
                message.getProvisionalToolRequestIds());
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRollbackMessage(
                        0L, 0, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRollbackMessage(
                        1L, -1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRollbackMessage(
                        1L, 0, Set.of(" ")));
    }

    @Test
    void 恢复消息必须保留阶段与代次并复用框架校验() {
        GenerationStreamSignal.Recovery signal =
                new GenerationStreamSignal.Recovery(
                        GenerationStreamSignal.Recovery.Phase.STARTED,
                        3L, 4L, null);

        InternalOutputRecoveryMessage message =
                new InternalOutputRecoveryMessage(signal);

        assertEquals(StreamMessageTypeEnum.INTERNAL_OUTPUT_RECOVERY.getValue(),
                message.getType());
        assertEquals(GenerationStreamSignal.Recovery.Phase.STARTED,
                message.getPhase());
        assertEquals(3L, message.getOriginalFailedGeneration());
        assertEquals(4L, message.getRecoveryGeneration());
        assertEquals(null, message.getFailedGeneration());
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRecoveryMessage(
                        GenerationStreamSignal.Recovery.Phase.STARTED,
                        0L, 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalOutputRecoveryMessage(
                        GenerationStreamSignal.Recovery.Phase.RECOVERED,
                        3L, null, null));
    }

    @Test
    void 可信工具展示只允许请求与执行两个受信阶段() {
        TrustedToolDisplayMessage message = new TrustedToolDisplayMessage(
                2L, "tool-1", TrustedToolDisplayMessage.Stage.EXECUTED,
                "文件已经落盘");

        assertEquals(2L, message.generation());
        assertEquals("tool-1", message.toolRequestId());
        assertEquals(TrustedToolDisplayMessage.Stage.EXECUTED,
                message.stage());
        assertEquals("文件已经落盘", message.text());
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedToolDisplayMessage(
                        2L, " ", TrustedToolDisplayMessage.Stage.REQUESTED,
                        "正在执行"));
        assertThrows(NullPointerException.class,
                () -> new TrustedToolDisplayMessage(
                        2L, "tool-1", null, "正在执行"));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedToolDisplayMessage(
                        2L, "tool-1", TrustedToolDisplayMessage.Stage.REQUESTED,
                        ""));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedToolDisplayMessage(
                        0L, "tool-1",
                        TrustedToolDisplayMessage.Stage.REQUESTED,
                        "正在执行"));
    }

    @Test
    void 在线Vue业务消息必须拒绝零值generation() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiResponseMessage(0L, "正文"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRequestMessage(
                        0L, "tool-1", "writeFile", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolArgumentMessage(
                        0L, "tool-1", "writeFile", "content", "正文"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolArgumentDeltaMessage(
                        0L, "tool-1", "writeFile", "content", "正文"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExecutedMessage(
                        0L, dev.langchain4j.service.tool.ToolExecution.builder()
                        .request(dev.langchain4j.agent.tool.ToolExecutionRequest
                                .builder().id("tool-1").name("writeFile")
                                .arguments("{}").build())
                        .result("{}").build()));

        AiResponseMessage message = new AiResponseMessage();
        assertThrows(IllegalArgumentException.class,
                () -> message.setGeneration(0L));
    }
}
