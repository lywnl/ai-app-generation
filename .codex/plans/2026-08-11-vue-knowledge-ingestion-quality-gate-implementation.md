# Vue 知识摄取质量门禁实施计划

> **供智能代理执行：** 必须使用 `executing-plans` 逐任务实施；每个任务遵循 `test-driven-development` 的 RED → GREEN → REFACTOR，完成后使用 `requesting-code-review`，最终声明前使用 `verification-before-completion`。步骤使用复选框跟踪。

**目标：** 建立一个只摄取 Vue 的永久真实门禁，证明当前目录版本的 23 个 `text-embedding-v4` 向量在 `templates_vue` 中具有稳定 UUID、严格五项 metadata、1024 维度和正确检索短文本，并让真实检索评测拒绝不完整或过期摄取。

**架构：** 所有新增门禁组件位于测试源码，不改变生产摄取语义。目录快照独立计算期望值；PGVector 适配器通过 JDBC 读取实测表协议 `embedding_id/embedding/text/metadata`；纯比对器输出结构化核验结果；摄取和检索两个高成本入口复用同一核验链。

**技术栈：** Java 25、JUnit 5、Jackson、LangChain4j 1.1.0/PGVector 1.1.0-beta7、PostgreSQL JDBC、PGVector 0.8.6、Maven Wrapper。

## 全局约束

- 仅处理 `embed_text/vue-project`，不摄取或修改 HTML、MULTI_FILE。
- 正式模型固定为 DashScope `text-embedding-v4`，向量维度固定为 1024。
- 当前目录必须是 18 个父文档、23 个 `KnowledgeChunk`；物理核验以 23 条当前版本数据为硬门槛。
- metadata 键集合严格为 `chunkId`、`documentId`、`documentKind`、`chunkKind`、`catalogVersion`。
- 不自动删除历史目录版本；只统计其行数。
- 默认 Maven 不访问 DashScope 或 PGVector；真实摄取必须显式设置 `RAG_VUE_INGEST=true`。
- 报告、注释、测试名、任务清单和 Git 提交使用简体中文，文件编码为 UTF-8。
- 报告不得包含 API Key、密码、源码、检索短文本或向量内容。
- 使用项目 JDK 25：`.codex/runtime/jdk25/Contents/Home`。
- 本地 PGVector 命令增加 `-DsocksNonProxyHosts=localhost|127.*|[::1]`。
- 不修改 master，不推送远程，不覆盖用户改动。

---

## 文件结构

摄取门禁新增文件位于 `src/test/java/com/lyw/appgeneration/rag/ingest/`：

- `VuePgVectorTarget.java`：保存非秘密 PGVector 地址并解析环境默认值。
- `VueIngestionEnvironment.java`：执行显式开关、凭据存在性和端口可达性检查；对象不保存秘密。
- `VueIngestionExpectedSnapshot.java`：从 `TemplateCatalog` 独立生成当前目录的期望稳定 ID 和五项 metadata。
- `VuePgVectorRow.java`：承载 JDBC 读出的单条物理数据，不进入报告。
- `VueIngestionVerification.java`：承载脱敏核验结果和统计。
- `VuePgVectorIngestionVerifier.java`：检查表协议、读取当前/历史行并执行纯内存逐项比对。
- `VueIngestionReport.java`：渲染未执行、失败、通过三类 UTF-8 Markdown 报告。
- `VueKnowledgeIngestionQualityGateTest.java`：显式真实摄取入口。

修改文件：

- `src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateTest.java`：在创建模型/检索服务前执行物理核验。
- `src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateRunner.java`：只编排“摄取核验通过后才执行检索评测”的调用顺序。
- 既有 `VueRetrievalEvaluationReport.notExecuted(List<String>)` 已能表达“摄取前置失败”，无需修改报告类。
- `.codex/sdd/progress.md`：记录真实/默认门禁证据和仍缺失的外部条件。
- `.codex/sdd/whole-branch-review.md`：更新分支审查结论。

---

### 任务 1：建立无秘密运行环境模型

**文件：**

- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorTarget.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironment.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironmentTest.java`

**接口：**

- 产出：`VuePgVectorTarget.from(Map<String,String>)`、`jdbcUrl()`、`displayName()`。
- 产出：`VueIngestionEnvironment.inspectSystemEnvironment()`、包内 `inspect(Map, PortProbe)`。
- 约束：两个对象均不持有 `DASHSCOPE_API_KEY` 或 `SPRING_DATASOURCE_PASSWORD` 的值。

- [ ] **步骤 1：先写环境门禁失败测试**

```java
@Test
void 默认关闭或缺少凭据时不探测网络且不泄漏秘密() {
    CountingPortProbe disabledProbe = new CountingPortProbe(true);
    VueIngestionEnvironment disabled = VueIngestionEnvironment.inspect(Map.of(), disabledProbe);
    CountingPortProbe missingProbe = new CountingPortProbe(true);
    VueIngestionEnvironment missing = VueIngestionEnvironment.inspect(Map.of(
            "RAG_VUE_INGEST", "true",
            "DASHSCOPE_API_KEY", "dashscope-secret"), missingProbe);

    assertFalse(disabled.ready());
    assertTrue(disabled.reasons().contains("RAG_VUE_INGEST 未设置为 true"));
    assertFalse(missing.ready());
    assertTrue(missing.reasons().contains("缺少环境变量 SPRING_DATASOURCE_PASSWORD"));
    assertEquals(0, disabledProbe.calls);
    assertEquals(0, missingProbe.calls);
    assertFalse(disabled.toString().contains("dashscope-secret"));
    assertFalse(missing.toString().contains("dashscope-secret"));
}

@Test
void 凭据存在后检查端口并解析非秘密目标() {
    Map<String, String> environment = Map.of(
            "RAG_VUE_INGEST", "true",
            "DASHSCOPE_API_KEY", "dashscope-secret",
            "SPRING_DATASOURCE_PASSWORD", "database-secret",
            "RAG_PGVECTOR_HOST", "db.internal",
            "RAG_PGVECTOR_PORT", "15432",
            "RAG_PGVECTOR_DATABASE", "rag_test",
            "RAG_PGVECTOR_USER", "rag_user");

    VueIngestionEnvironment result = VueIngestionEnvironment.inspect(
            environment, (host, port) -> true);

    assertTrue(result.ready());
    assertEquals("jdbc:postgresql://db.internal:15432/rag_test", result.target().jdbcUrl());
    assertEquals("db.internal:15432/rag_test", result.target().displayName());
    assertEquals("rag_user", result.target().user());
    assertFalse(result.toString().contains("dashscope-secret"));
    assertFalse(result.toString().contains("database-secret"));
}
```

- [ ] **步骤 2：运行测试并确认因类型不存在而 RED**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest=VueIngestionEnvironmentTest test
```

预期：编译失败，提示 `VueIngestionEnvironment` 或 `VuePgVectorTarget` 不存在；失败原因只能是待实现类型缺失。

- [ ] **步骤 3：实现最小环境模型**

```java
public record VuePgVectorTarget(String host, int port, String database, String user) {
    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 5432;

    public static VuePgVectorTarget from(Map<String, String> environment) {
        return new VuePgVectorTarget(
                valueOrDefault(environment.get("RAG_PGVECTOR_HOST"), DEFAULT_HOST),
                validPortOrDefault(environment.get("RAG_PGVECTOR_PORT")),
                valueOrDefault(environment.get("RAG_PGVECTOR_DATABASE"), "ai_codegen_rag"),
                valueOrDefault(environment.get("RAG_PGVECTOR_USER"), "admin"));
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(host, port, database);
    }

    public String displayName() {
        return "%s:%d/%s".formatted(host, port, database);
    }
}
```

```java
public record VueIngestionEnvironment(
        boolean ready,
        List<String> reasons,
        VuePgVectorTarget target) {

    public VueIngestionEnvironment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static VueIngestionEnvironment inspectSystemEnvironment() {
        return inspect(System.getenv(), new SocketPortProbe(Duration.ofSeconds(1)));
    }

    static VueIngestionEnvironment inspect(Map<String, String> environment, PortProbe probe) {
        VuePgVectorTarget target = VuePgVectorTarget.from(environment);
        if (!"true".equalsIgnoreCase(environment.get("RAG_VUE_INGEST"))) {
            return new VueIngestionEnvironment(
                    false, List.of("RAG_VUE_INGEST 未设置为 true"), target);
        }
        List<String> reasons = new ArrayList<>();
        require(environment, "DASHSCOPE_API_KEY", reasons);
        require(environment, "SPRING_DATASOURCE_PASSWORD", reasons);
        if (reasons.isEmpty() && !probe.isReachable(target.host(), target.port())) {
            reasons.add("PGVector 端口不可达: " + target.host() + ":" + target.port());
        }
        return new VueIngestionEnvironment(reasons.isEmpty(), reasons, target);
    }
}
```

辅助方法必须使用端口范围 `1..65535`，非法端口回退 5432；`SocketPortProbe` 使用 1 秒连接超时和 try-with-resources。

- [ ] **步骤 4：运行 GREEN 并检查格式**

运行步骤 2 的命令，预期测试全部通过；再运行：

```bash
git diff --check
```

预期：无输出，退出码 0。

- [ ] **步骤 5：中文提交**

