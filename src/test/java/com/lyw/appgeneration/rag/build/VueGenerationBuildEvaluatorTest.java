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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueGenerationBuildEvaluatorTest {

    @TempDir
    Path tempDir;

    @Test
    void allocatesDisjointPositiveAppIdsAndFactPathsAcrossConsecutiveRuns() {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        List<VueGenerationBuildCase> cases = IntStream.range(0, 10)
                .mapToObj(index -> new VueGenerationBuildCase(
                        "case-" + index,
                        "类别-" + index,
                        "固定需求-" + index))
                .toList();
        List<Long> facadeAppIds = new ArrayList<>();
        List<String> facadePrompts = new ArrayList<>();
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateVueProjectForEvaluation(anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String prompt = invocation.getArgument(0);
                    long appId = invocation.getArgument(1);
                    facadePrompts.add(prompt);
                    facadeAppIds.add(appId);
                    return new AiCodeGeneratorFacade.VueProjectGeneration(
                            VueRagContext.unavailable(),
                            Flux.defer(() -> createGeneratedProject(sourceRoot, appId)));
                });
        List<Path> factPaths = new ArrayList<>();
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(anyString())).thenAnswer(invocation -> {
            factPaths.add(Path.of(invocation.getArgument(0, String.class)));
            return successfulBuild();
        });
        VueGenerationBuildEvaluator evaluator = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5));

        VueGenerationBuildReport first = evaluator.evaluate(cases);
        VueGenerationBuildReport second = evaluator.evaluate(cases);

        List<Long> firstRound = facadeAppIds.subList(0, 10);
        List<Long> secondRound = facadeAppIds.subList(10, 20);
        List<String> expectedPrompts = cases.stream()
                .map(VueGenerationBuildCase::prompt)
                .toList();
        assertAll(
                () -> assertTrue(first.passed(), first.renderMarkdown()),
                () -> assertTrue(second.passed(), second.renderMarkdown()),
                () -> assertEquals(10, new HashSet<>(firstRound).size()),
                () -> assertEquals(10, new HashSet<>(secondRound).size()),
                () -> assertEquals(20, new HashSet<>(facadeAppIds).size(),
                        "连续两轮必须使用互不相交的运行 appId"),
                () -> assertTrue(facadeAppIds.stream().allMatch(appId -> appId > 0)),
                () -> assertEquals(expectedPrompts, facadePrompts.subList(0, 10)),
                () -> assertEquals(expectedPrompts, facadePrompts.subList(10, 20)),
                () -> cases.forEach(testCase -> {
                    assertTrue(first.renderMarkdown().contains(testCase.caseId()));
                    assertTrue(second.renderMarkdown().contains(testCase.caseId()));
                }),
                () -> assertEquals(facadeAppIds.stream()
                                .map(appId -> reportRoot.resolve("vue_project_" + appId))
                                .toList(),
                        factPaths,
                        "事实目录必须由本轮分配的 appId 解析"));
    }

    @Test
    void doesNotDeleteHistoricalSourceOrFactPathsBeforeGeneration() throws Exception {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        VueGenerationBuildCase testCase = new VueGenerationBuildCase(
                "case-history", "基础站点", "历史隔离需求");
        Path historicalSourceMarker = sourceRoot.resolve("vue_project_77/historical.txt");
        Path historicalFactMarker = reportRoot.resolve("case-history/historical.txt");
        Files.createDirectories(historicalSourceMarker.getParent());
        Files.createDirectories(historicalFactMarker.getParent());
        Files.writeString(historicalSourceMarker, "历史源码事实");
        Files.writeString(historicalFactMarker, "历史报告事实");
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateVueProjectForEvaluation(anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    long appId = invocation.getArgument(1);
                    return new AiCodeGeneratorFacade.VueProjectGeneration(
                            VueRagContext.unavailable(),
                            Flux.defer(() -> createGeneratedProject(sourceRoot, appId)));
                });
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(anyString())).thenReturn(successfulBuild());

        new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5), () -> 78L)
                .evaluate(List.of(testCase));

        assertTrue(Files.isRegularFile(historicalSourceMarker), "不得清理历史生成目录");
        assertTrue(Files.isRegularFile(historicalFactMarker), "不得清理历史事实目录");
    }

    @Test
    void skipsAllocatedIdsWhoseSourceOrFactPathAlreadyExists() throws Exception {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        Path sourceCollision = sourceRoot.resolve("vue_project_501/collision.txt");
        Path factCollision = reportRoot.resolve("vue_project_502/collision.txt");
        Files.createDirectories(sourceCollision.getParent());
        Files.createDirectories(factCollision.getParent());
        Files.writeString(sourceCollision, "历史源码");
        Files.writeString(factCollision, "历史事实");
        long[] candidates = {501L, 502L, 503L};
        AtomicInteger index = new AtomicInteger();
        VueGenerationAppIdAllocator allocator = () -> candidates[index.getAndIncrement()];
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateVueProjectForEvaluation("碰撞需求", 503L))
                .thenReturn(new AiCodeGeneratorFacade.VueProjectGeneration(
                        VueRagContext.unavailable(),
                        Flux.defer(() -> createGeneratedProject(sourceRoot, 503L))));
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Path expectedFactPath = reportRoot.resolve("vue_project_503");
        when(builder.buildProjectDetailed(expectedFactPath.toString())).thenReturn(successfulBuild());

        VueGenerationBuildReport report = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5), allocator)
                .evaluate(List.of(new VueGenerationBuildCase("case-collision", "基础站点", "碰撞需求")));

        assertAll(
                () -> assertTrue(Files.isRegularFile(sourceCollision)),
                () -> assertTrue(Files.isRegularFile(factCollision)),
                () -> assertTrue(Files.isRegularFile(expectedFactPath.resolve("package.json"))),
                () -> assertTrue(report.renderMarkdown().contains("503")));
        verify(facade).generateVueProjectForEvaluation("碰撞需求", 503L);
        verify(builder).buildProjectDetailed(expectedFactPath.toString());
    }

    @Test
    void defaultAllocatorIsPositiveConcurrentAndFailsBeforeOverflow() throws Exception {
        AtomicVueGenerationAppIdAllocator allocator =
                new AtomicVueGenerationAppIdAllocator(1_000L);
        List<Long> allocated = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> futures = IntStream.range(0, 1_000)
                    .mapToObj(index -> executor.submit(allocator::nextAppId))
                    .toList();
            for (Future<Long> future : futures) {
                allocated.add(future.get());
            }
        }
        AtomicVueGenerationAppIdAllocator nearOverflow =
                new AtomicVueGenerationAppIdAllocator(Long.MAX_VALUE - 1);

        assertAll(
                () -> assertEquals(1_000, new HashSet<>(allocated).size()),
                () -> assertTrue(allocated.stream().allMatch(appId -> appId > 0)),
                () -> assertEquals(Long.MAX_VALUE, nearOverflow.nextAppId()),
                () -> assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalStateException.class, nearOverflow::nextAppId)
                        .getMessage().contains("耗尽")));
    }

    @Test
    void waitsForRealGenerationMovesActualOutputAndBuildsDetailedWithoutRepair() throws Exception {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        VueGenerationBuildCase testCase = new VueGenerationBuildCase(
                "case-01", "基础站点", "真实需求");
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
        Path expectedTarget = reportRoot.resolve("vue_project_7");
        when(builder.buildProjectDetailed(expectedTarget.toString()))
                .thenReturn(new BuildResult(
                        true, BuildStage.SUCCESS, 0, false, "built", 10L));

        VueGenerationBuildReport report = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5), () -> 7L)
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
                "case-fail", "基础站点", "失败需求");
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateVueProjectForEvaluation("失败需求", 9L))
                .thenReturn(new AiCodeGeneratorFacade.VueProjectGeneration(
                        VueRagContext.unavailable(),
                        Flux.error(new IllegalStateException("模型失败"))));
        VueProjectBuilder builder = mock(VueProjectBuilder.class);

        VueGenerationBuildReport report = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5), () -> 9L)
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

    private Flux<String> createGeneratedProject(Path sourceRoot, long appId) {
        try {
            Path generated = sourceRoot.resolve("vue_project_" + appId);
            Files.createDirectories(generated);
            Files.writeString(generated.resolve("package.json"), "{}");
            return Flux.just("完成");
        } catch (Exception exception) {
            return Flux.error(exception);
        }
    }

    private BuildResult successfulBuild() {
        return new BuildResult(true, BuildStage.SUCCESS, 0, false, "built", 10L);
    }
}
