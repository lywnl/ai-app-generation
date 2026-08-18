# 记忆投影与工具协议恢复：要求—代码—测试—运行证据矩阵

## 核心数据与记忆契约

| 要求 | 代码事实 | 自动化测试 | 运行证据 | 结论 |
|---|---|---|---|---|
| 展示 `message` 与模型 `memoryMessage` 分离 | `ChatHistory` 增加 `memoryMessage/memoryOutcome`；`ChatHistoryService.addAiMessageAndReturn` 专门写 AI 三元组 | `MemorySchemaMigrationContractTest`、`ChatHistoryServiceImplLoadTest`、`ChatHistoryMemoryResolverTest` | 任务 9 MySQL 迁移后 48 条 AI 全部具有完整投影状态，用户投影违规 0 | 通过 |
| Vue 投影只能来自真实结构化工具事实 | `JsonMessageStreamHandler` 解析受信工具结果为 `VueToolExecutionFact`；`VueTurnMemoryProjection` 只接收事实与终态 | `VueTurnMemoryProjectionTest`、`JsonMessageStreamHandlerTest`、`VueTurnFinalizerTest` | 压缩后真实回合投影 81 字符，结构化链为 `readFile→modifyFile→buildProject` | 通过 |
| AI 空投影不得回退展示正文 | `ChatHistoryMemoryResolver.resolveModelText` 对 AI 只读取 `memoryMessage`，空值返回空 | `ChatHistoryMemoryResolverTest`、`ChatHistoryServiceImplLoadTest`、`MemorySummaryDraftEngineTest` | 精确探针 SQL 层 `aiProjectionOnly=true`，10/10 结构化调用 | 通过 |
| L0、冷启动、L1 和 Token 统计统一读取投影 | `ToolMessageCollapser`、`ChatHistoryServiceImpl`、`MemorySummaryDraftEngine` 和压缩协调器统一经过解析器/投影消息 | `ToolMessageCollapserTest`、`LayeredMemoryIntegrationTest`、`ContextCompressionCoordinatorTest` | 真实 L0 中伪工具标记和内部纠正 Prompt 为 0；压缩前后 Token `31,893→15,961` | 通过 |
| L2 只用用户原话，排除不可信 AI 回合并推进游标 | `UserPreferenceBatchBuilder` 只把用户文本作为证据；用 AI `memoryOutcome` 闭合/排除回合 | `UserPreferenceBatchBuilderTest`、`UserMemoryServiceImplTest`、`LayeredMemoryL2IntegrationTest` | 真实 L2 9 行三类污染均为 0，游标 19 行且最大值继续推进 | 通过 |
| Redis 旧污染 key 隔离，不通配删除 | `SpringRedisChatMemoryStore` 固定前缀 `chat-memory:l0:v2:`；所有操作统一走 `redisKey` | `SpringRedisChatMemoryStoreTest` | `task-9-redis-namespace.log`：旧 key 忽略、新 key 往返成功、只精确清理 2 个 key | 通过 |
| 历史迁移不改展示正文且幂等 | 两份迁移按类型回填投影，定向故障行为 `PROTOCOL_ERROR`，用户行保持空 | `MemorySchemaMigrationContractTest` | 96 行 `message` 变化 0；两次迁移投影指纹一致；备份 96/96 | 通过 |

## 工具协议恢复与并发安全

| 要求 | 代码事实 | 自动化测试 | 运行证据 | 结论 |
|---|---|---|---|---|
| 只识别 Vue 中连续两个相同完整伪工具块 | `ToolProtocolRecoveryDetector` 使用注册工具名、严格完整 JSON 与规范化指纹，不做模糊匹配 | `ToolProtocolRecoveryDetectorTest` 12 项 | 生产适配层探针普通正文与伪标记 10/10 为 0 | 通过 |
| 第一次退化自动纠正一次，第二次退化熔断且无第三请求 | `ToolProtocolRecoveryCoordinator/Policy` 维护单回合纠正额度；流控制器二次失败转 `PROTOCOL_ERROR` | `AiServiceTokenStreamTest`、`StreamingRequestControllerRecoveryTest`、`ToolLoopTerminationProtocolTest` | Chrome 受控失败场景显示固定错误且不刷新预览 | 通过 |
| 旧 generation 所有迟到回调不得污染新 generation | `StreamingRequestController` 以 generation ticket/当前代校验包围正文、工具、完成和错误回调 | `StreamingRequestControllerRecoveryTest`、`AiServiceStreamingResponseHandlerTest` | generation 并发重复门禁 10/10 | 通过 |
| 临时纠正 Prompt 经过 28K/30K/32K 门禁但不持久化 | `GenerationAwareModelRequestOrchestrator` 把临时消息只加入本次请求；`ContextCompressionModelRequestGate` 估算完整请求 | `ContextCompressionModelRequestGateTest`、`ContextCompressionCoordinatorTest`、`AiServiceTokenStreamTest` | 真实 L0、L1、MySQL 中内部纠正 Prompt 均为 0；32K 安全 SSE 证据通过 | 通过 |
| 真实工具恢复后仍进入正常工具循环 | 纠正 generation 复用同一 token stream 与工具执行编排，不把恢复当回合终态 | `AiServiceTokenStreamTest`、`AiServiceStreamingResponseHandlerTest` | Chrome 校正成功场景出现完成的真实 `readDir` 工具卡 | 通过 |
| 供应商 usage 只累计真实返回值，不伪造 | generation 协调器合并已返回的 `TokenUsage`，不推测被语义撤销请求的精确用量 | `AiServiceTokenStreamTest` usage 用例 | wire observer 只记录实际响应的脱敏 Token 统计 | 通过 |

