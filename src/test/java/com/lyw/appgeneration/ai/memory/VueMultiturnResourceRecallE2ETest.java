package com.lyw.appgeneration.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.*;
import com.lyw.appgeneration.ai.image.*;
import com.lyw.appgeneration.ai.image.model.*;
import com.lyw.appgeneration.ai.image.tools.*;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.ai.tools.*;
import com.lyw.appgeneration.config.*;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.*;
import com.lyw.appgeneration.core.concurrency.*;
import com.lyw.appgeneration.core.handler.*;
import com.lyw.appgeneration.manger.*;
import com.lyw.appgeneration.model.entity.*;
import com.lyw.appgeneration.model.enums.*;
import com.lyw.appgeneration.monitor.*;
import com.lyw.appgeneration.service.*;
import com.lyw.appgeneration.service.impl.AppServiceImpl;
import com.lyw.appgeneration.service.rag.*;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.*;
import com.lyw.appgeneration.service.rag.monitor.VueRagMetricsCollector;
import com.lyw.appgeneration.service.rag.retrieval.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真正串联 AppService→Facade→RAG/图片→AiServices→请求门禁→文件/计划/构建工具→终态。
 * 数据库、分类模型、检索提供方、图片提供方和 npm 执行器均为替身，文件实际写到测试沙箱。
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "user.dir",
        matches = ".*[.]codex/enable-multiturn-resource-recall/.*")
