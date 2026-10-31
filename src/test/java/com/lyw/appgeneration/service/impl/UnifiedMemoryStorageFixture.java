package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.*;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.mapper.*;
import com.lyw.appgeneration.model.entity.*;
import com.lyw.appgeneration.monitor.*;
import com.lyw.appgeneration.service.*;
import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.core.mybatis.FlexSqlSessionFactoryBuilder;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 显式启用的真实存储夹具：每项新建随机库，只操作随机应用 ID 的三个 Redis 键。
 * 使用生产 Mapper、Spring 事务、摘要服务与 Redis Lua；仅摘要模型输出可控，无付费请求。
 */
abstract class UnifiedMemoryStorageFixture {
    protected final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    protected final ExecutorService models = Executors.newSingleThreadExecutor();
    protected final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    protected final MemoryTokenProperties properties = new MemoryTokenProperties();
    protected final ChatTokenEstimator estimator = new ConservativeChatTokenEstimator(properties);
    protected final AppDataLifecycleFence lifecycle = new AppDataLifecycleFence();
    protected final ChatModel model = mock(ChatModel.class);
    protected Connection admin;
    protected String database;
    protected long appId;
    protected FlexDataSource dataSource;
    protected DataSourceTransactionManager transactions;
    protected AppMemorySummaryMapper summaries;
    protected ChatHistoryMapper historiesMapper;
    protected AppMapper apps;
    protected ChatHistoryServiceImpl histories;
    protected ConversationDecisionEvidenceService evidence;
    protected MemorySummaryServiceImpl service;
    protected VueTurnContext context;
    protected TokenAwareChatMemory memory;
    protected AtomicChatMemoryStore sourceStore;
    protected UnifiedSummaryRequest request;
    protected JedisConnectionFactory redisFactory;
    protected StringRedisTemplate redis;
    private boolean ownsRedisKeys;
    private boolean ownsDatabase;

