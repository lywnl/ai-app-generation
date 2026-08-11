package com.lyw.appgeneration.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Vue 工程 npm 构建器。
 */
@Slf4j
@Component
public class VueProjectBuilder {

    private static final Duration NPM_INSTALL_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration NPM_BUILD_TIMEOUT = Duration.ofSeconds(180);
    private static final String TRUSTED_BUILD_SCRIPT = "vite build";
    private static final Set<String> FORBIDDEN_PACKAGE_FIELDS = Set.of(
            "overrides",
            "optionalDependencies",
            "peerDependencies",
            "bundledDependencies",
            "bundleDependencies",
            "workspaces");
    private static final List<String> FORBIDDEN_NPM_RESOLUTION_FILES = List.of(
            "package-lock.json",
            "npm-shrinkwrap.json",
            ".npmrc",
            "node_modules");
    private static final Map<String, String> TRUSTED_RUNTIME_DEPENDENCIES = Map.of(
            "vue", "3.3.4",
            "vue-router", "4.2.4",
            "element-plus", "2.8.8",
            "@element-plus/icons-vue", "2.3.1",
            "echarts", "5.5.1");
    private static final Map<String, String> TRUSTED_DEVELOPMENT_DEPENDENCIES = Map.of(
            "vite", "4.4.5",
            "@vitejs/plugin-vue", "4.2.3");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TRUSTED_VITE_CONFIG = """
            import { defineConfig } from 'vite'
            import vue from '@vitejs/plugin-vue'

            export default defineConfig({
              base: './',
              plugins: [vue()],
              css: {
                postcss: { plugins: [] }
              }
            })
            """;

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
        try {
            projectDirectory = projectDirectory.toRealPath();
        } catch (IOException | RuntimeException exception) {
            return result(false, BuildStage.VALIDATION, null, false,
                    "项目目录无法规范化: " + safeMessage(exception), startNanos);
        }

        StringBuilder output = new StringBuilder();
        BuildResult installResult = runCommand(
                projectDirectory,
                List.of(npmExecutable, "install", "--ignore-scripts", "--package-lock=false",
                        "--no-audit", "--no-fund"),
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

        BuildResult buildResult = runTrustedViteBuild(projectDirectory, output, startNanos);
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

    private BuildResult runTrustedViteBuild(
            Path projectDirectory,
            StringBuilder output,
            long startNanos) {
        Path trustedConfig = null;
        try {
            Path projectRoot = projectDirectory.toRealPath();
            trustedConfig = Files.createTempFile(
                    projectRoot, ".trusted-vite-config-", ".mjs");
            Files.writeString(trustedConfig, TRUSTED_VITE_CONFIG, StandardCharsets.UTF_8);
            return runCommand(
                    projectRoot,
                    List.of(
                            "node",
                            projectRoot.resolve("node_modules/vite/bin/vite.js").toString(),
                            "build",
                            "--config",
                            trustedConfig.toString()),
                    NPM_BUILD_TIMEOUT,
                    BuildStage.NPM_BUILD,
                    output,
                    startNanos);
        } catch (IOException | RuntimeException exception) {
            appendOutput(output, "准备可信 Vite 构建配置失败: " + safeMessage(exception));
            return result(false, BuildStage.NPM_BUILD, null, false,
                    output.toString(), startNanos);
        } finally {
            deleteTrustedConfig(trustedConfig, output);
        }
    }

    private void deleteTrustedConfig(Path trustedConfig, StringBuilder output) {
        if (trustedConfig == null) {
            return;
        }
        try {
            Files.deleteIfExists(trustedConfig);
        } catch (IOException exception) {
            appendOutput(output, "清理可信 Vite 构建配置失败: " + safeMessage(exception));
        }
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
        for (String fileName : FORBIDDEN_NPM_RESOLUTION_FILES) {
            if (Files.exists(projectDirectory.resolve(fileName), LinkOption.NOFOLLOW_LINKS)) {
                return "项目目录不允许包含可控制 npm 解析的文件: " + fileName;
            }
        }
        return validatePackageJson(projectDirectory.resolve("package.json"));
    }

    private String validatePackageJson(Path packageJsonPath) {
        JsonNode packageJson;
        try {
            packageJson = OBJECT_MAPPER.readTree(packageJsonPath.toFile());
        } catch (IOException | RuntimeException exception) {
            return "package.json 无法解析: " + safeMessage(exception);
        }
        if (packageJson == null || !packageJson.isObject()) {
            return "package.json 必须是 JSON 对象";
        }
        String buildScript = packageJson.path("scripts").path("build").asText(null);
        if (!TRUSTED_BUILD_SCRIPT.equals(buildScript)) {
            return "package.json build 脚本必须固定为 " + TRUSTED_BUILD_SCRIPT;
        }
        for (String fieldName : FORBIDDEN_PACKAGE_FIELDS) {
            if (packageJson.has(fieldName)) {
                return "package.json 不允许包含可改变依赖图的字段: " + fieldName;
            }
        }
        String runtimeError = validateDependencyNames(
                packageJson.get("dependencies"), TRUSTED_RUNTIME_DEPENDENCIES, "dependencies");
        if (runtimeError != null) {
            return runtimeError;
        }
        return validateDependencyNames(
                packageJson.get("devDependencies"),
                TRUSTED_DEVELOPMENT_DEPENDENCIES,
                "devDependencies");
    }

    private String validateDependencyNames(
            JsonNode dependencyNode,
            Map<String, String> trustedDependencies,
            String fieldName) {
        if (dependencyNode == null || !dependencyNode.isObject()) {
            return fieldName + " 必须是 JSON 对象";
        }
        var names = dependencyNode.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String trustedVersion = trustedDependencies.get(name);
            if (trustedVersion == null) {
                return fieldName + " 包含非受信依赖: " + name;
            }
            JsonNode versionNode = dependencyNode.get(name);
            if (!versionNode.isTextual() || !trustedVersion.equals(versionNode.textValue())) {
                return fieldName + " 中依赖 " + name + " 的版本必须固定为 " + trustedVersion;
            }
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
