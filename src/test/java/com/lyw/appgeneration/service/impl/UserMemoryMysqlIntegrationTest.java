package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Action;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation.Scope;
import com.lyw.appgeneration.mapper.*;
import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.core.mybatis.FlexSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 显式启用后，仅在隔离测试服务上创建随机数据库；不读写已有业务表。 */
@EnabledIfEnvironmentVariable(named = "L2_MEMORY_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/l2_memory_test.*")
class UserMemoryMysqlIntegrationTest {
    private Connection admin;
    private SqlSession session;
    private SqlSessionFactory factory;
    private VersionedUserMemoryRepository repository;
    private String database;

    @BeforeEach void 建立隔离数据库() throws Exception {
        String base = System.getenv("L2_MEMORY_MYSQL_URL");
        String user = System.getenv().getOrDefault("L2_MEMORY_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("L2_MEMORY_MYSQL_PASSWORD", "");
        admin = DriverManager.getConnection(base, user, password);
        database = "l2_memory_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            sql.execute("USE " + database);
            sql.execute("CREATE TABLE `user` (id BIGINT PRIMARY KEY, isDelete TINYINT NOT NULL DEFAULT 0)");
            sql.execute("INSERT INTO `user` VALUES (7,0),(8,0)");
            String schema = Files.readString(Path.of("sql/schema.sql"));
            for (String table : List.of("app_memory", "app_memory_candidate", "app_memory_evidence", "app_memory_event", "app_memory_extract_cursor")) {
                var match = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS " + table + " \\(.*?;\\s*").matcher(schema);
                assertTrue(match.find(), table);
                sql.execute(match.group());
            }
        }
        // 替换完整测试库名，保留连接参数，避免带后缀的隔离库名被拼接到新库名后。
        String url = base.replaceFirst("/l2_memory_test[^?]*", "/" + database);
        var source = new DriverManagerDataSource(url, user, password);
        var configuration = new FlexConfiguration(new Environment(database, new JdbcTransactionFactory(),
                new FlexDataSource(database, source)));
        factory = new FlexSqlSessionFactoryBuilder().build(configuration);
        for (Class<?> mapper : List.of(AppMemoryMapper.class, AppMemoryCandidateMapper.class,
                AppMemoryEvidenceMapper.class, AppMemoryEventMapper.class, AppMemoryExtractCursorMapper.class)) configuration.addMapper(mapper);
        session = factory.openSession(false);
        repository = repository(session);
    }

    @AfterEach void 删除本测试数据库() throws Exception {
        if (session != null) session.close();
        if (admin != null) {
            try {
                if (database != null) try (var sql = admin.createStatement()) { sql.execute("DROP DATABASE " + database); }
            } finally { admin.close(); }
        }
    }

    @Test void 真实Mapper执行中文日语中文版本链() {
        apply(add("中文", "EXPLICIT", 10)); session.commit();
        Item first = active(repository);
        apply(replace(first, "日语", "EXPLICIT", 20)); session.commit();
        Item second = active(repository);
        apply(replace(second, "中文", "EXPLICIT", 30)); session.commit();
        Item third = active(repository);
        var mapper = session.getMapper(AppMemoryMapper.class);
        assertEquals("SUPERSEDED", mapper.selectOneById(first.id()).getStatus());
        assertEquals(second.id(), mapper.selectOneById(first.id()).getSupersededBy());
        assertEquals(third.id(), mapper.selectOneById(second.id()).getSupersededBy());
        assertEquals("中文", third.content());
        assertEquals(3, mapper.selectAll().size());
        assertEquals(1, repository.activePage(7, 0).size());
        assertEquals(1, repository.evidence(third).size());
    }

    @Test void 真实事务回滚恢复旧生效版本() {
        apply(add("中文", "EXPLICIT", 10)); session.commit();
        Item original = active(repository);
        repository.lockUser(7);
        Snapshot shown = repository.load(7);
        var plan = planner(repository, shown).plan(List.of(replace(original, "日语", "EXPLICIT", 20)), shown);
        var failing = spy(repository);
        doThrow(new IllegalStateException("模拟新版本插入失败")).when(failing).insert(any(Item.class));
        assertThrows(IllegalStateException.class, () -> plan.apply(failing));
        session.rollback();
        assertEquals(original, active(repository));
        assertEquals(1, session.getMapper(AppMemoryMapper.class).selectAll().size());
    }

    @Test void 候选晋升证据和游标在事务失败时一起回滚() throws Exception {
        apply(add("日语", "IMPLICIT", 10)); session.commit();
        Item candidate = repository.load(7).items().values().iterator().next();
        apply(new UserMemoryOperation(Action.REINFORCE, candidate.id(), null, null, null, Scope.USER, "IMPLICIT", List.of(20L)));
        session.getMapper(AppMemoryExtractCursorMapper.class).insert(AppMemoryExtractCursor.builder()
                .id(1L).appId(100L).userId(7L).lastExtractedId(21L).failCount(0)
                .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).isDelete(0).build());
        session.rollback();
        assertEquals(candidate, repository.load(7).items().get(candidate.id()));
        assertTrue(repository.activePage(7, 0).isEmpty());
        assertTrue(session.getMapper(AppMemoryExtractCursorMapper.class).selectAll().isEmpty());
        assertEquals(1, repository.evidence(candidate).size());
    }

