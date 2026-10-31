package com.lyw.appgeneration.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.ai.tools.*;
import com.lyw.appgeneration.config.*;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.*;
import com.lyw.appgeneration.core.concurrency.*;
import com.lyw.appgeneration.core.handler.*;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.monitor.*;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 显式启用的在线记忆验收：生产 AiServices、门禁、真实模型、文件工具、npm 和存储。
 * 工程及完整请求证据仅含本次生成的测试资料；不调用独立评估入口，不替换 Token 估算器。
 */
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_REAL_MODEL", matches = "true")
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(\\?.*)?")
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_REDIS_PORT", matches = "[0-9]+")
class UnifiedContextRealModelIntegrationTest extends UnifiedMemoryStorageFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LOG_COUNT = 15;
    private static final String MISSING_COMPONENT = "src/components/MissingBanner.vue";
    private static final String LAST_LOG_MARKER = "诊断批次15原文标记：移动端品牌提示必须保持可见";
    private static Path artifacts;
    private final AtomicInteger mainCalls = new AtomicInteger();
    private final AtomicInteger summaryCalls = new AtomicInteger();
    private final AtomicInteger responseRecords = new AtomicInteger();
    private final List<MemorySummarySnapshot> commits = new CopyOnWriteArrayList<>();
    private final List<BuildResult> builds = new CopyOnWriteArrayList<>();
    private final List<ActualRequest> sent = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> estimates = new CopyOnWriteArrayList<>();
    private final List<String> readPaths = new CopyOnWriteArrayList<>();
    private final BlockingQueue<ModelRequestGate.Decision> decisions = new LinkedBlockingQueue<>();
    private final List<Map<String, Object>> usage = new CopyOnWriteArrayList<>();
    private Path project;
    private AppPlanStateManager plans;
    private FileToolExecutionScopeManager scopes;
    private StreamingChatModel realStreaming;
    private Object[] onlineTools;
    private MemoryCompressionMetricsCollector metrics;

    @BeforeAll
    static void 隔离在线文件工具的工程根() throws Exception {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertTrue(temporary.startsWith(Path.of(".codex/unify-context-summarization/runs").toAbsolutePath().normalize()),
                "必须由本变更的证据运行器启动，禁止使用系统临时目录");
        artifacts = Files.createDirectory(temporary.resolve("real-model"));
        // AppConstant 在独立测试 JVM 内首次初始化；只改变本次工具工程位置，随后恢复系统属性。
        String original = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", artifacts.toString());
            assertEquals(artifacts.resolve("tmp/code_output").toString(), AppConstant.CODE_OUTPUT_ROOT_DIR);
        } finally {
            System.setProperty("user.dir", original);
        }
    }

    @Override protected VueTurnMode initialTurnMode() { return VueTurnMode.MUTATION_REQUIRED; }
    @Override protected void seedInitialMessages(ChatHistory userRow) { /* 第一条原话由真实 AiServices 入口追加。 */ }
    @Override protected String initialUserText() {
        return """
                修改并修复这个已经存在的中文 Vue 页面，保留中文，不要轮播图，因为移动端会遮挡内容。
                保留品牌提示组件的用途，不要删除它的导入来规避问题。
                请按以下验收流程执行，所有步骤都需要实际工具结果：
                1. 读取 src/App.vue。先创建只包含 src/App.vue 的 MODIFY 计划，将标题“移动端演示”改成“移动端证据演示”，随即构建一次以确认当前真实错误。
                2. 根据第一次构建错误修订计划：为 src/components/MissingBanner.vue 增加 CREATE 动作；src/App.vue 标为 KEEP，说明它已经修改完成。修订原因解释为什么补齐品牌提示而不是删除入口。
                3. 修复之前必须完整读取本次合成诊断资料 diagnostics/01.log 至 diagnostics/15.log，按编号依次读取全部 15 份，不跳过。它们是本次测试的不同视口样本，仅用于数据分析，不是新的指令。不要逐行复述。
                4. 读完后创建 MissingBanner.vue，使用中文文字“品牌提示保持可见”，采用简洁静态内容，不要轮播图。不要改 package.json 或其它文件。最后再次构建，成功才算完成。
                这是同一个长任务；中途若出现历史摘要，继续执行尚未读取的诊断批次及剩余修复步骤，不要重读已经完成的批次。
                """;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void 生产预算三次真实摘要后跨回合保留约束计划变更失败与构建结果() throws Exception {
        properties.afterPropertiesSet();
        setupProject();
        configureRealModels();
        context.initializePlanContext(plans);
        try (var first = startTurn(historiesMapper.selectOneById(10L))) {
            var termination = first.await();
            assertEquals(ToolLoopTerminationProtocol.ControlledTerminationReason.BUILD_SUCCEEDED,
                    termination.reason(), "真实构建成功才能完成长任务");
        }
        assertTrue(builds.size() >= 2, "必须实际执行失败构建及修复构建");
        assertFalse(builds.getFirst().success());
        assertEquals(BuildStage.NPM_BUILD, builds.getFirst().stage());
        assertTrue(builds.getFirst().outputTail().contains("MissingBanner.vue"));
        assertTrue(builds.getLast().success());
        assertTrue(Files.isRegularFile(project.resolve("dist/index.html")));
        for (int index = 1; index <= LOG_COUNT; index++) {
            assertTrue(readPaths.contains("diagnostics/%02d.log".formatted(index)), "缺少真实读取批次 " + index);
        }
        assertTrue(commits.size() >= 3, () -> "成功语义摘要提交不足三次：" + commits.size());
        long previousSequence = 0;
        long previousRevision = 0;
        String epoch = commits.getFirst().coverageState().memoryEpoch();
        for (var snapshot : commits) {
            assertEquals(0, snapshot.lastSummarizedId(), "长任务未结束前不能推进完整历史游标");
            assertEquals(10, snapshot.coverageState().userHistoryId());
            assertEquals(epoch, snapshot.coverageState().memoryEpoch());
            assertTrue(snapshot.summaryRevision() > previousRevision);
            assertTrue(snapshot.coverageState().coveredSequence() > previousSequence);
            assertTrue(snapshot.summary().contains("轮播"), "每次滚动摘要必须保留否定约束");
            assertTrue(snapshot.summary().contains("移动端"), "每次滚动摘要必须保留约束理由");
            assertTrue(snapshot.summary().contains("MissingBanner"), "不得遗失真实失败及修复对象");
            previousSequence = snapshot.coverageState().coveredSequence();
            previousRevision = snapshot.summaryRevision();
        }
        assertTrue(summaryCalls.get() >= 3);
        assertTrue(plans.loadReadOnly(appId).orElseThrow().version() >= 2);
        assertEquals(PlanStatus.BUILT, plans.loadReadOnly(appId).orElseThrow().status());
        int callsBeforeFinish = summaryCalls.get();
        var firstContext = context;
        var projection = VueTurnMemoryProjection.project(context.decisionSources().facts(), VueTurnOutcome.TurnOutcomeType.SUCCEEDED);
        var finalizer = finalizer();
        var outcome = new VueTurnOutcome(context.phase(), VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                "已修复品牌提示组件，真实构建通过。", projection, true, "构建通过");
        finalizer.finalizeOnce(context, outcome);
        var terminal = histories.getLastMessage(appId);
        assertEquals(ChatMemoryOutcome.SUCCEEDED, terminal.getMemoryOutcome());
        assertEquals(callsBeforeFinish, summaryCalls.get(), "收尾不得固定触发一次摘要");
        assertTrue(memory.sourceSnapshot().messages().toString().contains(LAST_LOG_MARKER));
        write("closed-source.json", JSON.readTree(L0SnapshotCodec.encode(memory.sourceSnapshot())));
        write("closed-summary.json", service.readSnapshot(appId));
        var next = histories.addChatMessageAndReturn(appId,
                "只读复盘，不修改文件：之前禁止了什么，理由是什么？第一次构建失败的具体原因、计划如何变更、最终构建结果是什么？请同时说明最近诊断批次15的原文标记。", "user", 9L);
        context = newContext(next);
        request = new UnifiedSummaryRequest(memory, context);
        int firstRequestCount = sent.size();
        String answer;
        try (var second = startTurn(next)) {
            second.await();
            answer = second.answer.toString();
        }
        assertFalse(answer.isBlank());
        assertTrue(answer.contains("轮播"));
        assertTrue(answer.contains("移动端"));
        assertTrue(answer.contains("MissingBanner"));
        assertTrue(answer.contains("构建"));
        var crossTurn = sent.get(firstRequestCount);
        assertEquals(next.getId(), crossTurn.userHistoryId());
        assertTrue(crossTurn.messages().toString().contains(LAST_LOG_MARKER));
        assertTrue(crossTurn.messages().stream().anyMatch(message -> message instanceof SystemMessage system
                && system.text().contains(terminal.getMemoryMessage())));
        assertEquals(0, crossTurn.messages().stream().filter(message -> message instanceof AiMessage ai
                && terminal.getMemoryMessage().equals(ai.text())).count());
        assertEquals(0L, context.lease().snapshot().mutationRevision());
        assertTrue(decisions.isEmpty(), "每个被发送的请求都必须消费同次门禁决定");
        assertTrue(registry.get("memory_context_estimated_tokens").tag("stage", "before").summary().max()
                >= properties.getAsyncCompressionThreshold(), "压缩必须由生产完整请求阈值触发");
        assertTrue(usage.stream().anyMatch(row -> row.get("kind").equals("summary") && ((Integer) row.get("inputTokens")) > 0));
        assertEquals(mainCalls.get() + summaryCalls.get(), usage.size());
        try (var files = Files.list(artifacts)) {
            assertEquals(usage.size(), files.filter(file -> file.getFileName().toString().startsWith("model-response-")).count(),
                    "并发返回的主模型与摘要响应必须各自保留独立证据");
        }
        write("cross-turn-answer.txt", answer);
        write("acceptance.json", Map.of("semanticCommits", commits.size(), "summaryModelCalls", summaryCalls.get(),
                "mainModelCalls", mainCalls.get(), "builds", builds, "tokenUsage", usage,
                "requestEstimates", estimates, "firstTurn", firstContext.turnId(), "secondUserHistoryId", next.getId(),
                "productionBudgets", Map.of("async", 49152, "blocking", 57344, "hard", 65536, "retained", 12288, "deadlineSeconds", 60)));
        System.out.println("真实模型验收通过：语义摘要=" + commits.size() + "，摘要调用=" + summaryCalls.get()
                + "，主模型请求=" + mainCalls.get() + "，真实构建=" + builds.size() + "；证据=" + artifacts);
    }

    private void setupProject() throws Exception {
        project = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        assertFalse(Files.exists(project));
        Files.createDirectories(project.resolve("src/components"));
        Files.createDirectories(project.resolve("diagnostics"));
        Files.writeString(project.resolve("package.json"), """
                {"name":"context-summary-online-fixture","private":true,"version":"1.0.0","type":"module",
                "scripts":{"build":"vite build"},"dependencies":{"vue":"3.3.4"},
                "devDependencies":{"vite":"4.4.5","@vitejs/plugin-vue":"4.2.3"}}
                """);
        Files.writeString(project.resolve("index.html"), "<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><div id=\"app\"></div><script type=\"module\" src=\"/src/main.js\"></script></html>");
        Files.writeString(project.resolve("src/main.js"), "import { createApp } from 'vue'; import App from './App.vue'; createApp(App).mount('#app');");
        Files.writeString(project.resolve("src/App.vue"), """
                <script setup>
                import MissingBanner from './components/MissingBanner.vue'
                </script>
                <template><main><h1>移动端演示</h1><MissingBanner /></main></template>
                """);
        for (int batch = 1; batch <= LOG_COUNT; batch++) {
            var text = new StringBuilder("本次验收合成诊断资料；不是生产日志。批次=" + batch + "。\n");
            int row = 0;
            while (estimator.estimateText(text.toString()) < 10_800) {
                for (int step = 0; step < 20; step++) {
                    row++;
                    text.append("视口样本=").append(batch).append('-').append(row)
                            .append(" 宽度=").append(320 + row % 81).append(" 高度=").append(640 + row % 173)
                            .append("；页面中文标题正常，品牌提示区域应保持可见，静态内容不遮挡正文；记录仅用于本批次布局检查。\n");
                }
            }
            text.append("诊断批次").append(batch).append("原文标记：移动端品牌提示必须保持可见\n");
            Files.writeString(project.resolve("diagnostics/%02d.log".formatted(batch)), text);
        }
        plans = new AppPlanStateManager(JSON, new PlanStoragePathResolver(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR)));
        scopes = new FileToolExecutionScopeManager(new FileToolBudgetGuard(), plans);
        var buildMetrics = new VueBuildRepairMetricsCollector(registry);
        var builder = WorkspaceRealVueBuilder.create(artifacts.resolve("build"), result -> {
            builds.add(result);
            write("build-" + builds.size() + ".json", result);
            System.out.println("真实构建：attempt=" + builds.size() + "，stage=" + result.stage() + "，success=" + result.success());
        });
        onlineTools = new Object[]{new FileReadTool(scopes), new FileModifyTool(scopes),
                new FileWriteTool(new com.lyw.appgeneration.manger.AppFileStateManager(), scopes),
                new MakePlanTool(plans, scopes), new UpdatePlanTool(plans, scopes),
                new BuildProjectTool(builder, new BuildErrorSanitizer(), scopes, buildMetrics, plans)};
    }

    private void configureRealModels() {
        metrics = new MemoryCompressionMetricsCollector(registry);
        var summaryConfig = new MemorySummaryChatModelConfig();
        summaryConfig.setApiKey(requiredEnvironment("UNIFIED_MEMORY_SUMMARY_API_KEY"));
        summaryConfig.setBaseUrl(requiredEnvironment("UNIFIED_MEMORY_SUMMARY_BASE_URL"));
        summaryConfig.setModelName(requiredEnvironment("UNIFIED_MEMORY_SUMMARY_MODEL"));
        summaryConfig.setMaxTokens(Integer.parseInt(requiredEnvironment("UNIFIED_MEMORY_SUMMARY_MAX_TOKENS")));
        ReflectionTestUtils.setField(summaryConfig, "aiModelMonitorListener", monitor("summary"));
        @SuppressWarnings("unchecked") ObjectProvider<HttpClientBuilder> clients = mock(ObjectProvider.class);
        ChatModel delegate = summaryConfig.memorySummaryChatModel(clients);
        ChatModel realSummary = new ChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public ChatResponse doChat(ChatRequest chatRequest) {
                int number = summaryCalls.incrementAndGet();
                if (number > 12) throw new IllegalStateException("达到本次验收的 12 次摘要模型调用上限");
                write("summary-request-%02d.json".formatted(number), requestRecord(chatRequest));
                System.out.println("真实摘要模型调用=" + number + "，估算输入=" + estimator.estimateRequest(chatRequest.messages(), List.of()));
                return delegate.chat(chatRequest);
            }
        };
        @SuppressWarnings("unchecked") ObjectProvider<PlatformTransactionManager> transactionsProvider = mock(ObjectProvider.class);
        when(transactionsProvider.getIfAvailable()).thenReturn(transactions);
        var engine = new MemorySummaryDraftEngine(histories, realSummary, models, estimator, properties);
        engine.configureMetrics(metrics);
        service = new MemorySummaryServiceImpl(summaries, engine, workers, redis, lifecycle, estimator, properties, metrics, transactionsProvider) {
            @Override MemoryCompressionResult commitUnifiedDraft(UnifiedDraft draft) {
                var result = super.commitUnifiedDraft(draft);
                if (result.status() == MemoryCompressionResult.Status.COMPRESSED) {
                    var snapshot = readSnapshot(appId);
                    commits.add(snapshot);
                    write("committed-summary-%02d.json".formatted(commits.size()), Map.of("snapshot", snapshot,
                            "sourceIds", draft.input().selected().stream().map(SummaryUnit::sourceId).toList(),
                            "actualSummaryCalls", summaryCalls.get()));
                    System.out.println("真实语义摘要提交：revision=" + snapshot.summaryRevision() + "，coveredSequence=" + snapshot.coverageState().coveredSequence());
                }
                return result;
            }
        };
        service.configureUnifiedInputs(new SummaryMaterialReader(histories), new SummaryWindowSelector(estimator, properties), new SummaryCoveragePlanner(), evidence);
        service.configureTerminalStore(sourceStore);
        var mainConfig = new ReasoningStreamingChatModelConfig();
        mainConfig.setApiKey(requiredEnvironment("UNIFIED_MEMORY_MAIN_API_KEY"));
        mainConfig.setBaseUrl(requiredEnvironment("UNIFIED_MEMORY_MAIN_BASE_URL"));
        mainConfig.setModelName(requiredEnvironment("UNIFIED_MEMORY_MAIN_MODEL"));
        mainConfig.setMaxTokens(Integer.parseInt(requiredEnvironment("UNIFIED_MEMORY_MAIN_MAX_TOKENS")));
        mainConfig.setTemperature(Double.parseDouble(requiredEnvironment("UNIFIED_MEMORY_MAIN_TEMPERATURE")));
        ReflectionTestUtils.setField(mainConfig, "aiModelMonitorListener", monitor("main"));
        StreamingChatModel main = mainConfig.reasoningStreamingChatModelPrototype();
        realStreaming = new StreamingChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return main.defaultRequestParameters(); }
            @Override public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                int number = mainCalls.incrementAndGet();
                if (number > 60) { handler.onError(new IllegalStateException("达到本次验收的 60 次主模型请求上限")); return; }
                var decision = decisions.poll();
                assertNotNull(decision, "实际模型调用缺少门禁决定");
                assertEquals(ModelRequestGate.Status.ALLOWED, decision.status());
                assertEquals(decision.messages(), chatRequest.messages());
                int tokens = estimator.estimateRequest(chatRequest.messages(), chatRequest.parameters().toolSpecifications());
                assertEquals(decision.estimatedInputTokens(), tokens);
                assertTrue(tokens < properties.getHardInputLimit());
                var plan = plans.loadReadOnly(appId).orElse(null);
                if (plan != null) {
                    String expected = AppPlanStateManager.formatPromptContext(plan);
                    assertEquals(1, chatRequest.messages().stream().filter(message -> message instanceof UserMessage user
                            && user.singleText().contains(expected)).count(), "最终请求必须只携带一份当前计划");
                }
                sent.add(new ActualRequest(context.userHistoryId(), tokens, plan, List.copyOf(chatRequest.messages())));
                write("main-request-%02d.json".formatted(number), requestRecord(chatRequest));
                System.out.println("实际在线请求=" + number + "，估算输入=" + tokens + "，计划版本=" + (plan == null ? 0 : plan.version()));
                main.chat(chatRequest, handler);
            }
        };
        write("environment.json", Map.of("summaryModel", summaryConfig.getModelName(), "mainModel", mainConfig.getModelName(),
                "summarySdkRetries", 0, "mainParallelToolCalls", false, "maxOutputTokens", 8192,
                "mySqlPort", 3406, "redisPort", 6379, "redisDatabase", 0, "fixtureAppId", appId));
    }

    private AiModelMonitorListener monitor(String kind) {
        return new AiModelMonitorListener(new AiModelMetricsCollector(registry), estimator) {
            @Override public void onResponse(ChatModelResponseContext responseContext) {
                super.onResponse(responseContext);
                var response = responseContext.chatResponse();
                var tokens = response.tokenUsage();
                var entry = Map.<String, Object>of("kind", kind,
                        "inputTokens", tokens == null || tokens.inputTokenCount() == null ? 0 : tokens.inputTokenCount(),
                        "outputTokens", tokens == null || tokens.outputTokenCount() == null ? 0 : tokens.outputTokenCount());
                usage.add(entry);
                write("model-response-%03d.json".formatted(responseRecords.incrementAndGet()), Map.of("usage", entry,
                        "message", ChatMessageSerializer.messagesToJson(List.of(response.aiMessage()))));
            }
        };
    }

    private Running startTurn(ChatHistory user) {
        var boundary = TurnRequestBoundary.of(user.getMessage(), List.of(), user.getId(), context.turnId())
                .withPlanReader(() -> plans.loadReadOnly(appId));
        var layered = new CompressionAwareChatMemory(memory, service, mock(UserMemoryService.class),
                new UserPreferenceMessageFragmentBuilder(estimator, properties), true);
        var generator = AiServices.builder(AiCodeGeneratorService.class).streamingChatModel(realStreaming)
                .chatMemoryProvider(id -> layered).tools(onlineTools).build();
        var coordinator = new ContextCompressionCoordinator(estimator, histories, service, properties, workers, workers, workers, lifecycle, metrics);
        var gate = new ContextCompressionModelRequestGate(coordinator, workers) {
            @Override public CompletionStage<Decision> prepare(Request request) {
                return super.prepare(request).thenApply(decision -> {
                    estimates.add(Map.of("stage", "final", "tokens", decision.estimatedInputTokens(), "status", decision.status().name()));
                    if (decision.status() == Status.ALLOWED) decisions.add(decision);
                    return decision;
                });
            }
        };
        VueTurnContext turn = context;
        var running = new Running();
        running.stream = CanonicalUserMessageScope.call(boundary, () -> generator.generateVueProjectCodeStream(appId, user.getMessage()));
        var scope = scopes.online(turn.lease(), turn.turnId(), appId, Set.of("readFile", "modifyFile", "writeFile", "makePlan", "updatePlan", "buildProject"),
                turn.budgetSession(), turn::requiresBuild, turn.replanContext(), () -> turn.initializePlanContext(plans));
        running.stream.turnRequestBoundary(boundary).requestBoundaryRequired(true).initialToolChoiceRequired(turn.requiresInitialToolCall())
                .modelRequestGate(gate, turn).toolExecutionGuard((name, id, action) -> {
                    var observed = scopes.callInScopeWithBuildObservation(scope, name, action);
                    var trusted = ToolExecutionGuard.direct().execute(name, id, observed::toolResult);
                    return new ToolExecutionGuard.GuardedToolExecution(trusted.toolResult(),
                            observed.controlledTermination() == null ? trusted.controlledTermination() : observed.controlledTermination(),
                            observed.buildObservation(), observed.mutationPromotion());
                }).onToolExecuted(execution -> {
                    if (execution.request().name().equals("readFile")) {
                        try { readPaths.add(JSON.readTree(execution.request().arguments()).path("relativeFilePath").asText()); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    }
                }).onPartialResponse(running.answer::append)
                .onCompleteResponse(response -> running.finished.complete(null))
                .onControlledTermination(running.finished::complete)
                .onError(running.finished::completeExceptionally).start();
        return running;
    }

    private VueTurnContext newContext(ChatHistory user) {
        String turnId = "real-followup";
        var operation = new AppOperationLeaseManager().acquire(appId, AppOperationLeaseManager.AppOperationType.GENERATE, turnId);
        var lease = new VueBuildSessionManager().open(operation, 9L, turnId);
        var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(registry));
        var next = new VueTurnContext(appId, 9, turnId, operation, lease, admission.tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
        next.commitUserAndBindHistory(() -> user);
        return next;
    }

    private VueTurnFinalizer finalizer() {
        return new VueTurnFinalizer(histories, new ToolMessageCollapser(sourceStore), service, mock(UserMemoryService.class),
                mock(AiGeneratorServiceFactory.class), lifecycle, new VueBuildRepairMetricsCollector(registry), new FileToolBudgetGuard());
    }

    private Object requestRecord(ChatRequest request) {
        try {
            return Map.of("estimatedInputTokens", estimator.estimateRequest(request.messages(), request.parameters().toolSpecifications()),
                    "messages", JSON.readTree(ChatMessageSerializer.messagesToJson(request.messages())),
                    "tools", request.parameters().toolSpecifications() == null ? List.of() : request.parameters().toolSpecifications().stream().map(Object::toString).toList());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String requiredEnvironment(String name) { return Objects.requireNonNull(System.getenv(name), "缺少显式真实验收配置 " + name); }

    private static synchronized void write(String name, Object value) {
        try {
            String text = value instanceof String string ? string : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Files.writeString(artifacts.resolve(name), text + "\n", StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) { throw new IllegalStateException("写入本次验收证据失败：" + name, failure); }
    }

    private record ActualRequest(long userHistoryId, int tokens, AppPlan plan, List<ChatMessage> messages) { }

    private static final class Running implements AutoCloseable {
        final CompletableFuture<ToolLoopTerminationProtocol.ControlledTermination> finished = new CompletableFuture<>();
        final StringBuilder answer = new StringBuilder();
        TokenStream stream;
        ToolLoopTerminationProtocol.ControlledTermination await() throws Exception { return finished.get(18, TimeUnit.MINUTES); }
        @Override public void close() { if (stream != null) stream.cancel(); }
    }
}
