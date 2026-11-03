package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.image.*;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.ai.tools.*;
import com.lyw.appgeneration.config.*;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.*;
import com.lyw.appgeneration.core.handler.*;
import com.lyw.appgeneration.core.resource.ResourcePreparationScope;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.service.*;
import com.lyw.appgeneration.service.rag.*;
import com.lyw.appgeneration.service.rag.model.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 Facade、TokenStream、统一门禁及来源快照；资料和摘要模型为本轮本地替身。 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "user.dir",
        matches = ".*[.]codex/enable-multiturn-resource-recall/.*")
class VueResourceContextReuseTest {
    @ParameterizedTest
    @ValueSource(strings = {"工具续调", "协议恢复", "未完成工具链恢复"})
    void 资料只准备一次且同步摘要后在所有续调路径保持同一份(String path) throws Exception {
        try (var f = new UnifiedContextRequestGateTest.Fixture(false, VueTurnMode.MUTATION_REQUIRED);
             var prep = new VueContextPreparationExecutorConfig().vueContextPreparationExecutor(new RagProperties())) {
            var props = new RagProperties(); props.setEnabled(true);
            var imageService = mock(ImageCollectionService.class);
            var retrieval = mock(RagRetrievalService.class);
            var assembler = mock(RagPromptAssembler.class);
            AtomicInteger imageCalls = new AtomicInteger(), ragCalls = new AtomicInteger();
            doAnswer(i -> {
                imageCalls.incrementAndGet();
                var slot = i.<ResourcePreparationScope.Slot>getArgument(3);
                slot.publish("固定图片 https://fixed-images.test/current.jpg", 1, 1);
                slot.finish(ResourcePreparationScope.Outcome.SUCCEEDED); return null;
            }).when(imageService).startPromptContext(anyString(), any(), any(), any());
            when(assembler.assembleVueProjectContext(any(), anyBoolean())).thenReturn("RAG_FIXED_CURRENT_TURN");
            when(retrieval.prepareVueResources(anyString(), any(), anyBoolean(), any(), any())).thenAnswer(i -> {
                ragCalls.incrementAndGet();
                var result = VueTurnRagResult.initial(VueRagContext.unavailable());
                i.<Consumer<VueTurnRagResult>>getArgument(4).accept(result); return result;
            });
            var plans = new AppPlanStateManager(new com.fasterxml.jackson.databind.ObjectMapper(),
                    new PlanStoragePathResolver(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR)));
            var scopes = new FileToolExecutionScopeManager(new FileToolBudgetGuard());
            var tools = VueMultiturnResourceRecallE2ETest.tools(plans, scopes, mock(VueProjectBuilder.class),
                    new VueBuildRepairMetricsCollector(f.registry));
            var model = new UnifiedContextRequestGateTest.RecordingModel();
            var generator = AiServices.builder(AiCodeGeneratorService.class).streamingChatModel(model)
                    .chatMemoryProvider(id -> f.memory).tools((Object[]) tools).build();
            var facade = new AiCodeGeneratorFacade();
            ReflectionTestUtils.setField(facade, "imageCollectionService", imageService);
            ReflectionTestUtils.setField(facade, "ragRetrievalService", retrieval);
            ReflectionTestUtils.setField(facade, "ragPromptAssembler", assembler);
            ReflectionTestUtils.setField(facade, "ragProperties", props);
            ReflectionTestUtils.setField(facade, "currentVueProjectReader", new CurrentVueProjectReader(new PlanStoragePathResolver(
                    Path.of(System.getProperty("java.io.tmpdir"), "empty-resource-context"))));
            ReflectionTestUtils.setField(facade, "vueContextPreparationExecutor", prep);
            ReflectionTestUtils.setField(facade, "fileToolExecutionScopeManager", scopes);
            ReflectionTestUtils.setField(facade, "modelRequestGate", f.gate);
            var failures = new LinkedBlockingQueue<Throwable>();
            var policy = VueTurnResourcePolicy.decide(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, false, false), false, true, "初始化需求");
            var subscription = facade.generateVueProjectStream(f.rawUser, 7, policy, f.context, generator)
                    .subscribe(ignored -> { }, failures::add);
            try {
                var initial = model.calls.poll(5, TimeUnit.SECONDS);
                assertNotNull(initial, failures.toString());
                var materials = initial.request().messages().stream().filter(message -> message instanceof SystemMessage system
                        && (system.text().startsWith("以下 RAG") || system.text().startsWith("以下图片"))).toList();
                assertEquals(2, materials.size());
                assertEquals(initial.request().messages(), f.lastEstimated.get());
                // 注入一条完整旧原文使真实预算超过同步阈值；摘要替身覆盖该原文，不读取候选资料。
                f.l0.add(AiMessage.from("旧诊断内容".repeat(30_000)));
                doAnswer(i -> {
                    var work = i.<UnifiedSummaryRequest>getArgument(0);
                    var source = work.memory().sourceSnapshot();
                    assertFalse(source.messages().toString().contains("RAG_FIXED_CURRENT_TURN"));
                    assertFalse(source.messages().toString().contains("fixed-images.test"));
                    var coverage = new SummaryCoverage(10, "turn", source.memoryEpoch(), 2,
                            L0SourceOperations.fingerprint(source, 10, 2, 0), 0, null, null, false);
                    f.summary.set(new MemorySummarySnapshot(UnifiedContextRequestGateTest.summaryText(), 0, 1, 2, coverage));
                    return new MemoryCompressionResult(MemoryCompressionResult.Status.COMPRESSED, 1, 1, "替身摘要成功");
                }).when(f.summaries).compressUnified(any(), any());
                if (path.equals("工具续调")) {
                    initial.handler().onCompleteResponse(UnifiedContextRequestGateTest.response(AiMessage.from(
                            ToolExecutionRequest.builder().id("read-current").name("readFile")
                                    .arguments("{\"relativeFilePath\":\"src/App.vue\"}").build())));
                } else if (path.equals("协议恢复")) {
                    initial.handler().onPartialResponse("[工具调用]\nreadFile\n{}\n[工具调用]\nreadFile\n{}");
                } else initial.handler().onCompleteResponse(UnifiedContextRequestGateTest.response(AiMessage.from("先说明下一步")));
                var next = model.calls.poll(5, TimeUnit.SECONDS);
                assertNotNull(next, failures.toString());
                assertEquals(next.request().messages(), f.lastEstimated.get());
                for (var material : materials) assertEquals(1, next.request().messages().stream().filter(material::equals).count());
                assertFalse(next.request().messages().toString().contains("旧诊断内容"));
                assertTrue(next.request().messages().toString().contains("已整理早期说明"));
                assertEquals(1, ragCalls.get()); assertEquals(1, imageCalls.get());
                assertEquals(1, f.summary.get().summaryRevision());
                verify(f.summaries, times(1)).compressUnified(any(), any());
                assertFalse(f.l0.messages().toString().contains("RAG_FIXED_CURRENT_TURN"));
                assertFalse(f.l0.messages().toString().contains("fixed-images.test"));
                assertTrue(failures.isEmpty(), failures.toString());
                assertTrue(f.registry.get("memory_transient_resource_tokens").tag("kind", "rag").summary().max() > 0);
                assertTrue(f.registry.get("memory_transient_resource_tokens").tag("kind", "images").summary().max() > 0);
            } finally { subscription.dispose(); }
            assertTrue(f.context.awaitQuiescence(Duration.ofSeconds(2)));
        }
    }
}
