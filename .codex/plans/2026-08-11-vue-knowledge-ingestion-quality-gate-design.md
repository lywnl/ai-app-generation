# Vue 知识摄取质量门禁设计

## 1. 背景与问题

当前 Vue RAG 已具备生产摄取实现：

- `TemplateCatalog` 从 `embed_text/vue-project` 加载 18 个父文档并生成 23 个 `KnowledgeChunk`；
- `VueKnowledgeIngestor` 使用 `text-embedding-v4` 对 `searchText` 批量生成 1024 维稠密向量；
- 每个知识块按 `UUID.nameUUIDFromBytes(chunkId UTF-8)` 生成稳定 ID；
- PGVector 的 metadata 仅包含 `chunkId`、`documentId`、`documentKind`、`chunkKind`、`catalogVersion`；
- Dense 检索按当前 `catalogVersion + documentKind` 过滤。

目前缺少的是发布顺序第 2 步的可重复证据：没有一个入口能够只摄取 Vue，并在摄取后证明 `templates_vue` 中当前目录版本的 23 条物理数据与文件目录完全一致。现有 `TemplateIngestService` 会遍历 HTML、MULTI_FILE 和 Vue；现有真实检索门禁又假定正式表已经正确摄取，无法区分“检索质量差”和“摄取前置条件错误”。

## 2. 目标与非目标

### 2.1 目标

新增永久测试门禁 `VueKnowledgeIngestionQualityGateTest`，完成以下闭环：

1. 只有显式设置 `RAG_VUE_INGEST=true` 才访问 DashScope 和 PostgreSQL；
2. 只加载并摄取 `embed_text/vue-project`，不处理 HTML 或 MULTI_FILE；
3. 复用生产 `VueKnowledgeIngestor` 和项目现有 `text-embedding-v4` 配置；
4. 允许 PGVector 在首次摄取时创建 `templates_vue`；
5. 摄取完成后直接查询 PostgreSQL，严格核验当前目录版本的 23 条物理数据；
6. 输出 UTF-8 Markdown 报告到 `target/rag-eval/vue-ingestion-report.md`；
7. 真实检索门禁在评测前复用只读核验逻辑，拒绝未摄取或目录版本不一致的数据。

### 2.2 非目标

- 不修改 `VueKnowledgeIngestor` 的生产摄取语义；
- 不启动完整 Spring Boot 应用，不触发无关外部依赖；
- 不摄取、删除或迁移 HTML、MULTI_FILE 数据；
- 不自动删除 Vue 历史 `catalogVersion` 数据；
- 不创建 HNSW 索引，不调整 PGVector 检索参数；
- 不使用本地假 Embedding 代替正式 `text-embedding-v4`；
- 不把 API Key、数据库密码、源码或完整向量写入日志和报告。

## 3. 方案比较与决策

### 3.1 方案一：独立 JUnit 质量门禁（采用）

门禁直接组装生产摄取器与 PGVector 存储；摄取完成后使用 JDBC 做物理核验。默认测试只生成“未执行”报告，高成本路径必须由环境变量显式开启。

优点：边界清晰、能纳入 Maven、可复用现有评测约定、不会启动整套应用。JDBC 能检查行数、维度和 metadata 键集合，这些属性无法通过相似度检索可靠证明。

代价：测试侧需要一个小型 PostgreSQL 核验器，并了解 LangChain4j PGVector 的表结构。

### 3.2 方案二：把物理核验加入生产摄取器（不采用）

优点是调用入口少；缺点是 `VueKnowledgeIngestor` 将从通用 `EmbeddingStore` 抽象泄漏到 PostgreSQL 表结构，生产逻辑和发布审计职责耦合，测试也更困难。

### 3.3 方案三：Spring Boot 运维命令（不采用）

优点是可复用 Spring Bean；缺点是会启动数据库、Redis、模型代理等无关组件，环境门槛和误操作面都大于独立门禁。

## 4. 架构与组件

摄取环境、快照、JDBC 核验和报告代码放在 `src/test/java/com/lyw/appgeneration/rag/ingest/`，生产目录不新增运维类。真实检索门禁允许在既有 `rag/vue` 测试包新增一个 package-private 顺序编排器，只负责保证“摄取核验通过后才创建模型和执行评测”，不得复制检索业务逻辑。

### 4.1 `VueIngestionEnvironment`

负责无秘密的前置检查和环境解析：