```bash
git add src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorTarget.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironment.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironmentTest.java
git commit -m "测试: 建立Vue摄取无秘密环境门禁"
```

---

### 任务 2：建立独立目录期望快照

**文件：**

- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionExpectedSnapshot.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionExpectedSnapshotTest.java`

**接口：**

- 消费：`TemplateCatalog#getCatalogVersion()` 与 `getChunks()`。
- 产出：`VueIngestionExpectedSnapshot.from(TemplateCatalog)`。
- 产出：`ExpectedRow`，字段为 `embeddingId/chunkId/documentId/documentKind/chunkKind/searchText`。
- 包内测试入口：`from(String catalogVersion, List<KnowledgeChunk> chunks, int expectedCount)`。

- [ ] **步骤 1：先写当前目录和非法快照测试**

```java
@Test
void 当前目录生成二十三条稳定期望数据() {
    TemplateCatalog catalog = new TemplateCatalog(
            Path.of("embed_text/vue-project"), new ObjectMapper());

    VueIngestionExpectedSnapshot snapshot = VueIngestionExpectedSnapshot.from(catalog);

    assertEquals(catalog.getCatalogVersion(), snapshot.catalogVersion());
    assertEquals(23, snapshot.rowsByChunkId().size());
    assertEquals(1024, snapshot.embeddingDimension());
    assertEquals(Set.of("chunkId", "documentId", "documentKind", "chunkKind", "catalogVersion"),
            snapshot.metadataKeys());
    snapshot.rowsByChunkId().forEach((chunkId, row) ->
            assertEquals(UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)),
                    row.embeddingId()));
}

@Test
void 拒绝非二十三条目录和重复块标识() {
    KnowledgeChunk chunk = new KnowledgeChunk(
            "duplicate", "doc", RagDocumentKind.FEATURE_SNIPPET,
            RagChunkKind.OVERVIEW, "检索文本");

    assertThrows(IllegalArgumentException.class, () ->
            VueIngestionExpectedSnapshot.from("version", List.of(chunk), 23));
    assertThrows(IllegalArgumentException.class, () ->
            VueIngestionExpectedSnapshot.from("version", List.of(chunk, chunk), 2));
}
```

- [ ] **步骤 2：运行测试并确认 RED**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest=VueIngestionExpectedSnapshotTest test
```

预期：编译失败，提示 `VueIngestionExpectedSnapshot` 不存在。

- [ ] **步骤 3：实现不可变期望快照**

```java
public record VueIngestionExpectedSnapshot(
        String catalogVersion,
        int embeddingDimension,
        Set<String> metadataKeys,
        Map<String, ExpectedRow> rowsByChunkId) {

    private static final int CURRENT_CHUNK_COUNT = 23;
    private static final int EMBEDDING_DIMENSION = 1024;
    private static final Set<String> METADATA_KEYS = Set.of(
            "chunkId", "documentId", "documentKind", "chunkKind", "catalogVersion");

    public static VueIngestionExpectedSnapshot from(TemplateCatalog catalog) {
        return from(catalog.getCatalogVersion(), catalog.getChunks(), CURRENT_CHUNK_COUNT);
    }

    static VueIngestionExpectedSnapshot from(
            String catalogVersion,
            List<KnowledgeChunk> chunks,
            int expectedCount) {
        if (catalogVersion == null || catalogVersion.isBlank()) {
            throw new IllegalArgumentException("Vue 目录版本为空");
        }
        if (chunks == null || chunks.size() != expectedCount) {
            throw new IllegalArgumentException(
                    "Vue 知识块数量必须为 %d，实际为 %d".formatted(
                            expectedCount, chunks == null ? 0 : chunks.size()));
        }
        Map<String, ExpectedRow> rows = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            ExpectedRow row = ExpectedRow.from(chunk);
            if (rows.putIfAbsent(chunk.chunkId(), row) != null) {
                throw new IllegalArgumentException("Vue 知识块 ID 重复: " + chunk.chunkId());
            }
        }
        if (rows.values().stream().map(ExpectedRow::embeddingId).distinct().count() != rows.size()) {
            throw new IllegalArgumentException("Vue 知识块稳定 UUID 重复");
        }
        return new VueIngestionExpectedSnapshot(
                catalogVersion, EMBEDDING_DIMENSION, METADATA_KEYS, rows);
    }
}
```

`ExpectedRow.from` 必须独立执行 `UUID.nameUUIDFromBytes(chunkId.getBytes(UTF_8))`，不得调用摄取结果反推期望；record 紧凑构造器使用 `Set.copyOf` 和 `Map.copyOf` 固化输入。

- [ ] **步骤 4：运行 GREEN 与目录回归**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest='VueIngestionExpectedSnapshotTest,VueTemplateDatasetTest' test
git diff --check
```

