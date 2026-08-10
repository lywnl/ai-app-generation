package com.lyw.appgeneration.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Vue 工程 npm 构建器。
 */
@Slf4j
@Component
public class VueProjectBuilder {

    private static final Duration NPM_INSTALL_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration NPM_BUILD_TIMEOUT = Duration.ofSeconds(180);

    private final CommandExecutor commandExecutor;
    private final String npmExecutable;

    /**
     * 生产环境始终使用真实 ProcessBuilder 命令执行器。
     */
    @Autowired
    public VueProjectBuilder() {
        this(new ProcessCommandExecutor(), npmExecutable(System.getProperty("os.name")));
    }

    VueProjectBuilder(CommandExecutor commandExecutor, String npmExecutable) {
        this.commandExecutor = commandExecutor;
        this.npmExecutable = npmExecutable;
    }

    /**
     * 异步构建 Vue 项目，不阻塞调用线程。
     *
     * @param projectPath 项目根目录路径
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual()
                .name("vue-builder-" + System.currentTimeMillis())
                .start(() -> logAsyncResult(projectPath, buildProjectDetailed(projectPath)));
    }

    /**
     * 保留旧布尔接口，结果语义完全委托给详细构建接口。
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        return buildProjectDetailed(projectPath).success();
    }

    /**
     * 执行 npm install、npm build 与 dist 验证并返回有界诊断结果。
     *
     * @param projectPath 项目根目录路径
     * @return 不会抛出命令异常的结构化结果
     */
    public BuildResult buildProjectDetailed(String projectPath) {
        long startNanos = System.nanoTime();
        Path projectDirectory = toProjectDirectory(projectPath);
        String validationError = validate(projectDirectory);
        if (validationError != null) {
            return result(false, BuildStage.VALIDATION, null, false,
                    validationError, startNanos);
        }

        StringBuilder output = new StringBuilder();
        BuildResult installResult = runCommand(
                projectDirectory,
                List.of(npmExecutable, "install", "--ignore-scripts", "--no-audit", "--no-fund"),
                NPM_INSTALL_TIMEOUT,
                BuildStage.NPM_INSTALL,
                output,
                startNanos);
        if (installResult != null) {
            return installResult;
        }

        BuildResult distCleanupResult = cleanPreviousDist(
                projectDirectory, output, startNanos);
        if (distCleanupResult != null) {
            return distCleanupResult;
        }

        BuildResult buildResult = runCommand(
                projectDirectory,
                List.of(npmExecutable, "run", "build"),
                NPM_BUILD_TIMEOUT,
                BuildStage.NPM_BUILD,
                output,
                startNanos);
        if (buildResult != null) {
            return buildResult;
        }

        Path distDirectory = projectDirectory.resolve("dist");
        if (!Files.isDirectory(distDirectory)) {
            appendOutput(output, "构建命令成功，但 dist 目录未生成");
            return result(false, BuildStage.DIST_CHECK, 0, false, output.toString(), startNanos);
        }
        return result(true, BuildStage.SUCCESS, 0, false, output.toString(), startNanos);
    }

    private BuildResult cleanPreviousDist(Path projectDirectory,
                                          StringBuilder output,
                                          long startNanos) {
        try {
            deleteDistDirectory(projectDirectory);
            return null;
        } catch (IOException | RuntimeException exception) {
            appendOutput(output, "清理旧 dist 目录失败: " + safeMessage(exception));
            return result(false, BuildStage.NPM_BUILD, null, false,
                    output.toString(), startNanos);
        }
    }

    private void deleteDistDirectory(Path projectDirectory) throws IOException {
        Path projectRoot = projectDirectory.toRealPath();
        Path distDirectory = projectRoot.resolve("dist").normalize();
        if (!projectRoot.equals(distDirectory.getParent())) {
            throw new IOException("dist 目录超出项目根目录");
        }
        if (!Files.exists(distDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Files.walkFileTree(distDirectory, new SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) throws IOException {
                deleteWithinDist(distDirectory, file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(
                    Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                deleteWithinDist(distDirectory, directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteWithinDist(Path distDirectory, Path path) throws IOException {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(distDirectory)) {
            throw new IOException("拒绝删除 dist 目录之外的路径");
        }
        Files.delete(path);
    }

    static String npmExecutable(String operatingSystemName) {
        String normalized = operatingSystemName == null
                ? ""
                : operatingSystemName.toLowerCase(Locale.ROOT);
        return normalized.contains("windows") ? "npm.cmd" : "npm";
    }

    private Path toProjectDirectory(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        try {
            return Path.of(projectPath);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String validate(Path projectDirectory) {
        if (projectDirectory == null || !Files.isDirectory(projectDirectory)) {
            return "项目目录不存在或不是目录";
        }
        if (!Files.isRegularFile(projectDirectory.resolve("package.json"))) {
            return "package.json 文件不存在或不是普通文件";
        }
        return null;
    }

    private BuildResult runCommand(Path projectDirectory,
                                   List<String> command,
                                   Duration timeout,
                                   BuildStage failureStage,
                                   StringBuilder output,
                                   long startNanos) {
        try {
            CommandResult commandResult = commandExecutor.execute(projectDirectory, command, timeout);
            appendOutput(output, commandResult.outputTail());
            if (commandResult.timedOut() || !Integer.valueOf(0).equals(commandResult.exitCode())) {
                return result(false, failureStage, commandResult.exitCode(), commandResult.timedOut(),
                        output.toString(), startNanos);
            }
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            appendOutput(output, "构建命令等待被中断");
            return result(false, failureStage, null, false, output.toString(), startNanos);
        } catch (IOException | RuntimeException exception) {
            appendOutput(output, "构建命令启动或执行失败: " + safeMessage(exception));
            return result(false, failureStage, null, false, output.toString(), startNanos);
        }
    }

    private void appendOutput(StringBuilder output, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!output.isEmpty()) {
            output.append(System.lineSeparator());
        }
        output.append(text);
        if (output.length() > BuildResult.MAX_OUTPUT_TAIL_CHARS) {
            output.delete(0, output.length() - BuildResult.MAX_OUTPUT_TAIL_CHARS);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private BuildResult result(boolean success,
                               BuildStage stage,
                               Integer exitCode,
                               boolean timedOut,
                               String output,
                               long startNanos) {
        long durationMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        return new BuildResult(success, stage, exitCode, timedOut, output, durationMillis);
    }

    private void logAsyncResult(String projectPath, BuildResult result) {
        if (result.success()) {
            log.info("Vue 项目异步构建成功: projectPath={},stage={},exitCode={},durationMs={}",
                    projectPath, result.stage(), result.exitCode(), result.durationMillis());
            return;
        }
        log.error("Vue 项目异步构建失败: projectPath={},stage={},exitCode={},timedOut={},durationMs={}",
                projectPath, result.stage(), result.exitCode(), result.timedOut(), result.durationMillis());
    }
}
