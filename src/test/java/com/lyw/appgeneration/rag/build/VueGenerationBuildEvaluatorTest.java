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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CyclicBarrier;
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
    void 两个并发评测器竞争同一候选appId时通过原子目录领取避免冲突() throws Exception {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        CyclicBarrier candidateBarrier = new CyclicBarrier(2);
        CyclicBarrier generationBarrier = new CyclicBarrier(2);
        List<Long> facadeAppIds = Collections.synchronizedList(new ArrayList<>());
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateVueProjectForEvaluation(anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    long appId = invocation.getArgument(1);
                    facadeAppIds.add(appId);
                    generationBarrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return new AiCodeGeneratorFacade.VueProjectGeneration(
                            VueRagContext.unavailable(),
                            Flux.defer(() -> createGeneratedProject(sourceRoot, appId)));
                });
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProjectDetailed(anyString())).thenReturn(successfulBuild());
        VueGenerationBuildCase testCase = new VueGenerationBuildCase(
                "case-race", "基础站点", "并发领取需求");
        VueGenerationBuildEvaluator first = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5),
                collidingAllocator(candidateBarrier, 701L, 702L));
        VueGenerationBuildEvaluator second = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot, Duration.ofSeconds(5),
                collidingAllocator(candidateBarrier, 701L, 703L));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<VueGenerationBuildReport> firstReport =
                    executor.submit(() -> first.evaluate(List.of(testCase)));
            Future<VueGenerationBuildReport> secondReport =
                    executor.submit(() -> second.evaluate(List.of(testCase)));
            firstReport.get(10, java.util.concurrent.TimeUnit.SECONDS);
            secondReport.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertEquals(2, new HashSet<>(facadeAppIds).size(),
                "同一候选只能由一个评测器领取，另一个必须重试新 appId");
        assertEquals(2, facadeAppIds.stream()
                .map(appId -> reportRoot.resolve("vue_project_" + appId))
                .filter(Files::isDirectory)
                .count());
    }

    @Test
    void 两个真实JVM竞争同一候选appId时只有一个成功领取() throws Exception {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        Path ready = tempDir.resolve("claim-ready");
        Path go = tempDir.resolve("claim-go");
        Path results = tempDir.resolve("claim-results");
        Files.createDirectories(ready);
        Files.createDirectories(results);
        Process first = startClaimFixture(sourceRoot, reportRoot, ready, go, results, "first");
        Process second = startClaimFixture(sourceRoot, reportRoot, ready, go, results, "second");
        awaitFileCount(ready, 2);
        Files.createFile(go);

        assertTrue(first.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(second.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        String firstOutput = new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String secondOutput = new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, first.exitValue(), firstOutput);
        assertEquals(0, second.exitValue(), secondOutput);
        List<String> claims;
        try (var paths = Files.list(results)) {
            claims = paths.map(path -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).sorted().toList();
        }

        assertEquals(List.of("false", "true"), claims);
        assertTrue(Files.isDirectory(sourceRoot.resolve("vue_project_801")));
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
        verify(builder, org.mockito.Mockito.times(1))
                .buildProjectDetailed(expectedTarget.toString());
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

    @Test
    void generationTimeoutCancelsStreamAndDoesNotInvokeBuilder() {
        Path sourceRoot = tempDir.resolve("tmp/code_output");
        Path reportRoot = tempDir.resolve("target/rag-eval/generated");
        AtomicInteger cancellations = new AtomicInteger();
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateVueProjectForEvaluation("超时需求", 10L))
                .thenReturn(new AiCodeGeneratorFacade.VueProjectGeneration(
                        VueRagContext.unavailable(),
                        Flux.<String>never().doOnCancel(cancellations::incrementAndGet)));
        VueProjectBuilder builder = mock(VueProjectBuilder.class);

        VueGenerationBuildReport report = new VueGenerationBuildEvaluator(
                facade, builder, sourceRoot, reportRoot,
                Duration.ofMillis(50), () -> 10L)
                .evaluate(List.of(new VueGenerationBuildCase(
                        "case-timeout", "基础站点", "超时需求")));

        assertFalse(report.passed());
        assertEquals(1, cancellations.get());
        verify(builder, org.mockito.Mockito.never())
                .buildProjectDetailed(org.mockito.ArgumentMatchers.any());
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

    private VueGenerationAppIdAllocator collidingAllocator(
            CyclicBarrier barrier, long collidingCandidate, long fallbackCandidate) {
        AtomicInteger invocation = new AtomicInteger();
        return () -> {
            if (invocation.getAndIncrement() == 0) {
                try {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("同步候选 appId 失败", exception);
                }
                return collidingCandidate;
            }
            return fallbackCandidate;
        };
    }

    private Process startClaimFixture(
            Path sourceRoot,
            Path reportRoot,
            Path ready,
            Path go,
            Path results,
            String id) throws Exception {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                AppIdClaimFixture.class.getName(),
                sourceRoot.toString(),
                reportRoot.toString(),
                ready.toString(),
                go.toString(),
                results.toString(),
                id)
                .redirectErrorStream(true)
                .start();
    }

    private void awaitFileCount(Path directory, long expectedCount) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (var files = Files.list(directory)) {
                if (files.count() == expectedCount) {
                    return;
                }
            }
            Thread.sleep(Duration.ofMillis(20));
        }
        org.junit.jupiter.api.Assertions.fail("真实 JVM 未在期限内就绪");
    }

    public static final class AppIdClaimFixture {

        private AppIdClaimFixture() {
        }

        public static void main(String[] args) throws Exception {
            Path sourceRoot = Path.of(args[0]);
            Path reportRoot = Path.of(args[1]);
            Path ready = Path.of(args[2]);
            Path go = Path.of(args[3]);
            Path results = Path.of(args[4]);
            String id = args[5];
            Files.createFile(ready.resolve(id));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(go) && System.nanoTime() < deadline) {
                Thread.sleep(Duration.ofMillis(10));
            }
            if (!Files.exists(go)) {
                throw new IllegalStateException("等待启动信号超时");
            }
            boolean claimed = VueGenerationBuildEvaluator.tryClaimAppId(
                    sourceRoot, reportRoot, 801L);
            Files.writeString(results.resolve(id), Boolean.toString(claimed),
                    StandardCharsets.UTF_8);
        }
    }
}
