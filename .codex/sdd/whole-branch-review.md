# Vue RAG 混合检索全分支最终审查

## 最终独立复审结论（2026-08-11，当前结论）

### 审查身份与范围

- 分支：`codex/vue-rag-hybrid-retrieval`；基线：`5850ef9f4ffb50d58839245c3cd4dfaf4bad67a8`；终点：`b05e7aa7d08f2490345ff4d225a8fe6ffc30783c`；共 60 个提交。
- 最终评审包：`.codex/sdd/review-5850ef9..b05e7aa.diff`，SHA-256：`3e4aa9fb824727f6075cd5a9ebf4841e727efa7dac5405bfc202b29e389587dc`。
- 最后四次生产修复分别为：`cfab860` 路径边界与依赖精确白名单、`3dc3b4d` 忽略依赖目录的安全遍历、`cdd06f7` 提示词与依赖版本契约对齐、`b05e7aa` 成功写盘后记账及规范状态键。

### Fresh 验证与报告真实性

- 文件工具、状态管理器、构建器及进程执行相关 fresh 回归：63/63，0 failure、0 error、0 skipped。
- Vue RAG/构建扩大定向历史门禁：231/231，0 failure、0 error、0 skipped；五骨架显式真实构建：6/6，0 failure、0 error、0 skipped，五个骨架均真实完成 npm 安装和可信 Vite 构建。
- 最终完整 Maven 使用项目 JDK 25.0.4，显式清空三个 RAG 门禁开关、五骨架开关、两个模型密钥和 PG 密码；432 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。
- 三份 `target/rag-eval/` 报告均由本轮完整 Maven 重写为“状态：未执行”：正式摄取因 `RAG_VUE_INGEST` 未开启、真实检索因 `RAG_EVAL` 未开启、十条生成因 `RAG_BUILD_EVAL` 未开启。报告没有真实成功指标，默认 Maven 成功不能替代外部门禁。

### 独立复审 verdict

- Spec Compliance：通过。
- Task quality：通过。
- Critical：0；Important：0；Minor：0。
- `Ready to merge`：Yes。上一轮发现的“真实文件 I/O 前提交状态”和“等价路径使用不同状态键”已由 `b05e7aa` 关闭；首次失败不创建空应用状态，同一 appId 的写入、重置和计数共享固定条带锁。
- 代码合并判断：可以合并。
- 发布判断：不可发布。正式 23 条 `text-embedding-v4` 摄取及 PGVector 物理核验、30 条真实 Hybrid/Dense 检索指标、十条首次真实生成 10/10 均未执行，生产开关必须保持 `false`。
- 外部门禁顺序不可调整：正式摄取并物理核验 → 30 条真实检索达标 → 十条首次生成 10/10。
- 路径安全遗留限制：标准 JVM `Path` API 无法可移植地提供 `openat` 式原子语义，因此无法完全消除本地高权限进程交换符号链接造成的 TOCTOU；当前实现覆盖模型可直接控制的绝对路径、父级逃逸和稳定符号链接边界。

## 终审修复验收（2026-08-11，历史阶段）

### 当前范围与修复状态

- 首轮全分支终审范围为 `5850ef9..2371204`。终审确认目录契约、专用 PG 密码、同轮前置、报告原子替换和生产开关主体成立，但发现 Critical=1、Important=2、Minor=3，因此结论为不可合并、不可发布。
- finding 已由 `7fa30d2` 统一修复并由 `7c602af` 记录证据；`6fca9e5` 只修复完整套件暴露的双 JVM 测试启动预算不足，没有修改生产代码。当前复审范围为 `5850ef9..6fca9e5`，完整包为 `.codex/sdd/review-5850ef9..6fca9e5.diff`。
- 安全根因修复位于进程创建和构建信任边界：子进程不继承秘密，模型脚本和项目 Vite/PostCSS 配置不执行，依赖解析输入 fail-closed。并发根因修复位于报告完整生命周期和生成目录领取边界：跨进程锁阻止报告交错，原子双目录占用阻止 appId TOCTOU。

### Fresh 验证证据

