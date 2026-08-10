package com.lyw.appgeneration.rag.build;

import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueGenerationBuildEvaluatorTest {

    @TempDir
    Path tempDir;

    @Test
    void waitsForRealGenerationMovesActualOutputAndBuildsDetailedWithoutRepair() throws Exception {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        VueGenerationBuildCase testCase = new VueGenerationBuildCase(
                "case-01", 7L, "基础站点", "真实需求");
        TemplateDoc skeleton = document("skeleton-1");
        TemplateDoc feature = document("feature-1");
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        Flux<String> generationStream = Flux.defer(() -> {
            try {
                Path generated = sourceRoot.resolve("vue_project_7");
                Files.createDirectories(generated);
                Files.writeString(generated.resolve("package.json"), "{}");
                return Flux.just("完成");
            } catch (Exception exception) {
                return Flux.error(exception);
            }
        });
        when(facade.generateVueProjectForEvaluation("真实需求", 7L))
                .thenReturn(new AiCodeGeneratorFacade.VueProjectGeneration(
                        new VueRagContext(skeleton, List.of(feature), "catalog", false),
                        generationStream));
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Path expectedTarget = reportRoot.resolve("case-01");
        when(builder.buildProjectDetailed(expectedTarget.toString()))
                .thenReturn(new BuildResult(
                        true, BuildStage.SUCCESS, 0, false, "built", 10L));

        VueGenerationBuildReport report = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5))
                .evaluate(List.of(testCase));

        assertTrue(report.executed());
        assertFalse(report.passed(), "硬门槛必须恰好 10/10，单条成功不能冒充通过");
        assertTrue(Files.isRegularFile(expectedTarget.resolve("package.json")));
        assertFalse(Files.exists(sourceRoot.resolve("vue_project_7")));
        String markdown = report.renderMarkdown();
        assertTrue(markdown.contains("skeleton-1"));
        assertTrue(markdown.contains("feature-1"));
        verify(facade).generateVueProjectForEvaluation("真实需求", 7L);
        verify(builder).buildProjectDetailed(expectedTarget.toString());
    }

    @Test
    void recordsGenerationFailureAndDoesNotInvokeBuilderOrRetry() {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        VueGenerationBuildCase testCase = new VueGenerationBuildCase(
                "case-fail", 9L, "基础站点", "失败需求");
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateVueProjectForEvaluation("失败需求", 9L))
                .thenReturn(new AiCodeGeneratorFacade.VueProjectGeneration(
                        VueRagContext.unavailable(),
                        Flux.error(new IllegalStateException("模型失败"))));
        VueProjectBuilder builder = mock(VueProjectBuilder.class);

        VueGenerationBuildReport report = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5))
                .evaluate(List.of(testCase));

        assertFalse(report.passed());
        assertTrue(report.renderMarkdown().contains("模型失败"));
        verify(facade).generateVueProjectForEvaluation("失败需求", 9L);
        verify(builder, org.mockito.Mockito.never()).buildProjectDetailed(org.mockito.ArgumentMatchers.any());
    }

    private TemplateDoc document(String id) {
        TemplateDoc document = new TemplateDoc();
        document.setId(id);
        return document;
    }
}