预期：两类测试全部通过，仍证明 18 个父文档和 23 个块。

- [ ] **步骤 5：中文提交**

```bash
git add src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionExpectedSnapshot.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionExpectedSnapshotTest.java
git commit -m "测试: 建立Vue摄取目录期望快照"
```

---

### 任务 3：实现 PGVector 物理数据核验

**文件：**

- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorRow.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionVerification.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorIngestionVerifier.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorIngestionVerifierTest.java`

**接口：**

- 消费：`VueIngestionExpectedSnapshot`、`VuePgVectorTarget`、数据库密码。
- 产出：`verify(snapshot, target, password)`，真实读取 PostgreSQL。
- 包内纯比对入口：`verifyRows(snapshot, List<VuePgVectorRow>, long historicalCount)`。
- 产出：`VueIngestionVerification(passed, catalogVersion, expectedCount, actualCount, historicalCount, dimensions, issues)`。

- [ ] **步骤 1：先写逐项比对失败测试**

用 23 个确定性 `KnowledgeChunk` 生成快照和正确物理行，再逐一替换字段：

```java
@Test
void 完全一致的当前版本通过且历史版本只统计() {
    VueIngestionExpectedSnapshot snapshot = snapshot();

    VueIngestionVerification result = VuePgVectorIngestionVerifier.verifyRows(
            snapshot, validRows(snapshot), 7);

    assertTrue(result.passed());
    assertEquals(23, result.actualCount());
    assertEquals(7, result.historicalCount());
    assertEquals(Set.of(1024), result.dimensions());
    assertTrue(result.issues().isEmpty());
}

@Test
void 缺行额外行错误标识元数据维度和文本均失败() {
    VueIngestionExpectedSnapshot snapshot = snapshot();
    List<VuePgVectorRow> validRows = validRows(snapshot);

    assertIssue(snapshot, validRows.subList(0, 22), "当前目录版本行数");
    assertIssue(snapshot, appendUnexpectedRow(validRows), "存在未声明块");
    assertIssue(snapshot, replaceFirst(validRows, row -> row.withEmbeddingId(UUID.randomUUID())),
            "稳定 UUID");
    assertIssue(snapshot, replaceFirst(validRows, row -> row.withoutMetadata("chunkKind")),
            "metadata 键集合");
    assertIssue(snapshot, replaceFirst(validRows, row -> row.withMetadata("documentId", "wrong")),
            "documentId");
    assertIssue(snapshot, replaceFirst(validRows, row -> row.withVectorDimension(768)),
            "向量维度");
    assertIssue(snapshot, replaceFirst(validRows, row -> row.withText("错误文本")),
            "检索文本");
}
```

测试辅助方法使用新建 record 构造替换行，不在生产核验类加入测试专用 mutation API。

- [ ] **步骤 2：运行测试并确认 RED**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest=VuePgVectorIngestionVerifierTest test
```

预期：编译失败，提示核验相关类型不存在。

- [ ] **步骤 3：实现纯比对器**

```java
static VueIngestionVerification verifyRows(
        VueIngestionExpectedSnapshot expected,
        List<VuePgVectorRow> rows,
        long historicalCount) {
    List<String> issues = new ArrayList<>();
    if (rows.size() != expected.rowsByChunkId().size()) {
        issues.add("当前目录版本行数不一致: 期望=%d,实际=%d".formatted(
                expected.rowsByChunkId().size(), rows.size()));
    }
    Map<String, VuePgVectorRow> actualByChunkId = indexRows(rows, issues);
    expected.rowsByChunkId().forEach((chunkId, expectedRow) -> {
        VuePgVectorRow actual = actualByChunkId.remove(chunkId);
        if (actual == null) {
            issues.add("缺少知识块: " + chunkId);
            return;
        }
        compareRow(expected, expectedRow, actual, issues);
    });
    actualByChunkId.keySet().forEach(chunkId ->
            issues.add("存在未声明块: " + chunkId));
    Set<Integer> dimensions = rows.stream()
            .map(VuePgVectorRow::vectorDimension)
            .collect(Collectors.toUnmodifiableSet());
    return new VueIngestionVerification(
            issues.isEmpty(), expected.catalogVersion(),
            expected.rowsByChunkId().size(), rows.size(), historicalCount,
            dimensions, issues);
}
```

`compareRow` 必须分别比较 `embedding_id`、严格 metadata 键集合、五个 metadata 值、1024 维度和 `text`；问题列表只包含 `chunkId` 与差异类型，不包含 `text/searchText` 内容。

- [ ] **步骤 4：实现 JDBC 读取适配器**