- 修复后定向命令使用项目 JDK 25，显式 unset 三个 RAG 开关、五骨架开关、两个模型密钥和 PG 密码，并设置回环直连参数；实际执行 213 项，0 failure、0 error、0 skipped，`BUILD SUCCESS`。原始 UTF-8 日志为 `.codex/sdd/vue-rag-findings-fix-targeted-2026-08-11.log`。
- 修复后显式五骨架门禁执行 6 项，0 failure、0 error、0 skipped，五个骨架均真实完成 npm 安装和可信 Vite 构建；原始日志为 `.codex/sdd/vue-rag-findings-fix-five-skeletons-2026-08-11.log`。
- 首次修复后完整 Maven 为 406 项中 1 failure，原因是进程树测试仅给双 JVM 200ms 启动预算；失败日志保留为 `.codex/sdd/vue-rag-findings-fix-full-maven-2026-08-11.log`。`6fca9e5` 将该用例启动预算改为 2 秒并保留 8 秒有界上限，五个独立 Maven 进程均 8/8。
- 最终 fresh 完整 Maven 实际执行 406 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。`AiAppGenerationApplicationTests` 使用 Java 25.0.4 启动 Spring 上下文并通过；7 项跳过均属于显式外部测试。原始 UTF-8 日志为 `.codex/sdd/vue-rag-findings-fix-full-maven-final-2026-08-11.log`。
- 三份 `target/rag-eval/` 报告修改时间均落在本轮完整 Maven 内：摄取、真实检索、十条生成均为“状态：未执行”，原因分别是对应显式开关未设置为 `true`。报告没有通过状态或真实质量指标，不能把默认 Maven 成功解释为外部门禁成功。

### 当前结论

- 代码验证：修复后 fresh 定向回归、五骨架真实构建和完整 Maven 均通过。
- 合并判断：等待 `5850ef9..6fca9e5` 修复后独立复审；在 Critical/Important 清零前不下结论。
- 发布判断：不可发布。正式 23 条 `text-embedding-v4` 摄取及物理核验、30 条真实 Hybrid/Dense 检索指标、十条首次真实生成 10/10 均没有本轮成绩，生产开关必须保持 `false`。

## Vue 知识摄取物理门禁（2026-08-11，本轮）

### 审查范围与门禁结构

- 本轮验证基线为 `016888e`。新增摄取门禁任务 1～5 的提交链为：任务 1 `aff0895`；任务 2 `e7cb551`、`afa530f`、`1b6b5ff`；任务 3 `6885591`、`fbf578e`、`d00c300`；任务 4 `86c0e1f`、`37b0c23`、`79e4a87`；任务 5 `3223701`、`016888e`。
- 任务 1～3 建立无秘密环境模型、可信 23 条目录快照与只读 JDBC 物理核验；任务 4 建立三态摄取报告和显式真实摄取入口；任务 5 让真实检索在构造模型服务前强制依赖同一 PGVector 目标的摄取核验。上述提交只改测试门禁与实施计划，没有新增生产接口。
- 正式入口、开关、报告一一对应：`VueKnowledgeIngestionQualityGateTest` / `RAG_VUE_INGEST=true` / `vue-ingestion-report.md`；`VueRetrievalQualityGateTest` / `RAG_EVAL=true` / `vue-hybrid-retrieval-report.md`；`VueGenerationBuildQualityGateTest` / `RAG_BUILD_EVAL=true` / `vue-generation-build-report.md`。
- 门禁依赖顺序不可交换：正式 `text-embedding-v4` 摄取并通过 23 条物理核验后才能运行 30 条真实检索；前两项通过且生成模型条件就绪后才能运行十条首次生成构建。

### 本轮实测证据

- 11 类定向测试使用项目 JDK 25，显式 unset 三个 RAG 门禁开关与两个模型变量，并设置 `MAVEN_OPTS='-DsocksNonProxyHosts=localhost|127.*|[::1]'`；精确结果为 51 项、0 failure、0 error、0 skipped，`BUILD SUCCESS`。摄取与检索报告均 fresh 写为“未执行”，且没有正式行数或检索指标。
- `ai-codegen-rag-eval-pg` 为 `running/healthy`，`vector 0.8.6`。无模型探针使用 PID 后缀的独立一次性夹具表，由 trap 和显式 `DROP TABLE` 清理；实测列二元类型为 `embedding_id data_type=uuid, udt_name=uuid`、`embedding data_type=USER-DEFINED, udt_name=vector`、`text data_type=text, udt_name=text`、`metadata data_type=json, udt_name=json`。实际向量为 1024 维，项目 Jackson 成功读取严格五个字符串 metadata 键。夹具表随后删除，`templates_vue` 未写入；该探针只证明无模型协议兼容。
- 完整 Maven 按简报命令 fresh 执行，项目 JDK 25、三个 RAG 门禁开关与两个模型变量均 unset；结果为 317 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。Spring `contextLoads` 实际启动并通过，跳过项均为既有显式外部测试。
- 完整 Maven fresh 生成的摄取、检索、生成三份报告均为“未执行”，分别由 `RAG_VUE_INGEST`、`RAG_EVAL`、`RAG_BUILD_EVAL` 未启用而短路；报告无 Hit@1、Recall@4、Dense 相对差值或 10/10 构建伪成绩。
- 环境变量只做存在性判断：`DASHSCOPE_API_KEY=UNSET`、`DEEPSEEK_API_KEY=UNSET`。本轮未读取变量值，未搜索工作区外凭据，也没有执行任何真实模型门禁。

