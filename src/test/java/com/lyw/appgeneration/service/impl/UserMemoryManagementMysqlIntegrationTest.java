package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.mapper.*;
import com.lyw.appgeneration.model.dto.memory.*;
import com.lyw.appgeneration.model.dto.memory.UserMemoryMutationRequest.VersionReference;
import com.lyw.appgeneration.model.vo.UserMemoryVO;
import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.core.mybatis.FlexSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Mapper 和 Spring 事务；仅在显式指定的本地测试服务创建随机隔离库。 */
@EnabledIfEnvironmentVariable(named = "L2_MEMORY_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/l2_memory_test.*")
class UserMemoryManagementMysqlIntegrationTest {
    private Connection admin;
    private String database;
    private VersionedUserMemoryRepository repository;
    private DataSourceTransactionManager transactionManager;
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private UserMemoryManagementServiceImpl service;

    @BeforeEach void 创建隔离数据库与真实事务服务() throws Exception {
        String base = System.getenv("L2_MEMORY_MYSQL_URL");
        String user = System.getenv().getOrDefault("L2_MEMORY_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("L2_MEMORY_MYSQL_PASSWORD", "");
        admin = DriverManager.getConnection(base, user, password);
        database = "l2_memory_manage_it_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        sql("USE " + database);
        sql("CREATE TABLE `user` (id BIGINT PRIMARY KEY, isDelete TINYINT NOT NULL DEFAULT 0)");
        sql("INSERT INTO `user` VALUES (7,0),(8,0)");
        String schema = Files.readString(Path.of("sql/schema.sql"));
        for (String name : List.of("app_memory", "app_memory_candidate", "app_memory_evidence", "app_memory_event")) {
            var match = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS " + name + " \\(.*?;\\s*").matcher(schema);
            assertTrue(match.find(), name); sql(match.group());
        }
        String url = base.replaceFirst("/l2_memory_test[^?]*", "/" + database);
        var dataSource = new FlexDataSource(database, new DriverManagerDataSource(url, user, password));
        var configuration = new FlexConfiguration(new Environment(database, new SpringManagedTransactionFactory(), dataSource));
        var factory = new FlexSqlSessionFactoryBuilder().build(configuration);
        for (Class<?> mapper : List.of(AppMemoryMapper.class, AppMemoryCandidateMapper.class, AppMemoryEvidenceMapper.class, AppMemoryEventMapper.class)) configuration.addMapper(mapper);
        var session = new SqlSessionTemplate(factory);
        repository = new VersionedUserMemoryRepository(session.getMapper(AppMemoryMapper.class), session.getMapper(AppMemoryCandidateMapper.class),
                session.getMapper(AppMemoryEvidenceMapper.class), session.getMapper(AppMemoryEventMapper.class));
        transactionManager = new DataSourceTransactionManager(dataSource);
        service = newService();
    }

    @AfterEach void 清理本测试数据库() throws Exception {
        if (admin != null) {
            try { if (database != null) sql("DROP DATABASE " + database); }
            finally { admin.close(); }
        }
    }

    @Test void 手动中文日语中文真实落库并保留来源证据和历史() throws Exception {
        service.change(7, create("页面默认语言", "中文", "ACTIVE"));
        var original = active();
        var pendingResult = service.change(7, create("页面默认语言", "日语", "CANDIDATE"));
        var candidate = page("CANDIDATE").records().getFirst();
        assertTrue(candidate.requiresConfirmation());
        var adopt = target("ADOPT", candidate); adopt.setExpectedActive(reference(original));
        service.change(7, adopt);
        var edit = target("EDIT", active()); edit.setContent("中文"); service.change(7, edit);
        assertEquals("中文", active().content());
        assertNotEquals(original.id(), active().id());
        assertEquals(3, count("SELECT COUNT(*) FROM app_memory"));
        assertEquals(2, count("SELECT COUNT(*) FROM app_memory WHERE status='SUPERSEDED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory_candidate WHERE id=" + pendingResult.targetId() + " AND status='PROMOTED'"));
        assertEquals(0, count("SELECT COUNT(*) FROM app_memory_evidence e LEFT JOIN app_memory_event a ON a.id=e.turnId AND a.userId=e.userId WHERE e.sourceType='MANUAL' AND (a.id IS NULL OR a.sourceType <> 'MANUAL')"));
        var history = service.history(7, new UserMemoryQueryRequest());
        assertEquals(4, history.totalRow());
        assertEquals("EDIT", history.records().getFirst().changes().getFirst().action());
    }

    @Test void 旧记录以真实快照展示而不是伪造手动操作() throws Exception {
        sql("INSERT INTO app_memory (id,userId,name,memoryKey,attributeName,content,evidenceCount,legacyEvidenceCount,lastEvidenceTurnId,legacyEvidenceTurnId,createTime,updateTime) VALUES (10,7,'语言偏好','ui.default_language','页面默认语言','中文',6,6,5,5,'2026-10-20 12:00:00','2026-10-21 12:00:00')");
        var before = service.history(7, new UserMemoryQueryRequest());
        assertEquals(1, before.totalRow());
        assertEquals("LEGACY", before.records().getFirst().sourceType());
        var edit = target("EDIT", active()); edit.setContent("日语"); service.change(7, edit);
        var history = service.history(7, new UserMemoryQueryRequest());
        assertEquals(2, history.totalRow());
        assertEquals("MANUAL", history.records().getFirst().sourceType());
        assertEquals("中文", history.records().getFirst().changes().getFirst().beforeContent());
        assertEquals(6, count("SELECT legacyEvidenceCount FROM app_memory WHERE id=10"));
    }

    @Test void 同类多属性分页搜索不受召回预算限制且用户隔离() {
        for (int index = 0; index < 12; index++) service.change(7, create("语言偏好" + index, "偏好值" + index, "ACTIVE"));
        service.change(8, create("第二用户", "仅第二用户可见", "ACTIVE"));
        var query = new UserMemoryQueryRequest(); query.setPageNum(2); query.setPageSize(5);
        var page = service.list(7, query);
        assertEquals(12, page.totalRow()); assertEquals(5, page.records().size()); assertEquals(12, page.activeCount());
        assertTrue(page.records().stream().noneMatch(row -> row.content().contains("第二用户")));
        query.setPageNum(1); query.setKeyword("语言偏好1");
        assertEquals(3, service.list(7, query).totalRow());
        query.setKeyword("%' OR 1=1 --");
        assertEquals(0, service.list(7, query).totalRow());
    }

    @Test void 并发同名新增只有一个成功另一个要求重新确认() throws Exception {
        var gate = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> race(gate, newService(), create("页面默认语言", "中文", "ACTIVE")));
            var second = threads.submit(() -> race(gate, newService(), create("页面默认语言", "日语", "ACTIVE")));
            gate.countDown();
            assertEquals(List.of(0, 40900), java.util.stream.Stream.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory WHERE status='ACTIVE'"));
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory_event"));
    }

    @Test void 并发重复请求只提交一次并返回同一结果() throws Exception {
        var request = create("页面默认语言", "中文", "ACTIVE"); var gate = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> { gate.await(); return newService().change(7, request); });
            var second = threads.submit(() -> { gate.await(); return newService().change(7, request); });
            gate.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory"));
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory_evidence"));
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory_event"));
    }

    @Test void 操作历史写入失败回滚正式替换证据和请求记录() throws Exception {
        service.change(7, create("页面默认语言", "中文", "ACTIVE"));
        var original = active();
        sql("CREATE TRIGGER fail_memory_event BEFORE INSERT ON app_memory_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='测试操作记录失败'");
        var edit = target("EDIT", original); edit.setContent("日语");
        assertThrows(BusinessException.class, () -> service.change(7, edit));
        assertEquals(original, active());
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory"));
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory_evidence"));
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory_event"));
    }

    @Test void 旧版本和越权目标都不能被覆盖() {
        service.change(7, create("页面默认语言", "中文", "ACTIVE"));
        var original = active();
        var edit = target("EDIT", original); edit.setContent("日语"); service.change(7, edit);
        edit.setRequestId(UUID.randomUUID().toString()); edit.setContent("英文");
        assertEquals(40900, assertThrows(BusinessException.class, () -> service.change(7, edit)).getCode());
        var foreign = target("REVOKE", active());
        assertEquals(40400, assertThrows(BusinessException.class, () -> service.change(8, foreign)).getCode());
        assertEquals("日语", active().content());
    }

    @Test void 正式缓存失效失败时不写库但候选仍可独立保存() throws Exception {
        doThrow(new IllegalStateException("缓存不可用")).when(redis).delete("mem:pref:v3:7");
        assertThrows(BusinessException.class, () -> service.change(7, create("页面默认语言", "中文", "ACTIVE")));
        assertEquals(0, count("SELECT COUNT(*) FROM app_memory_event"));
        clearInvocations(redis);
        service.change(7, create("页面默认语言", "日语", "CANDIDATE"));
        assertEquals(1, count("SELECT COUNT(*) FROM app_memory_candidate WHERE requiresConfirmation=1"));
        verifyNoInteractions(redis);
    }

    private UserMemoryManagementServiceImpl newService() {
        var properties = new MemoryTokenProperties();
        return new UserMemoryManagementServiceImpl(repository, new UserMemoryConsistencyCoordinator(), redis,
                new ConservativeChatTokenEstimator(properties), properties, transactionManager);
    }
    private int race(CountDownLatch gate, UserMemoryManagementServiceImpl service, UserMemoryMutationRequest request) throws Exception {
        gate.await(); try { service.change(7, request); return 0; } catch (BusinessException exception) { return exception.getCode(); }
    }
    private com.lyw.appgeneration.model.vo.UserMemoryPageVO page(String status) { var request = new UserMemoryQueryRequest(); request.setStatus(status); return service.list(7, request); }
    private UserMemoryVO active() { return page("ACTIVE").records().getFirst(); }
    private static VersionReference reference(UserMemoryVO row) { return new VersionReference(row.id(), row.revision()); }
    private static UserMemoryMutationRequest create(String name, String content, String mode) {
        var request = new UserMemoryMutationRequest(); request.setRequestId(UUID.randomUUID().toString()); request.setAction("CREATE");
        request.setCategory("语言偏好"); request.setAttributeName(name); request.setContent(content); request.setSaveMode(mode); return request;
    }
    private static UserMemoryMutationRequest target(String action, UserMemoryVO row) {
        var request = new UserMemoryMutationRequest(); request.setRequestId(UUID.randomUUID().toString()); request.setAction(action); request.setTarget(reference(row)); return request;
    }
    private void sql(String statement) throws Exception { try (var sql = admin.createStatement()) { sql.execute(statement); } }
    private long count(String query) throws Exception { try (var sql = admin.createStatement(); var rows = sql.executeQuery(query)) { assertTrue(rows.next()); return rows.getLong(1); } }
}