`verify(snapshot, target, password)` 使用 `DriverManager.getConnection(target.jdbcUrl(), target.user(), password)`，并按以下实测 SQL 协议读取：

```sql
SELECT embedding_id, vector_dims(embedding), text, metadata::text
FROM templates_vue
WHERE metadata->>'catalogVersion' = ?
ORDER BY embedding_id
```

历史统计 SQL：

```sql
SELECT count(*)
FROM templates_vue
WHERE COALESCE(metadata->>'catalogVersion', '') <> ?
```

读取前通过 `information_schema.columns` 核验：

```text
embedding_id -> data_type=uuid,         udt_name=uuid
embedding    -> data_type=USER-DEFINED, udt_name=vector
text         -> data_type=text,         udt_name=text
metadata     -> data_type=json,         udt_name=json
```

表名必须是私有常量 `templates_vue`，不得拼接任何环境输入。`ObjectMapper.readValue(metadataJson, new TypeReference<Map<String,String>>() {})` 解析 metadata。缺表、列协议不一致或 SQL 异常时返回失败 `VueIngestionVerification`，问题只包含固定中文类别和异常类简单名，不拼接数据库密码或原始异常消息。

- [ ] **步骤 5：运行 GREEN 与生产摄取单元回归**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest='VuePgVectorIngestionVerifierTest,VueKnowledgeIngestorTest' test
git diff --check
```

预期：全部通过；现有摄取器仍证明稳定 ID、五项 metadata 和源码不入库。

- [ ] **步骤 6：中文提交**

```bash
git add src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorRow.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionVerification.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorIngestionVerifier.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VuePgVectorIngestionVerifierTest.java
git commit -m "测试: 实现Vue向量物理数据核验"
```

---

### 任务 4：建立摄取报告和显式真实门禁

**文件：**

- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionReport.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionReportTest.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/ingest/VueKnowledgeIngestionQualityGateTest.java`

**接口：**

- 消费：任务 1～3 的环境、快照和核验器。
- 产出：`VueIngestionReport.notExecuted(...)`、`failed(...)`、`verified(...)`。
- 产出报告：`target/rag-eval/vue-ingestion-report.md`。

- [ ] **步骤 1：先写三态报告与脱敏测试**

```java
@Test
void 未执行失败和通过状态不会互相混淆() {
    String notExecuted = VueIngestionReport.notExecuted(
            "127.0.0.1:5432/ai_codegen_rag",
            List.of("缺少环境变量 DASHSCOPE_API_KEY")).renderMarkdown();
    String failed = VueIngestionReport.failed(
            "127.0.0.1:5432/ai_codegen_rag", "catalog", List.of("缺少表 templates_vue"))
            .renderMarkdown();
    String passed = VueIngestionReport.verified(
            "127.0.0.1:5432/ai_codegen_rag",
            verification(true, 23, 23, 4)).renderMarkdown();

    assertTrue(notExecuted.contains("状态：未执行"));
    assertTrue(failed.contains("状态：未通过"));
    assertTrue(passed.contains("状态：通过"));
    assertTrue(passed.contains("当前版本行数：23/23"));
    assertTrue(passed.contains("历史版本行数：4"));
}

@Test
void 报告不泄漏凭据源码检索文本或向量() {
    String markdown = VueIngestionReport.failed(
            "db.internal:5432/rag",
            "catalog",
            List.of("password=database-secret Authorization: Bearer token-secret"))
            .renderMarkdown();

    assertFalse(markdown.contains("database-secret"));
    assertFalse(markdown.contains("token-secret"));
    assertFalse(markdown.contains("<template>"));
    assertFalse(markdown.contains("[0.1,"));
}
```

- [ ] **步骤 2：运行测试并确认 RED**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest=VueIngestionReportTest test
```

预期：编译失败，提示 `VueIngestionReport` 不存在。

- [ ] **步骤 3：实现三态报告**

```java
public final class VueIngestionReport {
    private enum Status { NOT_EXECUTED, FAILED, VERIFIED }

    public boolean passed() {
        return status == Status.VERIFIED && verification != null && verification.passed();
    }

