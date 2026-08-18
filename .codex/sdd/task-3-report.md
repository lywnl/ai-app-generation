# 任务 3 实施报告：统一分层记忆读取可信投影

## 结论

任务 3 已完成。L0、旧历史加载与冷重建、L1 摘要、L2 长期偏好、稳定回合边界及 Token 估算现在遵守同一口径：用户消息读取原始 `message`，AI 消息只读取经持久化验证的可信 `memoryMessage`，AI 投影缺失时不回退展示文本。Redis L0 key 已切换为 V2 命名空间，避免继续命中旧格式缓存。

## TDD 证据

### RED

- 运行环境：JDK 25.0.4。
- 指定测试共执行 120 项，其中 8 项按预期失败。
- 失败覆盖：展示文本污染旧历史加载、冷重建、Token 估算、稳定回合边界和 L1 prompt；L2 未执行长期偏好资格门；Redis 仍使用裸 `"7"` key。
- 证据日志：`.codex/verification/task-3-red.log`。
- 在有效 RED 前曾误用 JDK 17，编译器报“不支持发行版本 25”。这是环境不匹配，未执行到行为断言，因此不计入 TDD RED 证据；随后已用 JDK 25.0.4 重新取得上述有效失败。

### GREEN

执行：

```bash
export JAVA_HOME="$PWD/.codex/runtime/jdk-25.0.4+7/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
bash mvnw -Dtest='ToolMessageCollapserTest,ChatHistoryServiceImplLoadTest,MemorySummaryDraftEngineTest,UserPreferenceBatchBuilderTest,UserMemoryServiceImplTest,SpringRedisChatMemoryStoreTest,ContextCompressionCoordinatorTest,LayeredMemoryIntegrationTest,LayeredMemoryL2IntegrationTest' test
git diff --check
```

结果：168 项通过，0 failure，0 error；`git diff --check` 通过。GREEN 日志位于 `.codex/verification/task-3-green.log`。

独立审查后的组合回归属于 GREEN 后加固，已通过，但不作为 RED 证据。

## 生产修改

- `ToolMessageCollapser`：参数统一命名为 `memoryAiText`；契约明确调用方必须提供可信记忆投影，不允许从展示 Markdown 反推记忆内容。
- `ChatHistoryServiceImpl`：注入并复用 `ChatHistoryMemoryResolver`；旧历史加载、冷重建、完整回合 Token 估算与稳定边界都读取可信投影。AI 投影缺失时整轮丢弃，避免留下悬空 USER；正序、倒序和跨页场景保持严格 USER→AI 及 ID 顺序。
- `MemorySummaryDraftEngine`：注入 Resolver；L1 用户读取原话，AI 只读取可信投影。连续 USER 或空 AI 投影不会伪造闭合回合，prompt 与 Token 估算不包含展示工具 Markdown、源码或 diff。
- `UserPreferenceBatchBuilder`：用户文本也通过 Resolver 读取；AI 使用 `isEligibleForLongTermPreference` 资格门。协议异常、`LEGACY_UNVERIFIED`、空投影或空 outcome 会清除 `pendingUser`、将 `completedThroughId` 推进到该 AI ID，并且不进入偏好证据。批次达到上限时不会越过尚未提交的后续合格回合，prompt 始终只含用户原话。
- `UserMemoryServiceImpl`：生产代码原先手工创建 `UserPreferenceBatchBuilder`，因此必须把 Spring 注入的同一个 Resolver 传入，否则统一注入与复用只会停留在 Builder 单测，真实服务路径仍会绕开该契约。此文件虽未列在最初任务文件清单中，却是实现目标必需的最小范围扩展；同时保留兼容旧测试的构造方式。
- `SpringRedisChatMemoryStore`：唯一 key 规则变为 `chat-memory:l0:v2:` + 经校验的 `memoryId.toString()`；get、有 TTL set、无 TTL set、delete 与 Lua CAS 的 `KEYS[1]` 均使用同一规则。未读取旧裸 key，未扫描或通配删除，也未改变 TTL 与 Lua CAS 语义。

固定 Token 数值 `12288`、`3072`、`1024`、`28672`、`30720`、`32768`、`8192` 和倍率 `1.15` 均未修改。

## 测试修改

- 为 L0、冷启动、L1、L2、Token 估算、稳定回合边界和 Redis V2 key 补充污染与状态机回归。
- 旧测试中的正常 AI 行补齐 `memoryMessage=text` 和 `memoryOutcome=LEGACY_IMPORTED`，适配任务 1 引入的新持久化契约。
- 真正验证不完整 AI 的用例继续使用投影为空的专用夹具，没有通过放宽生产逻辑让测试通过。

## L2 游标状态机语义

L2 扫描以“已完成检查”而不是“已形成证据”推进游标。遇到 `PROTOCOL_ERROR`、`LEGACY_UNVERIFIED`、空投影或空 outcome 的 AI 行时，该回合不能进入偏好证据，但已经被确定为不合格，因此清除待配对用户并把 `completedThroughId` 推进至该 AI ID。这样重试不会永久卡在坏回合，也不会把下一条 AI 错配给旧 USER。若批次已满，则游标停在最后一个已提交回合，不能越过仍待下批处理的合格回合。

## 静态自审

对以下生产入口执行了 `getMessage()` 搜索：

- `ChatHistoryServiceImpl`
- `MemorySummaryDraftEngine`
- `UserPreferenceBatchBuilder`
- `ToolMessageCollapser`
- `SpringRedisChatMemoryStore`

仅保留两处：`ChatHistoryServiceImpl` 从查询 DTO 获取筛选条件，以及 `ToolMessageCollapser` 记录异常对象的 `e.getMessage()`。两者都不是从 `ChatHistory` 构造模型记忆的入口，不存在 AI 展示文本回退。

## 独立审查

- 结论：Approved。
- Critical：0。
- Required：0。
- 已确认：L2 游标推进、不完整回合处理、Redis V2 key 一致性和 Resolver 职责符合任务契约。
- 审查建议的组合回归已补充并通过。

## 关注项

- 真实 Redis 环境与旧缓存 TTL 的运行验证属于任务 9；本任务只负责通过 V2 key 进行版本隔离，不改变或清理旧缓存。
- 当前没有阻断提交的问题。
