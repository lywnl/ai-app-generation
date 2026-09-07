package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.ai.skill.SkillDefinition;
import com.lyw.appgeneration.ai.skill.SkillReadSession;
import com.lyw.appgeneration.ai.skill.SkillCatalog;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 受控读取 classpath 内置 Skill 的完整正文。 */
@Component
public final class ReadSkillTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(ReadSkillTool.class);

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
        long startedNanos = System.nanoTime();
        log.info("[Skill] readSkill 开始,appId={},skillName={}",
                appId, logSkillName(safeName));
        FileToolExecutionScopeManager.FileToolScope scope = null;
        boolean repeated = false;
        try {
            scope = scopeManager.requireCurrent(
                            appId == null ? Long.MIN_VALUE : appId, getToolName());
            if (scope.type() != FileToolExecutionScopeManager.ScopeType.ONLINE) {
                return complete(appId, startedNanos,
                        SkillToolResult.rejected(safeName, "INVALID_SKILL_NAME"));
            }
            if (!isValidSkillName(safeName)) {
                return complete(appId, startedNanos,
                        SkillToolResult.rejected(
                                safeName.isBlank() ? "unknown" : safeName,
                                "INVALID_SKILL_NAME"));
            }
            if (skillCatalog.findMetadata(safeName).isEmpty()) {
                return complete(appId, startedNanos, SkillToolResult.notFound(safeName));
            }
            SkillReadSession.ClaimStatus claim = scope.skillReadSession().claimStatus(safeName);
            repeated = claim == SkillReadSession.ClaimStatus.REPEATED;
            if (claim == SkillReadSession.ClaimStatus.REJECTED) {
                return complete(appId, startedNanos,
                        SkillToolResult.rejected(safeName, "TOO_MANY_SKILLS"));
            }
            SkillDefinition definition = skillCatalog.load(safeName);
            if (definition.body().codePointCount(0, definition.body().length())
                    > SkillReadSession.MAX_BODY_CODE_POINTS) {
                return complete(appId, startedNanos,
                        SkillToolResult.rejected(safeName, "RESOURCE_LIMIT_EXCEEDED"));
            }
            return complete(appId, startedNanos,
                    SkillToolResult.applied(safeName, definition.body()));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            log.warn("[Skill] readSkill 作用域校验异常,appId={},skillName={},exceptionType={}",
                    appId, logSkillName(safeName), exception.getClass().getSimpleName());
            return complete(appId, startedNanos,
                    SkillToolResult.rejected(
                            safeName.isBlank() ? "unknown" : safeName, "INVALID_SKILL_NAME"));
        } catch (RuntimeException exception) {
            log.error("[Skill] readSkill 加载异常,appId={},skillName={},exceptionType={}",
                    appId, logSkillName(safeName), exception.getClass().getSimpleName(), exception);
            return complete(appId, startedNanos,
                    SkillToolResult.failed(safeName.isBlank() ? "unknown" : safeName));
        } finally {
            if (scope != null
                    && scope.type() == FileToolExecutionScopeManager.ScopeType.ONLINE) {
                log.info("[Skill] 回合配额,appId={},turnId={},skillName={},claimedCount={},maxSkills={},repeated={}",
                        scope.appId(), scope.ownerToken().replaceAll("[\\r\\n\\t]", "_"),
                        logSkillName(safeName), scope.skillReadSession().claimedCount(),
                        SkillReadSession.MAX_DISTINCT_SKILLS, repeated);
            }
        }
    }

    private String complete(Long appId, long startedNanos, SkillToolResult result) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        log.info("[Skill] readSkill 完成,appId={},durationMs={},{}", appId, durationMs,
                SkillToolProtocolSupport.observabilitySummary(result));
        return json(result);
    }

    private String logSkillName(String skillName) {
        return isValidSkillName(skillName) ? skillName : "<invalid>";
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
