# Vue RAG 混合检索全分支最终审查

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
- 未解决事项：完整 Maven 仍有简报明确排除的既有密钥注入与测试夹具问题；本次未运行真实外部 Vue 检索评测或十条生成构建门禁，也未修改其逻辑。

## 修复后独立复审结论

- 审查范围：`b2be84e1f2dcd3f0409a8724c7dc5dddf49e1680..65e1e6881aab8bccd4b3b42b5c2da15f71104d77`。
- Spec Compliance：通过。
- Task quality：通过。
- Critical：0；Important：0；Minor：0（本段已修正原报告状态冲突）。
- 原 Important-1 已关闭：Hybrid 关闭时使用新版生产 Dense-only、当前 `catalogVersion + documentKind` 和父文档回查，不再读取旧 Vue metadata 协议。
- 原 Important-2 已关闭：`rag.enabled` 在 Facade 与 Service 层优先于 Hybrid 开关，关闭时不执行任何 Vue RAG。
- 代码可以进入全范围验证；外部真实检索、10 条真实生成构建和完整 Maven 门禁仍需按实际结果单独审计，不能由本次代码复审替代。

## 历史审查记录（修复前，已被上述结论替代）

- 审查范围：`5850ef9f4ffb50d58839245c3cd4dfaf4bad67a8..b2be84e1f2dcd3f0409a8724c7dc5dddf49e1680`
- 审查性质：最终只读代码审查；未修改生产代码，未运行全量测试
- 历史总结：未发现 Critical；发现 2 项 Important；无 Minor。当时不应合并或发布；两项 Important 均已由 `65e1e68` 关闭。

## Plan compliance

### Achieved

- 任务 1～6 的主体实现已经落地：双层父文档/检索块、稳定 `catalogVersion`、稳定块 ID、PGVector 最小 metadata、Lucene BM25、Dense 双 metadata filter、父文档聚合、RRF、分池重排、兼容性筛选以及 Vue 专用 Prompt 双分区预算均有对应生产实现和定向测试。
- Hybrid 开启时的生产链顺序符合计划：原始 `userMessage` 进入 BM25、Dense、RRF 和 Rerank；图片增强结果只作为最终 `generationRequest`。
- HTML、MULTI_FILE 仍调用旧检索和旧拼装接口；分支没有引入 learned sparse、ONNX、Python 或 OpenSearch。
- `BuildResult`、并发消费进程输出、超时/进程树清理、旧 `dist` 清理、30 条检索数据集、10 条生成数据集及报告脱敏代码均已建立。
- 分支未提交新增的 `target/`、`node_modules/`、`dist/` 或运行时构建目录；`git diff --check` 通过。

### Partially achieved

- 任务 7 的 Hybrid 开启链已接通，但“关闭 Hybrid 回退旧 Dense”与任务 3 的新版 Vue 摄取 schema 跨任务不兼容，见 Important-1。
- `rag.enabled` 被声明为 RAG 总开关，但 Vue Hybrid 分支没有消费它，见 Important-2。
- 任务 8 已建立真实门禁代码和数据，但门禁结果尚未取得：真实 Hybrid/Dense 检索没有执行，10 条真实生成构建没有执行。

### Needs fixes

- 修复下述 2 项 Important，并增加覆盖“真实新版摄取数据 + Hybrid 关闭”和“总开关关闭 + Hybrid 开启”的跨层测试。
- 发布门禁尚未达成：计划要求的 `Skeleton Hit@1 >= 0.90`、`Feature Recall@4 >= 0.85`、相对 Dense 基线退化不超过 `0.05` 没有真实结果；10/10 真实生成的 `npm install` 与 `npm run build` 没有执行；完整 `mvn test` 当前为 252 项、10 errors、2 skipped，而不是计划要求的通过。以上是发布状态，不另计代码 finding。

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

#### Important-1：Hybrid 关闭的旧 Dense 回退无法消费新版 Vue 摄取数据，发布顺序会产生错误/陈旧上下文

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

#### Important-2：`rag.enabled` 总开关不能关闭 Vue Hybrid 检索

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

但部署/回滚链不一致：任务 3 改变了 Vue 向量表 schema，任务 7 的关闭开关路径仍消费旧 schema；同时总开关未覆盖 Vue 新链。因此单任务测试可以全部通过，而按计划真实部署后仍会在 Hybrid 关闭窗口产生错误上下文。再叠加任务 8 的外部门禁未完成，当前集成状态不满足发布条件。

## Assessment

**Ready to merge：No。**

合并前至少需要修复两项 Important 并通过相应跨层回归；发布前还必须取得真实 30 条检索门槛、10/10 真实生成构建以及完整 `mvn test` 成功证据。
