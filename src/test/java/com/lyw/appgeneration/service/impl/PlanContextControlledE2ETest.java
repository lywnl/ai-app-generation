package com.lyw.appgeneration.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.ai.tools.*;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.*;
import com.lyw.appgeneration.core.concurrency.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import com.lyw.appgeneration.manger.AppFileStateManager;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.monitor.*;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.ToolExecutionGuard;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import dev.langchain4j.service.V;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立 JVM 在隔离目录运行真实 Java 链；模型、存储适配器与构建进程为本地替身。 */
class PlanContextControlledE2ETest {
    @Test
    void 创建写入修订连续压缩续做和构建使用同一真实请求链() throws Exception {
        Path evidence = Path.of(System.getProperty("java.io.tmpdir")).resolve("plan-context-e2e");
        Files.createDirectories(evidence);
        Path sandbox = Files.createTempDirectory(evidence, "sandbox-");
        Path childTemp = Files.createDirectories(sandbox.resolve("tmp"));
        Path log = evidence.resolve("pipeline.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + childTemp, "-cp", classpath, Runner.class.getName(), evidence.toString())
                .directory(sandbox.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(50, TimeUnit.SECONDS), "受控 E2E 超时，日志=" + log);
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(Files.isRegularFile(evidence.resolve("token-comparison.json")));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return failure.toString(); }
    }

    public static final class Runner {
        public static void main(String[] args) throws Exception {
            try (var fixture = new Fixture(Path.of(args[0]))) {
                fixture.run();
            }
        }
    }

    interface Generator {
        @dev.langchain4j.service.UserMessage("{{request}}")
        TokenStream generate(@MemoryId long appId, @V("request") String request);
    }