    @BeforeEach
    void 创建本次独立数据库和来源窗口() throws Exception {
        String base = System.getenv("UNIFIED_MEMORY_MYSQL_URL");
        assertNotNull(base);
        assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(\\?.*)?"));
        String user = System.getenv().getOrDefault("UNIFIED_MEMORY_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("UNIFIED_MEMORY_MYSQL_PASSWORD", "");
        admin = DriverManager.getConnection(base, user, password);
        database = "context_summary_it_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        ownsDatabase = true;
        sql("USE " + database);
        String schema = Files.readString(Path.of("sql/schema.sql"));
        for (String table : List.of("app", "chat_history", "app_memory_summary")) {
            var match = Pattern.compile("(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?" + table + "\\s*\\(.*?;\\s*").matcher(schema);
            assertTrue(match.find(), table);
            sql(match.group());
        }
        int query = base.indexOf('?');
        String url = (query < 0 ? base : base.substring(0, query)) + database + (query < 0 ? "" : base.substring(query));
        dataSource = new FlexDataSource(database, new DriverManagerDataSource(url, user, password));
        var configuration = new FlexConfiguration(new Environment(database, new SpringManagedTransactionFactory(), dataSource));
        var factory = new FlexSqlSessionFactoryBuilder().build(configuration);
        for (Class<?> mapper : List.of(AppMapper.class, ChatHistoryMapper.class, AppMemorySummaryMapper.class)) configuration.addMapper(mapper);
        var session = new SqlSessionTemplate(factory);
        summaries = session.getMapper(AppMemorySummaryMapper.class);
        historiesMapper = session.getMapper(ChatHistoryMapper.class);
        apps = session.getMapper(AppMapper.class);
        transactions = new DataSourceTransactionManager(dataSource);
        histories = new ChatHistoryServiceImpl();
        ReflectionTestUtils.setField(histories, "mapper", historiesMapper);
        ReflectionTestUtils.setField(histories, "lifecycleFence", lifecycle);
        evidence = new ConversationDecisionEvidenceService(historiesMapper, apps, lifecycle, transactions);

        var standalone = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("UNIFIED_MEMORY_REDIS_PORT")));
        standalone.setDatabase(Integer.parseInt(System.getenv().getOrDefault("UNIFIED_MEMORY_REDIS_DATABASE", "0")));
        String redisUser = System.getenv().getOrDefault("UNIFIED_MEMORY_REDIS_USERNAME", "");
        if (!redisUser.isBlank()) standalone.setUsername(redisUser);
        String redisPassword = System.getenv().getOrDefault("UNIFIED_MEMORY_REDIS_PASSWORD", "");
        if (!redisPassword.isBlank()) standalone.setPassword(redisPassword);
        redisFactory = new JedisConnectionFactory(standalone, JedisClientConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(1)).build());
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
        // 避开当前 snowflake 区间，且在占用前逐键检查；清理只使用本次成功占用的 ID。
        for (int attempt = 0; attempt < 5 && !ownsRedisKeys; attempt++) {
            appId = 6_000_000_000_000_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000_000L);
            if (keys().stream().anyMatch(key -> Boolean.TRUE.equals(redis.hasKey(key)))) continue;
            ownsRedisKeys = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(l0Key(),
                    L0SnapshotCodec.encode(L0Snapshot.empty(Long.toString(appId), 0)), Duration.ofMinutes(10)));
        }
        assertTrue(ownsRedisKeys, "未获得独立 Redis 测试键");
        sql("INSERT INTO app(id,appName,userId,codeGenType) VALUES (" + appId + ",'统一摘要存储测试',9,'vue_project')");
        ChatHistory userRow = ChatHistory.builder().id(10L).appId(appId).userId(9L).messageType("user")
                .message("保留中文，不要轮播图，因为移动端会遮挡内容。").isDelete(0).build();
        assertTrue(histories.save(userRow), "沿生产服务的选择性插入使用数据库默认时间");
        var operation = new AppOperationLeaseManager().acquire(appId, AppOperationLeaseManager.AppOperationType.GENERATE, "storage-turn");
        var lease = new VueBuildSessionManager().open(operation, 9L, "storage-turn");
        var admission = new VueTurnAdmissionController(new VueBuildRepairMetricsCollector(registry));
        context = new VueTurnContext(appId, 9, "storage-turn", operation, lease, admission.tryAcquire().orElseThrow(), new FileToolBudgetGuard().newSession());
        context.commitUserAndBindHistory(() -> userRow);
        sourceStore = new AtomicChatMemoryStore(new SpringRedisChatMemoryStore(redis, 600, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        memory = new TokenAwareChatMemory(MessageWindowChatMemory.builder().id(appId).chatMemoryStore(sourceStore)
                .maxMessages(Integer.MAX_VALUE).build(), sourceStore);
        CanonicalUserMessageScope.call(TurnRequestBoundary.of(userRow.getMessage(), List.of(), 10L, context.turnId()), () -> {
            memory.add(UserMessage.from(userRow.getMessage())); return null;
        });
        append(1, 10);
        request = new UnifiedSummaryRequest(memory, context);
        when(model.chat(anyString())).thenReturn(UnifiedSummaryCoordinationTest.SUMMARY);
        service = createService(summaries, transactions);
        System.out.println("真实存储夹具=" + database + "；随机应用=" + appId + "；MySQL=" + admin.getMetaData().getDatabaseProductVersion());
    }

    protected MemorySummaryServiceImpl createService(AppMemorySummaryMapper mapper, PlatformTransactionManager manager) {
        @SuppressWarnings("unchecked") ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        var engine = new MemorySummaryDraftEngine(histories, model, models, estimator, properties);
        engine.configureMetrics(new MemoryCompressionMetricsCollector(registry));
        var result = new MemorySummaryServiceImpl(mapper, engine, workers, redis, lifecycle, estimator, properties,
                new MemoryCompressionMetricsCollector(registry), provider);
        result.configureUnifiedInputs(new SummaryMaterialReader(histories), new SummaryWindowSelector(estimator, properties), new SummaryCoveragePlanner(), evidence);
        result.configureTerminalStore(sourceStore);
        return result;
    }

    protected MemorySummaryServiceImpl.UnifiedDraft prepared() {
        var deadline = deadline();
        var input = service.prepareUnifiedInput(request, deadline);
        assertNotNull(input);
        return new MemorySummaryServiceImpl.UnifiedDraft(request, input, new MemorySummaryDraftEngine.DraftResult(
                UnifiedSummaryCoordinationTest.SUMMARY, input.targetHistoryCursor(), estimator.estimateText(UnifiedSummaryCoordinationTest.SUMMARY),
                0, true, null, "可控草稿"), deadline);
    }

    protected void append(int from, int through) {
        for (int index = from; index <= through; index++) {
            int generation = index;
            SourceCommitScope.run(index, context, () -> memory.add(AiMessage.from("诊断批次" + generation + "原文".repeat(700))));
        }
    }

    /** 先执行真实 Mapper 调用再施加故障或暂停，不能伪造数据库返回值。 */
    protected AppMemorySummaryMapper afterMapperCall(BiConsumer<String, Object> after) {
        return (AppMemorySummaryMapper) Proxy.newProxyInstance(AppMemorySummaryMapper.class.getClassLoader(),
                new Class<?>[]{AppMemorySummaryMapper.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(summaries, args);
                        after.accept(method.getName(), result);
                        return result;
                    } catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
    }

    protected AdmissionDeadline deadline() { return AdmissionDeadline.start(Duration.ofSeconds(15)); }
    protected String l0Key() { return "chat-memory:l0:v2:" + appId; }
    protected List<String> keys() { return List.of(l0Key(), "mem:summary:" + appId, "mem:summary:v2:" + appId); }
    protected void sql(String sql) throws Exception { try (var statement = admin.createStatement()) { statement.execute(sql); } }
    protected long count(String sql) throws Exception {
        try (var statement = admin.createStatement(); var result = statement.executeQuery(sql)) { assertTrue(result.next()); return result.getLong(1); }
    }

    @AfterEach
    void 仅清理本次夹具() throws Exception {
        try {
            if (context != null) context.closeResources();
            if (ownsRedisKeys) {
                redis.delete(keys());
                for (String key : keys()) assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
            }
        } finally {
            if (redisFactory != null) redisFactory.destroy();
            workers.shutdownNow(); models.shutdownNow(); registry.close();
            if (admin != null) {
                try {
                    if (ownsDatabase && database != null && database.matches("context_summary_it_[0-9a-f]{32}")) {
                        sql("DROP DATABASE " + database);
                        assertEquals(0, count("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + database + "'"));
                    }
                } finally { admin.close(); }
            }
        }
    }
}
