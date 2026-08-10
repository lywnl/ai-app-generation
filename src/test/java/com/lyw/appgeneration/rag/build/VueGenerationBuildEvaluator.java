package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 十条真实 Vue 生成与首次构建编排器。
 */
public final class VueGenerationBuildEvaluator {

    private final AiCodeGeneratorFacade facade;
    private final VueProjectBuilder projectBuilder;
    private final Path generatedSourceRoot;
    private final Path reportGeneratedRoot;
    private final Duration generationTimeout;

    public VueGenerationBuildEvaluator(
            AiCodeGeneratorFacade facade,
            VueProjectBuilder projectBuilder,
            Path generatedSourceRoot,
            Path reportGeneratedRoot,
            Duration generationTimeout) {
        this.facade = facade;
        this.projectBuilder = projectBuilder;
        this.generatedSourceRoot = generatedSourceRoot;
        this.reportGeneratedRoot = reportGeneratedRoot;
        this.generationTimeout = generationTimeout;
    }

    public VueGenerationBuildReport evaluate(List<VueGenerationBuildCase> cases) {
        List<VueGenerationBuildRow> rows = new ArrayList<>();
        for (VueGenerationBuildCase testCase : cases) {
            rows.add(evaluateOne(testCase));
        }
        return VueGenerationBuildReport.executed(rows);
    }

    private VueGenerationBuildRow evaluateOne(VueGenerationBuildCase testCase) {
        VueRagContext context = VueRagContext.unavailable();
        try {
            Path source = generatedSourceRoot.resolve("vue_project_" + testCase.appId());
            Path target = reportGeneratedRoot.resolve(testCase.caseId());
            deleteRecursively(source);
            deleteRecursively(target);
            AiCodeGeneratorFacade.VueProjectGeneration generation =
                    facade.generateVueProjectForEvaluation(testCase.prompt(), testCase.appId());
            context = generation.context();
            generation.stream().then().block(generationTimeout);
            if (!Files.isDirectory(source)) {
                throw new IllegalStateException("真实生成完成但项目目录不存在: " + source);
            }
            moveGeneratedProject(source, target);
            BuildResult buildResult = projectBuilder.buildProjectDetailed(target.toString());
            return row(testCase, true, context, buildResult, null);
        } catch (Exception exception) {
            return row(testCase, false, context, null, safeError(exception));
        }
    }

    private VueGenerationBuildRow row(
            VueGenerationBuildCase testCase,
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
                testCase, generationCompleted, skeletonId, featureIds, buildResult, error);
    }

    private void moveGeneratedProject(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            copyRecursively(source, target);
            deleteRecursively(source);
        }
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
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
