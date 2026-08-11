package com.lyw.appgeneration.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import com.lyw.appgeneration.manger.AppFileStateManager;
import com.lyw.appgeneration.manger.AppFileStateManager.WriteResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 * <p>
 * 关键设计:返回值会包含"已写入文件清单"和"是否重复写入"信号,
 * 让 LLM 在工具循环中能感知当前进度,避免反复写入同一文件陷入死循环。
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    @Resource
    private AppFileStateManager appFileStateManager;

    private final ProjectPathResolver projectPathResolver = new ProjectPathResolver();

    @Tool("写入文件到指定路径。每个文件只应被写入一次;若已写入过请勿重复调用,可改用 modifyFile 或直接结束。")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId Long appId
    ) {
        try {
            ProjectPathResolver.ResolvedProjectPath resolvedPath =
                    projectPathResolver.resolveForWrite(appId, relativeFilePath);
            Path path = resolvedPath.path();
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            WriteResult result = appFileStateManager.writeAndRecord(
                    appId,
                    resolvedPath.stateKey(),
                    content,
                    () -> Files.write(
                            path,
                            content.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING));

            // 情况 1:重复写入相同内容 → 不实际写盘,告知 LLM 跳过
            if (result.status == AppFileStateManager.WriteStatus.DUPLICATE_SAME_CONTENT) {
                log.warn("[FileWrite] 重复写入相同内容,跳过: appId={}, path={}, writeCount={}",
                        appId, relativeFilePath, result.writeCount);
                return buildResponse(
                        "⚠️ 跳过:文件已写入过且内容未变化 → " + relativeFilePath
                                + "(该路径第 " + result.writeCount + " 次写入)。请不要再重写此文件,继续处理其他文件或调用 exit 结束。",
                        result);
            }

            // 情况 2:重复写入但内容不同 → 写盘,但强烈警告
            if (result.status == AppFileStateManager.WriteStatus.DUPLICATE_DIFFERENT_CONTENT) {
                log.warn("[FileWrite] 覆盖已存在文件: appId={}, path={}, writeCount={}",
                        appId, relativeFilePath, result.writeCount);
                return buildResponse(
                        "⚠️ 已覆盖已存在文件 → " + relativeFilePath
                                + "(第 " + result.writeCount + " 次写入)。请确认这是必要的修改;若只是重复工作,立即调用 exit 结束。",
                        result);
            }

            // 情况 3:首次写入 → 正常写盘
            log.info("[FileWrite] 成功写入: appId={}, path={}, 总文件数={}",
                    appId, relativeFilePath, result.totalFiles);
            return buildResponse("✅ 文件写入成功 → " + relativeFilePath, result);

        } catch (ProjectPathResolver.UnsafeProjectPathException exception) {
            return "错误：路径不安全 - " + exception.getMessage();
        } catch (IOException | RuntimeException e) {
            String errorMessage = "文件写入失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 组装返回给 LLM 的响应,附加"当前项目文件清单",让 LLM 感知进度
     */
    private String buildResponse(String mainMessage, WriteResult result) {
        String fileList = result.allFiles == null || result.allFiles.isEmpty()
                ? "(暂无)"
                : String.join(", ", result.allFiles);
        return String.format(
                "%s%n当前项目已写入 %d 个文件:[%s]%n写入完毕后请立即调用 exit 工具结束生成。",
                mainMessage, result.totalFiles, fileList
        );
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
        String relativeFilePath = arguments.getStr("relativeFilePath");
        String suffix = FileUtil.getSuffix(relativeFilePath);
        String content = arguments.getStr("content");
        return String.format("""
                        [工具调用] %s %s
                        ```%s
                        %s
                        ```
                        """, getDisplayName(), relativeFilePath, suffix, content);
    }
}