    static final class Fixture implements AutoCloseable {
        static final long APP = 7;
        static final String TURN = "plan-context-e2e";
        static final String USER = "制作中文企业官网，保留已经写入的文件，增加功能区与帮助区，最后构建。";
        static final String APP_CONTENT = "<template><main>中文企业官网</main></template>";
        static final String FEATURE_CONTENT = "<template><section>产品功能介绍</section></template>";
        static final String HELP_CONTENT = "<template><aside>帮助与支持</aside></template>";
        final Path evidence;
        final Path project = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + APP);
        final ObjectMapper json = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        final MemoryTokenProperties properties = new MemoryTokenProperties();
        final ChatTokenEstimator realEstimator = new ConservativeChatTokenEstimator(properties);
        final RecordingEstimator estimator = new RecordingEstimator(realEstimator);
        final SourceMemoryTestStore store = new SourceMemoryTestStore(L0Snapshot.empty(Long.toString(APP), 0));
        final AtomicChatMemoryStore atomic = new AtomicChatMemoryStore(store);
        final TokenAwareChatMemory l0 = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(APP)
                .chatMemoryStore(atomic).maxMessages(Integer.MAX_VALUE).build(), atomic);
        final AtomicReference<AppMemorySummary> summary = new AtomicReference<>();
        final ChatHistoryService histories = mock(ChatHistoryService.class);
        final AppMemorySummaryMapper mapper = mock(AppMemorySummaryMapper.class);
        final ConversationDecisionEvidenceService decisions = mock(ConversationDecisionEvidenceService.class);
        final ChatModel summaryModel = mock(ChatModel.class);
        final ExecutorService background = Executors.newSingleThreadExecutor();
        final ExecutorService modelWorkers = Executors.newSingleThreadExecutor();
        final ExecutorService compression = Executors.newSingleThreadExecutor();
        final ExecutorService reads = Executors.newFixedThreadPool(2);
        final ExecutorService gateWorkers = Executors.newSingleThreadExecutor();
        final AppDataLifecycleFence lifecycle = new AppDataLifecycleFence();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MemoryCompressionMetricsCollector metrics = new MemoryCompressionMetricsCollector(registry);
        final AtomicInteger planReads = new AtomicInteger();
        final AtomicInteger buildCalls = new AtomicInteger();
        final List<String> summaryPrompts = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> samples = new ArrayList<>();
        final LinkedBlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        final CompletableFuture<ToolLoopTerminationProtocol.ControlledTermination> completed = new CompletableFuture<>();
        final AppPlanStateManager plans = new AppPlanStateManager(json, new PlanStoragePathResolver());
        final MemorySummaryServiceImpl summaries;
        final VueTurnContext context;
        final CompressionAwareChatMemory memory;
        final TurnRequestBoundary boundary;
        final TokenStream stream;
        final List<ChatMessage> transientMessages = List.of(SystemMessage.from("本轮临时 RAG：使用简体中文，沿用已有文件。"));
        Call current;

        Fixture(Path evidence) throws Exception {
            this.evidence = evidence;
            properties.afterPropertiesSet();
            Files.createDirectories(project.resolve("docs"));
            // 每批约 3 万 Token，保留生产阈值与 12,288 软窗口，以两批真实读结果触发同步压缩。
            Files.writeString(project.resolve("docs/diagnostics.txt"), "诊断说明：保留文件，不需要重复写入。".repeat(1500));
            var user = ChatHistory.builder().id(10L).appId(APP).userId(9L).messageType("user").message(USER).isDelete(0).build();
            when(histories.listMessagesAfterCursor(APP, 0L, 100)).thenReturn(List.of(user));
            when(mapper.selectOneByQuery(any())).thenAnswer(ignored -> summary.get());
            when(mapper.insert(any(AppMemorySummary.class))).thenAnswer(invocation -> {
                assertNull(summary.get());
                AppMemorySummary row = invocation.getArgument(0);
                row.setId(1L); summary.set(row); return 1;
            });
            when(mapper.commitSnapshot(any(), anyLong())).thenAnswer(invocation -> {
                if (!summary.get().getSummaryRevision().equals(invocation.getArgument(1))) return 0;
                summary.set(invocation.getArgument(0)); return 1;
            });
            when(summaryModel.chat(anyString())).thenAnswer(invocation -> {
                String input = invocation.getArgument(0);
                summaryPrompts.add(input);
                assertFalse(input.contains(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK));
                assertFalse(input.contains("恢复时的计划快照"), "恢复附件不是新增摘要来源");
                return UnifiedSummaryCoordinationTest.SUMMARY.replace("继续处理", "已写入 src/App.vue；按当前计划继续处理剩余文件。");
            });
            var operation = new AppOperationLeaseManager().acquire(APP, AppOperationLeaseManager.AppOperationType.GENERATE, TURN);
            var lease = new VueBuildSessionManager().open(operation, 9, TURN);
            var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(registry));
            var budget = new FileToolBudgetGuard();
            context = new VueTurnContext(APP, 9, TURN, operation, lease, admission.tryAcquire().orElseThrow(), budget.newSession(), true);
            context.initializeMode(VueTurnMode.MUTATION_REQUIRED);
            context.commitUserAndBindHistory(() -> user);
            context.initializePlanContext(plans);
            boundary = TurnRequestBoundary.of(USER, transientMessages, 10, TURN).withPlanReader(() -> {
                planReads.incrementAndGet(); return plans.loadReadOnly(APP);
            }, () -> plans.definitionChange(APP));
            var engine = new MemorySummaryDraftEngine(histories, summaryModel, modelWorkers, realEstimator, properties);
            engine.configureMetrics(metrics);
            summaries = new MemorySummaryServiceImpl(mapper, engine, background, mock(StringRedisTemplate.class), lifecycle,
                    realEstimator, properties, metrics, TransactionOperations.withoutTransaction(), Clock.systemUTC());
            summaries.configureTerminalStore(atomic);
            when(decisions.prepare(eq(context), any(L0Snapshot.class), anyLong(), any(AdmissionDeadline.class))).thenAnswer(invocation -> {
                L0Snapshot source = invocation.getArgument(1);
                long through = invocation.getArgument(2);
                var old = DecisionEvidenceCodec.decode(user.getDecisionEvidence(), 10, TURN);
                var observed = context.decisionSources().snapshot();
                var additions = new DecisionEvidenceAssembler().collect(10, source, through, observed, old);
                long generation = observed.sources().values().stream().filter(ref -> ref.sequence() <= through)
                        .mapToLong(ref -> ref.generation()).max().orElse(0);
                var next = old.merge(additions, source.memoryEpoch(), through, generation, 384000);
                user.setDecisionEvidence(DecisionEvidenceCodec.encode(next));
                return new PreparedDecisionEvidence(APP, 9, 10, TURN, source, through, next,
                        L0SourceOperations.fingerprint(source, 10, through, next.revision()));
            });
            summaries.configureUnifiedInputs(new SummaryMaterialReader(histories), new SummaryWindowSelector(realEstimator, properties),
                    new SummaryCoveragePlanner(), decisions);
            memory = new CompressionAwareChatMemory(l0, summaries, mock(UserMemoryService.class),
                    new UserPreferenceMessageFragmentBuilder(realEstimator, properties), true);
            l0.add(SystemMessage.from("按已有计划执行中文工程任务，工具返回的真实结果决定进度。"));
            var scopes = new FileToolExecutionScopeManager(budget, plans);
            var scope = scopes.online(lease, TURN, APP, Set.of("makePlan", "updatePlan", "writeFile", "readFile", "buildProject"),
                    context.budgetSession(), context::requiresBuild, context.replanContext(), () -> context.initializePlanContext(plans));
            var build = new BuildProjectTool(new VueProjectBuilder() {
                @Override public BuildResult buildProjectDetailed(Path root, BuildExecutionContext buildContext) {
                    buildCalls.incrementAndGet();
                    try {
                        assertEquals(project.toAbsolutePath(), root.toAbsolutePath());
                        assertEquals(APP_CONTENT, Files.readString(root.resolve("src/App.vue")));
                        assertEquals(FEATURE_CONTENT, Files.readString(root.resolve("src/Feature.vue")));
                        assertEquals(HELP_CONTENT, Files.readString(root.resolve("src/Help.vue")));
                        Files.createDirectories(root.resolve("dist"));
                        Files.writeString(root.resolve("dist/index.html"), "<html lang=\"zh-CN\">本地构建进程替身</html>");
                    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                    return new BuildResult(true, BuildStage.SUCCESS, 0, false, false, null, "隔离目录校验成功；未调用 npm", 1);
                }
            }, new BuildErrorSanitizer(), scopes, new VueBuildRepairMetricsCollector(registry), plans);
            StreamingChatModel generator = new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    assertEquals(estimator.messages.get(), request.messages(), "发送消息必须与预算冻结副本相同");
                    assertEquals(estimator.tools.get(), request.toolSpecifications());
                    calls.add(new Call(request, handler));
                }
            };
            var service = AiServices.builder(Generator.class).streamingChatModel(generator).chatMemoryProvider(ignored -> memory)
                    .tools(new MakePlanTool(plans, scopes), new UpdatePlanTool(plans, scopes),
                            new FileWriteTool(new AppFileStateManager(), scopes), new FileReadTool(scopes), build).build();
            var coordinator = new ContextCompressionCoordinator(estimator, histories, summaries, properties,
                    compression, reads, background, lifecycle, metrics);
            stream = CanonicalUserMessageScope.call(boundary, () -> service.generate(APP, USER));
            var direct = ToolExecutionGuard.direct();
            stream.turnRequestBoundary(boundary).requestBoundaryRequired(true).turnTransientMessages(transientMessages)
                    .modelRequestGate(new ContextCompressionModelRequestGate(coordinator, gateWorkers), context)
                    .toolExecutionGuard((toolName, memoryId, action) -> {
                        var observed = scopes.callInScopeWithBuildObservation(scope, toolName, action);
                        var trusted = direct.execute(toolName, memoryId, observed::toolResult);
                        return new ToolExecutionGuard.GuardedToolExecution(trusted.toolResult(),
                                observed.controlledTermination() != null ? observed.controlledTermination() : trusted.controlledTermination(),
                                observed.buildObservation(), observed.mutationPromotion());
                    }).onPartialResponse(ignored -> { }).onError(errors::add).onControlledTermination(completed::complete);
        }

        void run() throws Exception {
            stream.start();
            current = awaitRequest("初始");
            assertEquals(0, attachments(current.request()).size());
            invoke("makePlan", Map.of("summary", "中文企业官网", "files", json.writeValueAsString(List.of(
                    file("src/App.vue", "主页面", "CREATE", List.of()), file("src/Feature.vue", "功能区", "CREATE", List.of("src/App.vue"))))));
            assertEquals(0, attachments(current.request()).size());
            String makeResult = toolResult(current.request(), "makePlan").text();
            assertEquals(PlanToolResult.Status.APPLIED, PlanToolProtocolSupport.parse(makeResult).status());
            invoke("writeFile", Map.of("relativeFilePath", "src/App.vue", "content", APP_CONTENT));
            assertEquals(PlanFileState.TOUCHED, plans.loadReadOnly(APP).orElseThrow().files().getFirst().state());
            assertEquals(0, attachments(current.request()).size());
            assertTrue(current.request().messages().contains(toolMessage("makePlan", makeResult)));
            invoke("updatePlan", Map.of("reason", "增加帮助区，保留已写入入口", "add", json.writeValueAsString(List.of(
                    file("src/Help.vue", "帮助区", "CREATE", List.of("src/Feature.vue")))), "remove", "[]", "modify", "[]"));
            assertEquals(2, plans.loadReadOnly(APP).orElseThrow().version());
            assertEquals(0, attachments(current.request()).size());
            assertEquals(PlanToolResult.Status.APPLIED, PlanToolProtocolSupport.parse(toolResult(current.request(), "updatePlan").text()).status());

            invoke("readFile", Map.of("relativeFilePath", "docs/diagnostics.txt"));
            assertNull(summary.get(), "第一批读结果不足生产压缩阈值");
            invoke("readFile", Map.of("relativeFilePath", "docs/diagnostics.txt"));
            assertNotNull(summary.get(), "两批真实工具结果应触发生产阈值下的同步压缩");
            assertEquals(1L, summary.get().getSummaryRevision());
            String recovered = attachments(current.request()).getFirst();
            assertTrue(recovered.contains("恢复时的计划快照 version=2"));
            assertFalse(current.request().messages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                    && (result.toolName().equals("makePlan") || result.toolName().equals("updatePlan"))));
            invoke("writeFile", Map.of("relativeFilePath", "src/Feature.vue", "content", FEATURE_CONTENT));
            assertEquals(List.of(recovered), attachments(current.request()), "普通进度不重建正文");
            assertTrue(indexOfAttachment(current.request()) < current.request().messages().indexOf(toolResult(current.request(), "writeFile")));
            assertEquals(2, context.lease().snapshot().mutationRevision());
            assertTrue(current.request().messages().toString().contains("实际变更修订=2"));

            invoke("readFile", Map.of("relativeFilePath", "docs/diagnostics.txt"));
            assertEquals(2L, summary.get().getSummaryRevision(), "新增来源再次达到生产阈值后连续压缩");
            String refreshed = attachments(current.request()).getFirst();
            assertNotEquals(recovered, refreshed, "新覆盖后恢复最新磁盘进度");
            assertTrue(refreshed.contains("src/Feature.vue [用途=功能区，动作=CREATE，依赖=src/App.vue，状态=TOUCHED]"));
            invoke("writeFile", Map.of("relativeFilePath", "src/Help.vue", "content", HELP_CONTENT));
            assertEquals(List.of(refreshed), attachments(current.request()));
            assertEquals(PlanStatus.READY_TO_BUILD, plans.loadReadOnly(APP).orElseThrow().status());
            respond("buildProject", Map.of());
            var terminal = completed.get(8, TimeUnit.SECONDS);
            assertNotNull(terminal);
            assertEquals(1, buildCalls.get(), "只有真实计划闸门通过后才进入构建进程替身");
            assertEquals(PlanStatus.BUILT, plans.loadReadOnly(APP).orElseThrow().status());
            assertEquals(VueBuildPhase.SUCCEEDED, context.lease().snapshot().phase());
            assertTrue(errors.isEmpty(), errors.toString());
            assertFalse(L0SnapshotCodec.encode(store.value.get()).contains(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK));
            assertTrue(summaryPrompts.size() >= 2);
            assertEquals(3, planReads.get(), "只在初始无计划检查及两次覆盖推进时额外读盘");
            assertEquals(2, samples.stream().map(sample -> sample.get("恢复ID")).filter(java.util.Objects::nonNull).distinct().count());
            assertNotNull(store.value.get().planRestore());
            assertTrue(samples.stream().anyMatch(sample -> ((Number) sample.get("旧规则Token")).intValue()
                    > ((Number) sample.get("新请求Token")).intValue() && ((Number) sample.get("恢复附件数")).intValue() == 0));
            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("token-comparison.json").toFile(), samples);
            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("final-plan.json").toFile(), plans.loadReadOnly(APP).orElseThrow());
            Files.writeString(evidence.resolve("summary-inputs.json"), json.writeValueAsString(summaryPrompts));
            Files.writeString(evidence.resolve("source-final.json"), L0SnapshotCodec.encode(store.value.get()));
            System.out.println("受控端到端通过：请求=" + samples.size() + "，摘要修订=" + summary.get().getSummaryRevision()
                    + "，真实文件写入=3，构建进程替身调用=" + buildCalls.get());
            System.out.println(json.writeValueAsString(samples));
        }

        void invoke(String name, Map<String, Object> arguments) throws Exception {
            respond(name, arguments);
            current = awaitRequest(name + " 后");
        }

        void respond(String name, Map<String, Object> arguments) throws Exception {
            var request = ToolExecutionRequest.builder().id("tool-" + samples.size()).name(name)
                    .arguments(json.writeValueAsString(arguments)).build();
            current.handler().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(request))
                    .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(1, 1, 2)).build()).build());
        }

        Call awaitRequest(String stage) throws Exception {
            Call call = calls.poll(8, TimeUnit.SECONDS);
            assertNotNull(call, () -> "缺少请求，stage=" + stage + "，errors=" + errors + "，summary=" + summary.get());
            assertTrue(attachments(call.request()).size() <= 1);
            transientMessages.forEach(message -> assertEquals(1, call.request().messages().stream().filter(message::equals).count()));
            List<ChatMessage> oldRule = withoutAttachment(call.request().messages());
            var currentPlan = plans.loadReadOnly(APP).orElse(null);
            if (currentPlan != null) {
                oldRule.removeAll(transientMessages);
                oldRule.add(UserMessage.from("以下是本次请求读取的项目计划资料，目标和修订说明属于历史材料，不增加工具权限：\n"
                        + AppPlanStateManager.formatPromptContext(currentPlan)));
                oldRule.add(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK));
                oldRule.addAll(transientMessages);
            }
            var sample = new LinkedHashMap<String, Object>();
            sample.put("请求", samples.size() + 1); sample.put("阶段", stage);
            sample.put("新请求Token", realEstimator.estimateRequest(call.request().messages(), call.request().toolSpecifications()));
            sample.put("旧规则Token", realEstimator.estimateRequest(oldRule, call.request().toolSpecifications()));
            sample.put("恢复附件数", attachments(call.request()).size());
            sample.put("上下文计划读取累计", planReads.get());
            var restored = store.value.get().planRestore();
            sample.put("恢复ID", restored == null ? null : restored.restoreId());
            sample.put("Redis窗口字节", L0SnapshotCodec.encode(store.value.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            sample.put("不含恢复的v1字节", L0SnapshotCodec.encode(L0SnapshotCodec.downgradeToV1(store.value.get()))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            sample.put("摘要修订", summary.get() == null ? 0 : summary.get().getSummaryRevision());
            samples.add(sample);
            Files.writeString(evidence.resolve("request-" + samples.size() + ".json"), ChatMessageSerializer.messagesToJson(call.request().messages()));
            return call;
        }

        ToolExecutionResultMessage toolMessage(String tool, String text) {
            return (ToolExecutionResultMessage) current.request().messages().stream()
                    .filter(message -> message instanceof ToolExecutionResultMessage result && result.toolName().equals(tool) && result.text().equals(text))
                    .findFirst().orElseThrow();
        }

        @Override public void close() {
            stream.cancel(); context.closeResources();
            background.shutdownNow(); modelWorkers.shutdownNow(); compression.shutdownNow(); reads.shutdownNow(); gateWorkers.shutdownNow();
        }
    }

    private static Map<String, Object> file(String path, String purpose, String action, List<String> dependencies) {
        return Map.of("path", path, "purpose", purpose, "action", action, "dependsOn", dependencies);
    }

    private static ToolExecutionResultMessage toolResult(ChatRequest request, String tool) {
        return request.messages().stream().filter(message -> message instanceof ToolExecutionResultMessage result && result.toolName().equals(tool))
                .map(ToolExecutionResultMessage.class::cast).reduce((earlier, later) -> later).orElseThrow();
    }

    private static List<String> attachments(ChatRequest request) {
        var result = new ArrayList<String>();
        for (int i = 1; i < request.messages().size(); i++) {
            if (request.messages().get(i).equals(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK))) {
                result.add(((UserMessage) request.messages().get(i - 1)).singleText());
            }
        }
        return result;
    }

    private static int indexOfAttachment(ChatRequest request) {
        return request.messages().indexOf(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK)) - 1;
    }

    private static List<ChatMessage> withoutAttachment(List<ChatMessage> source) {
        var result = new ArrayList<ChatMessage>();
        for (int i = 0; i < source.size(); i++) {
            if (i + 1 < source.size() && source.get(i + 1).equals(AiMessage.from(SyntheticMemoryMessageProtocol.PLAN_SNAPSHOT_ACK))) i++;
            else result.add(source.get(i));
        }
        return result;
    }

    record Call(ChatRequest request, StreamingChatResponseHandler handler) { }

    static final class RecordingEstimator implements ChatTokenEstimator {
        final ChatTokenEstimator delegate;
        final AtomicReference<List<ChatMessage>> messages = new AtomicReference<>();
        final AtomicReference<List<ToolSpecification>> tools = new AtomicReference<>();
        RecordingEstimator(ChatTokenEstimator delegate) { this.delegate = delegate; }
        @Override public int estimateText(String text) { return delegate.estimateText(text); }
        @Override public int estimateMessages(List<ChatMessage> messages) { return delegate.estimateMessages(messages); }
        @Override public int estimateToolSpecifications(List<ToolSpecification> tools) { return delegate.estimateToolSpecifications(tools); }
        @Override public int estimateRequest(List<ChatMessage> messages, List<ToolSpecification> tools) {
            this.messages.set(List.copyOf(messages)); this.tools.set(List.copyOf(tools));
            return delegate.estimateRequest(messages, tools);
        }
    }
}