    public String renderMarkdown() {
        StringBuilder output = new StringBuilder("# Vue 知识摄取质量报告\n\n");
        output.append("状态：").append(switch (status) {
            case NOT_EXECUTED -> "未执行";
            case FAILED -> "未通过";
            case VERIFIED -> passed() ? "通过" : "未通过";
        }).append("\n\n");
        output.append("目标：").append(target).append("/templates_vue\n\n");
        output.append("模型：text-embedding-v4（1024 维）\n\n");
        appendVerification(output);
        appendReasons(output);
        return EvaluationReportSanitizer.sanitize(output.toString());
    }
}
```

报告只渲染版本、计数、维度集合和受控问题；不得持有或渲染物理行对象。

- [ ] **步骤 4：运行报告 GREEN**

运行步骤 2 的命令，预期全部通过。

- [ ] **步骤 5：先写默认真实门禁测试入口**

`VueKnowledgeIngestionQualityGateTest` 的单个测试方法必须先检查环境；未就绪时写未执行报告并返回。显式路径必须使用以下生产对象：

```java
@Test
void 环境显式就绪时摄取并核验真实Vue知识() throws Exception {
    VueIngestionEnvironment environment = VueIngestionEnvironment.inspectSystemEnvironment();
    if (!environment.ready()) {
        writeReport(VueIngestionReport.notExecuted(
                environment.target().displayName(), environment.reasons()));
        return;
    }

    Map<String, String> variables = System.getenv();
    TemplateCatalog catalog = new TemplateCatalog(DATASET_ROOT, OBJECT_MAPPER);
    VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
    try {
        EmbeddingModel model = createEmbeddingModel(variables.get("DASHSCOPE_API_KEY"));
        EmbeddingStore<TextSegment> store = createVueStore(
                environment.target(), variables.get("SPRING_DATASOURCE_PASSWORD"));
        VueKnowledgeIngestor.IngestResult result = new VueKnowledgeIngestor(model, OBJECT_MAPPER)
                .ingest(DATASET_ROOT, store);
        assertEquals(expected.catalogVersion(), result.catalogVersion());
        assertEquals(23, result.chunkCount());

        VueIngestionVerification verification = new VuePgVectorIngestionVerifier(OBJECT_MAPPER)
                .verify(expected, environment.target(), variables.get("SPRING_DATASOURCE_PASSWORD"));
        VueIngestionReport report = VueIngestionReport.verified(
                environment.target().displayName(), verification);
        writeReport(report);
        assertTrue(report.passed(), "Vue 真实摄取物理核验失败，详见 " + REPORT);
    } catch (Exception exception) {
        writeReport(VueIngestionReport.failed(
                environment.target().displayName(), expected.catalogVersion(),
                List.of("真实摄取依赖失败: " + exception.getClass().getSimpleName())));
        throw exception;
    }
}
```

`createEmbeddingModel` 必须与生产 `RagConfig` 一致：DashScope OpenAI 兼容地址、`text-embedding-v4`、1024 维、10 秒超时、请求/响应日志关闭。`createVueStore` 固定表 `templates_vue`、`createTable=true`、`useIndex=false`。

- [ ] **步骤 6：验证默认路径不访问外部系统**

```bash
env -u RAG_VUE_INGEST -u DASHSCOPE_API_KEY -u SPRING_DATASOURCE_PASSWORD \
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest='VueIngestionReportTest,VueKnowledgeIngestionQualityGateTest' test
```

预期：测试通过；`target/rag-eval/vue-ingestion-report.md` 包含 `状态：未执行` 和 `RAG_VUE_INGEST 未设置为 true`，不创建 `templates_vue`。

- [ ] **步骤 7：中文提交**

```bash
git add src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionReport.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionReportTest.java \
  src/test/java/com/lyw/appgeneration/rag/ingest/VueKnowledgeIngestionQualityGateTest.java
git commit -m "测试: 增加Vue知识真实摄取门禁"
```

---

### 任务 5：让真实检索评测强制依赖正确摄取

**文件：**

- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateTest.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateRunner.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalIngestionPrerequisiteTest.java`

**接口：**

- 消费：`VueIngestionExpectedSnapshot`、`VuePgVectorTarget`、`VuePgVectorIngestionVerifier`。
- 行为：只有 `verification.passed()` 才调用 `createEvaluationServices()`。
- 报告：前置失败使用未执行报告，列出受控原因，并使显式真实检索测试失败。

- [ ] **步骤 1：先写前置失败不创建模型服务的测试**

新建包内 `VueRetrievalQualityGateRunner`，只接受 `Supplier<VueIngestionVerification>` 与 `Supplier<VueRetrievalEvaluationReport>`，使测试能证明调用顺序而不暴露 `VueRetrievalQualityGateTest` 的私有资源 record：

