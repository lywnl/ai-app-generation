# Vue RAG 混合检索执行进度

## Vue 知识摄取物理门禁（2026-08-11，本轮）

### 任务 1～5 提交范围与门禁

- 任务 1（`aff0895`）：新增 `VuePgVectorTarget`、`VueIngestionEnvironment` 及环境测试；只在 `RAG_VUE_INGEST=true` 且模型、数据库环境变量存在时探测数据库端口，不保存或输出秘密。
- 任务 2（`e7cb551`、`afa530f`、`1b6b5ff`）：新增可信目录快照及测试，固定当前目录为 18 个父文档、23 个知识块、1024 维、严格五项 metadata 和稳定 UUID。
- 任务 3（`6885591`、`fbf578e`、`d00c300`）：新增 PGVector 物理行、核验结果、JDBC 核验器及测试；固定读取 `templates_vue`，参数化目录版本，核验列协议、23 条当前版本数据、维度、文本、稳定 UUID 与严格五键，并隔离数据库脏数据标识。
- 任务 4（`86c0e1f`、`37b0c23`、`79e4a87`）：新增三态摄取报告和 `VueKnowledgeIngestionQualityGateTest`；显式开关为 `RAG_VUE_INGEST=true`，报告为 `target/rag-eval/vue-ingestion-report.md`。
- 任务 5（`3223701`、`016888e`）：真实检索门禁在创建模型与检索服务前强制核验同一 PGVector 目标的正式摄取；显式开关为 `RAG_EVAL=true`，报告为 `target/rag-eval/vue-hybrid-retrieval-report.md`。
- 十条真实生成构建的既有入口为 `VueGenerationBuildQualityGateTest`，显式开关为 `RAG_BUILD_EVAL=true`，报告为 `target/rag-eval/vue-generation-build-report.md`。本轮承载代码的验证基线为 `016888e`；任务 1～5 均只改测试门禁/计划，没有新增生产接口。

### 本轮 fresh 验证

