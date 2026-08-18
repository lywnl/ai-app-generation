package com.lyw.appgeneration.sql;

import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.mybatisflex.annotation.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySchemaMigrationContractTest {

    private static final List<String> SCHEMA_FILES = List.of(
            "sql/schema.sql",
            "prod/sql/schema.sql");
    private static final List<String> MIGRATION_FILES = List.of(
            "sql/migrations/2026-08-15-token-layered-memory-v3.sql",
            "prod/sql/migrations/2026-08-15-token-layered-memory-v3.sql");
    private static final List<String> CHAT_MEMORY_MIGRATION_FILES = List.of(
            "sql/migrations/2026-08-18-chat-history-memory-projection.sql",
            "prod/sql/migrations/2026-08-18-chat-history-memory-projection.sql");

    @Test
    void retryTimeEntitiesUseExplicitLocalDateTimeColumns() throws Exception {
        assertColumn(AppMemorySummary.class, "nextRetryTime");
        assertColumn(AppMemoryExtractCursor.class, "nextRetryTime");
    }

    @Test
    void chatHistoryEntityUsesExplicitMemoryProjectionColumns()
            throws Exception {
        assertColumn(ChatHistory.class, "memoryMessage", String.class);
        assertColumn(ChatHistory.class, "memoryOutcome",
                ChatMemoryOutcome.class);
    }

    @Test
    void freshSchemasKeepRollbackCompatibleDefaults() throws Exception {
        for (String relativePath : SCHEMA_FILES) {
            String schema = read(relativePath);
            String summary = tableDefinition(schema, "app_memory_summary");
            String memory = tableDefinition(schema, "app_memory");
            String cursor = tableDefinition(
                    schema, "app_memory_extract_cursor");

            assertContainsColumn(summary,
                    "nextRetryTime datetime null", relativePath);
            assertContainsColumn(cursor,
                    "nextRetryTime datetime null", relativePath);
            assertContainsColumn(memory,
                    "status varchar(16) not null default 'active'",
                    relativePath);
            assertContainsColumn(memory,
                    "evidenceType varchar(16) not null default 'explicit'",
                    relativePath);
            assertContainsColumn(memory,
                    "evidenceCount int not null default 1", relativePath);
            assertContainsColumn(memory,
                    "lastEvidenceTurnId bigint null", relativePath);
        }
    }

    @Test
    void migrationsAreIdenticalRerunnableAndStagedForMysql8040()
            throws Exception {
        for (String relativePath : MIGRATION_FILES) {
            assertTrue(Files.isRegularFile(projectRoot().resolve(relativePath)),
                    "缺少 migration: " + relativePath);
        }
        String development = read(MIGRATION_FILES.getFirst());
        String production = read(MIGRATION_FILES.getLast());
        assertEquals(development, production,
                "开发和生产 migration 必须保持完全一致");

        String normalized = normalize(development);
        assertTrue(normalized.contains("mysql 8.0.40"));
        assertTrue(normalized.contains("alter table 会隐式提交"));
        assertTrue(occurrences(normalized, "information_schema.columns") >= 6,
                "每个条件 DDL 和最终验收都必须查询 columns 元数据");
        assertTrue(normalized.contains("drop procedure if exists"));
        assertTrue(normalized.contains("create procedure"));
        assertTrue(normalized.stripTrailing().endsWith(
                "drop procedure if exists migrate_token_layered_memory_v3;"),
                "脚本结束必须清理临时过程");

        assertTrue(normalized.contains("start transaction"));
        assertTrue(normalized.contains("update app_memory"));
        assertTrue(normalized.contains("status = coalesce(status, 'active')"));
        assertTrue(normalized.contains(
                "evidencetype = coalesce(evidencetype, 'explicit')"));
        assertTrue(normalized.contains(
                "evidencecount = coalesce(evidencecount, 1)"));
        assertTrue(normalized.contains("commit"));
        assertTrue(normalized.contains("status varchar(16) not null default 'active'"));
        assertTrue(normalized.contains(
                "evidencetype varchar(16) not null default 'explicit'"));
        assertTrue(normalized.contains(
                "evidencecount int not null default 1"));

        assertTrue(normalized.contains("select 'app_memory_summary'"));
        assertTrue(normalized.contains("select 'app_memory_extract_cursor'"));
        assertTrue(normalized.contains("select 'app_memory'"));
        assertTrue(normalized.contains("backup_20260815"));
        assertTrue(normalized.contains("information_schema.statistics"));
        assertTrue(normalized.contains("uk_userid_type_name"));
        assertTrue(normalized.contains("uk_appid"));
        assertTrue(normalized.contains("默认回滚"));
        assertTrue(normalized.contains("先导出新增列内容"));
        assertTrue(normalized.contains("v1__hnsw_index.sql"));
        assertTrue(normalized.contains("不会自动迁移 mysql"));

        List<String> destructiveLines = development.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT)
                        .contains("drop column"))
                .toList();
        assertFalse(destructiveLines.isEmpty(), "应提供手工破坏性回滚参考");
        assertTrue(destructiveLines.stream()
                        .allMatch(line -> line.stripLeading().startsWith("--")),
                "破坏性删列 SQL 只能作为注释中的手工参考");
    }

    @Test
    void chatMemoryProjectionSchemasExposeNullableColumns() throws Exception {
        for (String relativePath : SCHEMA_FILES) {
            String history = tableDefinition(read(relativePath),
                    "chat_history");
            assertContainsColumn(history,
                    "memoryMessage MEDIUMTEXT NULL", relativePath);
            assertContainsColumn(history,
                    "memoryOutcome VARCHAR(32) NULL", relativePath);
        }
    }

    @Test
    void chatMemoryProjectionMigrationsAreIdenticalAndSafe() throws Exception {
        for (String relativePath : CHAT_MEMORY_MIGRATION_FILES) {
            assertTrue(Files.isRegularFile(projectRoot().resolve(relativePath)),
                    "缺少 migration: " + relativePath);
        }
        String development = read(CHAT_MEMORY_MIGRATION_FILES.getFirst());
        String production = read(CHAT_MEMORY_MIGRATION_FILES.getLast());
        assertEquals(development, production,
                "开发和生产聊天记忆投影 migration 必须字节级一致");

        String normalized = normalize(development);
        assertTrue(normalized.contains("mysql 8.0.40"));
        assertTrue(normalized.contains("information_schema.columns"));
        assertTrue(normalized.contains("if not exists"));
        assertTrue(normalized.contains(
                "add column memorymessage mediumtext null"));
        assertTrue(normalized.contains(
                "add column memoryoutcome varchar(32) null"));
        assertTrue(normalized.contains("start transaction"));
        assertTrue(normalized.contains("commit"));
        assertTrue(normalized.contains("join app"));
        assertTrue(normalized.contains("codegentype = 'vue_project'"));
        assertTrue(normalized.contains(
                "codegentype in ('html', 'multi_file')"));
        assertTrue(normalized.contains("messageid = 447109043745288192")
                        || normalized.contains("id = 447109043745288192"),
                "必须定向处理已知协议异常消息");
        assertTrue(normalized.contains("legacy_unverified"));
        assertTrue(normalized.contains("legacy_imported"));
        assertTrue(normalized.contains("protocol_error"));
        assertTrue(normalized.contains(
                "历史 vue 回合缺少可信结构化执行证据。本轮内容不得作为后续工程状态依据，后续操作以当前工程文件为准。"));
        assertTrue(normalized.contains(
                "本轮发生工具协议异常，未完成真实工具执行或构建。不得复用本轮生成内容，后续操作以当前工程文件为准。"));
        assertTrue(normalized.contains("messagetype = 'ai'"));
        assertTrue(normalized.contains("memorymessage is null"));
        assertTrue(normalized.contains("memoryoutcome is null"));
        assertTrue(normalized.contains("data_type = 'mediumtext'"));
        assertTrue(normalized.contains("character_maximum_length = 32"));
        assertTrue(Pattern.compile(
                "(?is)update\\s+chat_history\\s+set\\s+memorymessage"
                                + "[^;]*where\\s+id\\s*=\\s*447109043745288192"
                                + "[^;]*memorymessage\\s+is\\s+null"
                                + "[^;]*memoryoutcome\\s+is\\s+null\\s*;")
                        .matcher(development).find(),
                "已知故障行只能在双投影字段均为空时回填");
        assertAiPartialProjectionGuardPrecedesBackfill(development);
        assertFalse(Pattern.compile(
                        "(?is)\\bset\\s+(?:[a-z_][a-z0-9_]*\\.)?message\\s*=")
                        .matcher(development).find(),
                "迁移不得修改展示字段 message");
        assertFalse(Pattern.compile("(?is)\\b(create|replace|truncate)\\s+"
                        + "(?:table\\s+)?[^;]*backup")
                        .matcher(development).find(),
                "迁移不得创建或覆盖备份表");
    }

    private void assertAiPartialProjectionGuardPrecedesBackfill(
            String migration) {
        String normalized = normalize(migration);
        String xorCondition = "messagetype = 'ai' and ((memorymessage is null "
                + "and memoryoutcome is not null) or (memorymessage is not null "
                + "and memoryoutcome is null))";
        Matcher guard = Pattern.compile(
                        "select count\\(\\*\\) into v_partial_ai_rows "
                                + "from chat_history where "
                                + Pattern.quote(xorCondition)
                                + "; if v_partial_ai_rows <> 0 then "
                                + "signal sqlstate '45000' set message_text = "
                                + "'ai 记忆投影存在半状态，请先受控修复'; end if;")
                .matcher(normalized);
        assertTrue(guard.find(),
                "迁移必须用完整 XOR guard 阻断 AI 投影半状态");
        assertTrue(Pattern.compile(
                        "declare exit handler for sqlexception begin "
                                + "rollback; resignal; end;")
                        .matcher(normalized).find(),
                "XOR SIGNAL 必须由异常 handler 回滚并重新抛出");

        int userCleanupPosition = normalized.indexOf(
                "update chat_history set memorymessage = null");
        int knownFailureBackfillPosition = normalized.indexOf(
                "update chat_history set memorymessage = "
                        + "'本轮发生工具协议异常");
        int vueBackfillPosition = normalized.indexOf(
                "update chat_history as h join app as a");
        assertTrue(guard.end() < userCleanupPosition
                        && guard.end() < knownFailureBackfillPosition
                        && guard.end() < vueBackfillPosition,
                "XOR 验收必须发生在用户清理和 AI 回填之前");
        assertAiBackfillUsesDoubleNullGate(normalized,
                "a.codegentype = 'vue_project'", "旧 Vue");
        assertAiBackfillUsesDoubleNullGate(normalized,
                "a.codegentype in ('html', 'multi_file')", "旧简单模式");
    }

    private void assertAiBackfillUsesDoubleNullGate(
            String normalizedMigration, String branchCondition,
            String branchName) {
        Matcher update = Pattern.compile(
                        "update chat_history as h join app as a[^;]*"
                                + Pattern.quote(branchCondition)
                                + "[^;]*h.memorymessage is null "
                                + "and h.memoryoutcome is null;")
                .matcher(normalizedMigration);
        assertTrue(update.find(),
                branchName + " AI 回填必须保持双 NULL 幂等门");
    }

    private void assertColumn(Class<?> entityType, String fieldName)
            throws Exception {
        assertColumn(entityType, fieldName, LocalDateTime.class);
    }

    private void assertColumn(
            Class<?> entityType, String fieldName, Class<?> expectedType)
            throws Exception {
        Field field = entityType.getDeclaredField(fieldName);
        assertEquals(expectedType, field.getType());
        Column column = field.getAnnotation(Column.class);
        assertTrue(column != null, fieldName + " 必须显式声明 @Column");
        assertEquals(fieldName, column.value());
    }

    private void assertContainsColumn(
            String tableDefinition, String expected, String relativePath) {
        assertTrue(normalize(tableDefinition).contains(normalize(expected)),
                relativePath + " 缺少最终列定义: " + expected);
    }

    private String tableDefinition(String schema, String tableName) {
        Pattern pattern = Pattern.compile(
                "(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?`?"
                        + Pattern.quote(tableName)
                        + "`?\\s*\\((.*?)\\)\\s*comment");
        Matcher matcher = pattern.matcher(schema);
        assertTrue(matcher.find(), "找不到表定义: " + tableName);
        return matcher.group(1);
    }

    private int occurrences(String source, String target) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private String normalize(String source) {
        return source.toLowerCase(Locale.ROOT)
                .replace('`', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(projectRoot().resolve(relativePath),
                StandardCharsets.UTF_8);
    }

    private Path projectRoot() {
        Path candidate = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到包含 pom.xml 的项目根目录");
    }
}