```java
@Test
void 摄取核验失败时不创建模型或检索服务() {
    AtomicInteger serviceCreations = new AtomicInteger();
    VueIngestionVerification failed = new VueIngestionVerification(
            false, "catalog", 23, 0, 0, Set.of(), List.of("缺少表 templates_vue"));

    VueRetrievalEvaluationReport report = new VueRetrievalQualityGateRunner().evaluateWhenIngested(
            () -> failed,
            () -> {
                serviceCreations.incrementAndGet();
                throw new AssertionError("不得创建模型服务");
            });

    assertFalse(report.executed());
    assertEquals(0, serviceCreations.get());
    assertTrue(report.renderMarkdown().contains("摄取前置条件不满足"));
}

@Test
void 摄取核验通过后只执行一次检索评测() {
    AtomicInteger evaluations = new AtomicInteger();
    VueIngestionVerification passed = new VueIngestionVerification(
            true, "catalog", 23, 23, 2, Set.of(1024), List.of());
    VueRetrievalMetrics metrics = new VueRetrievalMetrics(
            1.0, 1.0, 1,
            Map.of("精确技术词", new VueRetrievalMetrics.StyleSlice(1.0, 1.0, 1)));
    VueRetrievalEvaluationReport expected = VueRetrievalEvaluationReport.executed(
            VueRetrievalComparison.compare(metrics, metrics), List.of(), List.of());

    VueRetrievalEvaluationReport actual = new VueRetrievalQualityGateRunner()
            .evaluateWhenIngested(() -> passed, () -> {
                evaluations.incrementAndGet();
                return expected;
            });

    assertSame(expected, actual);
    assertEquals(1, evaluations.get());
}
```

Runner 的最小实现固定为：

```java
final class VueRetrievalQualityGateRunner {

    VueRetrievalEvaluationReport evaluateWhenIngested(
            Supplier<VueIngestionVerification> verificationSupplier,
            Supplier<VueRetrievalEvaluationReport> evaluationSupplier) {
        VueIngestionVerification verification = verificationSupplier.get();
        if (!verification.passed()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("摄取前置条件不满足");
            reasons.addAll(verification.issues());
            return VueRetrievalEvaluationReport.notExecuted(reasons);
        }
        return evaluationSupplier.get();
    }
}
```

该类不得创建模型、连接数据库或复制检索算法。

- [ ] **步骤 2：运行测试并确认 RED**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest='VueRetrievalIngestionPrerequisiteTest,VueRetrievalEvaluationReportTest' test
```

预期：测试失败，因为当前真实检索入口没有摄取前置核验。

- [ ] **步骤 3：接入真实物理核验**

在 `VueRetrievalQualityGateTest` 的环境检查之后，通过 Runner 执行物理核验和原有评测：

```java
Map<String, String> variables = System.getenv();
VuePgVectorTarget target = VuePgVectorTarget.from(variables);
TemplateCatalog catalog = new TemplateCatalog(
        Path.of("embed_text/vue-project"), new ObjectMapper());
VueIngestionExpectedSnapshot expected = VueIngestionExpectedSnapshot.from(catalog);
VueRetrievalEvaluationReport report = new VueRetrievalQualityGateRunner().evaluateWhenIngested(
        () -> new VuePgVectorIngestionVerifier(new ObjectMapper()).verify(
                expected, target, variables.get("SPRING_DATASOURCE_PASSWORD")),
        () -> evaluateDataset(dataset));
writeReport(report);
if (!report.executed()) {
    fail("Vue 真实检索的摄取前置条件不满足，详见 " + REPORT);
}
if (!report.passed()) {
    fail("Vue 真实检索未达到质量门槛，详见 " + REPORT);
}
```

`evaluateDataset` 内部使用 try-with-resources 调用原有 `createEvaluationServices()` 和 `VueRetrievalEvaluator.evaluate(...)`。原有 30 条 Hybrid/Dense 评测保持不变；`createVueStore` 继续使用 `createTable=false`。前置失败报告不渲染伪造的 Hit@1、Recall@4 或 Dense 差值。

- [ ] **步骤 4：运行 GREEN 与默认门禁回归**

```bash
env -u RAG_EVAL -u DASHSCOPE_API_KEY -u SPRING_DATASOURCE_PASSWORD \
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest='VueRetrievalIngestionPrerequisiteTest,VueRetrievalEvaluationReportTest,VueRetrievalQualityGateTest' test
git diff --check
```

预期：所有测试通过；默认真实检索报告仍为“未执行”，且不访问数据库或模型。

- [ ] **步骤 5：中文提交**

```bash
git add src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateTest.java \
  src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateRunner.java \
  src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalIngestionPrerequisiteTest.java
git commit -m "测试: 增加Vue检索摄取前置门禁"
```

---

### 任务 6：全量验证、外部条件审计和分支文档

**文件：**

- 修改：`.codex/sdd/progress.md`
- 修改：`.codex/sdd/whole-branch-review.md`

**接口：** 无生产接口变化；本任务只生成最新验证证据和审计记录。

- [ ] **步骤 1：运行所有新增与直接相关测试**

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
bash mvnw -Dtest='VueIngestionEnvironmentTest,VueIngestionExpectedSnapshotTest,VuePgVectorIngestionVerifierTest,VueIngestionReportTest,VueKnowledgeIngestionQualityGateTest,VueRetrievalIngestionPrerequisiteTest,VueRetrievalEvaluationReportTest,VueRetrievalQualityGateTest,VueKnowledgeIngestorTest,VueTemplateDatasetTest' test
```

