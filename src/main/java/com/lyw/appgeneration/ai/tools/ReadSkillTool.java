package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.ai.skill.SkillDefinition;
import com.lyw.appgeneration.ai.skill.SkillReadSession;
import com.lyw.appgeneration.ai.skill.SkillCatalog;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/** 受控读取 classpath 内置 Skill 的完整正文。 */
@Component
public final class ReadSkillTool extends BaseTool {

    private final SkillCatalog skillCatalog;
    private final FileToolExecutionScopeManager scopeManager;

    public ReadSkillTool(
            SkillCatalog skillCatalog,
            FileToolExecutionScopeManager scopeManager) {
        this.skillCatalog = skillCatalog;
        this.scopeManager = scopeManager;
    }

    @Tool("按名称读取一个内置 Skill 的完整 SKILL.md")
    public String readSkill(
            @P("Skill 名称，例如 vue-frontend-design") String skillName,
            @ToolMemoryId Long appId) {
        String safeName = skillName == null ? "" : skillName.strip();
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(
                            appId == null ? Long.MIN_VALUE : appId, getToolName());
            if (scope.type() != FileToolExecutionScopeManager.ScopeType.ONLINE) {
                return json(SkillToolResult.rejected(
                        safeName, "INVALID_SKILL_NAME"));
            }
            if (!isValidSkillName(safeName)) {
                return json(SkillToolResult.rejected(
                        safeName.isBlank() ? "unknown" : safeName,
                        "INVALID_SKILL_NAME"));
            }
            if (skillCatalog.findMetadata(safeName).isEmpty()) {
                return json(SkillToolResult.notFound(safeName));
            }
            if (!scope.skillReadSession().claim(safeName)) {
                return json(SkillToolResult.rejected(
                        safeName, "TOO_MANY_SKILLS"));
            }
            SkillDefinition definition = skillCatalog.load(safeName);
            if (definition.body().codePointCount(0, definition.body().length())
                    > SkillReadSession.MAX_BODY_CODE_POINTS) {
                return json(SkillToolResult.rejected(
                        safeName, "RESOURCE_LIMIT_EXCEEDED"));
            }
            return json(SkillToolResult.applied(safeName, definition.body()));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return json(SkillToolResult.rejected(
                    safeName.isBlank() ? "unknown" : safeName, "INVALID_SKILL_NAME"));
        } catch (RuntimeException exception) {
            return json(SkillToolResult.failed(
                    safeName.isBlank() ? "unknown" : safeName));
        }
    }

    private boolean isValidSkillName(String name) {
        return name.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    private String json(SkillToolResult result) {
        return SkillToolProtocolSupport.json(result);
    }

    @Override
    public String getToolName() {
        return "readSkill";
    }

    @Override
    public String getDisplayName() {
        return "读取 Skill";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return generateToolExecutedResult(arguments, null);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String rawResult) {
        String name = arguments == null ? "unknown" : arguments.getStr("skillName");
        return SkillToolProtocolSupport.stableSummary(this, rawResult,
                name == null || name.isBlank() ? "unknown" : name);
    }
}
