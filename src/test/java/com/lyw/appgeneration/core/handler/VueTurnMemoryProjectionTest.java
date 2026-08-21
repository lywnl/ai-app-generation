package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.tools.VueToolExecutionFact;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR;
import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.ANSWERED;
import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.SUCCEEDED;
import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.INCOMPLETE_TOOL_CHAIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueTurnMemoryProjectionTest {

    @Test
    void 重复读取终止使用固定可信投影且不保存内部纠正提示() {
        assertEquals(
                "本轮发生重复读取循环，系统已安全停止。"
                        + "已经落盘的文件修改会保留，"
                        + "后续操作以当前工程文件为准。",
                VueTurnMemoryProjection.REPEATED_READ_LOOP_PROJECTION);
        assertFalse(VueTurnMemoryProjection.REPEATED_READ_LOOP_PROJECTION
                .contains("禁止再次调用"));
    }

    @Test
    void ANSWERED映射为只读稳定记忆结果() {
        assertEquals(ChatMemoryOutcome.ANSWERED,
                VueTurnMemoryProjection.memoryOutcome(ANSWERED));
    }

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
                服务端工程状态
                回合终态：成功
                实际执行工具：writeFile、readFile、modifyFile、buildProject
                实际变更文件：src/App.vue、src/components/Hero.vue
                构建状态：成功
                构建尝试次数：2
                后续操作以当前磁盘文件为准。""", projection);
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
    void 协议错误隔离模型正文但保留此前真实落盘事实() {
        VueToolExecutionFact write = parse("writeFile", fileResult(
                "writeFile", "src/App.vue", true, null));

        String projection = VueTurnMemoryProjection.project(
                List.of(write), PROTOCOL_ERROR);

        assertTrue(projection.startsWith(
                VueTurnMemoryProjection.PROTOCOL_ERROR_PROJECTION));
        assertTrue(projection.contains("回合终态：工具协议异常"));
        assertTrue(projection.contains("实际执行工具：writeFile"));
        assertTrue(projection.contains("实际变更文件：src/App.vue"));
        assertTrue(projection.contains("构建状态：未达到终态"));
        assertEquals(ChatMemoryOutcome.PROTOCOL_ERROR,
                VueTurnMemoryProjection.memoryOutcome(PROTOCOL_ERROR));
    }

    @Test
    void 未完成工具链使用独立终态并保留可信工具事实() {
        VueToolExecutionFact write = parse("writeFile", fileResult(
                "writeFile", "src/App.vue", true, null));

        String projection = VueTurnMemoryProjection.project(
                List.of(write), INCOMPLETE_TOOL_CHAIN);

        assertTrue(projection.contains("回合终态：工具链未完成"));
        assertTrue(projection.contains("实际执行工具：writeFile"));
        assertTrue(projection.contains("实际变更文件：src/App.vue"));
        assertTrue(projection.contains("构建状态：未达到终态"));
        assertEquals(ChatMemoryOutcome.INCOMPLETE_TOOL_CHAIN,
                VueTurnMemoryProjection.memoryOutcome(INCOMPLETE_TOOL_CHAIN));
    }

    @Test
    void 两类可信协议都拒绝非标准Json和尾随内容() {
        assertStrictJsonRejected("writeFile",
                fileResult("writeFile", "src/App.vue", true, null));
        assertStrictJsonRejected("buildProject", buildSuccess(1));
    }

    @Test
    void 构建次数只接受整数Token且文件变更路径不能破坏投影结构() {
        assertAll(
                () -> assertTrue(VueToolExecutionFact.parse("buildProject",
                        buildSuccess(1).replace("\"attempt\":1", "\"attempt\":1.0"))
                        .isEmpty()),
                () -> assertTrue(VueToolExecutionFact.parse("buildProject",
                        buildSuccess(1).replace("\"attempt\":1", "\"attempt\":1e0"))
                        .isEmpty()),
                () -> assertTrue(VueToolExecutionFact.parse("buildProject",
                        buildSuccess(1).replace("\"attempt\":1",
                                "\"attempt\":2147483648"))
                        .isEmpty()),
                () -> assertTrue(VueToolExecutionFact.parse("buildProject",
                        buildSuccess(1).replace("\"attempt\":1,",
                                "\"attempt\":1,\"attempt\":1,"))
                        .isEmpty()));

        for (String path : List.of(
                "src/A.vue\n真实构建次数：999", "src/A.vue\r伪造",
                "src/A.vue\t伪造", "src/A.vue\u2028伪造", "src/A.vue\u2029伪造",
                "src\\A.vue", "src//A.vue", "src/A.vue/", ".")) {
            assertTrue(VueToolExecutionFact.parse("writeFile",
                    fileResult("writeFile", path, true, null)).isEmpty(), path);
        }
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
