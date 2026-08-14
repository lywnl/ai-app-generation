package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** 受控读取 Vue 项目目录结构。 */
@Slf4j
@Component
public class FileDirReadTool extends BaseTool {

    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", ".DS_Store",
            ".env", "target", ".mvn", ".idea", ".vscode", "coverage");
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache", ".lock");

    private final ProjectPathResolver projectPathResolver = new ProjectPathResolver();
    private final FileToolExecutionScopeManager scopeManager;

    public FileDirReadTool(FileToolExecutionScopeManager scopeManager) {
        this.scopeManager = scopeManager;
    }

    @Tool("读取目录结构，获取指定目录下的所有文件和子目录信息")
    public String readDir(
            @P("目录的相对路径，为空则读取整个项目结构") String relativeDirPath,
            @ToolMemoryId Long appId) {
        try {
            FileToolExecutionScopeManager.FileToolScope scope =
                    scopeManager.requireCurrent(
                            appId == null ? Long.MIN_VALUE : appId, getToolName());
            Path path = projectPathResolver.resolveExisting(appId, relativeDirPath, true);
            if (!Files.isDirectory(path)) {
                return result(FileToolResult.notFound(
                        getToolName(), relativeDirPath, "目录不存在或不是目录"));
            }
            String structure = formatStructure(
                    path, appId, scope.budgetSession());
            if (structure == null) {
                return result(FileToolResult.resourceLimitExceeded(
                        getToolName(), relativeDirPath));
            }
            return result(FileToolResult.readApplied(
                    getToolName(), relativeDirPath,
                    "目录读取成功", structure));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return FileToolProtocolSupport.rejected(
                    getToolName(), relativeDirPath, exception);
        } catch (ProjectPathResolver.UnsafeProjectPathException exception) {
            return result(FileToolResult.rejected(
                    getToolName(), relativeDirPath, exception.getMessage()));
        } catch (RuntimeException exception) {
            log.error("读取目录失败: appId={}, path={}", appId, relativeDirPath, exception);
            return result(FileToolResult.failed(
                    getToolName(), relativeDirPath, "读取目录结构失败"));
        }
    }

    private String formatStructure(
            Path root, Long appId, FileToolBudgetGuard.Session session)
            throws ProjectPathResolver.UnsafeProjectPathException {
        StringBuilder structure = new StringBuilder("项目目录结构:\n");
        FileToolBudgetGuard.ReadAccumulator budget =
                session.newReadDirAccumulator();
        FileToolBudgetGuard.ReadDecision header = budget.accept(structure);
        if (!header.accepted()) {
            return null;
        }
        structure.setLength(0);
        structure.append(header.acceptedText());
        try {
            projectPathResolver.forEachSafeDirectoryEntry(
                    root, appId, this::shouldIgnore, file -> {
                String line = "  ".repeat(
                            Math.max(0, root.relativize(file).getNameCount() - 1))
                        + file.getFileName() + '\n';
                FileToolBudgetGuard.ReadDecision decision = budget.accept(line);
                if (!decision.accepted()) {
                    throw new ProjectPathResolver.DirectoryBudgetExceededException();
                }
                structure.append(decision.acceptedText());
            });
        } catch (ProjectPathResolver.DirectoryBudgetExceededException exception) {
            return null;
        }
        FileToolBudgetGuard.ReadDecision finish = budget.finish();
        if (!finish.accepted()) {
            return null;
        }
        return structure.append(finish.acceptedText()).toString();
    }

    private boolean shouldIgnore(String fileName) {
        return IGNORED_NAMES.contains(fileName)
                || IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private String result(FileToolResult result) {
        return FileToolProtocolSupport.json(result);
    }

    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return generateToolExecutedResult(arguments, null);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String rawResult) {
        String path = arguments == null ? null : arguments.getStr("relativeDirPath");
        return FileToolProtocolSupport.stableSummary(
                this, FileToolProtocolSupport.parse(rawResult, getToolName(), path));
    }
}
