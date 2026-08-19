# 64K 上下文与未完成工具链压缩实施计划

> **执行要求：** 实施阶段使用 `executing-plans` 或 `subagent-driven-development` 按任务串行推进；每个任务先写失败测试，再写最小实现，完成后运行聚焦测试并提交一次全中文 Git 提交。当前文档仅是计划，尚未修改业务代码。

**目标：** 将 Vue 智能体的上下文门禁调整为 48K 异步压缩、56K 阻塞压缩、64K 强制压缩未完成工具链，并在压缩后继续模型/工具循环，同时避免工具消息孤立、源码泄漏、无限压缩和迟到回调。

**架构：** 48K 和 56K 继续压缩已经完成的稳定回合；当请求达到64K时，在下一次模型请求门禁处确认最近工具批次已完整提交，再把未完成工具链转换为后端根据真实结构化事件生成的可信检查点。检查点只注入本次请求视图，底层 Redis/L0 原始轨迹不被覆盖，回合结束后仍由现有 `ToolMessageCollapser` 正式折叠。

**技术栈：** Java 25、Spring Boot、LangChain4j 流式工具循环、Redis/MySQL 分层记忆、Micrometer、JUnit 5、Vue 现有 SSE 上下文压缩事件。

## 全局约束

- 所有回复、注释、测试名称和 Git 提交信息使用简体中文、UTF-8。
- 只修改当前工作树 `/Users/terminus1/Documents/ai-gen-soft/ai-app-generation/.worktrees/token-layered-memory-v3`；保留既有 `.codex/` 诊断材料和 `.codex/sdd/progress.md`，禁止 `git add .`、`git clean`、`git reset --hard`，未经允许不得 push。
- 固定预算：L0 保留 `12288`，L1 摘要最大 `3072`，L2 召回 `1024`，最大输出 `8192`，估算安全系数 `1.15`，阻塞压缩超时 `60s`，L2 防抖 `30s`。
- 新阈值必须严格等于：异步压缩 `49152`（48K）、阻塞压缩 `57344`（56K）、输入硬上限 `65536`（64K）、模型最小上下文窗口 `73728`（72K）。
- 所有门禁 Token 统计使用真正发给模型的请求视图，包含工具规格、工具参数和工具结果；API 实际用量仍按真实请求累计，不能因压缩或重试隐藏成本。
- 64K 压缩只允许在一个工具批次所有结果已持久化、`tool_call` 与 `tool_result` 完整配对后进行；不得在工具执行中间删除消息。
- 检查点不得包含 `readFile` 源码、`modifyFile.oldContent/newContent`、`writeFile` 完整内容、完整构建日志、重复工具轨迹、模型普通正文或未经执行的成功声明。
- 64K 压缩不调用摘要模型自由总结；只根据后端真实结构化工具事件生成确定性内容。
- 每个用户回合最多一次进入未完成工具链检查点模式；进入后允许基于最新完整工具批次重建请求视图，但不得再次调用摘要模型或递归进入压缩。检查点视图仍达到64K时必须安全失败。
- 现有前端 `ContextCompressionMessage.started()/completed()` 文案继续复用：开始时显示“正在压缩上下文，请稍候…”，完成后显示“上下文压缩完成，继续生成…”。

## 文件与接口边界

