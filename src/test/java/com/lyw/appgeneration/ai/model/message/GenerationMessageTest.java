package com.lyw.appgeneration.ai.model.message;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationMessageTest {



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
