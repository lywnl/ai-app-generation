package com.lyw.appgeneration.sql;

import com.lyw.appgeneration.model.entity.AppMemoryExtractCursor;
import com.lyw.appgeneration.model.entity.AppMemorySummary;
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

    @Test
    void retryTimeEntitiesUseExplicitLocalDateTimeColumns() throws Exception {
        assertColumn(AppMemorySummary.class, "nextRetryTime");
        assertColumn(AppMemoryExtractCursor.class, "nextRetryTime");
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

    private void assertColumn(Class<?> entityType, String fieldName)
            throws Exception {
        Field field = entityType.getDeclaredField(fieldName);
        assertEquals(LocalDateTime.class, field.getType());
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
                "(?is)create\\s+table\\s+if\\s+not\\s+exists\\s+`?"
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