- 必须满足 `RAG_VUE_INGEST=true`；
- 必须存在非空 `DASHSCOPE_API_KEY` 和 `SPRING_DATASOURCE_PASSWORD`；
- PGVector 主机与端口必须可达；
- 数据库连接参数沿用真实检索门禁约定：
  - `RAG_PGVECTOR_HOST`，默认 `127.0.0.1`；
  - `RAG_PGVECTOR_PORT`，默认 `5432`；
  - `RAG_PGVECTOR_DATABASE`，默认 `ai_codegen_rag`；
  - `RAG_PGVECTOR_USER`，默认 `admin`；
  - 密码读取 `SPRING_DATASOURCE_PASSWORD`。

环境对象和错误原因只保存变量名、主机、端口等非秘密信息，不保存 API Key 或密码。开关未启用或凭据缺失时不探测网络。

### 4.2 `VueIngestionExpectedSnapshot`

由当前 `TemplateCatalog` 建立不可变期望快照：

- `catalogVersion`；
- 期望块数，当前固定为 23；
- 每个 `chunkId` 对应的稳定 UUID；
- `documentId`、`documentKind`、`chunkKind`；
- `searchText`；
- 固定向量维度 1024；
- 固定 metadata 键集合五项。

快照是目录与数据库之间唯一的比对基准，不从摄取返回值反推期望结果，避免“写错和验错使用同一份错误结果”。

### 4.3 `VuePgVectorIngestionVerifier`

使用 PostgreSQL JDBC 对 `templates_vue` 做只读核验。表名是代码内固定常量，不接受外部输入，避免 SQL 标识符注入。核验分两步：

1. 检查表和必要列存在；
2. 读取当前 `catalogVersion` 的全部行并与期望快照逐项比较。

已通过项目当前 `langchain4j-pgvector 1.1.0-beta7` 对本地 PGVector 实际建表确认列协议为：`embedding_id uuid`、`embedding vector`、`text text`、`metadata json`。实现必须使用这些实测列名，不能假定存在通用 `id` 列。

每条当前版本数据必须满足：

- 总行数恰好为 23；
- `embedding_id` 与 `chunkId` 计算出的稳定 UUID 相同；
- 23 个期望 `chunkId` 全部存在且没有额外块；
- metadata 键集合严格等于五项，不多不少；
- 五个 metadata 值与目录快照一致；
- PGVector `vector_dims(embedding)` 等于 1024；
- 文本列等于对应 `KnowledgeChunk.searchText`，从而证明写入的是检索短文本而不是源码。

核验器还统计表内非当前 `catalogVersion` 的历史行数并写入报告，但历史行不导致失败，也不被自动删除。Dense 查询仍依赖当前目录版本过滤，历史数据保留策略由后续独立迁移决定。

核验器返回结构化 `VueIngestionVerification`，包含状态、目录版本、期望数、实际数、历史数、维度分布和脱敏问题列表。任何连接、表结构或数据不一致都产生明确失败结果。

### 4.4 `VueIngestionReport`

将三种状态渲染为 Markdown：

- `未执行`：显式开关或前置环境不满足；
- `通过`：真实摄取和全部物理核验均通过；
- `失败`：模型、数据库、摄取或核验出现异常。

报告包含执行时间、目标数据库的非秘密地址、表名、模型名、维度、目录版本及统计结果。不包含凭据、源码、`searchText`、向量内容或原始异常请求体。

### 4.5 `VueKnowledgeIngestionQualityGateTest`

高成本主流程如下：

```text
检查 RAG_VUE_INGEST 与运行环境
  ├─ 不满足 → 写“未执行”报告并正常返回
  └─ 满足
       → 加载当前 TemplateCatalog 和期望快照
       → 创建 text-embedding-v4 EmbeddingModel
       → 以 createTable=true 连接 templates_vue
       → 调用生产 VueKnowledgeIngestor.ingest(...)
       → JDBC 只读核验当前 catalogVersion 的 23 行
       → 写报告
       → 核验失败则令测试失败
```

“未执行”不等于发布通过。它只保证默认 Maven 不依赖真实模型与数据库；发布检查必须读取报告状态，并要求为“通过”。

## 5. 与真实检索门禁的衔接

`VueRetrievalQualityGateTest` 在创建检索服务和发出任何模型请求前：

