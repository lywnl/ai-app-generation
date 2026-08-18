package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR;
import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTurnMemoryProjectionTest {

    @Test
    void 只从严格受信工具结果投影真实工具变更文件构建次数和终态() {
        VueToolExecutionFact write = parse("writeFile", fileResult(
                "writeFile", "src/App.vue", true, null));
        VueToolExecutionFact read = parse("readFile", fileResult(
                "readFile", "src/App.vue", false, "绝密读取正文"));
        VueToolExecutionFact duplicateWrite = parse("writeFile", fileResult(
                "writeFile", "src/App.vue", true, null));
        VueToolExecutionFact modify = parse("modifyFile", fileResult(
                "modifyFile", "src/components/Hero.vue", true, null));
        VueToolExecutionFact firstBuild = parse("buildProject", buildFailure(1));
        VueToolExecutionFact finalBuild = parse("buildProject", buildSuccess(2));

        String projection = VueTurnMemoryProjection.project(
                List.of(write, read, duplicateWrite, modify,
                        firstBuild, finalBuild), SUCCEEDED);

        assertEquals("""
                Vue 项目回合结果：成功
                实际执行工具：writeFile、readFile、modifyFile、buildProject
                实际变更文件：src/App.vue、src/components/Hero.vue
                真实构建次数：2""", projection);
        assertFalse(projection.contains("绝密读取正文"));
        assertFalse(projection.contains("oldContent"));
        assertFalse(projection.contains("[工具调用]"));
    }

    @Test
    void 畸形协议同名字段和越界路径都不能成为受信事实() {
        assertTrue(VueToolExecutionFact.parse("writeFile",
                fileResult("modifyFile", "src/App.vue", true, null)).isEmpty());
        assertTrue(VueToolExecutionFact.parse("writeFile",
                fileResult("writeFile", "../secret.txt", true, null)).isEmpty());
        assertTrue(VueToolExecutionFact.parse("writeFile",
                "{\"protocol\":\"file-tool/v1\","
                        + "\"operation\":\"writeFile\",\"status\":\"APPLIED\","
                        + "\"relativePath\":null,\"changed\":true,"
                        + "\"message\":\"已执行\",\"failureReason\":null,"
                        + "\"content\":null}").isEmpty());
        assertEquals("src/App.vue", VueToolExecutionFact.parse("writeFile",
                fileResult("writeFile", "src/view/../App.vue", true, null))
                .orElseThrow().changedRelativePath());
        assertTrue(VueToolExecutionFact.parse("writeFile",
                fileResult("writeFile", "src/App.vue", true, null)
                        .replaceFirst("}$", ",\"unknown\":true}")).isEmpty());
        assertTrue(VueToolExecutionFact.parse("buildProject",
                buildSuccess(1).replaceFirst("}$", ",\"unknown\":true}"))
                .isEmpty());
        assertTrue(VueToolExecutionFact.parse("buildProject",
                buildSuccess(1).replace("\"errorSummary\":null,", ""))
                .isEmpty());
        assertTrue(VueToolExecutionFact.parse("buildProject",
                buildSuccess(1).replace("\"maxAttempts\":3",
                        "\"maxAttempts\":4"))
                .isEmpty());
    }

    @Test
    void 协议错误始终使用固定投影且不携带此前事实() {
        VueToolExecutionFact write = parse("writeFile", fileResult(
                "writeFile", "src/App.vue", true, null));

        assertEquals(VueTurnMemoryProjection.PROTOCOL_ERROR_PROJECTION,
                VueTurnMemoryProjection.project(List.of(write), PROTOCOL_ERROR));
        assertFalse(VueTurnMemoryProjection.PROTOCOL_ERROR_PROJECTION
                .contains("src/App.vue"));
        assertEquals(ChatMemoryOutcome.PROTOCOL_ERROR,
                VueTurnMemoryProjection.memoryOutcome(PROTOCOL_ERROR));
    }

    @Test
    void 两类可信协议都拒绝非标准Json和尾随内容() {
        assertStrictJsonRejected("writeFile",
                fileResult("writeFile", "src/App.vue", true, null));
        assertStrictJsonRejected("buildProject", buildSuccess(1));
    }

    private void assertStrictJsonRejected(String toolName, String validJson) {
        assertAll(
                () -> assertTrue(VueToolExecutionFact.parse(toolName,
                        validJson.replace('"', '\'')).isEmpty()),
                () -> assertTrue(VueToolExecutionFact.parse(toolName,
                        validJson.replaceFirst("\\\"protocol\\\"", "protocol"))
                        .isEmpty()),
                () -> assertTrue(VueToolExecutionFact.parse(toolName,
                        validJson.replaceFirst("}$", ",}"))
                        .isEmpty()),
                () -> assertTrue(VueToolExecutionFact.parse(toolName,
                        validJson + " trailing").isEmpty()));
    }

    private VueToolExecutionFact parse(String toolName, String rawResult) {
        return VueToolExecutionFact.parse(toolName, rawResult).orElseThrow();
    }

    private String fileResult(
            String operation, String path, boolean changed, String content) {
        String operationType = operation.startsWith("read")
                ? "APPLIED" : "APPLIED";
        return "{\"protocol\":\"file-tool/v1\","
                + "\"operation\":\"" + operation + "\","
                + "\"status\":\"" + operationType + "\","
                + "\"relativePath\":\"" + path + "\","
                + "\"changed\":" + changed + ","
                + "\"message\":\"已执行\",\"failureReason\":null,"
                + "\"content\":" + (content == null
                ? "null" : "\"" + content + "\"") + "}";
    }

    private String buildFailure(int attempt) {
        return "{\"protocol\":\"vue-build-tool/v1\","
                + "\"invocationStatus\":\"COMPLETED\",\"success\":false,"
                + "\"attempt\":" + attempt + ",\"maxAttempts\":3,"
                + "\"stage\":\"NPM_BUILD\",\"failureKind\":\"CODE\","
                + "\"timedOut\":false,\"repairable\":true,"
                + "\"reflectionRequired\":false,\"nextAction\":\"REPAIR\","
                + "\"message\":\"第 1 次构建失败，请进行最小代码修复\","
                + "\"errorSummary\":\"脱敏诊断\","
                + "\"terminateToolLoop\":false,\"finalResponse\":null}";
    }

    private String buildSuccess(int attempt) {
        return "{\"protocol\":\"vue-build-tool/v1\","
                + "\"invocationStatus\":\"COMPLETED\",\"success\":true,"
                + "\"attempt\":" + attempt + ",\"maxAttempts\":3,"
                + "\"stage\":\"SUCCESS\",\"failureKind\":null,"
                + "\"timedOut\":false,\"repairable\":false,"
                + "\"reflectionRequired\":false,\"nextAction\":\"STOP\","
                + "\"message\":\"构建成功\",\"errorSummary\":null,"
                + "\"terminateToolLoop\":true,"
                + "\"finalResponse\":\"项目已生成并构建成功。\"}";
    }
}