- 修改 `src/main/java/com/lyw/appgeneration/config/MemoryTokenProperties.java` 和 `src/main/resources/application.yml`：同步新阈值及严格启动校验。
- 新增 `src/main/java/com/lyw/appgeneration/ai/memory/UnfinishedToolChainCheckpointProjector.java`：解析未完成尾部，输出受信 `SystemMessage` 和投影结果；不持久化、不修改 Redis。
- 必要时新增同包的不可变 DTO（例如 `UnfinishedToolChainCheckpoint`、`ToolChainCheckpointResult`），只承载原始 `UserMessage`、工具事实、文件路径、构建状态和继续约束；用户要求不得提升为 `SystemMessage`。
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ConversationTurnSnapshotParser.java`：明确未完成尾部边界，保留工具调用/结果配对顺序供检查点投影器使用。
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinator.java`：实现三段阈值路由、无旧回合放行、64K请求视图压缩、重估和单次失败关闭。
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextAdmissionResult.java`、`ContextCompressionMode.java`：增加检查点压缩成功/失败所需的类型化状态，禁止把“无旧回合但低于64K”误判为失败。
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionModelRequestGate.java`：发布检查点压缩开始/完成事件并映射安全失败状态。
- 修改 `src/main/java/dev/langchain4j/service/ModelRequestGate.java`、`AiServiceTokenStream.java`、`AiServiceStreamingResponseHandler.java`、`GenerationAwareModelRequestOrchestrator.java`：在同一用户回合共享一次压缩尝试状态，传播到初始请求、工具续请求和协议恢复请求。
- 修改 `src/main/java/com/lyw/appgeneration/monitor/MemoryCompressionMetricsCollector.java`：增加低基数检查点压缩尝试、成功、失败及压缩前后 Token 指标。
- 修改/新增对应 `src/test/java` 单元测试；回合结束折叠相关测试必须保持通过。

## 修改后的确定性行为

1. 请求估算 `<48K`：直接允许模型请求。
2. `48K ≤ Token <56K`：异步提交“旧完整回合”压缩计划，本次请求继续；没有可压缩旧回合时也继续，不阻塞。
3. `56K ≤ Token <64K`：阻塞压缩旧完整回合；若唯一失败原因是 `NO_COMPRESSIBLE_TURN`，允许本次请求继续；对齐失败、游标失败、依赖失败、超时、执行器拒绝等仍失败关闭。
4. `Token ≥64K`：先完成可用的旧完整回合阻塞压缩并重新读取最新请求；若重新估算仍达到64K且存在未完成尾部，执行一次工具链检查点压缩。没有未完成尾部、无法构造合法检查点或压缩后仍达到64K时，返回类型化安全失败，禁止调用模型。
5. 检查点压缩成功后，删除本次请求视图中的冗长未完成尾部；替换片段保留原始 `UserMessage`，并追加一个只包含受信工具事实与继续约束的 `SystemMessage`，内容至少包括：已执行工具及状态、已读取路径、真实创建/修改/删除路径、构建次数、最近构建状态和结构化错误摘要、文件已落盘且以磁盘为准、需要源码必须重新 `readFile`、当前任务尚未完成并继续剩余工作。
6. 检查点只存在于 `ModelRequestGate.Decision.messages`；Redis 中的原始未完成工具链保持不变，下一次门禁重新从最新活动记忆生成请求视图。
7. 工具批次完成后才调用 `submitNextModelRequest()`；代次取消、删除接管、回合终态或旧 generation 迟到回调都不得启动新的模型请求。

---

## 任务 1：配置与启动契约改为 48K/56K/64K/72K

**文件：**

- 修改 `src/main/java/com/lyw/appgeneration/config/MemoryTokenProperties.java`
- 修改 `src/main/resources/application.yml`
- 修改 `src/test/java/com/lyw/appgeneration/config/MemoryTokenPropertiesTest.java`
- 修改 `src/test/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinatorTest.java` 中固定预算断言

**接口与实现要求：**

- 将严格常量和默认值改为 `49152`、`57344`、`65536`、`73728`；保留 `maxOutputTokens=8192`。
- 启动校验继续要求 `L0 < async < blocking < hard`，并要求 `hard + maxOutput ≤ minimumModelContextWindow`。
- `validateCanonicalValues()` 必须拒绝旧的 28K/30K/32K/40K 和任意自定义值，YAML 与 Java 常量必须同步。

**TDD 步骤：**

