# Vue RAG 混合检索执行计划

## 目标与约束

将 `VUE_PROJECT` 的 RAG 改造为：

```text
工程骨架 + 功能片段
→ Lucene BM25 + text-embedding-v4 稠密召回
→ RRF 融合
→ gte-rerank-v2 重排
→ 兼容性筛选与上下文拼装
→ Vue Agent 生成
→ npm install + npm run build 验收
```

- 首版只改 `VUE_PROJECT`，HTML、MULTI_FILE 保持原链路。
- 不引入 learned sparse、BGE-M3、ONNX、Python 或 OpenSearch。
- PostgreSQL/PGVector 继续保存稠密向量；Lucene 使用 JVM 内存索引。
- 模板 JSON 是唯一知识源，Lucene 索引随应用启动重建。
- 检索使用原始用户需求，图片增强结果只进入最终生成提示词。
- 所有代码及数据文件使用 UTF-8；提交信息使用中文；不推送远程。

## 核心数据模型与接口

新增 `RagDocumentKind`（`PROJECT_SKELETON`、`FEATURE_SNIPPET`）、`RagChunkKind`（`OVERVIEW`、`ENGINEERING`）、`KnowledgeChunk`、`RankedCandidate` 和 `VueRagContext`。`TemplateDoc` 补充 `schemaVersion`、`documentKind`、`version`、`framework`、`language`、`buildTool`、`dependencies` 和 `devDependencies`。

新增 Vue 专用接口：

```java
VueRagContext RagRetrievalService.retrieveVueProject(String rawQuery);

String RagPromptAssembler.assembleVueProject(
        String generationRequest,
        VueRagContext context
);

BuildResult VueProjectBuilder.buildProjectDetailed(String projectPath);
```

原有 `retrieve(String, CodeGenTypeEnum)` 保留，继续服务 HTML 和 MULTI_FILE。

## 任务 1：建立 Vue 知识目录和校验机制

- 扩展 `TemplateDoc`，新增 `TemplateCatalog` 和 `KnowledgeChunkFactory`。
- 递归读取 `embed_text/vue-project`，检查重复 ID、非法路径、缺失字段、依赖冲突并计算稳定 `catalogVersion`。
- 不按固定字符数切源码：功能片段生成一个 `OVERVIEW` 块；工程骨架生成 `OVERVIEW` 和 `ENGINEERING` 两个块。
- 检索只命中短检索块，生成阶段再由父文档 ID 获取完整源码。
- 拒绝重复 ID/块 ID、绝对路径/`..`、骨架缺少关键工程文件、质量分越界、声明依赖与 `package.json` 不一致。
- 测试：`TemplateCatalogTest`、`KnowledgeChunkFactoryTest`。
- 提交：`功能: 建立Vue RAG双层知识模型`。

## 任务 2：策展工程骨架和功能片段

- 将现有 13 个 Vue 模板迁入 `embed_text/vue-project/features/`，标记为 `FEATURE_SNIPPET` 并补全元数据。
- 新增 5 个可独立构建骨架到 `embed_text/vue-project/skeletons/`：基础 Vue、Element Plus 管理后台、Element Plus 商城、内容门户、Element Plus + ECharts 看板。
- 固定版本：Vue 3.3.4、Vue Router 4.2.4、Vite 4.4.5、Vue Plugin 4.2.3、Element Plus 2.8.8、Element Icons 2.3.1、ECharts 5.5.1。
- 每个骨架包含 `package.json`、`index.html`、`vite.config.js`、`src/main.js`、`src/App.vue`、路由和必要页面。
- 骨架验收要求 5/5 完成 `npm install`、`npm run build` 并生成 `dist`。
- 提交：`功能: 增加可构建的Vue工程骨架`。

## 任务 3：改造稠密向量摄取

- 摄取对象从父文档改为 `KnowledgeChunk`，Embedding 只输入 `searchText`。
- PGVector metadata 只保存 `chunkId`、`documentId`、`documentKind`、`chunkKind`、`catalogVersion`，不再保存完整源码。
- 以 `UUID.nameUUIDFromBytes(chunkId)` 生成稳定 ID，通过批量 API 幂等更新。
- 查询过滤当前 `catalogVersion`，使旧版和已删除文档不可见。
- 测试稳定 UUID、metadata、源码不入 metadata 和重复摄取可见结果不重复。
- 提交：`重构: 实现Vue知识块幂等向量摄取`。