    @Test void 数据库唯一槽及状态约束拒绝重复生效和跨表状态() {
        apply(add("中文", "EXPLICIT", 10)); session.commit();
        Item original = active(repository);
        assertThrows(RuntimeException.class, () -> repository.insert(original.toBuilder().id(repository.nextId()).content("日语").build()));
        session.rollback();
        assertThrows(RuntimeException.class, () -> session.getMapper(AppMemoryCandidateMapper.class).insert(
                VersionedUserMemoryRepository.toCandidate(original.toBuilder().kind(Kind.CANDIDATE)
                        .status("CANDIDATE").id(repository.nextId()).build()).toBuilder().status("ACTIVE").build()));
        session.rollback();
        assertThrows(RuntimeException.class, () -> repository.addEvidence(original, 100L, 10, "EXPLICIT", LocalDateTime.now()));
        session.rollback();
        assertEquals(1, repository.evidence(original).size());
    }

    @Test void 两个连接由用户行锁串行提交并拒绝过期目标() throws Exception {
        apply(add("中文", "EXPLICIT", 10)); session.commit();
        Item original = active(repository);
        try (SqlSession other = factory.openSession(false); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            VersionedUserMemoryRepository second = repository(other);
            Snapshot stale = second.load(7);
            other.commit();
            repository.lockUser(7);
            CountDownLatch waiting = new CountDownLatch(1);
            var contender = executor.submit(() -> {
                waiting.countDown();
                second.lockUser(7);
                Snapshot current = second.loadCurrent(7);
                try {
                    planner(second, current).plan(List.of(replace(original, "英文", "EXPLICIT", 30)), stale);
                    return false;
                } catch (IllegalStateException expected) {
                    return true;
                } finally { other.rollback(); }
            });
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertFalse(contender.isDone());
            apply(replace(original, "日语", "EXPLICIT", 20));
            session.commit();
            assertTrue(contender.get(5, TimeUnit.SECONDS));
            assertEquals("日语", active(repository).content());
            assertEquals(1, repository.activePage(7, 0).size());
        }
    }

    @Test void 乐观版本兜底拒绝同一旧行二次更新() {
        apply(add("中文", "EXPLICIT", 10)); session.commit();
        Item old = active(repository);
        Item next = old.toBuilder().revision(old.revision()+1).status("REVOKED").revokedByTurnId(20L).build();
        repository.update(old, next); session.commit();
        assertThrows(IllegalStateException.class, () -> repository.update(old, next));
        session.rollback();
        assertEquals("REVOKED", repository.load(7).items().get(old.id()).status());
    }

    @Test void 来源应用解绑保留跨应用正式候选和证据() {
        apply(add("中文", "EXPLICIT", 10)); session.commit();
        apply(replace(active(repository), "日语", "IMPLICIT", 20)); session.commit();
        session.getMapper(AppMemoryMapper.class).unlinkAppId(100L);
        session.getMapper(AppMemoryCandidateMapper.class).unlinkAppId(100L);
        session.getMapper(AppMemoryEvidenceMapper.class).unlinkAppId(100L);
        session.commit();
        Snapshot result = repository.load(7);
        assertEquals(2, result.items().size());
        assertTrue(result.items().values().stream().allMatch(item -> item.appId() == null));
        assertTrue(session.getMapper(AppMemoryEvidenceMapper.class).selectAll().stream().allMatch(row -> row.getAppId() == null));
        assertEquals("中文", active(repository).content());
    }

    private VersionedUserMemoryRepository repository(SqlSession sql) {
        return new VersionedUserMemoryRepository(sql.getMapper(AppMemoryMapper.class),
                sql.getMapper(AppMemoryCandidateMapper.class), sql.getMapper(AppMemoryEvidenceMapper.class), sql.getMapper(AppMemoryEventMapper.class));
    }
    private Item active(VersionedUserMemoryRepository repo) { return repo.load(7).items().values().stream().filter(Item::active).findFirst().orElseThrow(); }
    private UserMemoryChangePlanner planner(VersionedUserMemoryRepository repo, Snapshot snapshot) {
        return new UserMemoryChangePlanner(repo, 7, 100L, snapshot, LocalDateTime.now().withNano(0));
    }
    private void apply(UserMemoryOperation operation) {
        repository.lockUser(7);
        Snapshot snapshot = repository.load(7);
        planner(repository, snapshot).plan(List.of(operation), snapshot).apply(repository);
    }
    private UserMemoryOperation add(String content, String evidence, long turn) {
        return new UserMemoryOperation(Action.ADD, null, "语言偏好", "页面默认语言", content, Scope.USER, evidence, List.of(turn));
    }
    private UserMemoryOperation replace(Item target, String content, String evidence, long turn) {
        return new UserMemoryOperation(Action.REPLACE, target.id(), target.category(), null, content, Scope.USER, evidence, List.of(turn));
    }
}
