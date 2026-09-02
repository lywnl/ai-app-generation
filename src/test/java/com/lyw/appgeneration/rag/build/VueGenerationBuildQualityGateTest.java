package com.lyw.appgeneration.rag.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.AiAppGenerationApplication;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.rag.eval.EvaluationReportLifecycle;
import com.lyw.appgeneration.rag.ingest.VueIngestionExpectedSnapshot;
import com.lyw.appgeneration.rag.ingest.VueIngestionVerification;
import com.lyw.appgeneration.rag.ingest.VueMilvusIngestionVerifier;
import com.lyw.appgeneration.rag.ingest.VueMilvusTarget;
import com.lyw.appgeneration.rag.vue.VueRetrievalQualityGateRunner;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.retrieval.VueRetrievalResourceProvider;
import com.lyw.appgeneration.service.rag.retrieval.MilvusBm25Retriever;
import com.lyw.appgeneration.utils.SpringContextUtil;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 十条 Vue 首次真实生成与 npm 构建的高成本质量门禁。
 */
class VueGenerationBuildQualityGateTest {

    private static final String DISABLED_PEXELS_API_KEY =
            "disabled-for-vue-generation-build-evaluation";

    private static final Path REPORT = Path.of(
            "target/rag-eval/vue-generation-build-report.md");
    private static final Path GENERATED_ROOT = Path.of(
            "target/rag-eval/generated");
    private static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void 入口异常时旧十条通过报告被本轮失败状态覆盖(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("generation.md");
        Files.writeString(report, "状态：通过\n构建成功数：10/10\n", StandardCharsets.UTF_8);
        IllegalArgumentException original = new IllegalArgumentException("Bearer 不得写入");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> EvaluationReportLifecycle.execute(
                        report,
                        VueGenerationBuildReport.failed(
                                "run-generation-new", java.util.List.of("本轮生成正在执行"))
                                .renderMarkdown(),
                        () -> { throw original; },
                        VueGenerationBuildReport::renderMarkdown,
                        VueGenerationBuildReport.failed(
                                "run-generation-new", java.util.List.of("本轮生成执行异常"))
                                .renderMarkdown()));

        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertSame(original, thrown);
        assertTrue(markdown.contains("状态：未通过"));
        assertTrue(markdown.contains("run-generation-new"));
        assertFalse(markdown.contains("10/10"));
        assertFalse(markdown.contains("不得写入"));
    }

    @Test
    void evaluationPropertiesReceivesDedicatedMilvusPassword() {
        Map<String, String> environment = Map.of(
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_MILVUS_PASSWORD", "milvus-secret");
        VueMilvusTarget target = VueMilvusTarget.from(environment);

        Map<String, Object> properties = evaluationProperties(
                target, environment);

        assertEquals("milvus-secret", properties.get("rag.milvus.password"));
        assertEquals(DISABLED_PEXELS_API_KEY, properties.get("PEXELS_API_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"非法端口", "0", "70000"})
    void 生成属性复用同轮规范化Milvus目标(String rawPort) {
        Map<String, String> variables = Map.of(
                "RAG_MILVUS_HOST", "same-run-host",
                "RAG_MILVUS_PORT", rawPort,
                "RAG_MILVUS_DATABASE", "same-run-database",
                "RAG_MILVUS_USERNAME", "same-run-user",
                "RAG_MILVUS_PASSWORD", "same-run-password");
        VueMilvusTarget target = VueMilvusTarget.from(variables);

        Map<String, Object> properties = evaluationProperties(
                target, variables);

        assertEquals(target.host(), properties.get("rag.milvus.host"));
        assertEquals(target.port(), properties.get("rag.milvus.port"));
        assertEquals(target.database(), properties.get("rag.milvus.database"));
        assertEquals(target.username(), properties.get("rag.milvus.username"));
        assertEquals("same-run-password", properties.get("rag.milvus.password"));
    }

    @Test
    void 真实生成Spring复用同一目录快照并在关闭时释放资源() {
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        MilvusBm25Retriever bm25;

        try (ConfigurableApplicationContext context = startEvaluationApplication(
                catalog,
                VueMilvusTarget.from(Map.of()),
                Map.of(),
                contextTestHostProperties())) {
            VueRetrievalResourceProvider provider =
                    context.getBean(VueRetrievalResourceProvider.class);
            assertSame(catalog, provider.current().orElseThrow().catalog());
            bm25 = context.getBean(MilvusBm25Retriever.class);
        }

        assertThrows(RuntimeException.class, () -> bm25.retrieve(
                "Vue 基础站点",
                catalog.getCatalogVersion(),
                com.lyw.appgeneration.service.rag.model.RagDocumentKind.PROJECT_SKELETON,
                1));
    }

    @Test
    void 懒加载评测上下文会初始化并在关闭后恢复原Spring上下文() {
        ApplicationContext originalContext = (ApplicationContext) ReflectionTestUtils.getField(
                SpringContextUtil.class, "applicationContext");
        ApplicationContext previousContext = mock(ApplicationContext.class);
        ReflectionTestUtils.setField(
                SpringContextUtil.class, "applicationContext", previousContext);
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());

        try {
            try (ConfigurableApplicationContext context = startEvaluationApplication(
                    catalog,
                    VueMilvusTarget.from(Map.of()),
                    Map.of(),
                    contextTestHostProperties())) {
                StreamingChatModel model = SpringContextUtil.getBean(
                        "reasoningStreamingChatModelPrototype", StreamingChatModel.class);

                assertTrue(context.isActive());
                assertTrue(model != null);
            }
            assertSame(previousContext, ReflectionTestUtils.getField(
                    SpringContextUtil.class, "applicationContext"));
        } finally {
            ReflectionTestUtils.setField(
                    SpringContextUtil.class, "applicationContext", originalContext);
        }
    }

    @Test
    void 评测强制属性覆盖宿主冲突配置且保持快照生命周期() {
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        Map<String, String> evaluationEnvironment = Map.of(
                "RAG_MILVUS_HOST", "evaluation-host",
                "RAG_MILVUS_PORT", "6543",
                "RAG_MILVUS_DATABASE", "evaluation-database",
                "RAG_MILVUS_USERNAME", "evaluation-user",
                "RAG_MILVUS_PASSWORD", "evaluation-password");
        Map<String, Object> hostProperties = Map.ofEntries(
                Map.entry("rag.enabled", "false"),
                Map.entry("rag.hybrid.enabled", "false"),
                Map.entry("rag.ingest.enabled", "true"),
                Map.entry("rag.milvus.host", "host-value"),
                Map.entry("rag.milvus.port", "19532"),
                Map.entry("rag.milvus.database", "host-database"),
                Map.entry("rag.milvus.username", "host-user"),
                Map.entry("rag.milvus.password", "host-password"),
                Map.entry("DEEPSEEK_API_KEY", "host-deepseek-key"),
                Map.entry("DASHSCOPE_API_KEY", "host-dashscope-key"),
                Map.entry("PEXELS_API_KEY", "host-pexels-key"));
        MilvusBm25Retriever bm25;

        try (ConfigurableApplicationContext context = startEvaluationApplication(
                catalog,
                VueMilvusTarget.from(evaluationEnvironment),
                evaluationEnvironment,
                hostProperties)) {
            RagProperties properties = context.getBean(RagProperties.class);
            assertTrue(properties.isEnabled());
            assertTrue(properties.getHybrid().isEnabled());
            assertFalse(properties.getIngest().isEnabled());
            assertEquals("evaluation-host", properties.getMilvus().getHost());
            assertEquals(6543, properties.getMilvus().getPort());
            assertEquals("evaluation-database", properties.getMilvus().getDatabase());
            assertEquals("evaluation-user", properties.getMilvus().getUsername());
            assertEquals("evaluation-password", properties.getMilvus().getPassword());
            assertEquals("host-deepseek-key",
                    context.getEnvironment().getProperty("DEEPSEEK_API_KEY"));
            assertEquals("host-dashscope-key",
                    context.getEnvironment().getProperty("DASHSCOPE_API_KEY"));
            assertEquals(DISABLED_PEXELS_API_KEY,
                    context.getEnvironment().getProperty("PEXELS_API_KEY"));
            VueRetrievalResourceProvider provider =
                    context.getBean(VueRetrievalResourceProvider.class);
            assertSame(catalog, provider.current().orElseThrow().catalog());
            bm25 = context.getBean(MilvusBm25Retriever.class);
        }

        assertThrows(RuntimeException.class, () -> bm25.retrieve(
                "Vue 基础站点",
                catalog.getCatalogVersion(),
                com.lyw.appgeneration.service.rag.model.RagDocumentKind.PROJECT_SKELETON,
                1));
    }

    @Test
    void requiresTenOfTenRealFirstGenerationBuilds() throws Exception {
        Map<String, String> variables = Map.copyOf(System.getenv());
        String runId = UUID.randomUUID().toString();
        VueGenerationBuildEnvironment[] inspectedEnvironment =
                new VueGenerationBuildEnvironment[1];
        VueGenerationBuildReport report = EvaluationReportLifecycle.execute(
                REPORT,
                VueGenerationBuildReport.failed(
                        runId, java.util.List.of("本轮真实生成运行中，旧结果已失效"))
                        .renderMarkdown(),
                () -> evaluateCurrentRun(runId, inspectedEnvironment, variables),
                VueGenerationBuildReport::renderMarkdown,
                VueGenerationBuildReport.failed(
                        runId, java.util.List.of("本轮真实生成发生异常"))
                        .renderMarkdown());
        if (!inspectedEnvironment[0].ready()) {
            return;
        }
        if (!report.passed()) {
            fail("Vue 首次真实生成构建未达到 10/10，详见 " + REPORT);
        }
    }

    @Test
    void 生成入口使用调用方冻结的同一环境快照() {
        VueGenerationBuildEnvironment[] inspectedEnvironment =
                new VueGenerationBuildEnvironment[1];
        Map<String, String> frozenVariables = Map.of(
                "DASHSCOPE_API_KEY", "frozen-dashscope",
                "DEEPSEEK_API_KEY", "frozen-deepseek",
                "RAG_MILVUS_PASSWORD", "frozen-milvus-password");

        VueGenerationBuildReport report = evaluateCurrentRun(
                "frozen-run", inspectedEnvironment, frozenVariables);

        assertFalse(report.executed());
        assertEquals("RAG_BUILD_EVAL 未设置为 true",
                inspectedEnvironment[0].reasons().getFirst());
    }

    private VueGenerationBuildReport evaluateCurrentRun(
            String runId,
            VueGenerationBuildEnvironment[] inspectedEnvironment,
            Map<String, String> variables) {
        VueGenerationBuildEnvironment environment =
                VueGenerationBuildEnvironment.inspect(variables);
        inspectedEnvironment[0] = environment;
        if (!environment.ready()) {
            return VueGenerationBuildReport.notExecuted(runId, environment.reasons());
        }

        VueMilvusTarget target = VueMilvusTarget.from(variables);
        String milvusPassword = variables.get("RAG_MILVUS_PASSWORD");
        String dashScopeApiKey = variables.get("DASHSCOPE_API_KEY");
        TemplateCatalog catalog = new TemplateCatalog(
                Path.of("embed_text/vue-project"), new ObjectMapper());
        VueGenerationBuildReport report = new VueGenerationBuildQualityGateRunner().evaluate(
                () -> verifyIngestion(target, milvusPassword, catalog),
                () -> new VueRetrievalQualityGateRunner()
                        .evaluateDataset(
                                target, milvusPassword, dashScopeApiKey, catalog),
                () -> evaluateGeneration(catalog, target, variables));
        return report.withRunId(runId);
    }

    private VueIngestionVerification verifyIngestion(
            VueMilvusTarget target,
            String milvusPassword,
            TemplateCatalog catalog) {
        VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
        return new VueMilvusIngestionVerifier(new ObjectMapper()).verify(
                expected, target, milvusPassword);
    }

    private VueGenerationBuildReport evaluateGeneration(
            TemplateCatalog catalog,
            VueMilvusTarget target,
            Map<String, String> variables) {
        VueGenerationBuildDataset dataset;
        try {
            dataset = VueGenerationBuildDataset.load(
                    "rag/vue-generation-build-cases.json", new ObjectMapper());
        } catch (IOException exception) {
            throw new UncheckedIOException("加载 Vue 生成构建评测集失败", exception);
        }
        try (ConfigurableApplicationContext application = startEvaluationApplication(
                catalog, target, variables, Map.of())) {
            return new VueGenerationBuildEvaluator(
                    application.getBean(AiCodeGeneratorFacade.class),
                    application.getBean(VueProjectBuilder.class),
                    Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                    GENERATED_ROOT,
                    GENERATION_TIMEOUT)
                    .evaluate(dataset.cases());
        }
    }

    private ConfigurableApplicationContext startEvaluationApplication(
            TemplateCatalog catalog,
            VueMilvusTarget target,
            Map<String, String> variables,
            Map<String, Object> hostProperties) {
        ApplicationContext previousContext = (ApplicationContext) ReflectionTestUtils.getField(
                SpringContextUtil.class, "applicationContext");
        Map<String, Object> properties = evaluationProperties(target, variables);
        ConfigurableApplicationContext application = new SpringApplicationBuilder(
                AiAppGenerationApplication.class)
                .web(WebApplicationType.NONE)
                .lazyInitialization(true)
                .initializers(context -> {
                    context.addApplicationListener((ContextClosedEvent event) -> {
                        ApplicationContext currentContext =
                                (ApplicationContext) ReflectionTestUtils.getField(
                                        SpringContextUtil.class, "applicationContext");
                        if (currentContext == context) {
                            ReflectionTestUtils.setField(
                                    SpringContextUtil.class,
                                    "applicationContext",
                                    previousContext);
                        }
                    });
                    if (!hostProperties.isEmpty()) {
                        context.getEnvironment().getPropertySources().addFirst(
                                new MapPropertySource("simulatedHost", hostProperties));
                    }
                    context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource(
                                    "vueGenerationBuildEvaluation", properties));
                    context.addBeanFactoryPostProcessor(
                            new VueEvaluationCatalogSnapshotConfigurer(catalog));
                })
                .run();
        application.getBean(SpringContextUtil.class);
        return application;
    }

    private Map<String, Object> evaluationProperties(
            VueMilvusTarget target,
            Map<String, String> variables) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rag.enabled", "true");
        properties.put("rag.hybrid.enabled", "true");
        properties.put("rag.ingest.enabled", "false");
        properties.put("rag.templates-dir", Path.of("embed_text").toAbsolutePath().toString());
        properties.put("rag.milvus.host", target.host());
        properties.put("rag.milvus.port", target.port());
        properties.put("rag.milvus.database", target.database());
        properties.put("rag.milvus.username", target.username());
        properties.put("rag.milvus.password", variables.get("RAG_MILVUS_PASSWORD"));
        properties.put("PEXELS_API_KEY", DISABLED_PEXELS_API_KEY);
        putIfPresent(properties, "DASHSCOPE_API_KEY", variables.get("DASHSCOPE_API_KEY"));
        putIfPresent(properties, "DEEPSEEK_API_KEY", variables.get("DEEPSEEK_API_KEY"));
        return properties;
    }

    private void putIfPresent(Map<String, Object> properties, String name, String value) {
        if (value != null) {
            properties.put(name, value);
        }
    }

    private Map<String, Object> contextTestHostProperties() {
        return Map.of(
                "DEEPSEEK_API_KEY", "unused-by-context-lifecycle-test",
                "DASHSCOPE_API_KEY", "unused-by-context-lifecycle-test",
                "PEXELS_API_KEY", "unused-by-context-lifecycle-test");
    }

}
