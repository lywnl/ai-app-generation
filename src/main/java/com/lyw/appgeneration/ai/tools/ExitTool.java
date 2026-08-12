package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.manger.AppFileStateManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/** 评测结束工具；在线 Vue 回合必须以真实构建结果结束。 */
@Component
public class ExitTool extends BaseTool {

    private final AppFileStateManager appFileStateManager;
    private final FileToolExecutionScopeManager scopeManager;

    public ExitTool(
            AppFileStateManager appFileStateManager,
            FileToolExecutionScopeManager scopeManager) {
        this.appFileStateManager = appFileStateManager;
        this.scopeManager = scopeManager;
    }

    @Tool("完成所有项目文件操作后结束生成。在线 Vue 项目应改为调用 buildProject。")
    public String exit(
            @P("简短的完成原因") String reason,
            @ToolMemoryId Long appId) {
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(
                            appId == null ? Long.MIN_VALUE : appId, getToolName());
            if (scope.type() == FileToolExecutionScopeManager.ScopeType.ONLINE) {
                return FileToolProtocolSupport.json(FileToolResult.noChange(
                        getToolName(), null,
                        "当前 Vue 项目尚未通过真实构建，请调用 buildProject"));
            }
            int count = appFileStateManager.fileCount(appId);
            return FileToolProtocolSupport.json(FileToolResult.applied(
                    getToolName(), null, false,
                    "生成完毕，共 " + count + " 个文件。原因："
                            + (reason == null ? "" : reason)));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return FileToolProtocolSupport.rejected(getToolName(), null, exception);
        }
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
        return generateToolExecutedResult(arguments, null);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String rawResult) {
        return FileToolProtocolSupport.stableSummary(
                this, FileToolProtocolSupport.parse(rawResult, getToolName(), null));
    }
}
