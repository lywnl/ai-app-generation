package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.manger.AppFileStateManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 退出/完成工具
 * <p>
 * 提供给 LLM 一个明确的"生成完毕"信号锚点。调用本工具后会收到"生成完毕"
 * 响应,LLM 基于此判断不再需要继续调用任何工具,自然走到最终文本响应,工具循环结束。
 */
@Slf4j
@Component
public class ExitTool extends BaseTool {

    @Resource
    private AppFileStateManager appFileStateManager;

    @Tool("当所有项目文件都已经通过 writeFile 写入完毕后,必须调用此工具结束生成。调用后请立即停止调用任何其他工具,只向用户回复一句简短的完成确认即可。")
    public String exit(
            @P("简短的完成原因,例如:'已完成 Vue 工程全部文件的写入'")
            String reason,
            @ToolMemoryId Long appId
    ) {
        int count = appFileStateManager.fileCount(appId);
        log.info("[Exit] LLM 主动结束: appId={}, reason={}, 已写入文件数={}",
                appId, reason, count);
        return String.format(
                "✅ 生成完毕(共 %d 个文件)。原因:%s。请立即停止调用任何工具,仅向用户回复一句简短确认,不要复述项目内容。",
                count, reason == null ? "" : reason
        );
    }

    @Override
    public String getToolName() {
        return "exit";
    }

    @Override
    public String getDisplayName() {
        return "结束生成";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String reason = arguments == null ? "" : arguments.getStr("reason");
        return String.format("""

                [工具调用] %s
                %s
                """, getDisplayName(), reason == null ? "" : reason);
    }
}