- [x] ✅ 先把默认值、YAML 读取和旧值拒绝测试改为新契约；运行 `bash mvnw -Dtest=MemoryTokenPropertiesTest test`，确认生产常量尚未更新时失败。
- [x] ✅ 实现配置和严格校验；重跑同一测试，17/17 通过。
- [x] ✅ 运行 `git diff --check`，提交：`调整上下文压缩阈值为48K、56K和64K`。

## 任务 2：定义未完成工具链可信检查点协议

**文件：**

- 新增 `src/main/java/com/lyw/appgeneration/ai/memory/UnfinishedToolChainCheckpointProjector.java`
- 必要时新增同包 DTO 文件
- 修改 `src/main/java/com/lyw/appgeneration/ai/tools/VueToolExecutionFact.java` 及其测试，使事实同时保留读取路径、变更路径、工具状态、构建尝试、最近构建状态和受限错误摘要
- 新增 `src/test/java/com/lyw/appgeneration/ai/memory/UnfinishedToolChainCheckpointProjectorTest.java`

**输入与输出：**

- 输入：`ConversationTurnSnapshotParser.Snapshot.unfinishedTail()`、当前回合工具注册表和可信工具结果文本；输入必须只接受结构化 `AiMessage.toolExecutionRequests` 与 `ToolExecutionResultMessage`。
- 输出：不可变 `ToolChainCheckpointResult`，包含由原始 `UserMessage` 和受信 `SystemMessage` 组成的 `List<ChatMessage> requestMessages` 替换片段、`SystemMessage checkpointMessage`、Token 前后统计所需的事实集合和 `complete`/`failureReason`。
- 检查点统一以固定前缀标识，例如“本轮可信执行检查点”；路径以明确标注的 JSON 数据数组渲染，每个路径和状态都做长度上限与控制字符校验。

**确定性投影规则：**

- 用户原始要求取未完成尾部首个 `UserMessage` 的纯文本；缺失或多用户边界不明确时拒绝投影。
- 工具调用记录工具名和调用状态；工具结果按 `id` 配对，孤立调用或孤立结果直接拒绝，不允许猜测。
- `readFile/readDir` 只保留路径和成功/失败状态，删除正文；`writeFile/modifyFile/deleteFile` 只保留真实状态和路径，禁止保留内容或 diff。
- `buildProject` 只保留次数、最近成功/失败/取消/超时状态，以及由阶段、失败类型、超时等受信结构化枚举确定性生成的错误摘要；禁止复用模型或构建工具返回的自由文本日志。
- 固定追加“文件已落盘，以当前工程文件为准；源码正文未保留，需要时重新调用 readFile；继续完成剩余修改并执行真实构建”。
- 任意协议不完整、事实解析失败或结果超出边界都返回不可用，不生成部分可信检查点。

**TDD 步骤：**

- [x] ✅ 先测试成功投影、读取路径保留、修改路径保留、结构化构建错误摘要及所有敏感内容排除，确认新类不存在或断言失败。
- [x] ✅ 再测试孤立 `tool_call`、孤立 `tool_result`、跨批次不完整、非法路径、路径注入、完整源码/`oldContent`/`newContent` 注入、用户角色提升和缺失用户要求必须拒绝。
- [x] ✅ 实现最小确定性投影器；运行 `bash mvnw -Dtest=UnfinishedToolChainCheckpointProjectorTest,VueToolExecutionFactTest test`，预期通过。
- [x] ✅ 提交：`增加未完成工具链可信检查点投影`。

## 任务 3：扩展上下文协调器的三段门禁与64K请求视图压缩

**文件：**

- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinator.java`
- 新增 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionAttemptState.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextAdmissionResult.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionMode.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ConversationTurnSnapshotParser.java`
- 新增/修改 `src/test/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinatorTest.java`

**接口与状态：**

- 为 `admit` 增加携带 `ContextCompressionAttemptState` 的入口；旧测试用的无状态重载保留并创建独立状态。
- `ContextCompressionAttemptState` 使用线程安全状态机；只允许一次从普通模式进入检查点模式。进入后每次续调用可根据最新完整工具批次重建临时请求视图，但失败不允许递归重试。
- `ContextAdmissionResult` 必须返回与最终请求消息同次估算的 `finalTokens`；检查点压缩结果必须能区分“允许继续”“已到64K仍拒绝”“回合已终止”。