### 五轴自审与原总计划完成度

- 正确性：可信快照固定 23 条、1024 维、稳定 UUID、检索文本和严格五键；真实检索先核验摄取，再创建模型及评测服务。报告的“未执行、未通过、通过”状态未混淆。
- 可读性与架构：环境、快照、物理核验、报告、入口、检索前置各自分层；生产实现无本轮变更，默认 Maven 不承担外部真实成绩。
- 安全：表名为代码内固定 `templates_vue`，目录版本使用 `PreparedStatement` 绑定；报告不输出密码、源码、向量或原始异常消息，本轮日志和文档也没有记录数据库密码。
- 资源与性能：JDBC Connection、Statement、ResultSet 使用受控关闭；默认路径在数据库、DashScope 与评测服务构造前短路。高成本真实模型、30 条检索、十条生成和五骨架 npm 均未无授权执行。
- 原总计划审计：第 1 项任务 1～7 生产实现、单元测试和默认 Maven 有当前分支证据；第 2 项正式 23 条摄取未完成；第 3 项 30 条真实检索指标未完成；第 4 项十条首次生成 10/10 未完成；第 5 项引用现有 2026-08-11 五骨架 5/5 真实构建证据，本轮不重跑高成本 npm；第 6 项本轮完整 Maven 完成；第 7 项中文提交均留在本地分支，本轮未 push、未合并。

该阶段历史结论：当时未发现 Critical、Important 或 Minor 代码问题并判断代码可以合并。该判断早于本轮五项终审修复，当前合并判断以文档顶部的新终审为准。发布结论始终为不可发布，因为正式 `templates_vue` 当前目录版本 23 条物理核验、30 条真实检索门槛和十条首次生成 10/10 均没有成绩；默认 `BUILD SUCCESS` 与独立协议探针不能替代外部真实通过。

## 五骨架真实构建门禁补强复核（2026-08-11）

- 完成度审计确认历史 5/5 构建日志存在，但受控测试只覆盖基础骨架，不能持续证明任务 2 的 5/5 要求。现已将该测试改为“默认校验五个固定来源 + 显式动态构建五个真实工程”。
- TDD 证据完整：RED 为 `expected 5 but was 1`；最终显式门禁执行 6 项、0 failure、0 error、0 skipped、`BUILD SUCCESS`，其中五项分别对应基础工程、管理后台、商城、内容门户和 ECharts 看板。
- 每个动态测试都读取真实模板 JSON、写入独立 `target/rag-eval/skeleton-build/<id>`、调用真实 `VueProjectBuilder` 完成 `npm install` 和 `npm run build`，并同时断言 `BuildResult.success()` 与 `dist` 目录存在。报告按骨架隔离，不会被后一个结果覆盖。
- 默认模式只门控高成本动态构建，五个来源的数量和固定文件集合始终执行。最终完整 Maven 为 279 项、0 failure、0 error、7 skipped，Spring `contextLoads` 实际启动，`BUILD SUCCESS`。
- 五轴复核：正确性、可读性、架构、安全和性能均未发现 Critical/Important/Minor 问题。改动只在测试层，不含密钥、mock、新依赖或生产行为变化；路径同时防御骨架 ID 与模板内嵌文件逃逸。

当前结论：代码、默认 Maven、PGVector 协议和 5/5 策展骨架真实构建均已取得可重复证据；发布状态仍不变。正式 `templates_vue` 尚未由 `text-embedding-v4` 摄取，真实检索指标与十条首次生成 10/10 仍因模型凭据缺失没有成绩。

## 真实外部门禁基础设施补验（2026-08-11）

