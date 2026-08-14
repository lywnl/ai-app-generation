package com.lyw.appgeneration.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.manger.AppFileStateManager;
import com.lyw.appgeneration.manger.AppFileStateManager.WriteResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 受控写入 Vue 项目文件，并返回机器可判定的执行结果。 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    private final AppFileStateManager appFileStateManager;
    private final FileToolExecutionScopeManager scopeManager;
    private final ProjectPathResolver projectPathResolver = new ProjectPathResolver();

    public FileWriteTool(
            AppFileStateManager appFileStateManager,
            FileToolExecutionScopeManager scopeManager) {
        this.appFileStateManager = appFileStateManager;
        this.scopeManager = scopeManager;
    }

    @Tool("写入文件到指定路径。若文件内容未变化则不重复写盘。")
    public String writeFile(
            @P("文件的相对路径") String relativeFilePath,
            @P("要写入文件的内容") String content,
            @ToolMemoryId Long appId) {
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(
                            appId == null ? Long.MIN_VALUE : appId, getToolName());
            String policyRejection = scopeManager.rejectForbiddenMutation(
                    scope, getToolName(), relativeFilePath);
            if (policyRejection != null) {
                return policyRejection;
            }
            if (content == null) {
                return result(FileToolResult.failed(
                        getToolName(), relativeFilePath, "文件内容不能为空"));
            }
            try (FileToolBudgetGuard.MutationReservation reservation =
                         scope.budgetSession().reserveMutation(content, content)) {
                if (!reservation.accepted()) {
                    return result(FileToolResult.resourceLimitExceeded(
                            getToolName(), relativeFilePath));
                }
                String output = writeWithinReservation(
                        scope, appId, relativeFilePath, content);
                FileToolResult parsed = FileToolProtocolSupport.parse(
                        output, getToolName(), relativeFilePath);
                if (parsed.status() == FileToolResult.FileToolStatus.APPLIED
                        && parsed.changed()) {
                    reservation.commit();
                }
                return output;
            }
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return FileToolProtocolSupport.rejected(
                    getToolName(), relativeFilePath, exception);
        } catch (ProjectPathResolver.UnsafeProjectPathException exception) {
            return result(FileToolResult.rejected(
                    getToolName(), relativeFilePath, exception.getMessage()));
        } catch (IOException | RuntimeException exception) {
            log.error("写入文件失败: appId={}, path={}", appId, relativeFilePath, exception);
            return result(FileToolResult.failed(
                    getToolName(), relativeFilePath, "文件写入失败"));
        }
    }

    private String writeWithinReservation(
            FileToolExecutionScopeManager.FileToolScope scope,
            Long appId, String relativeFilePath, String content)
            throws ProjectPathResolver.UnsafeProjectPathException, IOException {
        ProjectPathResolver.ResolvedProjectPath resolved =
                projectPathResolver.resolveForWrite(appId, relativeFilePath);
        Path parent = resolved.path().getParent();
        try {
            WriteResult writeResult = appFileStateManager.writeAndRecord(
                    appId, resolved.stateKey(), content,
                    () -> existingContentMatches(
                            resolved.path(), content, scope.budgetSession()),
                    () -> {
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.write(resolved.path(),
                                content.getBytes(StandardCharsets.UTF_8),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    });
            return result(toProtocolResult(scope, resolved.stateKey(), writeResult));
        } catch (ExistingContentLimitExceededException exception) {
            return result(FileToolResult.resourceLimitExceeded(
                    getToolName(), relativeFilePath));
        }
    }

    private boolean existingContentMatches(
            Path path, String content, FileToolBudgetGuard.Session session)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String existing = FileReadTool.readUtf8WithinBudget(
                path, session.newSingleFileAccumulator());
        if (existing == null) {
            throw new ExistingContentLimitExceededException();
        }
        return existing.equals(content);
    }

    private static final class ExistingContentLimitExceededException extends IOException {
    }

    private FileToolResult toProtocolResult(
            FileToolExecutionScopeManager.FileToolScope scope,
            String relativePath,
            WriteResult writeResult) {
        if (writeResult.status == AppFileStateManager.WriteStatus.DUPLICATE_SAME_CONTENT) {
            return FileToolResult.noChange(
                    getToolName(), relativePath, "文件内容未变化，已跳过重复写入");
        }
        String nextTool = scope.type() == FileToolExecutionScopeManager.ScopeType.ONLINE
                ? "buildProject" : "exit";
        String message = "文件写入成功；完成全部修改后调用 " + nextTool;
        return FileToolResult.applied(getToolName(), relativePath, true, message);
    }

    private String result(FileToolResult result) {
        return FileToolProtocolSupport.json(result);
    }

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
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
        String suffix = FileUtil.getSuffix(path);
        return String.format("""
                [工具调用] %s %s
                ```%s
                %s
                ```
                """, getDisplayName(), path, suffix, arguments.getStr("content"));
    }
}