**协调流程：**

- 初始快照和每次 continuation 都重新读取活动记忆，不能使用构造时旧快照。
- `48K` 只调度旧完整回合异步摘要；`56K` 执行旧完整回合阻塞摘要。
- `NO_COMPRESSIBLE_TURN` 仅在输入 `<64K` 时转为 `NORMAL`/`允许继续`；其他失败原因保持失败关闭。
- 阻塞旧回合压缩完成后重新读取 L1、重新构造请求；如果仍 `≥64K`，进入任务 2 的检查点压缩。
- 检查点压缩前验证 `unfinishedTail` 的批次配对；通过后构造“去除尾部 + 追加 SystemMessage”的临时请求视图，不能调用 L0 替换或写 Redis。
- 检查点压缩后重新估算，必须 `<65536` 才允许继续；仍达到或超过时返回 `STILL_OVER_HARD_LIMIT`，不得再次调用摘要模型或自身递归。
- 没有未完成尾部但达到64K时，返回 `NO_COMPRESSIBLE_TURN` 的硬限制失败变体；消息必须明确是本轮无法安全继续，而不是把旧的“开启新会话”硬编码误用于可压缩工具链。

**TDD 步骤：**

- [x] ✅ 更新原有 28K/30K/32K 测试为 48K/56K/64K，并新增“56K无旧完整回合允许继续”。
- [x] ✅ 新增“64K工具链检查点成功、请求 Token 降到64K以下、Redis原始消息未改变、工具消息无孤立项”。
- [x] ✅ 新增“旧回合压缩后仍64K再做检查点”“检查点后仍64K只失败一次”“对齐/超时/执行器拒绝仍失败关闭”。
- [x] ✅ 新增取消、删除接管、代次变化和压缩期间记忆变化测试，确认迟到提交不会污染请求视图。
- [x] ✅ 先运行 `bash mvnw -Dtest=ContextCompressionCoordinatorTest test` 观察 RED，再实现并运行至 GREEN。
- [x] ✅ 提交：`实现64K未完成工具链上下文门禁`。

## 任务 4：把一次性压缩状态贯穿初始请求和工具续循环

**文件：**

- 修改 `src/main/java/dev/langchain4j/service/ModelRequestGate.java`
- 修改 `src/main/java/dev/langchain4j/service/AiServiceTokenStream.java`
- 修改 `src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java`
- 修改 `src/main/java/dev/langchain4j/service/GenerationAwareModelRequestOrchestrator.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionModelRequestGate.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinator.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionMode.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextAdmissionResult.java`
- 修改 `src/test/java/com/lyw/appgeneration/ai/memory/ContextCompressionModelRequestGateTest.java`
- 修改 `src/test/java/dev/langchain4j/service/AiServiceTokenStreamTest.java`、`AiServiceStreamingResponseHandlerTest.java`

**实现要求：**

- 为每个用户回合创建一个共享的 `ContextCompressionAttemptState`，从初始请求传递到每个工具 continuation、协议恢复请求和 child handler；不能每次 continuation 新建。
- `ModelRequestGate.Request` 增加该状态的只读引用，并保留兼容构造器以降低现有测试改动；生产调用点必须显式传递共享实例。
- `finishToolBatch()` 返回可继续后，才提交下一次模型请求；门禁收到64K状态后使用临时请求视图启动模型，不能让 `messagesToSend()` 把原始冗长尾部重新带回去。
- 检查点压缩开始/完成通过现有 `ContextCompressionMessage` 发布；事件必须走 `ContextContinuationGate`，取消或删除后不得迟到发布完成事件。
- 门禁映射：允许继续为 `ALLOWED`；检查点后低于64K也为 `ALLOWED`；压缩失败/重复尝试为 `COMPRESSION_FAILED` 或 `HARD_LIMIT_REJECTED`，不启动 SDK。
- generation 校验覆盖正文分片、tool call 分片、tool result 完成、`onCompleteResponse`、`onError`；旧 generation 的任何回调都不能触发再次压缩或启动新模型。