## 任务 4：实现 Lucene BM25

- 引入相同版本的 `lucene-core`、`lucene-analysis-common`、`lucene-analysis-smartcn`。
- 新增 `Bm25Retriever`，使用 `ByteBuffersDirectory`、`BM25Similarity`，启动时由 `TemplateCatalog` 重建。
- 中文说明走 SmartCN；技术词、依赖名、文件路径使用小写精确词字段，精确字段查询权重为普通字段 2 倍。
- 在索引层按 `documentKind` 过滤，返回父文档 ID、排名和 BM25 分数。
- 测试中文需求、`package.json`、`vue-router hash` 和骨架/片段隔离。
- 提交：`功能: 增加Lucene BM25召回通道`。

## 任务 5：实现 Dense、RRF 和双链检索

- 拆分 `Bm25Retriever`、`DenseRetriever`、`RrfFusionService` 与编排层 `RagRetrievalService`。
- 固定默认参数：每路 Top10、融合 Top15、RRF k=60、两路权重均为 1.0、骨架重排 Top3、片段重排 Top8、片段最终 Top4。
- 骨架和功能片段分别召回、融合和重排；每路先按父文档聚合。
- 质量分只用于同分排序；Rerank 文本使用标题、描述、意图、技术栈、依赖和文件清单，不使用源码开头。
- 最终选择一个骨架、最多四个功能片段，并按 Vue 主版本、语言、构建工具和依赖主版本检查兼容性。
- 降级：单路失败使用另一路；Rerank 失败使用 RRF；两路失败使用基础骨架；目录不可用则无 RAG 并记录错误。
- 提交：`功能: 实现Vue混合检索与RRF融合`。

## 任务 6：重写上下文拼装

- Vue 上下文总预算 12000 字符，其中骨架 4000、功能片段 8000。
- 顺序为工程约束与依赖、文件清单、关键工程文件、功能片段文件、图片增强后的用户需求。
- 不在文件中间截断；大块放不下时输出路径/用途/依赖并继续尝试后续片段，不能直接退出循环。
- 不输出检索分数；明确骨架工程约束必须遵守，功能片段只作参考。
- 修正 Vue 系统提示词中 `node:url` import，并要求使用组件库时同步声明依赖与注册方式。
- 提交：`修复: 优化Vue RAG上下文预算与工程约束`。

## 任务 7：修正生成接入顺序与观测

- Vue 分支必须使用原始 `userMessage` 完成 BM25、Dense 和 Rerank；图片增强结果只作为拼装器的 `generationRequest`。
- HTML 和 MULTI_FILE 行为不变；混合检索关闭时回退现有 Dense 链路。
- 增加检索耗时、候选数量、降级计数和上下文长度指标；日志只记录查询哈希、目录版本和候选 ID，不记录完整提示词和源码。
- 提交：`重构: 修正Vue RAG检索与图片增强顺序`。

## 任务 8：建立检索与真实构建验收

- 新增至少 30 条 Vue 双层检索评测，覆盖精确技术词、同义表达、长需求、多功能、陷阱、骨架选择和片段组合。
- 指标门槛：`Skeleton Hit@1 >= 0.90`、`Feature Recall@4 >= 0.85`，核心指标相对 Dense 基线退化不得超过 0.05。
- 新增 `BuildResult`，区分失败阶段、退出码、超时和最后 8000 字符输出；同时消费 stdout/stderr，避免进程阻塞；旧布尔接口保留。
- 新增 10 条固定真实生成用例，硬门槛是 10/10 完成 `npm install` 与 `npm run build`。
- `mvn test` 必须通过；报告写入 `target/rag-eval/`。
- 提交：`测试: 增加Vue RAG检索与构建质量门禁`。

## 发布顺序

1. 先部署代码并关闭混合检索开关。
2. 摄取新版 Vue 知识库并确认目录版本一致。
3. 运行检索评测和真实构建评测。
4. 全部门槛通过后开启混合检索。
5. 观察召回降级、Rerank 失败和构建成功率。
6. 严重回归时关闭混合检索开关，不回滚知识源。

首版不实现自动构建修复、不引入稀疏向量，也不调整 HNSW 参数。
