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
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

/** 受控读取 Vue 项目内的普通文件。 */
@Slf4j
@Component
public class FileReadTool extends BaseTool {

    private final ProjectPathResolver projectPathResolver = new ProjectPathResolver();
    private final FileToolExecutionScopeManager scopeManager;

    public FileReadTool(FileToolExecutionScopeManager scopeManager) {
        this.scopeManager = scopeManager;
    }

    @Tool("读取指定路径的文件内容")
    public String readFile(
            @P("文件的相对路径") String relativeFilePath,
            @ToolMemoryId Long appId) {
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(requireAppId(appId), getToolName());
            Path path = projectPathResolver.resolveExisting(appId, relativeFilePath, false);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return FileToolProtocolSupport.json(FileToolResult.notFound(
                        getToolName(), relativeFilePath, "文件不存在或不是普通文件"));
            }
            String content = readUtf8WithinBudget(
                    path, scope.budgetSession().newReadFileAccumulator());
            if (content == null) {
                return FileToolProtocolSupport.json(
                        FileToolResult.resourceLimitExceeded(
                                getToolName(), relativeFilePath));
            }
            return FileToolProtocolSupport.json(FileToolResult.readApplied(
                    getToolName(), relativeFilePath, "文件读取成功", content));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return FileToolProtocolSupport.rejected(
                    getToolName(), relativeFilePath, exception);
        } catch (ProjectPathResolver.UnsafeProjectPathException exception) {
            return FileToolProtocolSupport.json(FileToolResult.rejected(
                    getToolName(), relativeFilePath, exception.getMessage()));
        } catch (IOException | RuntimeException exception) {
            log.error("读取文件失败: appId={}, path={}", appId, relativeFilePath, exception);
            return FileToolProtocolSupport.json(FileToolResult.failed(
                    getToolName(), relativeFilePath, "读取文件失败"));
        }
    }

    static String readUtf8WithinBudget(
            Path path, FileToolBudgetGuard.ReadAccumulator budget)
            throws IOException {
        StringBuilder content = new StringBuilder();
        char[] buffer = new char[4_096];
        try (BufferedReader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                String chunk = new String(buffer, 0, read);
                FileToolBudgetGuard.ReadDecision decision = budget.accept(chunk);
                if (!decision.accepted()) {
                    return null;
                }
                content.append(decision.acceptedText());
            }
        }
        FileToolBudgetGuard.ReadDecision finish = budget.finish();
        if (!finish.accepted()) {
            return null;
        }
        content.append(finish.acceptedText());
        return content.toString();
    }

    private long requireAppId(Long appId) {
        return appId == null ? Long.MIN_VALUE : appId;
    }

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return generateToolExecutedResult(arguments, null);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String rawResult) {
        String path = arguments == null ? null : arguments.getStr("relativeFilePath");
        return FileToolProtocolSupport.stableSummary(
                this, FileToolProtocolSupport.parse(rawResult, getToolName(), path));
    }
}
