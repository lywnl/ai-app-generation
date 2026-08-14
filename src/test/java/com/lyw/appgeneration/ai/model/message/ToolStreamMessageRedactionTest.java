package com.lyw.appgeneration.ai.model.message;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolStreamMessageRedactionTest {

    @Test
    void mutationClientCopyKeepsOnlyPathAndDoesNotMutateInternalMessage() {
        String secret = "绝密写入代码-7f4e";
        ToolExecutedMessage internal = message(
                "writeFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"" + secret + "\"}",
                fileResult("writeFile", "src/App.vue", "APPLIED", null));

        ToolExecutedMessage client = internal.toClientSafeCopy();

        assertTrue(client.getArguments().contains("src/App.vue"));
        assertFalse(client.getArguments().contains(secret));
        assertFalse(JSONUtil.toJsonStr(client).contains(secret));
        assertTrue(internal.getArguments().contains(secret),
                "内部消息仍需保留完整参数供稳定 Markdown 与模型记忆使用");
    }

    @Test
    void modifyClientCopyRemovesOldAndNewContent() {
        ToolExecutedMessage client = message(
                "modifyFile",
                "{\"relativeFilePath\":\"src/main.ts\","
                        + "\"oldContent\":\"旧代码哨兵\","
                        + "\"newContent\":\"新代码哨兵\"}",
                fileResult("modifyFile", "src/main.ts", "APPLIED", null))
                .toClientSafeCopy();

        String serialized = JSONUtil.toJsonStr(client);
        assertTrue(serialized.contains("src/main.ts"));
        assertFalse(serialized.contains("旧代码哨兵"));
        assertFalse(serialized.contains("新代码哨兵"));
    }

    @Test
    void readClientCopyPreservesTrustedMetadataButNullsContent() {
        String secret = "读取正文机密-93ab";
        ToolExecutedMessage client = message(
                "readFile",
                "{\"relativeFilePath\":\"src/secret.ts\"}",
                fileResult("readFile", "src/secret.ts", "APPLIED", secret))
                .toClientSafeCopy();

        var result = JSONUtil.parseObj(client.getResult());
        assertEquals("file-tool/v1", result.getStr("protocol"));
        assertEquals("readFile", result.getStr("operation"));
        assertEquals("APPLIED", result.getStr("status"));
        assertEquals("src/secret.ts", result.getStr("relativePath"));
        assertTrue(result.containsKey("content"));
        assertEquals(cn.hutool.json.JSONNull.NULL, result.get("content"));
        assertFalse(JSONUtil.toJsonStr(client).contains(secret));
    }

    @Test
    void malformedReadResultIsRemovedInsteadOfForwarded() {
        ToolExecutedMessage client = message(
                "readDir",
                "{\"relativeDirPath\":\"src\"}",
                "{\"protocol\":\"file-tool/v1\",\"content\":\"泄漏正文\"}")
                .toClientSafeCopy();

        assertNull(client.getResult());
        assertFalse(JSONUtil.toJsonStr(client).contains("泄漏正文"));
    }

    private ToolExecutedMessage message(
            String name, String arguments, String result) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name(name)
                .arguments(arguments)
                .build();
        return new ToolExecutedMessage(ToolExecution.builder()
                .request(request)
                .result(result)
                .build());
    }

    private String fileResult(
            String operation, String path, String status, String content) {
        boolean changed = "APPLIED".equals(status)
                && !operation.startsWith("read");
        return "{"
                + "\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"" + operation + "\","
                + "\"status\":\"" + status + "\","
                + "\"relativePath\":\"" + path + "\","
                + "\"changed\":" + changed + ","
                + "\"message\":\"执行完成\","
                + "\"failureReason\":null,"
                + "\"content\":"
                + (content == null ? "null" : "\"" + content + "\"")
                + "}";
    }
}
