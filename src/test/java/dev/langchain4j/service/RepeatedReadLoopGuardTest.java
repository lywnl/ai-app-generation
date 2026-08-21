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
                .contains("再次读取本轮已经成功读取且内容未变化的路径"));
        assertTrue(guard.claimTransientMessages().isEmpty());

        assertEquals(RepeatedReadLoopGuard.Action.TERMINATE,
                guard.observe(readDir("call-3", "{\"path\":\"src\",\"depth\":2}"),
                        readDirResult("src", "[\"App.vue\"]")));
    }

    @Test
    @DisplayName("穿插其他读取时再次读取相同内容仍先纠正再终止")
    void repeatedReadAcrossOtherReadsStillEscalatesWithoutProgress() {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();

        ToolExecutionRequest first = readDir(
                "call-1", "{\"path\":\"src/views\"}");
        String firstResult = readDirResult(
                "src/views", "[\"Home.vue\"]");
        ToolExecutionRequest second = readDir(
                "call-2", "{\"path\":\"src/components\"}");
        String secondResult = readDirResult(
                "src/components", "[\"NavBar.vue\"]");

        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(first, firstResult));
        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(second, secondResult));
        assertEquals(RepeatedReadLoopGuard.Action.CORRECT_NEXT_REQUEST,
                guard.observe(readDir("call-3", "{\"path\":\"src/views\"}"),
                        firstResult));
        assertTrue(((SystemMessage) guard.claimTransientMessages().getFirst())
                .text().contains("本轮已经成功读取"));
        assertEquals(RepeatedReadLoopGuard.Action.TERMINATE,
                guard.observe(readDir("call-4", "{\"path\":\"src/components\"}"),
                        secondResult));
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
    @DisplayName("失败写入不算工程进展且不能绕过重复读取熔断")
    void failedMutationDoesNotResetReadProgress() {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();

        guard.observe(readDir("call-1", "{\"path\":\"src\"}"),
                readDirResult("src", "[\"App.vue\"]"));
        guard.observe(writeFile("write-1"), failedMutationResult());

        assertEquals(RepeatedReadLoopGuard.Action.CORRECT_NEXT_REQUEST,
                guard.observe(readDir("call-2", "{\"path\":\"src\"}"),
                        readDirResult("src", "[\"App.vue\"]")));
        assertEquals(1, guard.claimTransientMessages().size());
    }

    @Test
    @DisplayName("构建终态会重置读取状态并允许基于构建结果重新读取")
    void terminalBuildResultResetsReadProgress() {
        assertTerminalBuildResetsReadProgress(buildSuccessResult());
        assertTerminalBuildResetsReadProgress(buildFailureResult());
    }

    private void assertTerminalBuildResetsReadProgress(String buildResult) {
        RepeatedReadLoopGuard guard = new RepeatedReadLoopGuard();
        String readResult = readDirResult("src", "[\"App.vue\"]");

        guard.observe(readDir("call-1", "{\"path\":\"src\"}"), readResult);
        guard.observe(readDir("call-2", "{\"path\":\"src\"}"), readResult);
        assertEquals(1, guard.claimTransientMessages().size());

        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(buildProject("build-1"), buildResult));
        assertEquals(RepeatedReadLoopGuard.Action.CONTINUE,
                guard.observe(readDir("call-3", "{\"path\":\"src\"}"),
                        readResult));
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

    private ToolExecutionRequest buildProject(String id) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("buildProject")
                .arguments("{}")
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

    private String buildSuccessResult() {
        return "{\"protocol\":\"vue-build-tool/v1\","
                + "\"invocationStatus\":\"COMPLETED\",\"success\":true,"
                + "\"attempt\":1,\"maxAttempts\":3,"
                + "\"stage\":\"SUCCESS\",\"failureKind\":null,"
                + "\"timedOut\":false,\"repairable\":false,"
                + "\"reflectionRequired\":false,\"nextAction\":\"STOP\","
                + "\"message\":\"构建成功\",\"errorSummary\":null,"
                + "\"terminateToolLoop\":true,"
                + "\"finalResponse\":\"项目已生成并构建成功。\"}";
    }

    private String buildFailureResult() {
        return "{\"protocol\":\"vue-build-tool/v1\","
                + "\"invocationStatus\":\"COMPLETED\",\"success\":false,"
                + "\"attempt\":1,\"maxAttempts\":3,"
                + "\"stage\":\"NPM_BUILD\",\"failureKind\":\"CODE\","
                + "\"timedOut\":false,\"repairable\":true,"
                + "\"reflectionRequired\":false,\"nextAction\":\"REPAIR\","
                + "\"message\":\"构建失败\",\"errorSummary\":\"语法错误\","
                + "\"terminateToolLoop\":false,\"finalResponse\":null}";
    }
}