- 临时 PGVector 已在项目忽略目录 `.codex/runtime/pgvector-data` 中运行，容器 `ai-codegen-rag-eval-pg` 为 `running/healthy`，`127.0.0.1:5432` 可达，数据库 `ai_codegen_rag` 已启用 `vector 0.8.6`。
- Java 协议不是阻断：使用项目 JDK 25、`langchain4j-pgvector 1.1.0-beta7` 和独立探针表实际完成 1024 维向量批量写入、相似检索、ID/文本/metadata 读回，SQL 侧结果为 1 条、1024 维、`documentId=probe-document`；验证后探针表已删除。
- 本机 JVM 的 SOCKS 代理会错误接管回环地址数据库连接。评测 JVM 增加 `-DsocksNonProxyHosts=localhost|127.*|[::1]` 后验证通过；这是本机运行参数问题，不是项目 PGVector 配置或代码缺陷。
- 正式 `templates_vue` 表仍不存在，因为正式摄取需要 `text-embedding-v4` 为当前 23 个 `KnowledgeChunk` 生成真实稠密向量。空表、探针向量或本地假 Embedding 都不能替代计划指定的真实摄取。
- 在数据库前置条件已满足的情况下，`VueRetrievalQualityGateTest` 与 `VueGenerationBuildQualityGateTest` 均以项目 JDK 25 重新执行，各 1/1、0 failure、0 error、0 skipped、`BUILD SUCCESS`。两项成功只证明门控和未执行报告正常，不代表真实门槛通过。
- 最新真实检索报告只剩 `DASHSCOPE_API_KEY` 缺失；最新真实生成构建报告只剩 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY` 缺失。前者阻断正式摄取、Dense、Rerank 与检索指标，后者额外阻断十条真实生成。

该阶段历史结论：默认 Maven 门禁、PGVector 基础设施和 Java 协议均已验证，当时判断代码可以合并；该合并判断已由文档顶部的新终审状态替代。真实 Skeleton Hit@1、Feature Recall@4、Dense 相对退化与 10/10 构建没有成绩，所以仍不可发布。取得两个模型凭据后，应先摄取并核对 `templates_vue` 的当前目录版本与 23 条可见数据，再顺序运行两个真实门禁。

## Maven 门禁最终收敛（2026-08-11，替代此前失败状态）

- 根因一：`JsonMessageStreamHandlerTest` 使用 `@InjectMocks`，但没有提供生产类新增的 `ToolMessageCollapser`。测试已补齐 mock，并用包含 `UserMessage`、`AiMessage` 的非空快照配合 `same(snapshot)`，验证自检后恢复的是折叠方法返回的同一对象。
- 根因二：多个旧 `@SpringBootTest` 混合了纯逻辑、真实模型/网页调用和完整生产外部依赖。纯解析测试已去除无用 Spring 上下文；真实模型、网页测试改为 `EXTERNAL_INTEGRATION_TESTS=true` 显式执行。
- `contextLoads()` 没有被跳过。测试层只替换 Mapper、Redis/Redisson、COS、模型、Embedding/向量库、Pexels/DashScope 图片工具和 LangChain4j 运行时代理等外部或数据访问边界；`AppService`、记忆 Service、RAG 编排、`RagRerankService`、`AiCodeGeneratorFacade` 等业务 Bean 保持真实装配。
- 测试代码没有提交真实或虚假凭据。`dashscope.api-key=` 是空测试配置，仅使真实 `RagRerankService` 构造不发请求的 `RestClient`；运行进程同时显式清空所有相关环境变量。
- 主代理最终定向验证：`AiAppGenerationApplicationTests,JsonMessageStreamHandlerTest` 共 3 项，0 failure、0 error、0 skipped；日志明确出现 `Started AiAppGenerationApplicationTests`。
- 主代理补强五骨架门禁后的最终完整验证：279 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。七项均是显式外部门控的真实模型、网页、旧 RAG 联网评测或真实 npm 构建测试。
- 该阶段独立复审：Spec Compliance 通过，Task quality 通过，Critical/Important/Minor 均为 0，当时 `Ready to merge` 为 `Yes`，所有当时的 finding 已关闭；当前合并判断以文档顶部的新终审状态为准。
- 对应提交：`427fb16`、`25b83cc`、`a5c09b8`。未修改 `src/main/**`，未推送远程。

该阶段历史结论：默认 Maven 门禁完成后，当时判断代码可以合并；该合并判断已由文档顶部的新终审状态替代。PGVector 基础设施后来已补齐并通过 Java 协议探针，真实 Vue Hybrid/Dense 检索指标与十条真实生成构建当前只因缺少模型凭据未执行，所以发布结论仍为不可发布。

## 最终审查 Important 修复（2026-08-10）

### RED 证据

- `AiCodeGeneratorFacadeTest`：JDK 25 执行 `bash mvnw -Dtest=AiCodeGeneratorFacadeTest test`，11 项中 3 项按预期失败、0 error、0 skipped。失败分别证明：Hybrid 关闭时没有新版生产 Dense-only 入口；`rag.enabled=false` 仍调用 Vue RAG；真实生成评测未检查总开关便获取生成服务。
- `RagRetrievalServiceTest`：执行 `bash mvnw -Dtest=RagRetrievalServiceTest test`，5 项中 2 项按预期失败、0 error、0 skipped，生产 Dense-only 入口尚不存在，总开关也无法覆盖该入口。
- `VueHybridRetrievalServiceTest`：执行 `bash mvnw -Dtest=VueHybridRetrievalServiceTest test`，26 项中 3 项按预期失败、0 error、0 skipped，均因生产 Dense-only 入口不存在，无法满足当前目录父文档回查与基础骨架兜底。
- 跨层测试受控缺陷 RED：临时令 Facade 的 Hybrid 关闭路由返回不可用上下文，执行 `VueKnowledgeIngestorTest#productionDenseOnlyIgnoresLegacyRowsAndAssemblesCurrentParentSource`，1 项中 1 failure、0 error、0 skipped；失败证明测试能捕获完整父文档源码未进入最终生成请求的旁路回归。受控缺陷随后恢复。

### 根因与生产修复

- 根因一不是 Facade 单点问题，而是缺少生产专用 Dense-only 语义。新增 `VueHybridRetrievalService.retrieveDenseOnly`：只调用 `DenseRetriever`，按当前 `catalogVersion + documentKind` 过滤新版短块 metadata，以 `documentId` 回查当前 `TemplateCatalog` 父文档，选择一个骨架和最多四个兼容功能片段；不调用 BM25、RRF、Rerank。Dense 无结果或失败时使用固定基础骨架，目录不可用时返回无 RAG；评测 `retrieveDenseOnlyForEvaluation` 保持无骨架兜底。
- `RagRetrievalService.retrieveVueProjectDenseOnly` 提供生产入口；Hybrid 与生产 Dense-only 两个入口均优先检查 `rag.enabled`，关闭时返回 `VueRagContext.unavailable()`，不下调 Vue 服务。
- `AiCodeGeneratorFacade` 先检查 RAG 总开关：关闭时 Vue 直接使用原始需求（首次仅执行图片增强）生成，不执行检索或 Vue Prompt 拼装；开启时按 `hybrid.enabled` 选择 Hybrid 或新版生产 Dense-only。两条检索均收到原始需求，图片增强只进入最终 generationRequest。真实 Vue 生成评测同时要求两个开关开启，失败发生在获取生成服务之前。
- HTML 与 MULTI_FILE 沿用原链。任务 7 报告中“Hybrid 关闭回退旧 Vue Dense/assemble”的解释已失效，以本节新版摄取兼容语义为准。

### GREEN 与回归证据

- 核心三类 GREEN：`bash mvnw -Dtest='AiCodeGeneratorFacadeTest,RagRetrievalServiceTest,VueHybridRetrievalServiceTest' test`，42/42，0 failure、0 error、0 skipped。
- 跨层真实存储：同一 `InMemoryEmbeddingStore` 预置旧 `id/title/category/code` schema 和旧 `catalogVersion` 新 schema 行，再由真实 `VueKnowledgeIngestor` 摄取当前 23 个知识块，串联真实 `DenseRetriever`、当前 `TemplateCatalog`、生产 `RagRetrievalService`、Hybrid 关闭的真实 Facade 路由与真实 `RagPromptAssembler`。旧行均不可见，当前父文档 `src/App.vue` 完整源码进入最终生成请求，BM25/RRF/Rerank 均未调用。恢复受控缺陷后 1/1 通过。
- 简报指定覆盖：JDK 25 执行 `bash mvnw -Dtest='AiCodeGeneratorFacadeTest,RagRetrievalServiceTest,VueHybridRetrievalServiceTest,DenseRetrieverTest,VueKnowledgeIngestorTest' test`，52/52，0 failure、0 error、0 skipped。追加自审用例还锁定 Dense-only 任一文档池空召回必须标记降级。
- 纯单元回归：JDK 25 执行仓库既有排除外部/Spring/真实构建门禁分类，并排除高成本 `VueSkeletonRealBuildTest`，265/265，0 failure、0 error、0 skipped。
- `git diff --check`：通过。

### 提交与遗留

- 提交 SHA：`65e1e6881aab8bccd4b3b42b5c2da15f71104d77`（`修复: 统一Vue RAG开关与新版回退链`）。
- 截至该提交的遗留：完整 Maven 当时仍有密钥注入与测试夹具问题；这部分已由 2026-08-11 的三个 Maven 门禁提交关闭。真实外部 Vue 检索评测和十条生成构建门禁仍未执行。

## 修复后独立复审结论

- 审查范围：`b2be84e1f2dcd3f0409a8724c7dc5dddf49e1680..65e1e6881aab8bccd4b3b42b5c2da15f71104d77`。
- Spec Compliance：通过。
- Task quality：通过。
- Critical：0；Important：0；Minor：0（本段已修正原报告状态冲突）。
- 原 Important-1 已关闭：Hybrid 关闭时使用新版生产 Dense-only、当前 `catalogVersion + documentKind` 和父文档回查，不再读取旧 Vue metadata 协议。
- 原 Important-2 已关闭：`rag.enabled` 在 Facade 与 Service 层优先于 Hybrid 开关，关闭时不执行任何 Vue RAG。
- 代码可以进入全范围验证；在该次复审时，外部真实检索、10 条真实生成构建和完整 Maven 门禁仍需单独审计。完整 Maven 后续已通过，两个外部门禁仍未执行。

## 2026-08-10 全范围验证历史结果（已由顶部新结果替代）

- 任务 1～8 目标回归：176 项，0 failure、0 error、1 skipped；跳过项是默认门控的真实骨架联网构建。
- 纯单元回归：266 项，0 failure、0 error、0 skipped。
- 当时的完整 `mvn test`：278 项，0 failure、10 error、2 skipped，`BUILD FAILURE`。该历史错误集合为：
  - 8 个 Spring 上下文测试因未配置 `DEEPSEEK_API_KEY` 失败；
  - 2 个 `JsonMessageStreamHandlerTest` 因既有测试夹具未注入 `ToolMessageCollapser` 失败。
- 显式 `RAG_EVAL=true`：门禁入口 1/1 通过，报告状态为“未执行”，缺少 `DASHSCOPE_API_KEY`、`SPRING_DATASOURCE_PASSWORD`；没有真实 Hit@1、Recall@4 或相对 Dense 指标。
- 显式 `RAG_BUILD_EVAL=true`：门禁入口 1/1 通过，报告状态为“未执行”，缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`、`SPRING_DATASOURCE_PASSWORD`；没有 10/10 真实生成构建成绩。
- `git diff --check`：通过。

当时结论：代码目标回归与纯单元回归通过，但完整 Maven 和两个真实外部门禁尚未达成。完整 Maven 已在 2026-08-11 关闭；两个真实外部门禁仍未达成。

## 修复后全分支最终复审

- 审查范围：`5850ef9f4ffb50d58839245c3cd4dfaf4bad67a8..034d01dcb3d025e6c52c78854cf12dc8ffa9f997`。
- Spec Compliance：通过。
- Task quality：通过。
- Critical：0；Important：0；Minor：0。
- 该阶段代码合并结论：可以合并；当前合并判断以文档顶部的新终审状态为准。
- 当时发布结论：不可发布。完整 Maven 的 10 个错误已在 2026-08-11 关闭；当前不可发布的剩余原因只有真实检索指标和 10/10 真实生成构建没有成绩。
- 本轮未合并、未推送、未删除分支或工作树。

## 历史审查记录（修复前，已被上述结论替代）

- 审查范围：`5850ef9f4ffb50d58839245c3cd4dfaf4bad67a8..b2be84e1f2dcd3f0409a8724c7dc5dddf49e1680`
- 审查性质：最终只读代码审查；未修改生产代码，未运行全量测试
- 历史总结：未发现 Critical；发现 2 项 Important；无 Minor。当时不应合并或发布；两项 Important 均已由 `65e1e68` 关闭。

以下 `Plan compliance`、`Issues` 和 `Assessment` 是 `b2be84e` 阶段的修复前审查明细，仅为保留审计链；其中两项 Important 已由 `65e1e68` 关闭，完整 Maven 已由 `a5c09b8` 关闭。当前结论以文档顶部为准。

## Plan compliance

### Achieved

- 任务 1～6 的主体实现已经落地：双层父文档/检索块、稳定 `catalogVersion`、稳定块 ID、PGVector 最小 metadata、Lucene BM25、Dense 双 metadata filter、父文档聚合、RRF、分池重排、兼容性筛选以及 Vue 专用 Prompt 双分区预算均有对应生产实现和定向测试。
- Hybrid 开启时的生产链顺序符合计划：原始 `userMessage` 进入 BM25、Dense、RRF 和 Rerank；图片增强结果只作为最终 `generationRequest`。
- HTML、MULTI_FILE 仍调用旧检索和旧拼装接口；分支没有引入 learned sparse、ONNX、Python 或 OpenSearch。
- `BuildResult`、并发消费进程输出、超时/进程树清理、旧 `dist` 清理、30 条检索数据集、10 条生成数据集及报告脱敏代码均已建立。
- 分支未提交新增的 `target/`、`node_modules/`、`dist/` 或运行时构建目录；`git diff --check` 通过。

### Partially achieved

- 历史 Important-1：任务 7 的 Hybrid 开启链已接通，但“关闭 Hybrid 回退旧 Dense”与任务 3 的新版 Vue 摄取 schema 跨任务不兼容；已由 `65e1e68` 关闭。
- 历史 Important-2：`rag.enabled` 被声明为 RAG 总开关，但 Vue Hybrid 分支没有消费它；已由 `65e1e68` 关闭。
- 任务 8 已建立真实门禁代码和数据，但门禁结果尚未取得：真实 Hybrid/Dense 检索没有执行，10 条真实生成构建没有执行。

### Needs fixes

- 历史两项 Important 和对应跨层测试均已由 `65e1e68` 关闭。
- 当前发布门禁尚未达成：计划要求的 `Skeleton Hit@1 >= 0.90`、`Feature Recall@4 >= 0.85`、相对 Dense 基线退化不超过 `0.05` 没有真实结果；10/10 真实生成的 `npm install` 与 `npm run build` 没有执行。完整 `mvn test` 已通过，不再是待修项。

## Strengths

- `TemplateCatalog` 由有序相对路径和 UTF-8 原文计算版本，目录、BM25 和 Dense 查询共享同一目录实例；Dense 又在存储层及返回数据层验证 `catalogVersion + documentKind`，旧版本不会进入 Hybrid 新链。
- BM25 使用应用生命周期资源，初始化失败可退 Dense，`@PreDestroy` 关闭 reader/analyzer/directory；BM25/Dense 均先聚合父文档。
- 固定参数与计划一致：单路 Top10、融合 Top15、RRF `k=60`、等权、骨架重排 Top3、片段重排 Top8、最终片段 Top4；质量分只在同分时参与排序。
- Vue Rerank 文本没有源码，响应数量、索引唯一性/边界和有限分数均被验证；仅 `RerankException` 触发 RRF 降级，开关关闭不会伪造精排指标。
- Prompt 对骨架和片段分别实行 4000/8000 字符预算，不截断文件中部；参考源码逐行引用并与指令隔离，用户生成需求完整置于末尾。
- 构建器对 install/build/dist 阶段、退出码、超时和 8000 字符日志尾部建模，并处理大输出、晚派生子进程和旧 `dist` 假阳性；评测报告未伪造成已执行成绩。

## Issues

### Critical

无。

### Important

#### Important-1（历史，已由 `65e1e68` 关闭）：Hybrid 关闭的旧 Dense 回退无法消费新版 Vue 摄取数据

- 位置：
  - `src/main/java/com/lyw/appgeneration/service/rag/ingest/VueKnowledgeIngestor.java:62`
  - `src/main/java/com/lyw/appgeneration/service/rag/ingest/VueKnowledgeIngestor.java:70`
  - `src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java:119`
  - `src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java:124`
  - `src/main/java/com/lyw/appgeneration/service/rag/RagRetrievalService.java:92`
  - `src/main/java/com/lyw/appgeneration/service/rag/RagRetrievalService.java:131`
  - `.codex/plans/2026-08-10-vue-rag-hybrid-retrieval.md:117`
- 触发路径：按计划先以 `rag.hybrid.enabled=false` 部署，再运行新版 Vue 摄取；随后用户请求 Vue 生成。Facade 因 Hybrid 关闭调用旧 `retrieve(..., VUE_PROJECT)` 和旧 `assemble(...)`。
- 根因：新版摄取只写 `chunkId`、`documentId`、`documentKind`、`chunkKind`、`catalogVersion` 五个 metadata，且源码只保留在父目录；旧 Dense 链却无任何 metadata filter，并仍读取 `id`、`title`、`category`、`code`。新版块会被转换成 ID/标题/源码均为空的旧 `RetrievedSnippet`。同时，新版稳定 chunk UUID 不会删除此前由旧 `store.add(...)` 生成的随机 ID 行，旧链又不按 `catalogVersion` 过滤，所以旧版及已删除模板仍可能被召回。
- 影响：计划中的“关闭 Hybrid → 摄取新版 → 验证 → 开启”灰度窗口并不安全；关闭开关回滚也不能可靠回到有效 Dense 上下文。结果可能是空参考代码、旧源码泄入 Prompt、已删除模板继续可见，且单任务 Mock 测试不会暴露这个 schema 断点。
- 修法：不要让 Hybrid 开关控制新旧 metadata schema。Vue 在新版摄取后即使关闭 BM25/RRF，也应走当前 `catalogVersion` 的新版 Dense 父文档链和 `assembleVueProject`；或者使用物理隔离的旧表并明确迁移/清理策略。不能通过把完整源码重新写回 PGVector metadata 来规避，否则违反任务 3 的源码隔离要求。
- 验收条件：用同一个测试存储先放入旧版/已删除行，再通过 `VueKnowledgeIngestor` 摄取当前目录；在 `rag.hybrid.enabled=false` 下走真实 Vue 生成增强入口，断言仅当前目录父文档可见、拼装上下文含有效父文档源码、无旧行或空 metadata 候选；随后切换开关时生成语义保持可用。

#### Important-2（历史，已由 `65e1e68` 关闭）：`rag.enabled` 总开关不能关闭 Vue Hybrid 检索

- 位置：
  - `src/main/java/com/lyw/appgeneration/config/RagProperties.java:17`
  - `src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java:119`
  - `src/main/java/com/lyw/appgeneration/service/rag/RagRetrievalService.java:49`
  - `src/main/java/com/lyw/appgeneration/service/rag/RagRetrievalService.java:70`
- 触发路径：配置 `rag.enabled=false`、`rag.hybrid.enabled=true` 后请求 Vue 生成，或直接调用 `retrieveVueProject`。
- 根因：旧 `retrieve` 在第 71 行检查 `props.isEnabled()`，但 Vue 分支只判断 `hybrid.enabled`；Vue 专用委托入口也无总开关 guard。
- 影响：运维认为已经关闭 RAG 时，Vue 仍会访问 Embedding、PGVector、Lucene 和 Rerank，并把 RAG 上下文加入生成请求。依赖故障、成本控制或安全事件时，总开关无法完成其声明的隔离语义；HTML/MULTI_FILE 与 Vue 的配置行为也不一致。
- 修法：集中定义 Vue RAG 启用条件为 `rag.enabled && rag.hybrid.enabled`；Facade 在总开关关闭时保留图片增强但不执行任何 RAG，Vue 专用检索入口也应防御性返回不可用上下文，避免其他调用方绕过 Facade。
- 验收条件：新增 `rag.enabled=false + hybrid.enabled=true` 的 Facade 和 `RagRetrievalService` 测试，断言不调用 `VueHybridRetrievalService`、Embedding、向量存储或 Rerank，最终生成只收到原始/图片增强后的用户需求；同时验证 `enabled=true + hybrid=true` 行为不变。

### Minor

无。

## Cross-task integration verdict

Hybrid 开启时，从目录/BM25/Dense 到 RRF、Rerank、兼容筛选、Prompt 和 Vue Agent 的主链总体一致；资源生命周期、固定参数、降级协议、Prompt 隔离和构建结果模型没有发现其他高确信跨任务断点。

历史判断中的部署/回滚链问题和总开关问题均已由 `65e1e68` 关闭。当前集成状态不满足发布条件的唯一原因是任务 8 的两个真实外部门禁尚未执行。

## Assessment

**该阶段 Ready to merge：Yes；当前合并判断以文档顶部的新终审状态为准。**

两项历史 Important、跨层回归和完整 Maven 均已关闭。发布前仍必须取得真实 30 条检索门槛及 10/10 真实生成构建成绩。
