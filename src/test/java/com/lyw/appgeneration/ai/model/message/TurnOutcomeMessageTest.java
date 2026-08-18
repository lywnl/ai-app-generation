package com.lyw.appgeneration.ai.model.message;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TurnOutcomeMessageTest {

    @Test
    void resultAndOutcomeFieldsSurviveJsonRoundTripWithoutDoubleEscaping() {
        String rawResult = "{\"protocol\":\"vue-build-tool/v1\",\"success\":true}";
        ToolExecutedMessage tool = new ToolExecutedMessage();
        tool.setType(StreamMessageTypeEnum.TOOL_EXECUTED.getValue());
        tool.setId("build-1");
        tool.setName("buildProject");
        tool.setArguments("{}");
        tool.setResult(rawResult);

        ToolExecutedMessage decodedTool = JSONUtil.toBean(
                JSONUtil.toJsonStr(tool), ToolExecutedMessage.class);
        assertEquals(rawResult, decodedTool.getResult());
        assertEquals("vue-build-tool/v1",
                JSONUtil.parseObj(decodedTool.getResult()).getStr("protocol"));

        TurnOutcomeMessage message = new TurnOutcomeMessage(new VueTurnOutcome(
                VueBuildPhase.SUCCEEDED,
                VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                "不应进入控制消息",
                "可信记忆投影",
                true,
                "项目已生成并构建成功。"));
        String json = JSONUtil.toJsonStr(message);
        TurnOutcomeMessage decoded = JSONUtil.toBean(json, TurnOutcomeMessage.class);

        assertEquals("turn_outcome", decoded.getType());
        assertEquals(VueTurnOutcome.TurnOutcomeType.SUCCEEDED, decoded.getOutcome());
        assertEquals(VueBuildPhase.SUCCEEDED, decoded.getPhase());
        assertTrue(decoded.isShouldRefreshPreview());
        assertEquals("项目已生成并构建成功。", decoded.getMessage());
        assertFalse(json.contains("不应进入控制消息"),
                "canonicalAiText 不能进入实时控制消息");
    }

    @Test
    void generationStreamEventMustExposeOnlyClientSafeControlEvents()
            throws NoSuchMethodException {
        var nestedTypes = java.util.Arrays.stream(
                        GenerationStreamEvent.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(java.util.Set.of(
                "Content", "TurnOutcome", "ContextCompression",
                "ToolProtocolRecovery"), nestedTypes);
        assertNotNull(GenerationStreamEvent.class.getMethod(
                "turnOutcome", VueTurnOutcome.class));
        assertNotNull(GenerationStreamEvent.class.getMethod(
                "contextCompression", ContextCompressionMessage.class));
        assertNotNull(GenerationStreamEvent.class.getMethod(
                "toolProtocolRecovery", ToolProtocolRecoveryMessage.class));
    }

    @Test
    void contextCompressionMessageMustRejectUntrustedContractFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompressionMessage(
                        "context-compression/v2",
                        ContextCompressionMessage.Phase.STARTED,
                        "正在压缩上下文，请稍候…"));
        assertThrows(NullPointerException.class,
                () -> new ContextCompressionMessage(
                        ContextCompressionMessage.PROTOCOL,
                        null,
                        "正在压缩上下文，请稍候…"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompressionMessage(
                        ContextCompressionMessage.PROTOCOL,
                        ContextCompressionMessage.Phase.STARTED,
                        "内部异常详情"));
    }

    @Test
    void toolProtocolRecoveryMessageMustExposeOnlyFixedTrustedContract() {
        assertEquals("tool-protocol-recovery/v1",
                ToolProtocolRecoveryMessage.started().protocol());
        assertEquals("正在校正工具调用，请稍候…",
                ToolProtocolRecoveryMessage.started().message());
        assertEquals("工具调用已校正，继续生成…",
                ToolProtocolRecoveryMessage.recovered().message());
        assertEquals("工具调用格式异常，系统自动校正后仍未恢复。"
                        + "本轮没有执行相关工具，请重新发送请求。",
                ToolProtocolRecoveryMessage.failed().message());
        assertThrows(IllegalArgumentException.class,
                () -> new ToolProtocolRecoveryMessage(
                        "tool-protocol-recovery/v2",
                        ToolProtocolRecoveryMessage.Phase.STARTED,
                        "正在校正工具调用，请稍候…"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolProtocolRecoveryMessage(
                        ToolProtocolRecoveryMessage.PROTOCOL,
                        ToolProtocolRecoveryMessage.Phase.FAILED,
                        "泄露内部异常"));
    }
}