## SSE 与前端状态机

| 要求 | 代码事实 | 自动化测试 | 运行证据 | 结论 |
|---|---|---|---|---|
| 恢复状态只能来自受信 SSE | 后端发布 `ToolProtocolRecoveryMessage`；前端只解析协议 `tool-protocol-recovery/v1` 与固定阶段/文案 | `AppControllerSseTest`、`TurnProgressChannelTest`、`generationSession.test.ts` | 受控 Chrome 三种恢复状态按服务端事件显示 | 通过 |
| 状态优先级为压缩 > 校正 > 思考 | `deriveGenerationStatusText` 统一派生两处加载文案 | `AppChatPageGeneratingStatus.test.ts`、`generationSession.test.ts` | overlap 三张截图依次显示压缩、校正、最终正文 | 通过 |
| 正文或结构化工具开始后隐藏思考/校正提示，并在退化代回滚时保留前序可信正文 | 前端 session 在可信正文/工具事件时清理临时状态；`tool_executed` 建立可信正文 checkpoint，后续 `STARTED` 只回滚退化 generation 的正文和 buffer | `AppChatPageGeneratingStatus.test.ts`、`generationSession.test.ts` | Chrome 定向回归保留“此前可信正文”与 `readDir` 工具卡，退化代正文不残留 | 通过 |
| 失败终态唯一且不错误刷新预览 | 前端拒绝终态后事件；`PROTOCOL_ERROR` 映射固定错误并关闭刷新资格 | `generationSession.test.ts`、`AppControllerSseTest` | Chrome 恢复失败固定错误；预览仍为前一可信版本 | 通过 |

## 固定预算、运行环境与交付门禁

| 要求 | 代码/配置事实 | 自动化/静态测试 | 运行证据 | 结论 |
|---|---|---|---|---|
| L0 12288、L1 3072、L2 1024、28K/30K/32K、输出 8192、安全系数 1.15 保持不变 | `MemoryProperties` 与压缩协调器沿用既有常量/配置，无本分支预算修改 | `ContextCompressionCoordinatorTest`、`MemorySummaryServiceImplTest`、配置契约测试 | 真实 31,893 触发 blocking，压缩至 15,961；摘要 460 Token | 通过 |
| 工具协议检测只启用 Vue 在线生成 | `AiCodeGeneratorFacade/AppServiceImpl` 仅为 Vue token stream 安装恢复能力；HTML/MULTI_FILE 兼容原流程 | `AiCodeGeneratorFacadeTest`、`AppServiceImplVueTurnTest`、Simple 生命周期测试 | 真实 Vue Chrome 链路通过；7 个外部生成测试的跳过原因单独审计 | 通过 |
| 四个既有容器复用且不清卷 | 运行时无代码变更 | 不适用 | 四个目标 `StartedAt` 前后一致；当前 daemon 可见窗口生命周期/卷破坏事件为 0；最终恰好四容器。证据边界不外推到不可见历史 | 通过 |
| 后端全量、前端全量/type-check/build | 不适用 | 全量套件 | 后端最终新鲜验证 1555/0/0/7；7 个 skip 均为显式外部门禁。前端 7 文件 163/163，type-check、build 通过 | 通过 |
| 跨层与 generation 重复回归 | 不适用 | 后端聚合 283 项、前端聚合 119 项 | `task-10-cross-layer-regression.log`、`task-10-cross-layer-frontend.log`、`task-10-generation-concurrency-10x.log` | 通过 |
| Chrome 四个受控场景、真实压缩前后多轮及最终定向回归 | 不适用 | 前端状态机测试为辅助 | `tool-protocol-recovery-chrome-e2e.md`、原 9 张截图/脱敏指标；另补 checkpoint 与 `STARTED→RECOVERED→FAILED` 两张 Chrome 截图 | 通过 |
| 不 push、不合并 master；提交信息全中文 | Git 操作约束 | `git status/log` | 最终提交后核对本地分支与上游状态 | 待最终提交 |

## 完成判定映射

计划末尾 11 条完成判定分别由上表覆盖：

1. 展示/记忆分离：核心契约第 1 行。
2. Vue 确定性投影与异常隔离：核心契约第 2、4 行。
3. L2 用户证据边界：核心契约第 5 行。
4. 空投影无回退：核心契约第 3 行。
5. Redis V2 隔离：核心契约第 6 行。
6. 一次纠正、二次熔断：协议恢复第 2 行。
7. 旧 generation 隔离：协议恢复第 3 行。
8. 临时 Prompt 门禁且不持久化：协议恢复第 4 行。
9. 前端可信 SSE 和提示行为：SSE/前端全部四行。
10. 全量、探针与 Chrome：运行门禁第 4～6 行。
11. `✅`、中文提交且未 push：最终计划与 Git 审计。
