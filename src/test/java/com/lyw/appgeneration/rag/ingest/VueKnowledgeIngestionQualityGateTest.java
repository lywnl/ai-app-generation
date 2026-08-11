package com.lyw.appgeneration.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.ingest.VueKnowledgeIngestor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 仅在显式授权且依赖完整时执行的 Vue 知识真实摄取门禁。
 */
class VueKnowledgeIngestionQualityGateTest {

    private static final Path DATASET_ROOT = Path.of("embed_text/vue-project");
    private static final Path REPORT = Path.of("target/rag-eval/vue-ingestion-report.md");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DASHSCOPE_COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String EMBEDDING_MODEL = "text-embedding-v4";
    private static final int EMBEDDING_DIMENSION = 1024;
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void 摄取门禁将专用PGVector密码传给存储与核验器() throws Exception {
        Map<String, String> variables = Map.of(
                "SPRING_DATASOURCE_PASSWORD", "mysql-secret",
                "RAG_PGVECTOR_PASSWORD", "pg-secret");
        VueIngestionExpectedSnapshot expected = expectedSnapshot();
        AtomicReference<String> storePassword = new AtomicReference<>();
        AtomicReference<String> verifierPassword = new AtomicReference<>();

        VueIngestionReport report = executeIngestion(
                expected, readyEnvironment().target(), variables, new IngestionDependencies() {
                    @Override
                    public EmbeddingStore<TextSegment> createStore(
                        VuePgVectorTarget target,
                        String password) {
                        storePassword.set(password);
                        return null;
                    }

                    @Override
                    public VueKnowledgeIngestor.IngestResult ingest(
                            EmbeddingStore<TextSegment> store) {
                        return new VueKnowledgeIngestor.IngestResult(expected.catalogVersion(), 23);
                    }

                    @Override
                    public VueIngestionVerification verify(
                            VueIngestionExpectedSnapshot snapshot,
                            VuePgVectorTarget target,
                            String password) {
                        verifierPassword.set(password);
                        return new VueIngestionVerification(
                                true, snapshot.catalogVersion(), 23, 23, 0, Set.of(1024), List.of());
                    }
                });

        assertTrue(report.passed());
        assertEquals("pg-secret", storePassword.get());
        assertEquals("pg-secret", verifierPassword.get());
    }

    @Test
    void 环境显式就绪时摄取并核验真实Vue知识() throws Exception {
        VueIngestionEnvironment environment = VueIngestionEnvironment.inspectSystemEnvironment();
        Map<String, String> variables = System.getenv();
        run(environment, execution -> {
            TemplateCatalog catalog = new TemplateCatalog(DATASET_ROOT, OBJECT_MAPPER);
            VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
            execution.setExpected(expected);
            return executeIngestion(expected, environment.target(), variables, new IngestionDependencies() {
                @Override
                public EmbeddingStore<TextSegment> createStore(
                        VuePgVectorTarget target,
                        String password) {
                    return createVueStore(target, password);
                }

                @Override
                public VueKnowledgeIngestor.IngestResult ingest(EmbeddingStore<TextSegment> store) {
                    EmbeddingModel model = createEmbeddingModel(variables.get("DASHSCOPE_API_KEY"));
                    return new VueKnowledgeIngestor(model, OBJECT_MAPPER).ingest(DATASET_ROOT, store);
                }

                @Override
                public VueIngestionVerification verify(
                        VueIngestionExpectedSnapshot snapshot,
                        VuePgVectorTarget target,
                        String password) {
                    return new VuePgVectorIngestionVerifier(OBJECT_MAPPER)
                            .verify(snapshot, target, password);
                }
            });
        }, this::writeReport);
    }

    @Test
    void 显式目录或快照动作失败时写入失败报告并保留原异常() {
        IllegalStateException original = new IllegalStateException("私有目录错误");
        AtomicReference<VueIngestionReport> written = new AtomicReference<>();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> run(readyEnvironment(), execution -> {
                    throw original;
                }, written::set));

