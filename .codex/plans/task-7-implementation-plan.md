# 任务 7：Vue 混合检索生成链路接入实施计划

> 依据 `.codex/sdd/task-7-brief.md` 执行；所有生产行为均先由失败测试锁定。

## 目标

在 `rag.hybrid.enabled` 显式开启时，让 Vue 检索始终接收原始 `userMessage`，图片服务只增强最终生成请求；关闭时精确保持旧 Dense + 旧拼装链。HTML、MULTI_FILE 不改变。Vue 混合检索和专用拼装增加真实阶段指标及脱敏日志。

## 架构

- `AiCodeGeneratorFacade` 只负责编排和灰度分流：Vue 新链先安全检索原消息，再按首次标志增强原消息，最后专用拼装；旧链和其他类型沿用原逻辑。
- `VueHybridRetrievalService` 在现有 `ChannelResult`、RRF、Rerank、最终选择这些真实边界调用独立 `VueRagMetricsCollector`；领域模型不携带监控依赖或伪造诊断字段。
- `RagPromptAssembler` 以实际渲染出的骨架区和片段区长度记录最终上下文长度。`VueRagLogSanitizer` 只生成 UTF-8 SHA-256 前 12 位和候选父文档 ID。

## 全局约束

- UTF-8、简体中文；不记录完整 query、增强 Prompt、上下文、源码或 `TemplateDoc.toString()`。
- 降级标签只允许 `bm25_failed`、`dense_failed`、`rerank_failed`、`fallback_skeleton`、`catalog_unavailable`。
- HTML、MULTI_FILE 的调用顺序和参数语义保持不变；图片异常继续使用 `ImageCollectionService` 的原消息回退契约。
- 不实现任务 8+ 的评测、BuildResult 或真实生成构建。

## Task 1：开关与 Facade 编排

**文件：**

- 修改 `src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java`
- 新增/修改 `src/test/java/com/lyw/appgeneration/config/RagPropertiesTest.java`
- 修改 `src/main/java/com/lyw/appgeneration/config/RagProperties.java`
- 修改 `src/main/resources/application.yml`
- 修改 `src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java`

- [ ] 先写纯单元测试：捕获 Vue 首次/非首次的新检索、图片、拼装、生成参数；锁定关闭时旧链；锁定 HTML/MULTI_FILE；锁定检索抛异常仍调用图片与生成。
- [ ] 运行目标测试，确认因缺少 `hybrid` 配置或新编排而按预期失败，将输出保存到 `.codex/sdd/task-7-facade-red.log`。
- [ ] 最小实现嵌套 `RagProperties.Hybrid(enabled=false)`、YAML 默认 false 和 Facade Vue 分流/空上下文降级。
- [ ] 重跑目标测试并保存 `.codex/sdd/task-7-facade-green.log`。

## Task 2：真实阶段指标

**文件：**

- 新增 `src/test/java/com/lyw/appgeneration/service/rag/monitor/VueRagMetricsCollectorTest.java`
- 修改 `src/test/java/com/lyw/appgeneration/service/rag/VueHybridRetrievalServiceTest.java`
- 修改 `src/test/java/com/lyw/appgeneration/service/rag/RagPromptAssemblerTest.java`
- 新增 `src/main/java/com/lyw/appgeneration/service/rag/monitor/VueRagMetricsCollector.java`
- 新增 `src/main/java/com/lyw/appgeneration/service/rag/monitor/VueRagDegradationReason.java`
- 修改 `src/main/java/com/lyw/appgeneration/service/rag/VueHybridRetrievalService.java`
- 修改 `src/main/java/com/lyw/appgeneration/service/rag/RagPromptAssembler.java`

- [ ] 先写 `SimpleMeterRegistry` 测试，锁定 Timer、各阶段 DistributionSummary、上下文长度和有限 reason Counter 的名称、标签和值。
- [ ] 用服务行为测试证明 BM25/Dense/RRF/Rerank/最终数量来自每个真实阶段，目录不可用、通道异常、Rerank 异常、基础骨架兜底分别记正确枚举。
- [ ] 运行目标测试，保存 `.codex/sdd/task-7-metrics-red.log`。
- [ ] 实现独立收集器并在现有捕获点接入；不修改 `VueRagContext`。
- [ ] 重跑并保存 `.codex/sdd/task-7-metrics-green.log`。

## Task 3：脱敏日志

**文件：**

- 新增 `src/test/java/com/lyw/appgeneration/service/rag/monitor/VueRagLogSanitizerTest.java`
- 新增 `src/main/java/com/lyw/appgeneration/service/rag/monitor/VueRagLogSanitizer.java`
- 修改 `src/main/java/com/lyw/appgeneration/service/rag/VueHybridRetrievalService.java`

- [ ] 先写非 ASCII UTF-8 稳定短哈希测试，以及候选日志数据仅含 ID、不含源码/完整 query 的测试。
- [ ] 运行并保存 `.codex/sdd/task-7-logging-red.log`。
- [ ] 实现无状态辅助类，并把 Vue 新链日志统一为 queryHash、catalogVersion、ID、计数、耗时允许字段。
- [ ] 重跑并保存 `.codex/sdd/task-7-logging-green.log`。

## Task 4：验证、审查与报告

- [ ] 运行任务 7 目标测试、任务 1-7 相关回归、既定纯单元回归并保存日志。
- [ ] 运行全套测试，如仍为既有 13 errors，逐项和基线区分，不冒充通过。
- [ ] 运行 `git diff --check`；检查无完整查询/Prompt/上下文/源码日志及无高基数指标标签。
- [ ] 按正确性、可读性、架构、安全、性能五轴自审。
- [ ] 更新 `.codex/sdd/task-7-report.md`，写入 RED/GREEN、参数捕获、开关、降级、指标、脱敏、验证、文件及最终 SHA。
- [ ] 使用固定信息 `重构: 修正Vue RAG检索与图片增强顺序` 提交，不 push。

## Task 5：独立审查修复——精排开关与指标执行语义

**文件：**

- 修改 `src/test/java/com/lyw/appgeneration/service/rag/VueHybridRetrievalServiceTest.java`
- 修改 `src/main/java/com/lyw/appgeneration/service/rag/VueHybridRetrievalService.java`

- [x] 先写失败测试，证明 `rag.rerank.enabled=false` 时仍错误调用 Vue 精排；保存 `task-7-rerank-disabled-red.log`。
- [x] 先写失败测试，证明 RRF 或父文档为空、精排未执行时仍错误记录 0 候选；保存 `task-7-rerank-empty-red.log`。
- [x] 最小注入 `RagProperties`：关闭精排时按 RRF 父文档顺序和既定链路 TopN 截断，空候选直接返回。
- [x] 仅在真实精排成功时记录实际数量；仅在真实调用抛 `RerankException` 时记录 0 和 `rerank_failed`，其他异常边界不变。
- [x] 运行两个新增测试、定向服务测试、任务 7 目标集、任务 1–7 回归、既定纯单元回归和 `git diff --check`。
- [x] 更新任务 7 报告，使用 `修复: 遵循Vue精排开关与指标语义` 提交，不 push。