1. 从同一 `TemplateCatalog` 生成期望快照；
2. 调用 `VuePgVectorIngestionVerifier`；
3. 只有核验通过才运行 30 条 Hybrid/Dense 评测；
4. 核验失败时写入真实检索报告并硬失败，问题原因明确标为“摄取前置条件不满足”。

该衔接避免空表、旧目录版本、错误维度或不完整摄取被误诊为召回算法质量问题。检索门禁使用 `createTable=false`，因此不会把“表不存在”悄悄修复为空表。

## 6. 错误处理与安全边界

- Embedding 返回数量异常继续由生产 `VueKnowledgeIngestor` 抛错；
- PGVector 首次建表、批量写入、JDBC 核验任一步异常都会写失败报告并使显式门禁失败；
- 默认未开启门禁时不访问网络，不创建表，不调用模型；
- 所有文件以 UTF-8 写入；
- 异常写入报告前只保留不含凭据的异常类型和受控中文描述；
- 不输出 HTTP 请求、模型响应、JDBC URL 中的密码、环境变量值或原始向量；
- 本机执行 PGVector 评测时继续使用 JVM 参数 `-DsocksNonProxyHosts=localhost|127.*|[::1]`，防止全局 SOCKS 代理接管回环连接。

## 7. 测试策略

严格采用 TDD：

1. `VueIngestionEnvironmentTest`
   - 默认关闭且不探测网络；
   - 缺少变量时列出变量名且不泄漏变量值；
   - 端口不可达时返回脱敏原因；
   - 合法环境能生成非秘密连接配置。
2. `VueIngestionExpectedSnapshotTest`
   - 当前目录得到 23 个块和稳定 UUID；
   - metadata 五项与目录模型一致；
   - 快照拒绝非 23 条目录和重复稳定 ID。
3. `VuePgVectorIngestionVerifierTest`
   - 使用测试数据库夹具或可控 JDBC 边界验证正确数据通过；
   - 缺表、缺行、额外当前版本行、错误 UUID、错误 metadata 键/值、错误维度、错误文本分别失败；
   - 历史版本只统计不失败；
   - 数据库异常不泄漏密码。
4. `VueIngestionReportTest`
   - 三种状态和统计正确渲染；
   - 报告不包含测试秘密、源码或向量内容。
5. `VueKnowledgeIngestionQualityGateTest`
   - 默认路径写“未执行”报告且不访问外部依赖；
   - 显式路径复用生产摄取器并对失败执行硬门禁。
6. `VueRetrievalQualityGateTest`
   - 真实检索前必须通过当前目录物理核验；
   - 摄取前置条件错误时不调用 Embedding/Rerank。

完成定向测试后运行完整 `mvn test`，确保默认 279 项基线及新增测试全部通过。拥有真实 `DASHSCOPE_API_KEY` 后，按以下顺序执行外部门禁：

```bash
RAG_VUE_INGEST=true \
DASHSCOPE_API_KEY='...' \
SPRING_DATASOURCE_PASSWORD='...' \
JAVA_TOOL_OPTIONS='-DsocksNonProxyHosts=localhost|127.*|[::1]' \
bash mvnw -Dtest=VueKnowledgeIngestionQualityGateTest test

RAG_EVAL=true \
DASHSCOPE_API_KEY='...' \
SPRING_DATASOURCE_PASSWORD='...' \
JAVA_TOOL_OPTIONS='-DsocksNonProxyHosts=localhost|127.*|[::1]' \
bash mvnw -Dtest=VueRetrievalQualityGateTest test
```

命令示例中的省略号只表示由操作者注入真实环境变量，不会写入仓库、测试输出或报告。

## 8. 验收标准

实现只有同时满足下列条件才算完成：

- 默认 Maven 不访问 DashScope/PGVector，且全量测试通过；
- `RAG_VUE_INGEST=true` 时只摄取 Vue；
- 正式报告证明当前 `catalogVersion` 恰好 23 行；
- 23 个稳定 UUID、五项 metadata、1024 维向量和 `searchText` 全部与目录一致；
- 报告不泄漏任何秘密、源码或向量；
- 真实检索门禁拒绝不完整或过期的正式摄取结果；
- 所有 Git 提交信息为中文，且不推送远程。

当前环境缺少 `DASHSCOPE_API_KEY`，因此可以完成代码、默认测试、PGVector 核验器测试和报告未执行路径，但不能将真实摄取报告标记为“通过”，也不能据此宣称发布门禁已经完成。