**TDD 步骤：**

- [x] ✅ 测试同一回合只进入一次检查点模式，多个 continuation 均基于最新完整工具批次重建检查点视图，且模型请求不包含原始源码。
- [x] ✅ 测试工具批次未全部落库时不压缩，批次完成后才压缩；测试压缩后继续第二批工具并最终完成。
- [x] ✅ 测试取消、删除接管、旧 generation 迟到回调不会启动模型、不会发送完成事件、不会写入错误记忆。
- [x] ✅ 运行 `bash mvnw -Dtest=ContextCompressionModelRequestGateTest,AiServiceTokenStreamTest,AiServiceStreamingResponseHandlerTest test`，先 RED 后 GREEN。
- [x] ✅ 提交：`贯通64K检查点与工具循环代次状态`。

## 任务 5：复用前端压缩状态并补充观测指标

**文件：**

- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionModelRequestGate.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinator.java`
- 修改 `src/main/java/com/lyw/appgeneration/ai/model/message/ContextCompressionMessage.java`（只有协议/文案测试证明需要时才改）
- 修改 `src/main/java/com/lyw/appgeneration/monitor/MemoryCompressionMetricsCollector.java`
- 修改相关指标测试

**指标契约：**

- 保留现有 `memory_context_gate_total`、`memory_context_estimated_tokens`、`memory_compression_total` 低基数语义。
- 新增 `memory_tool_chain_checkpoint_total{outcome}`、`memory_tool_chain_checkpoint_tokens{stage=before|after}` 和耗时指标；标签只允许 `success`、`failed`、`already_attempted`、`no_unfinished_tail` 等固定枚举，不得包含 appId、用户文本、路径或源码。
- 指标记录失败必须旁路，不能改变压缩结果；开始/完成事件必须最多各发布一次。

**TDD 步骤：**

- [x] ✅ 测试检查点成功、失败、重复尝试和无尾部场景的计数及前后 Token 值。
- [x] ✅ 测试 MeterRegistry 抛异常时门禁结果不改变，延续现有 `ThrowingMeterRegistry` 契约。
- [x] ✅ 提交：`增加64K检查点压缩观测与前端状态`。

## 任务 6：回归分层记忆与正式回合折叠

**文件：**

- 运行并必要时修正 `src/main/java/com/lyw/appgeneration/ai/memory/ToolMessageCollapser.java`
- 运行 `src/main/java/com/lyw/appgeneration/ai/memory/LayeredChatMemory.java`、L1/L2 相关测试，不改变其已确认语义
- 修改/新增 `src/test/java/com/lyw/appgeneration/ai/memory/ToolMessageCollapserTest.java`、`LayeredMemoryIntegrationTest.java`、`ContextCompressionModelRequestGateTest.java`、`src/test/java/com/lyw/appgeneration/ai/AiGeneratorServiceFactoryTest.java`、`src/test/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngineTest.java`、`UserPreferenceBatchBuilderTest.java`（仅在测试暴露接口回归时）

**验收要求：**

- 64K请求级检查点不会写入 Redis、MySQL、L0、L1 或 L2。
- 正常回合完成后 `ToolMessageCollapser` 仍只保存可信 `memoryAiText`，最终 L0 仍是一条用户消息加一条终态 AI 投影。
- L1 摘要和 L2 偏好提取继续只读取可信 AI 投影；检查点中的源码/工具轨迹不会进入长期记忆。
- 后端重启/Redis 冷启动仍按原始存储的可信投影重建，不会把临时 SystemMessage 当作历史消息。

**TDD 步骤：**

- [x] ✅ 增加“请求级检查点结束后底层消息完全不变”的断言，并贯穿真实 `ModelRequestGate.Decision.messages()` 请求视图。
- [x] ✅ 增加“回合最终折叠后无孤立工具消息、摘要只见可信投影”的断言，并覆盖 Redis 空 L0 经工厂从 MySQL 冷启动重建。
- [x] ✅ 运行 `bash mvnw -Dtest=ToolMessageCollapserTest,LayeredMemoryIntegrationTest,MemorySummaryDraftEngineTest,UserPreferenceBatchBuilderTest,ChatHistoryServiceImplLoadTest,ChatHistoryMemoryResolverTest,ContextCompressionCoordinatorTest,ContextCompressionModelRequestGateTest,AiGeneratorServiceFactoryTest test`，193/193 通过。
- [x] ✅ 提交：`回归64K压缩后的分层记忆折叠`；独立审查修复提交：`补全64K分层记忆纵向回归`。

## 任务 7：端到端验证、配置审计与交付检查

**验证范围：**

- [x] ✅ 运行聚焦测试，238/238 通过：

  ```bash
  bash mvnw -Dtest=MemoryTokenPropertiesTest,UnfinishedToolChainCheckpointProjectorTest,ContextCompressionCoordinatorTest,ContextCompressionModelRequestGateTest,AiServiceTokenStreamTest,AiServiceStreamingResponseHandlerTest,ToolMessageCollapserTest,LayeredMemoryIntegrationTest test
  ```

- [x] ✅ 运行后端全量测试：`bash mvnw test`，1604 项测试、0 失败、0 错误、7 项按标签跳过；同时修复旧 32K 测试夹具导致的 L1 滚动摘要阈值回归。
- [x] ✅ 执行 `git diff --check`、源码 UTF-8/无 BOM/无 `U+FFFD` 检查及 Spring Boot YAML 启动绑定检查；生产代码不存在旧阈值 `28672/30720/32768/40960` 残留。
- [x] ✅ 完成日志与指标脱敏审计：新链路未新增正文日志，指标入口只接受固定枚举、Token 数和耗时；Prometheus 抓取测试确认不包含用户原文、源码、工具参数、完整日志或 Redis 原始消息。
- [x] ✅ 验证前端收到 `STARTED → COMPLETED → 后续正文 → 成功终态`；后端 SSE 契约测试与前端 163 项测试通过，乱序、重复或非法控制帧只产生一次安全错误终态。
- [x] ✅ 检查 `git status --short` 并显式暂存计划涉及文件；既有 `.codex/` 未跟踪材料和 `.codex/sdd/progress.md` 用户改动均未纳入提交。
- [x] ✅ 最终中文提交：`完成48K至64K上下文压缩与工具链续行`；未经用户明确允许未 push。

## 验收标准

- 48K异步压缩不阻塞当前模型请求；56K有旧完整回合时阻塞压缩；56K没有旧完整回合时允许继续。
- 64K时只在完整工具批次之后生成确定性检查点，模型可继续调用工具；请求视图中不存在孤立 `tool_call`/`tool_result`。
- 检查点不含源码、差异、完整参数和日志，且保留真实工具事实、路径、构建状态和继续约束。
- 检查点后 Token `<64K` 才能发模型；仍超限只失败一次，不无限压缩、不丢弃 Redis 原始轨迹。
- 取消、删除接管、代次变化和依赖超时均安全收口；前端压缩提示和正常生成状态不互相污染。
- 现有 L0/L1/L2、冷启动、正式回合折叠、RAG 和工具协议恢复测试不回归。

## 明确不做的事情

- 不把上下文窗口扩大到64K后直接放任模型请求；64K仍是输入硬门禁。
- 不在工具执行中间裁剪 Redis/L0，不删除历史 `chat_history.message`。
- 不让摘要模型总结未完成工具链，不把 AI 普通正文当作工程事实。
- 不在压缩后无限递归调用压缩，不通过简单截断 Token 静默丢掉用户要求或工具结果。
