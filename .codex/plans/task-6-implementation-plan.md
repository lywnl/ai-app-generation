# 任务 6 实施计划：Vue 回合统一终态与稳定记忆

## 事实与边界

- 基线提交：`9cb68b1`。
- `.codex/sdd/task-6-brief.md` 在终态持久化段后截断，缺少 GREEN、验证和提交章节；本文补齐工程闭环，不扩展功能范围。
- 任务 6 只建立 `TurnOutcomeMessage` 和统一收尾；正式 SSE event/done、心跳和 30 分钟网络超时属于任务 7。
- HTML、MULTI_FILE 保持现有入口和持久化顺序。
- `.codex/sdd/progress.md` 是外部改动，不修改、不暂存。

## 当前调用链

```text
AppServiceImpl.chatToGenCode
  -> 先判断首次对话并保存 User
  -> AiCodeGeneratorFacade 获取/创建缓存服务
  -> TokenStream.start
  -> JsonMessageStreamHandler
       -> complete: 保存 AI -> L0 折叠 -> L1/L2 -> 旧 buildProjectAsync
       -> error: 另存错误 AI
```

问题是租约尚未进入入口，在线工具没有词法作用域，complete/error/cancel 分别持有终态副作用，且旧异步构建位于成功钩子中。

## 目标调用链与所有权

```text
AppServiceImpl（入口顺序所有者）
  -> Flux.defer
  -> 领取 AppOperationLease(GENERATE)
  -> VueBuildSessionManager.open -> VueTurnContext
  -> 修复上一轮孤立 User（若有）并触发受控冷重建
  -> 计算 firstMessage
  -> facade 同步取得服务并创建未启动 TokenStream
  -> 保存本轮原始 User；false/异常时禁止启动
  -> StreamHandlerExecutor 订阅 Vue Flux

AiCodeGeneratorFacade（模型/工具回调所有者）
  -> FileToolExecutionScopeManager.online(精确 lease)
  -> ToolExecutionGuard 在真实工具线程 callInScope
  -> 每个回调先进入 VueTurnContext 回调票据
  -> 首次 controlled termination 写入精确上下文
  -> cancel 只关闭模型与回调门，不做持久化

JsonMessageStreamHandler（正文序列所有者）
  -> 顺序解析正文、工具事件和精简 Markdown
  -> complete/error 汇合为结构化 VueTurnOutcome
  -> cancel 把后台收尾交给受管协调器
  -> 不直接写 MySQL/L0/L1/L2，不再异步构建

VueTurnFinalizer（唯一终态副作用所有者）
  -> turnContext CAS 单赢家
  -> 生成唯一 canonicalAiText
  -> MySQL AI 一次
  -> 同源文本 L0 折叠一次
  -> 非 COLLAPSED 立即失效 Vue 服务缓存
  -> MySQL 成功后 L1/L2 各一次
  -> 生成最后一个 TurnOutcomeMessage
  -> 关闭 Vue lease 与 App lease
```

## 终态判定

- `BUILD_SUCCEEDED` + lease `SUCCEEDED`：`SUCCEEDED`，刷新预览。
- `BUILD_FAILED` + lease `FAILED`：`FAILED`，不刷新。
- `CANCELLED` 或 lease `CANCELLED`：`CANCELLED`。
- `LOOP_LIMIT_EXCEEDED`：`SYSTEM_ERROR`，固定“步骤过多”文案。
- `PROTOCOL_ERROR` / 在线误收 `EVALUATION_COMPLETED`：`PROTOCOL_ERROR`。
- 普通 complete 且没有可信 build 终止：`PROTOCOL_ERROR`，固定“尚未通过真实构建”文案。
- 普通模型异常：`SYSTEM_ERROR`。
- 回合绝对超时的 outcome 类型和文案先在结构中保留，网络层 30 分钟定时由任务 7 接入。
- MySQL AI 保存返回 false 或抛异常：客户端 outcome 降级为 `SYSTEM_ERROR`，不折叠、不触发 L1/L2。

## TDD 批次

1. 基础值对象与终态持久化：上下文首次终止、outcome 枚举、消息序列化、13 个终态场景、结构化折叠结果。
2. 在线链路：精确 lease、真实工具线程作用域、普通未构建完成、循环超限、cancel 后晚到回调拒绝。
3. 入口与缓存顺序：热缓存、冷缓存、孤立 User、User 保存 false、同步异常释放租约。
4. 取消协调：单赢家、受管执行、两层静默、后台完成前不释放租约。

## GREEN 实现顺序

1. 新增 `VueTurnContext`、`VueTurnOutcome`、`TurnOutcomeMessage`。
2. 结构化 `ToolMessageCollapser.CollapseResult`，增加工厂缓存命中/失效/冷重建接口。
3. 实现 `VueTurnFinalizer` 和取消协调器。
4. facade 接入在线 scope、精确回调票据和 context 终止记录。
5. Json handler 改为统一终态编排并删除旧异步构建。
6. AppServiceImpl 仅对 Vue 使用 `Flux.defer` 新顺序；其他类型保持原逻辑。
7. prompt 明确终态控制消息不属于 canonical 对话内容。

## 验证与提交

- 每批先运行对应 RED 测试，确认失败原因与目标一致，再实现 GREEN。
- 定向覆盖 brief 13 场景、缓存命中/冷启动顺序、`ToolExecutedMessage.result` 序列化。
- 扩展回归覆盖任务 3～5 的租约、构建工具、协议、facade、factory 和 memory。
- 可拆 2～3 个全中文提交；不 amend、不 push。
- 最后运行 fresh `clean test`、`git diff --check`、独立代码复审并生成 `.codex/sdd/task-6-report.md`（不提交）。
