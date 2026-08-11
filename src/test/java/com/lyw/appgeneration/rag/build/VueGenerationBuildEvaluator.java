package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 十条真实 Vue 生成与首次构建编排器。
 */
public final class VueGenerationBuildEvaluator {

    private static final int MAX_APP_ID_ALLOCATION_ATTEMPTS = 1_000;

    private final AiCodeGeneratorFacade facade;
    private final VueProjectBuilder projectBuilder;
    private final Path generatedSourceRoot;
    private final Path reportGeneratedRoot;
    private final Duration generationTimeout;
    private final VueGenerationAppIdAllocator appIdAllocator;

    public VueGenerationBuildEvaluator(
            AiCodeGeneratorFacade facade,
            VueProjectBuilder projectBuilder,
            Path generatedSourceRoot,
            Path reportGeneratedRoot,
            Duration generationTimeout) {
        this(facade, projectBuilder, generatedSourceRoot, reportGeneratedRoot,
                generationTimeout, new AtomicVueGenerationAppIdAllocator());
    }

    VueGenerationBuildEvaluator(
            AiCodeGeneratorFacade facade,
            VueProjectBuilder projectBuilder,
            Path generatedSourceRoot,
            Path reportGeneratedRoot,
            Duration generationTimeout,
            VueGenerationAppIdAllocator appIdAllocator) {
        this.facade = facade;
        this.projectBuilder = projectBuilder;
        this.generatedSourceRoot = generatedSourceRoot;
        this.reportGeneratedRoot = reportGeneratedRoot;
        this.generationTimeout = generationTimeout;
        this.appIdAllocator = appIdAllocator;
    }

    public VueGenerationBuildReport evaluate(List<VueGenerationBuildCase> cases) {
        List<VueGenerationBuildRow> rows = new ArrayList<>();
        Set<Long> allocatedAppIds = new HashSet<>();
        for (VueGenerationBuildCase testCase : cases) {
            long executionAppId = allocateUniqueAppId(allocatedAppIds);
            rows.add(evaluateOne(testCase, executionAppId));
        }
        return VueGenerationBuildReport.executed(rows);
    }

    private long allocateUniqueAppId(Set<Long> allocatedAppIds) {
        for (int attempt = 0; attempt < MAX_APP_ID_ALLOCATION_ATTEMPTS; attempt++) {
            long appId = appIdAllocator.nextAppId();
            if (appId <= 0) {
                throw new IllegalStateException("分配器返回的 appId 必须是正数: " + appId);
            }
            if (allocatedAppIds.contains(appId)) {
                continue;
            }
            try {
                if (!tryClaimAppId(generatedSourceRoot, reportGeneratedRoot, appId)) {
                    continue;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("领取评测 appId 失败: " + appId, exception);
            }
            allocatedAppIds.add(appId);
            return appId;
        }
        throw new IllegalStateException("无法分配无碰撞的评测 appId");
    }

    private VueGenerationBuildRow evaluateOne(
            VueGenerationBuildCase testCase,
            long executionAppId) {
        VueRagContext context = VueRagContext.unavailable();
        try {
            Path source = sourcePath(executionAppId);
            Path target = targetPath(executionAppId);
            AiCodeGeneratorFacade.VueProjectGeneration generation =
                    facade.generateVueProjectForEvaluation(testCase.prompt(), executionAppId);
            context = generation.context();
            generation.stream().then().block(generationTimeout);
            if (!Files.isDirectory(source) || isDirectoryEmpty(source)) {
                throw new IllegalStateException("真实生成完成但项目目录不存在或为空: " + source);
            }
            moveGeneratedProject(source, target);
            BuildResult buildResult = projectBuilder.buildProjectDetailed(target.toString());
            return row(testCase, executionAppId, true, context, buildResult, null);
        } catch (Exception exception) {
            deleteEmptyClaim(sourcePath(executionAppId));
            deleteEmptyClaim(targetPath(executionAppId));
            return row(testCase, executionAppId, false, context, null, safeError(exception));
        }
    }

    static boolean tryClaimAppId(
            Path generatedSourceRoot,
            Path reportGeneratedRoot,
            long appId) throws IOException {
        Path source = generatedSourceRoot.resolve("vue_project_" + appId);
        Files.createDirectories(source.getParent());
        try {
            Files.createDirectory(source);
        } catch (FileAlreadyExistsException exception) {
            return false;
        }
        Path target = reportGeneratedRoot.resolve("vue_project_" + appId);
        try {
            Files.createDirectories(target.getParent());
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException exception) {
            Files.deleteIfExists(source);
            return false;
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(source);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return true;
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private void deleteEmptyClaim(Path source) {
        try {
            if (Files.isDirectory(source) && isDirectoryEmpty(source)) {
                Files.deleteIfExists(source);
            }
        } catch (IOException ignored) {
            // 部分生成目录是失败事实，清理失败不得覆盖原始评测错误。
        }
    }

    private Path sourcePath(long executionAppId) {
        return generatedSourceRoot.resolve("vue_project_" + executionAppId);
    }

    private Path targetPath(long executionAppId) {
        return reportGeneratedRoot.resolve("vue_project_" + executionAppId);
    }

    private VueGenerationBuildRow row(
            VueGenerationBuildCase testCase,
            long executionAppId,
            boolean generationCompleted,
            VueRagContext context,
            BuildResult buildResult,
            String error) {
        String skeletonId = context == null || context.skeleton() == null
                ? null
                : context.skeleton().getId();
        List<String> featureIds = context == null
                ? List.of()
                : context.features().stream().map(TemplateDoc::getId).toList();
        return new VueGenerationBuildRow(
                testCase, executionAppId, generationCompleted,
                skeletonId, featureIds, buildResult, error);
    }

    private void moveGeneratedProject(Path source, Path target) throws IOException {
        if (!Files.isDirectory(target) || !isDirectoryEmpty(target)) {
            throw new IOException("评测事实目录未被独占或不为空: " + target);
        }
        copyRecursively(source, target);
        deleteRecursively(source);
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private String safeError(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

}