        assertSame(original, thrown);
        assertTrue(written.get().renderMarkdown().contains("状态：未通过"));
        assertFalse(written.get().renderMarkdown().contains("私有目录错误"));
    }

    @Test
    void 显式摄取断言失败时写入失败报告并保留原断言() {
        AssertionError original = new AssertionError("私有断言错误");
        AtomicReference<VueIngestionReport> written = new AtomicReference<>();

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> run(readyEnvironment(), execution -> {
                    execution.setExpected(expectedSnapshot());
                    throw original;
                }, written::set));

        assertSame(original, thrown);
        assertTrue(written.get().renderMarkdown().contains("状态：未通过"));
        assertFalse(written.get().renderMarkdown().contains("私有断言错误"));
    }

    @Test
    void 失败报告写入失败时保留原异常并附加写入异常() {
        IllegalArgumentException original = new IllegalArgumentException("私有摄取错误");
        IOException writeFailure = new IOException("报告写入失败");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> run(readyEnvironment(), execution -> {
                    throw original;
                }, report -> {
                    throw writeFailure;
                }));

        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(writeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void 同一写入异常不会对自身添加suppressed() {
        IOException original = new IOException("首次写入失败");
        AtomicBoolean actionExecuted = new AtomicBoolean();

        IOException thrown = assertThrows(IOException.class,
                () -> run(readyEnvironment(), execution -> {
                    actionExecuted.set(true);
                    execution.setExpected(expectedSnapshot());
                    return passedReport(execution.expected());
                }, report -> {
                    throw original;
                }));

        assertSame(original, thrown);
        assertTrue(actionExecuted.get());
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void 显式动作返回未通过报告时写入失败报告并抛出断言() {
        AtomicReference<VueIngestionReport> written = new AtomicReference<>();

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> run(readyEnvironment(), execution -> {
                    VueIngestionExpectedSnapshot expected = expectedSnapshot();
                    execution.setExpected(expected);
                    return VueIngestionReport.verified(expected, new VueIngestionVerification(
                            false, expected.catalogVersion(), 23, 22, 0, Set.of(1024), List.of()));
                }, written::set));

        assertTrue(thrown.getMessage().contains("未通过"));
        assertTrue(written.get().renderMarkdown().contains("状态：未通过"));
    }

    @Test
    void 显式动作返回空报告时写入失败报告并抛出断言() {
        AtomicReference<VueIngestionReport> written = new AtomicReference<>();

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> run(readyEnvironment(), execution -> {
                    execution.setExpected(expectedSnapshot());
                    return null;
                }, written::set));

        assertTrue(thrown.getMessage().contains("未通过"));
        assertTrue(written.get().renderMarkdown().contains("状态：未通过"));
    }

    @Test
    void 默认环境短路不会执行显式动作() throws Exception {
        AtomicBoolean actionExecuted = new AtomicBoolean();
        AtomicReference<VueIngestionReport> written = new AtomicReference<>();
        VueIngestionEnvironment disabled = new VueIngestionEnvironment(
                false,
                List.of("RAG_VUE_INGEST 未设置为 true"),
                new VuePgVectorTarget("127.0.0.1", 5432, "ai_codegen_rag", "admin"));

        run(disabled, execution -> {
            actionExecuted.set(true);
            return VueIngestionReport.failed(null, List.of("不应执行"));
        }, written::set);

        assertFalse(actionExecuted.get());
        assertTrue(written.get().renderMarkdown().contains("状态：未执行"));
    }

    @Test
    void 默认环境写入未执行报告失败时保留原异常且不执行动作() {
        IOException original = new IOException("默认报告失败");
        AtomicBoolean actionExecuted = new AtomicBoolean();
        VueIngestionEnvironment disabled = new VueIngestionEnvironment(
                false,
                List.of("RAG_VUE_INGEST 未设置为 true"),
                new VuePgVectorTarget("127.0.0.1", 5432, "ai_codegen_rag", "admin"));

        IOException thrown = assertThrows(IOException.class,
                () -> run(disabled, execution -> {
                    actionExecuted.set(true);
                    return VueIngestionReport.failed(null, List.of("不应执行"));
                }, report -> {
                    throw original;
                }));

        assertSame(original, thrown);
        assertFalse(actionExecuted.get());
    }

    @Test
    void 显式动作成功时写入通过报告() throws Exception {
        AtomicReference<VueIngestionReport> written = new AtomicReference<>();
        VueIngestionExpectedSnapshot expected = expectedSnapshot();

        run(readyEnvironment(), execution -> {
            execution.setExpected(expected);
            return passedReport(expected);
        }, written::set);

        assertTrue(written.get().passed());
        assertTrue(written.get().renderMarkdown().contains("状态：通过"));
    }

    private static VueIngestionReport passedReport(VueIngestionExpectedSnapshot expected) {
        return VueIngestionReport.verified(expected, new VueIngestionVerification(
                true, expected.catalogVersion(), 23, 23, 0, Set.of(1024), List.of()));
    }

    static void run(
            VueIngestionEnvironment environment,
            ExplicitIngestionAction action,
            ReportWriter reportWriter) throws Exception {
        if (!environment.ready()) {
            reportWriter.write(VueIngestionReport.notExecuted(environment.reasons()));
            return;
        }

        ExplicitExecution execution = new ExplicitExecution();
        try {
            VueIngestionReport report = action.execute(execution);
            requirePassedReport(report);
            reportWriter.write(report);
        } catch (Exception exception) {
            writeFailureReport(execution, reportWriter, exception);
            throw exception;
        } catch (AssertionError error) {
            writeFailureReport(execution, reportWriter, error);
            throw error;
        }
    }

    private static void writeFailureReport(
            ExplicitExecution execution,
            ReportWriter reportWriter,
            Throwable originalFailure) {
        try {
            reportWriter.write(VueIngestionReport.failed(
                    execution.expected(),
                    List.of("真实摄取依赖失败: "
                            + originalFailure.getClass().getSimpleName())));
        } catch (IOException reportFailure) {
            if (reportFailure != originalFailure) {
                originalFailure.addSuppressed(reportFailure);
            }
        }
    }

    private static void requirePassedReport(VueIngestionReport report) {
        if (report == null || !report.passed()) {
            throw new AssertionError("Vue 真实摄取未通过物理核验");
        }
    }

    private static VueIngestionEnvironment readyEnvironment() {
        return new VueIngestionEnvironment(
                true, List.of(), new VuePgVectorTarget("127.0.0.1", 5432, "ai_codegen_rag", "admin"));
    }

    private static VueIngestionExpectedSnapshot expectedSnapshot() {
        return VueIngestionExpectedSnapshot.from(new TemplateCatalog(DATASET_ROOT, OBJECT_MAPPER));
    }

    private static String pgVectorPassword(Map<String, String> environment) {
        return environment.get("RAG_PGVECTOR_PASSWORD");
    }

    static VueIngestionReport executeIngestion(
            VueIngestionExpectedSnapshot expected,
            VuePgVectorTarget target,
            Map<String, String> variables,
            IngestionDependencies dependencies) {
        String password = pgVectorPassword(variables);
        EmbeddingStore<TextSegment> store = dependencies.createStore(target, password);
        VueKnowledgeIngestor.IngestResult result = dependencies.ingest(store);
        assertEquals(expected.catalogVersion(), result.catalogVersion());
        assertEquals(23, result.chunkCount());

        VueIngestionVerification verification = dependencies.verify(expected, target, password);
        VueIngestionReport report = VueIngestionReport.verified(expected, verification);
        assertTrue(report.passed(), "Vue 真实摄取物理核验失败，详见 " + REPORT);
        return report;
    }

    interface IngestionDependencies {

        EmbeddingStore<TextSegment> createStore(VuePgVectorTarget target, String password);

        VueKnowledgeIngestor.IngestResult ingest(EmbeddingStore<TextSegment> store);

        VueIngestionVerification verify(
                VueIngestionExpectedSnapshot expected,
                VuePgVectorTarget target,
                String password);
    }

    @FunctionalInterface
    interface ExplicitIngestionAction {

        VueIngestionReport execute(ExplicitExecution execution) throws Exception;
    }

    @FunctionalInterface
    interface ReportWriter {

        void write(VueIngestionReport report) throws IOException;
    }

    static final class ExplicitExecution {

        private VueIngestionExpectedSnapshot expected;

        void setExpected(VueIngestionExpectedSnapshot expected) {
            this.expected = expected;
        }

        VueIngestionExpectedSnapshot expected() {
            return expected;
        }
    }

    private EmbeddingModel createEmbeddingModel(String apiKey) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(DASHSCOPE_COMPATIBLE_BASE_URL)
                .apiKey(apiKey)
                .modelName(EMBEDDING_MODEL)
                .dimensions(EMBEDDING_DIMENSION)
                .timeout(EMBEDDING_TIMEOUT)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private EmbeddingStore<TextSegment> createVueStore(
            VuePgVectorTarget target,
            String password) {
        return PgVectorEmbeddingStore.builder()
                .host(target.host())
                .port(target.port())
                .database(target.database())
                .user(target.user())
                .password(password)
                .table("templates_vue")
                .dimension(EMBEDDING_DIMENSION)
                .createTable(true)
                .useIndex(false)
                .build();
    }

    private void writeReport(VueIngestionReport report) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.renderMarkdown(), StandardCharsets.UTF_8);
    }
}
