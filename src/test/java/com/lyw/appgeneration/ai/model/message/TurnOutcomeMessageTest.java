package com.lyw.appgeneration.ai.model.message;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
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
}
