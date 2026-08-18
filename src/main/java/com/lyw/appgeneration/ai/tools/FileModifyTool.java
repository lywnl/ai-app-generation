package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 受控修改 Vue 项目内的普通文件。 */
@Slf4j
@Component
public class FileModifyTool extends BaseTool {

    private final ProjectPathResolver projectPathResolver = new ProjectPathResolver();
    private final FileToolExecutionScopeManager scopeManager;

    public FileModifyTool(FileToolExecutionScopeManager scopeManager) {
        this.scopeManager = scopeManager;
    }

    @Tool("修改文件内容，用新内容替换指定的旧内容")
    public String modifyFile(
            @P("文件的相对路径") String relativeFilePath,
            @P("要替换的旧内容") String oldContent,
            @P("替换后的新内容") String newContent,
            @ToolMemoryId Long appId) {
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(requireAppId(appId), getToolName());
            String policyRejection = scopeManager.rejectForbiddenMutation(
                    scope, getToolName(), relativeFilePath);
            if (policyRejection != null) {
                return policyRejection;
            }
            Path path = projectPathResolver.resolveExisting(appId, relativeFilePath, false);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return result(FileToolResult.notFound(
                        getToolName(), relativeFilePath, "文件不存在或不是普通文件"));
            }
            String original = FileReadTool.readUtf8WithinBudget(
                    path, scope.budgetSession().newSingleFileAccumulator());
            if (original == null) {
                return result(FileToolResult.resourceLimitExceeded(
                        getToolName(), relativeFilePath));
            }
            if (oldContent == null || !original.contains(oldContent)) {
                return result(FileToolResult.noChange(
                        getToolName(), relativeFilePath, "未找到要替换的内容，文件未修改"));
            }
            String modified = original.replace(oldContent, newContent == null ? "" : newContent);
            if (original.equals(modified)) {
                return result(FileToolResult.noChange(
                        getToolName(), relativeFilePath, "替换后文件内容未发生变化"));
            }
            try (FileToolBudgetGuard.MutationReservation reservation =
                         scope.budgetSession().reserveMutation(
                                 modified, newContent == null ? "" : newContent)) {
                if (!reservation.accepted()) {
                    return result(FileToolResult.resourceLimitExceeded(
                            getToolName(), relativeFilePath));
                }
                Files.writeString(path, modified,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                reservation.commit();
            }
            return result(FileToolResult.applied(
                    getToolName(), projectPathResolver.stateKey(appId, path),
                    true, "文件修改成功"));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return FileToolProtocolSupport.rejected(
                    getToolName(), relativeFilePath, exception);
        } catch (ProjectPathResolver.UnsafeProjectPathException exception) {
            return result(FileToolResult.rejected(
                    getToolName(), relativeFilePath, exception.getMessage()));
        } catch (IOException | RuntimeException exception) {
            log.error("修改文件失败: appId={}, path={}", appId, relativeFilePath, exception);
            return result(FileToolResult.failed(
                    getToolName(), relativeFilePath, "修改文件失败"));
        }
    }

    private String result(FileToolResult result) {
        return FileToolProtocolSupport.json(result);
    }

    private long requireAppId(Long appId) {
        return appId == null ? Long.MIN_VALUE : appId;
    }

    @Override
    public String getToolName() {
        return "modifyFile";
    }

    @Override
    public String getDisplayName() {
        return "修改文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return generateToolExecutedResult(arguments, null);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String rawResult) {
        String path = arguments == null ? null : arguments.getStr("relativeFilePath");
        FileToolResult result = FileToolProtocolSupport.parse(rawResult, getToolName(), path);
        if (result.status() != FileToolResult.FileToolStatus.APPLIED || !result.changed()) {
            return FileToolProtocolSupport.stableSummary(this, result);
        }
        return String.format("""
                [工具调用] %s %s

                替换前：
                ```
                %s
                ```

                替换后：
                ```
                %s
                ```
                """, getDisplayName(), path,
                arguments.getStr("oldContent"), arguments.getStr("newContent"));
    }
}
