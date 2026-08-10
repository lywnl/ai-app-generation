# Vue RAG 混合检索全分支最终审查

## 真实外部门禁基础设施补验（2026-08-11）

- 临时 PGVector 已在项目忽略目录 `.codex/runtime/pgvector-data` 中运行，容器 `ai-codegen-rag-eval-pg` 为 `running/healthy`，`127.0.0.1:5432` 可达，数据库 `ai_codegen_rag` 已启用 `vector 0.8.6`。
- Java 协议不是阻断：使用项目 JDK 25、`langchain4j-pgvector 1.1.0-beta7` 和独立探针表实际完成 1024 维向量批量写入、相似检索、ID/文本/metadata 读回，SQL 侧结果为 1 条、1024 维、`documentId=probe-document`；验证后探针表已删除。
- 本机 JVM 的 SOCKS 代理会错误接管回环地址数据库连接。评测 JVM 增加 `-DsocksNonProxyHosts=localhost|127.*|[::1]` 后验证通过；这是本机运行参数问题，不是项目 PGVector 配置或代码缺陷。
- 正式 `templates_vue` 表仍不存在，因为正式摄取需要 `text-embedding-v4` 为当前 23 个 `KnowledgeChunk` 生成真实稠密向量。空表、探针向量或本地假 Embedding 都不能替代计划指定的真实摄取。
- 在数据库前置条件已满足的情况下，`VueRetrievalQualityGateTest` 与 `VueGenerationBuildQualityGateTest` 均以项目 JDK 25 重新执行，各 1/1、0 failure、0 error、0 skipped、`BUILD SUCCESS`。两项成功只证明门控和未执行报告正常，不代表真实门槛通过。
- 最新真实检索报告只剩 `DASHSCOPE_API_KEY` 缺失；最新真实生成构建报告只剩 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY` 缺失。前者阻断正式摄取、Dense、Rerank 与检索指标，后者额外阻断十条真实生成。

当前结论：默认 Maven 门禁、PGVector 基础设施和 Java 协议均已验证；代码仍可合并，但真实 Skeleton Hit@1、Feature Recall@4、Dense 相对退化与 10/10 构建没有成绩，所以仍不可发布。取得两个模型凭据后，应先摄取并核对 `templates_vue` 的当前目录版本与 23 条可见数据，再顺序运行两个真实门禁。

## Maven 门禁最终收敛（2026-08-11，替代此前失败状态）

- 根因一：`JsonMessageStreamHandlerTest` 使用 `@InjectMocks`，但没有提供生产类新增的 `ToolMessageCollapser`。测试已补齐 mock，并用包含 `UserMessage`、`AiMessage` 的非空快照配合 `same(snapshot)`，验证自检后恢复的是折叠方法返回的同一对象。
- 根因二：多个旧 `@SpringBootTest` 混合了纯逻辑、真实模型/网页调用和完整生产外部依赖。纯解析测试已去除无用 Spring 上下文；真实模型、网页测试改为 `EXTERNAL_INTEGRATION_TESTS=true` 显式执行。
- `contextLoads()` 没有被跳过。测试层只替换 Mapper、Redis/Redisson、COS、模型、Embedding/向量库、Pexels/DashScope 图片工具和 LangChain4j 运行时代理等外部或数据访问边界；`AppService`、记忆 Service、RAG 编排、`RagRerankService`、`AiCodeGeneratorFacade` 等业务 Bean 保持真实装配。
- 测试代码没有提交真实或虚假凭据。`dashscope.api-key=` 是空测试配置，仅使真实 `RagRerankService` 构造不发请求的 `RestClient`；运行进程同时显式清空所有相关环境变量。
- 主代理最终定向验证：`AiAppGenerationApplicationTests,JsonMessageStreamHandlerTest` 共 3 项，0 failure、0 error、0 skipped；日志明确出现 `Started AiAppGenerationApplicationTests`。
- 主代理最终完整验证：278 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。七项均是显式外部门控的真实模型、网页、旧 RAG 联网评测或真实 npm 构建测试。
- 独立最终复审：Spec Compliance 通过，Task quality 通过，Critical/Important/Minor 均为 0，Ready to merge 为 Yes，所有历史 finding 已关闭。
- 对应提交：`427fb16`、`25b83cc`、`a5c09b8`。未修改 `src/main/**`，未推送远程。

当前结论：默认 Maven 门禁已完成，代码仍可合并；PGVector 基础设施后来已补齐并通过 Java 协议探针，真实 Vue Hybrid/Dense 检索指标与十条真实生成构建当前只因缺少模型凭据未执行，所以发布结论仍为不可发布。

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
- 代码合并结论：可以合并。
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

**历史 Ready to merge：No；当前 Ready to merge：Yes。**

两项历史 Important、跨层回归和完整 Maven 均已关闭。发布前仍必须取得真实 30 条检索门槛及 10/10 真实生成构建成绩。