@Timeout(45)
class VueMultiturnResourceRecallE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PACKAGE = "{\"scripts\":{\"build\":\"vite build\"},\"dependencies\":{\"vue\":\"3.3.4\"},"
            + "\"devDependencies\":{\"vite\":\"4.4.5\",\"@vitejs/plugin-vue\":\"4.2.3\"}}";
    private static final List<String> USERS = List.of("只解释页面", "首次创建", "重试生成", "调整样式", "新增新闻功能", "更换背景图");

    @Test
    void 连续六个回合真实组装请求并验证候选替换和首次成功边界() throws Exception {
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath();
        assertTrue(root.toString().contains(".codex/enable-multiturn-resource-recall/"), "文件工具必须在本变更测试沙箱");
        try (var f = new UnifiedContextRequestGateTest.Fixture(false);
             var prep = new VueContextPreparationExecutorConfig().vueContextPreparationExecutor(new RagProperties());
             var images = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20))) {
            RagProperties props = new RagProperties();
            props.setEnabled(true);
            props.getHybrid().setEnabled(true);
            props.getRerank().setEnabled(true);
            var ragMetrics = new VueRagMetricsCollector(f.registry);
            try (var ragWorkers = new VueRagExecutor(props, ragMetrics)) {
                var bm25 = mock(MilvusBm25Retriever.class);
                var dense = mock(DenseRetriever.class);
                when(dense.embedQuery(anyString())).thenReturn(Embedding.from(new float[]{1, 0}));
                var skeleton = document("vue-skeleton-basic-001", RagDocumentKind.PROJECT_SKELETON);
                var feature = document("news-feature", RagDocumentKind.FEATURE_SNIPPET);
                var catalog = mock(TemplateCatalog.class);
                when(catalog.getCatalogVersion()).thenReturn("resource-e2e-v1");
                when(catalog.getDocuments()).thenReturn(List.of(skeleton, feature));
                when(catalog.findDocumentById(anyString())).thenAnswer(i -> Optional.ofNullable(
                        Map.of(skeleton.getId(), skeleton, feature.getId(), feature).get(i.getArgument(0))));
                when(bm25.retrieve(anyString(), eq("resource-e2e-v1"), any(), anyInt())).thenAnswer(i -> {
                    var kind = i.<RagDocumentKind>getArgument(2);
                    return List.of(new RankedCandidate(kind == RagDocumentKind.PROJECT_SKELETON
                            ? skeleton.getId() : feature.getId(), kind, 1, 1));
                });
                var provider = mock(VueRetrievalResourceProvider.class);
                when(provider.current()).thenReturn(Optional.of(new VueRetrievalResources(catalog)));
                var rerank = mock(RagRerankService.class);
                when(rerank.rerankVue(anyString(), anyList(), anyInt())).thenAnswer(i -> i.getArgument(1));
                var hybrid = new VueHybridRetrievalService(provider, bm25, dense, new RrfFusionService(), rerank, ragMetrics, props, ragWorkers);
                var retrieval = new RagRetrievalService(null, Map.of(), props, rerank, hybrid, null);
                var assembler = new RagPromptAssembler(props, ragMetrics);
                var planner = mock(ImageCollectionPlanService.class);
                var search = mock(ImageSearchTool.class);
                var logo = mock(LogoGeneratorTool.class);
                AtomicInteger imageRounds = new AtomicInteger();
                when(planner.planImageCollection(anyString())).thenAnswer(i -> {
                    int round = imageRounds.incrementAndGet();
                    var plan = new ImageCollectionPlan();
                    plan.setContentImageTasks(java.util.stream.IntStream.range(0, 8).mapToObj(n ->
                            new ImageCollectionPlan.ImageSearchTask("round" + round + "-topic" + n)).toList());
                    return plan;
                });
                when(search.searchContentImages(anyString(), anyInt(), any(BooleanSupplier.class))).thenAnswer(i ->
                        java.util.stream.IntStream.range(0, 5).mapToObj(n -> ImageResource.builder()
                                .category(ImageCategoryEnum.CONTENT).description("候选图片")
                                .url("https://images.test/" + i.getArgument(0) + "/" + n + ".jpg?keep=query").build()).toList());
                var imageService = new ImageCollectionService(planner, search, logo, images, new ImageCollectionPromptBuilder(), 30);
                var paths = new PlanStoragePathResolver(root);
                var plans = new AppPlanStateManager(JSON, paths);
                var guard = new FileToolBudgetGuard();
                var scopes = new FileToolExecutionScopeManager(guard, plans);
                var buildMetrics = new VueBuildRepairMetricsCollector(f.registry);
                var builder = mock(VueProjectBuilder.class);
                when(builder.buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class))).thenAnswer(i -> {
                    Path project = i.getArgument(0);
                    assertTrue(Files.isRegularFile(project.resolve("src/App.vue")));
                    assertTrue(Files.isRegularFile(project.resolve("package.json")));
                    return new BuildResult(true, BuildStage.SUCCESS, 0, false, false, null, "替身构建执行器成功", 1);
                });
                BaseTool[] tools = tools(plans, scopes, builder, buildMetrics);
                var toolManager = new ToolManager();
                ReflectionTestUtils.setField(toolManager, "tools", tools);
                toolManager.initTools();
                var model = new ScriptedModel(f);
                var generator = AiServices.builder(AiCodeGeneratorService.class).streamingChatModel(model)
                        .chatMemoryProvider(id -> f.memory).tools((Object[]) tools).build();
                var factory = mock(AiGeneratorServiceFactory.class);
                when(factory.getAiCodeGeneratorService(7L, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(generator);
                List<ChatHistory> rows = new CopyOnWriteArrayList<>();
                AtomicInteger ids = new AtomicInteger(100);
                var history = mock(ChatHistoryService.class);
                when(history.getLastMessage(7L)).thenAnswer(i -> rows.isEmpty() ? null : rows.getLast());
                when(history.hasSucceededGeneration(7L)).thenAnswer(i -> rows.stream()
                        .anyMatch(row -> row.getMemoryOutcome() == ChatMemoryOutcome.SUCCEEDED));
                when(history.addChatMessageAndReturn(eq(7L), anyString(), eq("user"), eq(9L))).thenAnswer(i -> {
                    var row = ChatHistory.builder().id((long) ids.incrementAndGet()).appId(7L).userId(9L)
                            .messageType("user").message(i.getArgument(1)).build();
                    rows.add(row); return row;
                });
                when(history.addAiMessageAndReturn(eq(7L), anyString(), anyString(), any(), eq(9L))).thenAnswer(i -> {
                    var row = ChatHistory.builder().id((long) ids.incrementAndGet()).appId(7L).userId(9L).messageType("ai")
                            .message(i.getArgument(1)).memoryMessage(i.getArgument(2)).memoryOutcome(i.getArgument(3)).build();
                    rows.add(row); return row;
                });
                doAnswer(i -> {
                    var work = i.<UnifiedSummaryRequest>getArgument(0);
                    return new CompatibleMemorySnapshot(7, work.userHistoryId(), work.context().turnId(), f.summary.get(), f.store.value.get());
                }).when(f.summaries).readCompatible(any(), any());
                when(f.summaries.readSnapshot(7L)).thenAnswer(i -> f.summary.get());
                when(f.summaries.persistTurnOutcome(any(), any())).thenAnswer(i -> i.<Supplier<ChatHistory>>getArgument(1).get());
                var prefs = mock(UserMemoryService.class);
                var finalizer = new VueTurnFinalizer(history, new ToolMessageCollapser(f.atomic), f.summaries, prefs,
                        factory, f.lifecycle, buildMetrics, guard);
                var cancellation = new VueTurnCancellationCoordinator(finalizer, Runnable::run, buildMetrics);
                var handler = new JsonMessageStreamHandler(toolManager, finalizer, cancellation);
                var streamExecutor = new StreamHandlerExecutor();
                ReflectionTestUtils.setField(streamExecutor, "jsonMessageStreamHandler", handler);
                var facade = new AiCodeGeneratorFacade();
                set(facade, "aiGeneratorServiceFactory", factory, "imageCollectionService", imageService,
                        "ragRetrievalService", retrieval, "ragPromptAssembler", assembler, "ragProperties", props,
                        "vueContextPreparationExecutor", prep, "currentVueProjectReader", new CurrentVueProjectReader(paths),
                        "fileToolExecutionScopeManager", scopes, "appPlanStateManager", plans, "modelRequestGate", f.gate);
                var classifier = mock(VueTurnModeRoutingService.class);
                when(classifier.route(anyString())).thenAnswer(i -> decision(i.getArgument(0)));
                var routeFactory = mock(VueTurnModeRoutingServiceFactory.class);
                when(routeFactory.create()).thenReturn(classifier);
                AppServiceImpl app = spy(new AppServiceImpl());
                doReturn(App.builder().id(7L).userId(9L).codeGenType("vue_project").initPrompt("创建企业官网").build()).when(app).getById(7L);
                set(app, "aiCodeGeneratorFacade", facade, "aiGeneratorServiceFactory", factory,
                        "chatHistoryService", history, "appOperationLeaseManager", new AppOperationLeaseManager(),
                        "vueBuildSessionManager", new VueBuildSessionManager(), "vueTurnAdmissionController", new VueTurnAdmissionController(buildMetrics),
                        "vueTurnCancellationCoordinator", cancellation, "vueTurnFinalizer", finalizer,
                        "fileToolBudgetGuard", guard, "vueTurnModeRouter", new VueTurnModeRouter(routeFactory),
                        "generationCancellationRegistry", new GenerationCancellationRegistry(), "ragProperties", props,
                        "streamHandlerExecutor", streamExecutor, "appLifecycleMetricsCollector", new AppLifecycleMetricsCollector(f.registry),
                        "appDataLifecycleFence", f.lifecycle, "memorySummaryService", f.summaries, "userMemoryService", prefs);
                try {
                    List<ChatMemoryOutcome> outcomes = new ArrayList<>();
                    for (String user : USERS) {
                        int before = model.requests.size();
                        var events = app.chatToGenCode(7L, user, "e2e-" + USERS.indexOf(user), User.builder().id(9L).build())
                                .collectList().block(Duration.ofSeconds(10));
                        assertNotNull(events);
                        assertTrue(events.stream().anyMatch(e -> e instanceof GenerationStreamEvent.TurnOutcome), events.toString());
                        outcomes.add(rows.getLast().getMemoryOutcome());
                        var calls = model.requests.subList(before, model.requests.size());
                        assertFalse(calls.isEmpty(), user + ": " + events);
                        boolean rag = Set.of("首次创建", "重试生成", "新增新闻功能").contains(user);
                        boolean image = Set.of("首次创建", "重试生成", "更换背景图").contains(user);
                        var fixed = resourceMessages(calls.getFirst());
                        for (ChatRequest request : calls) {
                            assertEquals(fixed, resourceMessages(request), "同一回合的工具续调复用固定清单");
                            assertEquals(rag ? 1 : 0, resourceMessages(request).stream().filter(m -> m.text().startsWith("以下 RAG")).count());
                            assertEquals(image ? 1 : 0, resourceMessages(request).stream().filter(m -> m.text().startsWith("以下图片")).count());
                            assertToolPairs(request.messages());
                        }
                        assertToolPairs(f.l0.messages());
                        if (image) {
                            long count = fixed.stream().filter(m -> m.text().startsWith("以下图片")).findFirst().orElseThrow()
                                    .text().lines().filter(line -> line.startsWith("- ")).count();
                            assertEquals(user.equals("更换背景图") ? 6 : 18, count);
                        }
                        String raw = f.l0.messages().toString();
                        assertFalse(raw.contains("RAG_CANDIDATE_ONLY"));
                        assertFalse(raw.contains("https://images.test/"));
                        assertFalse(rows.toString().contains("RAG_CANDIDATE_ONLY"));
                        assertFalse(rows.toString().contains("https://images.test/"));
                    }
                    assertEquals(List.of(ChatMemoryOutcome.ANSWERED, ChatMemoryOutcome.SYSTEM_ERROR, ChatMemoryOutcome.SUCCEEDED,
                            ChatMemoryOutcome.SUCCEEDED, ChatMemoryOutcome.SUCCEEDED, ChatMemoryOutcome.SUCCEEDED), outcomes);
                    verify(classifier, times(6)).route(anyString());
                    verify(dense, times(3)).embedQuery(anyString());
                    verify(bm25, times(2)).retrieve(anyString(), anyString(), eq(RagDocumentKind.PROJECT_SKELETON), anyInt());
                    verify(bm25, times(3)).retrieve(anyString(), anyString(), eq(RagDocumentKind.FEATURE_SNIPPET), anyInt());
                    assertEquals(3, imageRounds.get());
                    verify(search, times(14)).searchContentImages(anyString(), anyInt(), any(BooleanSupplier.class));
                    verifyNoInteractions(logo);
                    verify(builder, times(4)).buildProjectDetailed(any(Path.class), any(BuildExecutionContext.class));
                    assertEquals(12, rows.size());
                    assertEquals(6, f.store.value.get().turns().size());
                    assertTrue(f.store.value.get().turns().stream().allMatch(L0Snapshot.Turn::closed));
                } finally { cancellation.close(); }
            }
        }
    }

    static BaseTool[] tools(AppPlanStateManager plans, FileToolExecutionScopeManager scopes,
                            VueProjectBuilder builder, VueBuildRepairMetricsCollector metrics) {
        return new BaseTool[]{new MakePlanTool(plans, scopes), new UpdatePlanTool(plans, scopes),
                new FileWriteTool(new AppFileStateManager(), scopes), new FileReadTool(scopes),
                new FileModifyTool(scopes), new FileDeleteTool(scopes), new FileDirReadTool(scopes),
                new ReadSkillTool(new com.lyw.appgeneration.ai.skill.SkillCatalog(), scopes),
                new BuildProjectTool(builder, new BuildErrorSanitizer(), scopes, metrics, plans)};
    }

    private static void set(Object target, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) ReflectionTestUtils.setField(target, (String) pairs[i], pairs[i + 1]);
    }
    private static List<SystemMessage> resourceMessages(ChatRequest request) {
        return request.messages().stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                .filter(m -> m.text().startsWith("以下 RAG") || m.text().startsWith("以下图片")).toList();
    }
    private static void assertToolPairs(List<ChatMessage> messages) {
        Set<String> pending = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                assertTrue(pending.isEmpty());
                ai.toolExecutionRequests().forEach(r -> assertTrue(pending.add(r.id())));
            } else if (message instanceof ToolExecutionResultMessage result) assertTrue(pending.remove(result.id()));
            else assertTrue(pending.isEmpty(), "工具批次被拆开");
        }
        assertTrue(pending.isEmpty());
    }
    private static String decision(String user) {
        return "{\"mode\":\"" + (user.equals("只解释页面") ? "READ_ONLY" : "MUTATION_REQUIRED")
                + "\",\"needRag\":" + user.equals("新增新闻功能")
                + ",\"needImageSearch\":" + user.equals("更换背景图") + "}";
    }
    private static TemplateDoc document(String id, RagDocumentKind kind) {
        var doc = new TemplateDoc(); doc.setId(id); doc.setDocumentKind(kind); doc.setTitle("RAG_CANDIDATE_ONLY");
        doc.setDescription("候选参考，仅测试资料生命周期"); doc.setFramework("vue@3.3.4"); doc.setLanguage("javascript");
        doc.setBuildTool("vite@4.4.5"); doc.setDependencies(Map.of("vue", "3.3.4"));
        doc.setDevDependencies(Map.of("vite", "4.4.5", "@vitejs/plugin-vue", "4.2.3"));
        doc.setFiles(List.of()); doc.setQualityScore(1.0); return doc;
    }
    private static final class ScriptedModel implements StreamingChatModel {
        private final UnifiedContextRequestGateTest.Fixture fixture;
        private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> steps = new ConcurrentHashMap<>();
        private ScriptedModel(UnifiedContextRequestGateTest.Fixture fixture) { this.fixture = fixture; }
        @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            assertEquals(fixture.lastEstimated.get(), request.messages(), "实际发送列表必须等于已计量冻结快照");
            requests.add(request);
            String user = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .map(UserMessage::singleText).filter(USERS::contains).reduce((a, b) -> b).orElseThrow();
            int step = steps.merge(user, 1, Integer::sum);
            if (user.equals("首次创建")) { handler.onError(new IllegalStateException("受控首次生成失败")); return; }
            if (user.equals("只解释页面")) { handler.onPartialResponse("当前尚无页面。"); handler.onCompleteResponse(
                    UnifiedContextRequestGateTest.response(AiMessage.from("当前尚无页面。"))); return; }
            try {
                if (user.equals("重试生成") && step == 1) {
                    var files = List.of(Map.of("path", "package.json", "purpose", "依赖", "action", "CREATE", "dependsOn", List.of()),
                            Map.of("path", "src/App.vue", "purpose", "页面", "action", "CREATE", "dependsOn", List.of("package.json")));
                    respond(handler, tool(user, step, "makePlan", Map.of("summary", "生成测试页面", "files", JSON.writeValueAsString(files))));
                } else if (user.equals("重试生成") && step == 2) {
                    respond(handler, tool(user, step, "writeFile", Map.of("relativeFilePath", "package.json", "content", PACKAGE)),
                            tool(user, step + 100, "writeFile", Map.of("relativeFilePath", "src/App.vue", "content", "<template><main>初始页面</main></template>")));
                } else if (!user.equals("重试生成") && step == 1) {
                    respond(handler, tool(user, step, "readFile", Map.of("relativeFilePath", "src/App.vue")));
                } else if (!user.equals("重试生成") && step == 2) {
                    respond(handler, tool(user, step, "writeFile", Map.of("relativeFilePath", "src/App.vue", "content", "<template><main>" + user + "</main></template>")));
                } else if (step == 3) respond(handler, tool(user, step, "buildProject", Map.of()));
                else handler.onError(new IllegalStateException("受控工具流程出现意外续调：" + user + "/" + step + ": "
                        + request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).reduce((a, b) -> b).orElse(null)));
            } catch (Exception failure) { handler.onError(failure); }
        }
        private ToolExecutionRequest tool(String user, int step, String name, Map<String, ?> args) throws Exception {
            return ToolExecutionRequest.builder().id(user + "-" + step).name(name).arguments(JSON.writeValueAsString(args)).build();
        }
        private void respond(StreamingChatResponseHandler handler, ToolExecutionRequest... tools) {
            handler.onCompleteResponse(UnifiedContextRequestGateTest.response(AiMessage.from(List.of(tools))));
        }
    }
}