- 11 类定向命令：使用项目 `.codex/runtime/jdk25`，显式 unset `RAG_VUE_INGEST`、`RAG_EVAL`、`RAG_BUILD_EVAL`、`DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，并设置 `MAVEN_OPTS='-DsocksNonProxyHosts=localhost|127.*|[::1]'`，执行简报指定的 11 类测试；结果为 51 项、0 failure、0 error、0 skipped，`BUILD SUCCESS`。
- 定向测试 fresh 生成的摄取报告与检索报告均为“状态：未执行”，原因分别为 `RAG_VUE_INGEST 未设置为 true`、`RAG_EVAL 未设置为 true`；两份报告没有正式摄取计数、Hit@1、Recall@4 或 Dense 相对退化伪指标。
- 本地容器 `ai-codegen-rag-eval-pg` 实测为 `running/healthy`，`vector` 扩展版本为 `0.8.6`。无模型探针使用 PID 后缀的独立一次性夹具表，由 trap 和显式 `DROP TABLE` 清理；实测列二元类型为 `embedding_id data_type=uuid, udt_name=uuid`、`embedding data_type=USER-DEFINED, udt_name=vector`、`text data_type=text, udt_name=text`、`metadata data_type=json, udt_name=json`，实际 `vector_dims(embedding)=1024`；项目 Jackson 成功读取且确认 metadata 恰好为 `chunkId`、`documentId`、`documentKind`、`chunkKind`、`catalogVersion` 五个字符串键。夹具表已删除，`templates_vue` 未被写入，故该结果只证明协议，不代表正式摄取通过。
- 完整 Maven 按简报命令 fresh 执行：项目 JDK 25、三个 RAG 门禁开关和两个模型变量均 unset，并使用回环直连 JVM 参数；结果为 317 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。Spring `contextLoads` 实际启动并通过；7 个跳过项均为既有显式外部测试，其中五骨架来源校验执行、真实 npm 动态构建按开关跳过。
- 完整 Maven fresh 生成的三份报告均为“未执行”：摄取、真实检索、十条生成分别由 `RAG_VUE_INGEST`、`RAG_EVAL`、`RAG_BUILD_EVAL` 未启用而短路，报告中没有外部真实成绩。默认测试通过只证明短路语义和代码回归通过，不等于外部真实门禁通过。

### 外部条件与最终完成审计

- 只按环境变量存在性审计：`DASHSCOPE_API_KEY=UNSET`、`DEEPSEEK_API_KEY=UNSET`；未读取变量值，也未搜索钥匙串、Shell 历史或其他凭据。由于前者 unset，本轮没有执行正式 `text-embedding-v4` 摄取和 30 条真实 Hybrid/Dense 检索；由于前两项没有通过且后者也 unset，没有执行十条首次真实生成构建。
- 原总计划第 1 项：任务 1～7 的生产实现、单元测试和默认 Maven 有当前分支证据；本轮未发现需代码修复的规格、安全、资源或异常语义问题。
- 原总计划第 2 项：未完成。正式 `templates_vue` 当前不存在；没有当前目录版本的 23 条真实 `text-embedding-v4` 物理核验成绩。
- 原总计划第 3 项：未完成。没有 30 条真实检索的 `Skeleton Hit@1 >= 0.90`、`Feature Recall@4 >= 0.85` 或相对 Dense 退化不超过 `0.05` 的成绩。
- 原总计划第 4 项：未完成。没有十条固定需求首次生成后的 10/10 `npm install` 与 `npm run build` 成绩。
- 原总计划第 5 项：已有 2026-08-11 历史显式门禁证据证明五个策展骨架真实构建 5/5；本轮按简报不重复高成本 npm，完整 Maven 只执行固定五来源校验并跳过显式真实构建。
- 原总计划第 6 项：完成。本轮完整 Maven 为 317 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。
- 原总计划第 7 项：当前中文提交均在本地分支；本轮未 push、未合并。

结论：代码与默认门禁可以合并；PGVector 基础设施和无模型物理协议可用。但正式 23 条摄取、30 条真实检索指标和十条首次生成构建均无成绩，因此当前不可发布。必须按“正式摄取并物理核验 → 真实检索达标 → 十条生成 10/10”顺序补齐外部门禁，不能用默认测试或协议探针缩小成功定义。

## 五骨架真实构建门禁补强（2026-08-11）

- 完成度审计发现：历史任务 2 曾手工验证 5/5 骨架，但永久测试 `VueSkeletonRealBuildTest` 只构建 `vue-skeleton-basic-001`，无法在后续回归中证明计划要求的 5/5。该问题是门禁覆盖不足，不是骨架实现失败。
- TDD RED：先增加“五个来源”断言并保留原单一来源，定向测试按预期失败，结果为 1 项、1 failure，错误为 `expected: <5> but was: <1>`。
- GREEN：测试现在固定校验五个计划骨架文件，使用动态测试从唯一知识源 JSON 提取工程，逐个调用真实 `VueProjectBuilder.buildProjectDetailed`，分别检查构建成功与 `dist` 目录存在；骨架 ID 和内嵌文件路径均不得逃逸各自构建目录。
- 离线默认模式：`VueSkeletonRealBuildTest` 共发现 2 项，来源完整性测试实际执行并通过，真实 npm 动态测试工厂按 `RAG_SKELETON_BUILD` 跳过 1 项。来源数量与固定 ID 不再因未设置外部开关而完全跳过。
- 显式真实模式：`RAG_SKELETON_BUILD=true` 执行 1 个来源断言和 5 个动态真实构建，共 6 项，0 failure、0 error、0 skipped，`BUILD SUCCESS`；五个独立结果均为 `success=true`、`stage=SUCCESS`、`exitCode=0`、`timedOut=false`，且 `dist` 全部存在。
- 最终完整 Maven：显式清空模型、图片、COS、外部集成和三类 RAG 门控变量，以项目 JDK 25 执行；279 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。日志明确包含 `Started AiAppGenerationApplicationTests`，Spring 上下文门禁未跳过。
- 本轮只修改测试门禁和审计文档，不修改 `src/main/**`、模板数据、依赖或生产配置。

## 真实外部门禁基础设施补验（2026-08-11）

- 已在项目忽略目录 `.codex/runtime/pgvector-data` 准备临时 PGVector，容器 `ai-codegen-rag-eval-pg` 状态为 `running/healthy`，仅映射 `127.0.0.1:5432`，数据库 `ai_codegen_rag` 的 `vector` 扩展版本为 `0.8.6`。
- 已使用项目 `.codex/runtime/jdk25` 和当前依赖 `langchain4j-pgvector 1.1.0-beta7` 执行 Java 协议探针：实际批量写入一个 1024 维向量，通过相同向量检索得到唯一命中，向量 ID、文本、相似度 `1.000000` 和 `documentId` metadata 均正确；SQL 侧确认维度与 metadata 后已删除独立探针表。
- 当前 JVM 全局配置了 SOCKS 代理，且默认 `socksNonProxyHosts` 不包含回环地址。直接连接 PGVector 会把本地 PostgreSQL 连接错误送入代理并报 `UnknownHostException: 127.0.0.1`；评测 JVM 显式增加 `-DsocksNonProxyHosts=localhost|127.*|[::1]` 后协议探针通过。该问题属于本机运行参数，不需要修改生产代码。
- 正式表 `templates_vue` 当前不存在。真实摄取必须由 `VueKnowledgeIngestor` 调用指定的 `text-embedding-v4`，为 5 个骨架和 13 个功能片段生成 23 个真实检索块向量；不能用探针向量、假向量或手工空表替代。
- 已提供本地临时数据库凭据，并用项目要求的 JDK 25 重新运行 `VueRetrievalQualityGateTest`：门禁入口 1/1，0 failure、0 error、0 skipped，`BUILD SUCCESS`；报告仍为“未执行”，当前只缺少 `DASHSCOPE_API_KEY`，未取得 Skeleton Hit@1、Feature Recall@4 或相对 Dense 退化指标。
- 已用相同数据库配置重新运行 `VueGenerationBuildQualityGateTest`：门禁入口 1/1，0 failure、0 error、0 skipped，`BUILD SUCCESS`；报告仍为“未执行”，当前只缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，未取得 10/10 真实生成构建结果。
- 结论：PGVector 基础设施与 Java 协议已经就绪；`DASHSCOPE_API_KEY` 是正式摄取、Dense、Rerank 和真实检索指标的不可替代阻断，`DEEPSEEK_API_KEY` 是十条真实生成的额外不可替代阻断。门禁入口通过不等于真实质量门槛通过，当前仍不可发布。

## 全分支最终审查修复

- 已修正任务 7 的旧链解释：`rag.hybrid.enabled=false` 只关闭 BM25、RRF、Rerank，不再回到旧 Vue `id/title/category/code` metadata 与通用 `assemble`；生产改用新版 Dense-only、当前目录父文档回查和 `assembleVueProject`。
- `rag.enabled` 现为 Vue 普通生成与真实生成评测的优先总开关；Service 层同步防御，不允许 Facade 之外的生产调用绕过。
- 跨层验收已串联同一 InMemory 存储、真实当前摄取、真实 Dense、父文档与 Hybrid 关闭 Facade，证明旧 schema/旧目录版本不可见、当前完整源码可用。
- 验证：指定覆盖 52/52，纯单元 265/265，均为 0 failure、0 error、0 skipped；`git diff --check` 通过。

## 修复后全范围验证

- 任务 1～8 目标回归：176 项，0 failure、0 error、1 skipped；跳过项为默认门控的真实骨架联网构建。
- 纯单元回归：266 项，0 failure、0 error、0 skipped。
- 完整 `mvn test`：补强五骨架门禁后为 279 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。运行进程显式清空模型、Pexels、COS、数据库及外部门控变量；`AiAppGenerationApplicationTests.contextLoads` 实际启动 Spring 上下文并通过，不在跳过项中。
- 七个跳过项均为显式外部测试：真实 npm 骨架构建 1 项、旧 RAG 联网评测 1 项、真实模型/网页外部集成 5 项。`CodeParserTest` 已移除无用 Spring 上下文，`JsonMessageStreamHandlerTest` 已补齐 `ToolMessageCollapser` 并验证非空折叠快照原样恢复。
- Maven 门禁独立最终复审：Spec Compliance 与 Task quality 均通过，Critical/Important/Minor 均为 0；所有历史 finding 已关闭。
- 真实检索门禁报告：状态“未执行”；数据库前置条件已补齐，当前只缺少 `DASHSCOPE_API_KEY`，未取得 Hit@1、Recall@4 或 Dense 相对退化指标。
- 十条真实生成构建报告：状态“未执行”；数据库前置条件已补齐，当前只缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，未取得 10/10 构建结果。
- `git diff --check`：通过；未推送远程。

- 任务 1：完成（提交 `f32d576..ac2bca9`，独立审查通过）
- 任务 2：完成（提交 `ac2bca9..8aead1b`，独立审查通过）
- 任务 3：完成（提交 `8aead1b..e4ce5fe`，独立审查通过）
- 任务 4：完成（提交 `e4ce5fe..b2a2db6`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 5：完成（提交 `b2a2db6..ede5a53`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 6：完成（提交 `ede5a53..12c5d61`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 7：完成（提交 `12c5d61..579e686`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 8：代码与默认 Maven 门禁完成（提交 `579e686..a5c09b8`，定向复审和 Maven 门禁复审均通过；真实外部门禁仍未执行）
- 全分支独立审查：最终 Spec Compliance 与 Task quality 均通过，Critical/Important/Minor 均为 0；代码可合并，默认 Maven 已通过，但两个真实外部门禁没有成绩，当前仍不可发布
