package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
            scopeManager.requireCurrent(appId == null ? Long.MIN_VALUE : appId, getToolName());
            Path path = projectPathResolver.resolveExisting(appId, relativeDirPath, true);
            if (!Files.isDirectory(path)) {
                return result(FileToolResult.notFound(
                        getToolName(), relativeDirPath, "目录不存在或不是目录"));
            }
            List<Path> files = projectPathResolver.collectSafeDirectoryEntries(
                    path, appId, this::shouldIgnore);
            return result(FileToolResult.readApplied(
                    getToolName(), relativeDirPath,
                    "目录读取成功", formatStructure(path, files)));
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

    private String formatStructure(Path root, List<Path> files) {
        StringBuilder structure = new StringBuilder("项目目录结构:\n");
        files.stream()
                .sorted(Comparator.comparingInt((Path file) -> root.relativize(file).getNameCount())
                        .thenComparing(Path::toString))
                .forEach(file -> structure.append("  ".repeat(
                                Math.max(0, root.relativize(file).getNameCount() - 1)))
                        .append(file.getFileName()).append('\n'));
        return structure.toString();
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
