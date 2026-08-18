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
import java.util.Set;

/** 受控删除 Vue 项目内的非关键普通文件。 */
@Slf4j
@Component
public class FileDeleteTool extends BaseTool {

    private static final Set<String> IMPORTANT_FILES = Set.of(
            "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "vite.config.js", "vite.config.ts", "vue.config.js",
            "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
            "index.html", "main.js", "main.ts", "App.vue", ".gitignore", "README.md");

    private final ProjectPathResolver projectPathResolver = new ProjectPathResolver();
    private final FileToolExecutionScopeManager scopeManager;

    public FileDeleteTool(FileToolExecutionScopeManager scopeManager) {
        this.scopeManager = scopeManager;
    }

    @Tool("删除指定路径的文件")
    public String deleteFile(
            @P("文件的相对路径") String relativeFilePath,
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
            Path path = projectPathResolver.resolveExisting(appId, relativeFilePath, false);
            if (!Files.exists(path)) {
                return result(FileToolResult.notFound(
                        getToolName(), relativeFilePath, "文件不存在，无需删除"));
            }
            if (!Files.isRegularFile(path)) {
                return result(FileToolResult.rejected(
                        getToolName(), relativeFilePath, "指定路径不是普通文件"));
            }
            if (isImportantFile(path.getFileName().toString())) {
                return result(FileToolResult.rejected(
                        getToolName(), relativeFilePath, "不允许删除项目关键文件"));
            }
            String stateKey = projectPathResolver.stateKey(appId, path);
            Files.delete(path);
            return result(FileToolResult.applied(
                    getToolName(), stateKey, true, "文件删除成功"));
        } catch (FileToolExecutionScopeManager.ScopeViolationException exception) {
            return FileToolProtocolSupport.rejected(
                    getToolName(), relativeFilePath, exception);
        } catch (ProjectPathResolver.UnsafeProjectPathException exception) {
            return result(FileToolResult.rejected(
                    getToolName(), relativeFilePath, exception.getMessage()));
        } catch (IOException | RuntimeException exception) {
            log.error("删除文件失败: appId={}, path={}", appId, relativeFilePath, exception);
            return result(FileToolResult.failed(
                    getToolName(), relativeFilePath, "删除文件失败"));
        }
    }

    private boolean isImportantFile(String fileName) {
        return IMPORTANT_FILES.stream().anyMatch(fileName::equalsIgnoreCase);
    }

    private String result(FileToolResult result) {
        return FileToolProtocolSupport.json(result);
    }

    @Override
    public String getToolName() {
        return "deleteFile";
    }

    @Override
    public String getDisplayName() {
        return "删除文件";
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
