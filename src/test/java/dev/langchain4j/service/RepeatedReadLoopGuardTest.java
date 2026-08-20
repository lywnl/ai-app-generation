package dev.langchain4j.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatedReadLoopGuardTest {

    @Test
    @DisplayName("同一读取的调用 ID 改变时第二次纠正、第三次终止")
    void repeatedTrustedReadIgnoresCallIdAndEscalates() {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();

        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(readDir("call-1", "{\"path\":\"src\",\"depth\":2}"),
                        readDirResult("src", "[\"App.vue\"]")));
        assertEquals(RepeatedReadLoopGuard.Action.CORRECT_NEXT_REQUEST,
                guard.observe(readDir("call-2", "{\"depth\":2,\"path\":\"src\"}"),
                        readDirResult("src", "[\"App.vue\"]")));

        List<dev.langchain4j.data.message.ChatMessage> correction =
                guard.claimTransientMessages();
        assertEquals(1, correction.size());
        assertTrue(correction.getFirst() instanceof SystemMessage);
        assertTrue(((SystemMessage) correction.getFirst()).text()
                .contains("连续两次执行完全相同的读取操作"));
        assertTrue(guard.claimTransientMessages().isEmpty());

        assertEquals(RepeatedReadLoopGuard.Action.TERMINATE,
                guard.observe(readDir("call-3", "{\"path\":\"src\",\"depth\":2}"),
                        readDirResult("src", "[\"App.vue\"]")));
    }

    @Test
    @DisplayName("参数或可信结果变化会重置连续读取计数")
    void changedArgumentsOrResultResetsSequence() {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();

        guard.observe(readDir("call-1", "{\"path\":\"src\"}"),
                readDirResult("src", "[\"App.vue\"]"));
        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(readDir("call-2", "{\"path\":\"src\"}"),
                        readDirResult("src", "[\"App.vue\",\"main.js\"]")));
        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(readDir("call-3", "{\"path\":\"src/views\"}"),
                        readDirResult("src/views", "[\"Home.vue\"]")));
        assertTrue(guard.claimTransientMessages().isEmpty());
    }

    @Test
    @DisplayName("非受信结果不能触发重复读取纠正")
    void untrustedReadResultCannotTriggerCorrection() {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();
        ToolExecutionRequest request = readDir(
                "call-1", "{\"path\":\"src\"}");

        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(request, "[\"App.vue\"]"));
        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(readDir("call-2", "{\"path\":\"src\"}"),
                        "[\"App.vue\"]"));
        assertTrue(guard.claimTransientMessages().isEmpty());
    }

    @Test
    @DisplayName("成功写入会重置重复读取计数")
    void successfulMutationResetsSequence() {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();
        guard.observe(readDir("call-1", "{\"path\":\"src\"}"),
                readDirResult("src", "[\"App.vue\"]"));
        guard.observe(readDir("call-2", "{\"path\":\"src\"}"),
                readDirResult("src", "[\"App.vue\"]"));

        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(writeFile("write-1"), mutationResult()));
        assertTrue(guard.claimTransientMessages().isEmpty());
        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(readDir("call-3", "{\"path\":\"src\"}"),
                        readDirResult("src", "[\"App.vue\"]")));
    }

    @Test
    @DisplayName("中间出现其他工具时即使失败也会打断连续读取")
    void interveningDifferentToolBreaksSequenceEvenWhenItFails() {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();

        guard.observe(readDir("call-1", "{\"path\":\"src\"}"),
                readDirResult("src", "[\"App.vue\"]"));
        guard.observe(writeFile("write-1"), failedMutationResult());

        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(readDir("call-2", "{\"path\":\"src\"}"),
                        readDirResult("src", "[\"App.vue\"]")));
        assertTrue(guard.claimTransientMessages().isEmpty());
    }

    private ToolExecutionRequest readDir(String id, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("readDir")
                .arguments(arguments)
                .build();
    }

    private ToolExecutionRequest writeFile(String id) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/App.vue\",\"content\":\"x\"}")
                .build();
    }

    private String readDirResult(String path, String content) {
        return "{\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"readDir\",\"status\":\"APPLIED\","
                + "\"relativePath\":\"" + path + "\",\"changed\":false,"
                + "\"message\":\"目录读取成功\",\"failureReason\":null,"
                + "\"content\":\"" + content.replace("\"", "\\\"") + "\"}";
    }

    private String mutationResult() {
        return "{\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"writeFile\",\"status\":\"APPLIED\","
                + "\"relativePath\":\"src/App.vue\",\"changed\":true,"
                + "\"message\":\"文件写入成功\",\"failureReason\":null,"
                + "\"content\":null}";
    }

    private String failedMutationResult() {
        return "{\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"writeFile\",\"status\":\"FAILED\","
                + "\"relativePath\":\"src/App.vue\",\"changed\":false,"
                + "\"message\":\"文件写入失败\",\"failureReason\":null,"
                + "\"content\":null}";
    }
}
