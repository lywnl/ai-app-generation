package com.lyw.appgeneration.sql;

import com.lyw.appgeneration.ai.memory.SummaryCoverage;
import com.lyw.appgeneration.ai.memory.SummaryCoverageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 必须明确授权后设置连接；仅连接本机测试服务并新建随机库，不使用现有业务数据库。
 * URL 示例 jdbc:mysql://127.0.0.1:3406/?useSSL=false；凭据由环境传入且不输出。
 */
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(\\?.*)?")
class UnifiedSummaryMysqlMigrationTest {
    private Connection connection;
    private String database;

    @BeforeEach
    void 建立随机隔离库() throws Exception {
        connection = DriverManager.getConnection(System.getenv("UNIFIED_MEMORY_MYSQL_URL"),
                System.getenv().getOrDefault("UNIFIED_MEMORY_MYSQL_USER", "root"),
                System.getenv().getOrDefault("UNIFIED_MEMORY_MYSQL_PASSWORD", ""));
        database = "context_summary_it_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        execute("USE " + database);
        System.out.println("迁移隔离库=" + database + "，MySQL=" + connection.getMetaData().getDatabaseProductVersion());
    }

    @AfterEach
    void 仅清理本次创建的库() throws Exception {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 默认自动提交时无需回滚；清理仍只针对本次随机库。
        }
        try {
            if (database != null && database.matches("context_summary_it_[0-9a-f]{32}")) {
                connection.setAutoCommit(true);
                execute("DROP DATABASE " + database);
            }
        } finally {
            connection.close();
        }
    }

    @Test
    void 两份初始化结构均可重入迁移() throws Exception {
        for (String schema : List.of("sql/schema.sql", "prod/sql/schema.sql")) {
            createTables(schema, false);
            insertLegacyFixture();
            var before = oldColumns();
            migrate();
            assertMetadata();
            assertEquals(before, oldColumns());
            assertLegacyDefaults();
            migrate();
            assertEquals(before, oldColumns());
            execute("DROP TABLE chat_history, app_memory_summary");
        }
    }

    @Test
    void 旧库升级及新数据写入后再次迁移都不覆盖原有内容() throws Exception {
        createTables("sql/schema.sql", true);
        insertLegacyFixture();
        var before = oldColumns();
        migrate();
        assertMetadata();
        assertLegacyDefaults();
        assertEquals(before, oldColumns());
        String coverage = SummaryCoverageCodec.encode(new SummaryCoverage(10L, "migration-turn",
                "migration-epoch", 12L, "a".repeat(64), 1L, null, null, false));
        try (var update = connection.prepareStatement("UPDATE app_memory_summary SET summaryRevision=1,"
                + "coverageVersion=2,lastSummarizedId=0,coverageState=? WHERE id=50")) {
            update.setString(1, coverage);
            assertEquals(1, update.executeUpdate());
        }
        try (var update = connection.prepareStatement("UPDATE chat_history SET decisionEvidence=? WHERE id=10")) {
            update.setString(1, "{\"formatVersion\":1,\"revision\":1,\"text\":\"中文否决 🚫\"}");
            assertEquals(1, update.executeUpdate());
        }
        var newData = allColumns();
        migrate();
        assertEquals(newData, allColumns());
        System.out.println("升级与重入：聊天行数=2，摘要行数=1；旧列、新覆盖及中文证据逐字段一致");
    }

    @Test
    void DDL失败保留已加列而DML回滚恢复原值() throws Exception {
        createTables("sql/schema.sql", true);
        insertLegacyFixture();
        var before = oldColumns();
        execute("ALTER TABLE app_memory_summary ADD COLUMN summaryRevision INT NOT NULL DEFAULT 0");
        assertThrows(SQLException.class, this::migrate);
        assertEquals(before, oldColumns());
        assertEquals("mediumtext", scalar("SELECT data_type FROM information_schema.columns WHERE "
                + "table_schema=DATABASE() AND table_name='chat_history' AND column_name='decisionEvidence'"));
        execute("ALTER TABLE app_memory_summary MODIFY COLUMN summaryRevision BIGINT NOT NULL DEFAULT 0");
        migrate();
        assertMetadata();
        connection.setAutoCommit(false);
        execute("UPDATE chat_history SET decisionEvidence='未提交的证据' WHERE id=10");
        connection.rollback();
        connection.setAutoCommit(true);
        assertNull(scalar("SELECT decisionEvidence FROM chat_history WHERE id=10"));
        assertEquals(before, oldColumns());
        System.out.println("失败边界：DDL 已完成列仍在；DML 回滚不留下证据半状态");
    }

    private void createTables(String file, boolean legacy) throws Exception {
        String schema = Files.readString(Path.of(file));
        for (String table : List.of("chat_history", "app_memory_summary")) {
            var match = Pattern.compile("(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?"
                    + table + "\\s*\\(.*?;\\s*").matcher(schema);
            assertTrue(match.find());
            String definition = match.group();
            if (legacy) {
                definition = definition.replaceAll("(?m)^\\s*(summaryRevision|coverageVersion|coverageState|decisionEvidence)\\s+[^\\r\\n]+[\\r\\n]+", "");
            }
            execute(definition);
        }
    }

    private void insertLegacyFixture() throws SQLException {
        execute("INSERT INTO chat_history(id,message,memoryMessage,memoryOutcome,messageType,appId,userId) VALUES "
                + "(10,'不要轮播图',NULL,NULL,'user',7,9),(20,'展示文本','可信失败结果','FAILED','ai',7,9)");
        execute("INSERT INTO app_memory_summary(id,appId,summary,lastSummarizedId,summaryTokens) "
                + "VALUES(50,7,'旧摘要保留',20,20)");
    }

    private void assertMetadata() throws SQLException {
        assertEquals(List.of(
                "app_memory_summary|summaryRevision|bigint|NO|0",
                "app_memory_summary|coverageVersion|int|NO|1",
                "app_memory_summary|coverageState|mediumtext|YES|null",
                "chat_history|decisionEvidence|mediumtext|YES|null"), rows(
                "SELECT table_name,column_name,column_type,is_nullable,column_default FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND column_name IN "
                        + "('summaryRevision','coverageVersion','coverageState','decisionEvidence') ORDER BY table_name,ordinal_position"));
    }

    private void assertLegacyDefaults() throws SQLException {
        assertEquals("0|1|null", rows("SELECT summaryRevision,coverageVersion,coverageState FROM app_memory_summary").getFirst());
        assertEquals("0", scalar("SELECT COUNT(*) FROM chat_history WHERE decisionEvidence IS NOT NULL"));
    }

    private List<String> oldColumns() throws SQLException {
        var result = new ArrayList<>(rows("SELECT id,message,memoryMessage,memoryOutcome,messageType,appId,userId,"
                + "createTime,updateTime,isDelete FROM chat_history ORDER BY id"));
        result.addAll(rows("SELECT id,appId,summary,lastSummarizedId,summaryTokens,failCount,nextRetryTime,"
                + "createTime,updateTime,isDelete FROM app_memory_summary ORDER BY id"));
        return result;
    }

    private List<String> allColumns() throws SQLException {
        var result = new ArrayList<>(rows("SELECT * FROM chat_history ORDER BY id"));
        result.addAll(rows("SELECT * FROM app_memory_summary ORDER BY id"));
        return result;
    }

    private String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private List<String> rows(String sql) throws SQLException {
        var rows = new ArrayList<String>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) {
                var values = new ArrayList<String>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    values.add(String.valueOf(result.getString(column)));
                }
                rows.add(String.join("|", values));
            }
        }
        return rows;
    }

    private void migrate() throws Exception {
        String delimiter = ";";
        StringBuilder sql = new StringBuilder();
        for (String line : Files.readAllLines(Path.of("sql/migrations/2026-10-31-unify-context-summarization.sql"))) {
            String stripped = line.strip();
            if (stripped.startsWith("--") || stripped.isEmpty()) continue;
            if (stripped.startsWith("DELIMITER ")) {
                assertTrue(sql.isEmpty());
                delimiter = stripped.substring("DELIMITER ".length());
                continue;
            }
            sql.append(line).append('\n');
            if (stripped.endsWith(delimiter)) {
                String complete = sql.toString().stripTrailing();
                execute(complete.substring(0, complete.length() - delimiter.length()));
                sql.setLength(0);
            }
        }
        assertTrue(sql.isEmpty());
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