预期：0 failure、0 error；高成本入口在未设置开关时生成“未执行”报告。

- [ ] **步骤 2：使用本地 PGVector 做无模型协议核验**

在项目忽略目录使用既有健康容器 `ai-codegen-rag-eval-pg`。运行测试侧 JDBC 核验器的协议测试或一次性测试夹具，确认：

```text
表列：embedding_id uuid、embedding vector、text text、metadata json
vector_dims(embedding)：1024
metadata：五项 JSON 键可由 Jackson 读取
```

测试结束必须删除夹具表；不得向 `templates_vue` 写假向量，不得把协议探针当成正式摄取通过证据。

- [ ] **步骤 3：运行完整 Maven**

```bash
env -u RAG_VUE_INGEST -u RAG_EVAL -u RAG_BUILD_EVAL \
  -u DASHSCOPE_API_KEY -u DEEPSEEK_API_KEY \
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
MAVEN_OPTS='-DsocksNonProxyHosts=localhost|127.*|[::1]' \
bash mvnw test
```

预期：`BUILD SUCCESS`，0 failure、0 error；只允许项目既有显式外部门禁被跳过或以未执行报告返回。

- [ ] **步骤 4：审计真实模型条件并执行可执行门禁**

只检查环境变量是否已设置，不输出值：

```bash
for name in DASHSCOPE_API_KEY DEEPSEEK_API_KEY; do
  if [[ -n "${(P)name}" ]]; then
    print "$name=SET"
  else
    print "$name=UNSET"
  fi
done
```

若 `DASHSCOPE_API_KEY=SET`，按设计文档中的真实命令先运行 `VueKnowledgeIngestionQualityGateTest`，再运行 `VueRetrievalQualityGateTest`。若仍为 `UNSET`，报告必须明确：正式 23 条 `text-embedding-v4` 摄取和真实检索指标没有成绩，不能宣称发布通过。

若 `DEEPSEEK_API_KEY=SET` 且正式摄取、真实检索均通过，再运行 10 条 `VueGenerationBuildQualityGateTest`；否则保持 10/10 未取得成绩的事实。

- [ ] **步骤 5：更新进度和全分支审查文档**

在两个文件顶部新增本轮章节，逐条记录：

```text
- 新增门禁文件与显式开关；
- RED/GREEN 定向测试命令和测试数量；
- 完整 Maven 的最新数量、失败、错误、跳过和 BUILD 状态；
- PGVector 实测列协议；
- 正式摄取报告的真实状态；
- 真实检索与十条生成构建是否取得真实成绩；
- Git 提交 SHA；
- 未 push、未合并。
```

禁止把默认“未执行”报告写成真实门禁通过。

- [ ] **步骤 6：执行代码质量审查并修复发现**

使用 `requesting-code-review` 与 `code-review-and-quality` 检查：规格符合性、秘密泄漏、默认外部访问、SQL 标识符注入、JDBC 资源关闭、异常语义、报告状态和检索调用顺序。发现问题时先补失败测试，再修复并重跑任务 1 与任务 3 的验证命令。

- [ ] **步骤 7：最终中文提交**

```bash
git add .codex/sdd/progress.md .codex/sdd/whole-branch-review.md
git commit -m "文档: 记录Vue摄取门禁验证结果"
git status --short --branch
git log -8 --oneline
```

预期：工作树干净；分支仍为 `codex/vue-rag-hybrid-retrieval`；未 push。

---

## 最终完成审计

在宣称原始 Vue RAG 改造目标完成前，必须重新逐项核对 `.codex/plans/2026-08-10-vue-rag-hybrid-retrieval.md`：

1. 任务 1～7 的生产实现、单元测试和默认 Maven 有当前分支证据；
2. `templates_vue` 当前目录版本由真实 `text-embedding-v4` 摄取并通过 23 条物理核验；
3. 30 条真实检索满足 `Skeleton Hit@1 >= 0.90`、`Feature Recall@4 >= 0.85`、两项相对 Dense 退化均不超过 0.05；
4. 10 条固定需求首次生成后全部完成 `npm install` 与 `npm run build`；
5. 五个策展骨架真实构建为 5/5；
6. 完整 Maven 为 `BUILD SUCCESS`；
7. 所有中文提交均在本地分支，未 push。

只要第 2～4 项缺少真实模型执行证据，目标就仍是“代码可合并但不可发布”，不得缩小成功定义。
