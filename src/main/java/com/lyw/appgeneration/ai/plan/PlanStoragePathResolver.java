package com.lyw.appgeneration.ai.plan;

import com.lyw.appgeneration.constants.AppConstant;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.Objects;
import java.util.function.Function;

/** 计划旁车文件的内部安全路径解析器，不暴露给普通文件工具。 */
@Component
public final class PlanStoragePathResolver {

    private static final String PLAN_FILE_NAME = ".plan.json";
    private final Path outputRoot;

    public PlanStoragePathResolver() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }

    public PlanStoragePathResolver(Path outputRoot) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    public Path projectRoot(long appId) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 必须大于 0");
        }
        Path root = outputRoot.resolve("vue_project_" + appId).normalize();
        if (!root.startsWith(outputRoot)) {
            throw new IllegalArgumentException("应用目录越出生成根目录");
        }
        return root;
    }

    public Path planPath(long appId) {
        return projectRoot(appId).resolve(PLAN_FILE_NAME);
    }

    public void ensureSafeProjectRoot(long appId) {
        Path root = projectRoot(appId);
        try {
            Files.createDirectories(outputRoot);
            Path realOutputRoot = outputRoot.toRealPath();
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(root)) {
                throw new IllegalStateException("项目根目录不能是符号链接");
            }
            Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(root)) {
                throw new IllegalStateException("项目根目录不是安全目录");
            }
            Path realRoot = root.toRealPath();
            if (!realRoot.startsWith(realOutputRoot)) {
                throw new IllegalStateException("项目根目录真实路径越出生成根目录");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法安全解析计划项目根目录", exception);
        }
    }

    /**
     * 在不跟随项目目录符号链接的安全目录句柄内执行计划文件操作。
     * 不支持安全目录句柄的文件系统直接拒绝，不能退回绝对路径写入。
     */
    public <T> T withSecureProjectDirectory(
            long appId, Function<SecureDirectoryStream<Path>, T> action) {
        Objects.requireNonNull(action, "安全目录动作不能为空");
        ensureSafeProjectRoot(appId);
        Path root = projectRoot(appId);
        Path projectName = root.getFileName();
        try (var parentStream = Files.newDirectoryStream(outputRoot)) {
            if (!(parentStream instanceof SecureDirectoryStream<?>)) {
                throw new IllegalStateException("当前文件系统不支持安全计划目录访问");
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> parent =
                    (SecureDirectoryStream<Path>) parentStream;
            SecureDirectoryStream<Path> project;
            try {
                project = parent.newDirectoryStream(
                        projectName, LinkOption.NOFOLLOW_LINKS);
            } catch (java.nio.file.NoSuchFileException missing) {
                Files.createDirectory(root);
                project = parent.newDirectoryStream(
                        projectName, LinkOption.NOFOLLOW_LINKS);
            }
            SecureDirectoryStream<Path> openedProject = project;
            try (openedProject) {
                return action.apply(openedProject);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法打开安全计划目录: " + root, exception);
        }
    }
}
