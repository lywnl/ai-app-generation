# Implementation Review: reuse-plan-context

- 评审日期：2026-11-02
- Profile：strict
- 代码质量结论：PASS
- 未解决阻塞：无。已复核广泛回归、真实存储、交错边界和相同输入基线对比，当前未发现未解决的业务代码或必要验证缺口。
- 评审者与独立性：独立执行上下文 `/root/l0_plan_independent_review`，未编写本轮业务代码或测试；直接阅读当前 diff、完整实现、实际调用方及保存的运行证据。本报告不替代最终实现验收。
- 核验方式：machine；本评审不生成或改写机器记录。
- 基线：Git `fc4685e` 上 U3 的当前未提交修改。当前业务源码与 regression-01 一致；其后仅 PlanRestoreStorageIntegrationTest 增加独立 Java main 验证，该文件及全部当前源码/测试与 storage-03 的 code-state 一致。旧独立附件方案的报告保存在 `history/fc4685e/`，不是本轮通过证据。

## 当前实现范围

已审查以下新增或修改内容，并结合既有服务、调用方与测试核对：

- `L0Snapshot` 的异构 `window()`、ORIGINAL 派生 `entries()`/`originalMessages()`、无原文身份的 `PlanRestore`、版本 1/2 兼容和单条限制。
- `L0SnapshotCodec` 的逐条分类编解码、旧版本 expected 保持、纯 v1 降级转换；`L0PlanRestoreDowngrade` 的显式应用 ID、精确值 CAS、剩余 TTL 和拒绝未知格式路径。
- `L0SourceOperations` 的原文追加、恢复插入/移除、可靠覆盖裁剪、原文指纹与转换校验；`L0SourceParser` 的原文解析与恢复位置不得拆批约束。
- `TrustedTurnRequestViewProjector`、`LayeredChatMemory`、`TokenAwareChatMemory` 和原子存储的请求投影与显式原文视图。
- `PlanDefinition`、`PlanCoverageBoundary`、`PlanContext.Check`、`CompressionAwareChatMemory` 的事件检查、定义身份、覆盖键和检查状态代次。
- `AppPlanStateManager` 的成功定义写入事件、计划外路径事件及普通状态排除；`TurnRequestBoundary` 与 `AiCodeGeneratorFacade` 的无 I/O 事件读取装配。
- `UnifiedContextRequestPreparer` 的按需选择、窗口提交、CAS 重试、取消/删除/截止、计量后定义复核，以及无写入同 epoch 分支的修复。
- 新增窗口、事件门禁、真实存储测试及既有计划上下文、摘要、受控 E2E 测试的适配。

参考调用链包括 `SummaryMaterialReader`、决策证据采集、`CompatibleMemorySnapshot`、工厂冷重建、`VueTurnContext` 的提交门、应用删除栅栏、`GenerationAwareModelRequestOrchestrator` 的实际 SDK 启动门。没有修改业务文件、规划文件或测试文件。

## 发现与处置

### IR-U3-1：无窗口写入仍要求原 L0 全值匹配，破坏合法旧相容副本的保留语义（P2，已关闭）

- 位置：`UnifiedContextRequestPreparer.commitPlanContext()`。
- 发现来源：主执行者运行真实存储回归，`storage-01` 的 `UnifiedContextStorageOnlineIntegrationTest.捕获完整旧组合后普通回收不改变已计量发送副本` 失败；本评审者读取失败日志、测试和代码后复核根因与修复，不冒称为本评审首先发现。
- 根因：即使 `replacement.equals(expected)`，检查状态提交仍要求当前来源全值等于捕获来源。普通异步摘要回收后，原完整相容副本仍合法，却被强制换成新组合。
- 修复：无窗口写入且当前来源为同 epoch 时只登记检查状态；真正恢复增删仍要求精确 expected CAS；新 epoch 继续重读，取消/删除/截止和定义事件校验不变。
- 关闭证据：已读当前修复；`storage-02` 26 项全部通过，其中原失败场景通过。无写入分支没有覆盖 Redis 或放宽原文回收规则。

未发现其他已确认的正确性、来源隔离、编码兼容或生命周期代码缺陷。审查提出的定义变更与旧 generation 迟到发送两个高风险交错场景已补齐并通过，见下列证据。

## 关键边界核对

| 边界 | 当前实现结论 |
| --- | --- |
| 持久窗口与来源 | `window()` 保存 ORIGINAL/PLAN_RESTORE 顺序；派生原文视图排除恢复。恢复没有 sequence、userHistoryId 或历史/工具身份，不走 SourceCommitScope。 |
| 编码兼容 | v1 被解码为 formatVersion=1，expected 编码保持 v1；来源操作的候选为 v2。codec 逐条处理 payload，不按展开消息下标对应条目。旧数组继续来源未知及现有冷重建路径。 |
| 恢复顺序 | 新恢复插在最后完整可见非终态来源单元之后；后续原文追加到现有窗口。保留同 restoreId 的正文不可改写、不可越过原文移动；parser 拒绝恢复拆开工具批次。 |
| 摘要与证据 | 摘要材料、指纹、前缀校验、决策证据及用户计数继续消费 ORIGINAL。可靠覆盖改变撤下恢复，单独移除恢复不推进 appliedSummaryRevision。恢复不进入原文保留额度，实际请求包含其预算。 |
| 计划定义与事件 | 指纹覆盖 ID/版本/目标及规范化文件定义；排除执行状态和活动回合。save 成功及计划外新增路径同步发布，失败/版本冲突无事件。Facade 正确提供事件读取能力。 |
| 完整结果优先 | 仅匹配完整 TOOL_BATCH 内配对成功的 makePlan/updatePlan 结果。pending 批次不算完整证据；检查状态保留 pendingBatch/nextSequence，使结果补齐后再次判定。 |
| 普通续调 | 已检查 epoch、覆盖、定义事件及恢复 ID 相符时跳过计划扫描与额外计划读盘；原文投影和 Token 计量仍按请求执行。 |
| 覆盖与终态 | 覆盖键不含 summaryRevision 或仅终态登记字段；普通终态保留恢复，可靠覆盖与新 epoch 触发新的检查。冷重建仍遵循 MySQL 完整历史来源，不增加重启续跑能力。 |
| 原子提交 | 计划读盘在回调票据之外；恢复提交使用 writer、回调门、租约/终态门、检查状态锁与 L0 CAS，并沿用原绝对截止。状态只在确认窗口提交成功后登记。 |
| 取消与删除 | 读盘后、提交前、计量后复检存活；删除 writer 许可与 tombstone 阻止迟到写入。显式检查代次失效拒绝旧候选；实际 SDK 启动仍由 GenerationAwareModelRequestOrchestrator 核对 generation、截止和回合门。 |
| 预算一致性 | 先提交需要的恢复，再投影分层资料及临时消息、冻结并估算；发出的模型请求使用同一份消息与工具定义。超限沿用受控失败。 |
| 停机降级 | 仅访问显式应用的单键，不启动 Spring 后端、不扫描/清空。Lua 比较完整旧值，并在同一操作中保留剩余 TTL；冲突不覆盖胜出者。 |
| 公共边界 | API/SSE、文件和计划工具协议、摘要提示词、原文软窗口、压缩阈值及前端未改。普通文件工具/计划工具仍会按原工程职责读盘，减少的是请求准备路径的重复读取。 |

## 已核对的运行证据

本评审没有重复启动 Maven 或真实存储测试；独立读取保存的命令、退出码、JUnit、代码状态、实际请求及维护子进程日志：

- `regression-01/command.json`、`junit/`：50 个测试类，**607 项通过，0 失败、0 错误、0 跳过**。覆盖记忆/来源、摘要/证据、计划/文件工具、构建、流式恢复、回合门、缓存工厂和受控 E2E。
- `storage-03/command.json`、`junit/`：3 个测试类，**27 项通过，0 失败、0 错误、0 跳过**。覆盖新恢复存储 7 项及原真实存储回归 20 项；新增独立 Java main 测试真实启动维护子进程，日志确认 DOWNGRADED，Redis 回写 v1 后原文、epoch、摘要依赖相等且 TTL 仍有效。
- `race-01/command.json`、`junit/`：42 项全部通过，已被最终 607 项回归包含，不重复计数。读盘和 Token 计量期间定义变化后，最终仅放行版本 2、恢复条目唯一、消息与计量一致；真实 TokenStream 第二次走已检查 fast path 后取消 sourceGeneration、更换 epoch，再释放旧 Decision，GenerationAwareModelRequestOrchestrator 未发送第二次请求，计划仅读取 1 次。该证据支持沿用现有 generation 门，无需额外增加每请求 Redis 重读。
- `baseline-fc4685e-sorted/command.json`、`junit/`：隔离重跑基线 E2E，退出码 0、1 项通过。独立按 git archive 校对隔离基线的 430 份业务 Java 文件，与 fc4685e 字节一致；测试只增加 planReads 观测并固定 ObjectMapper 的 Map 字段排序，当前 E2E 使用相同排序。
- `comparison.json` 及两边 `tmp/plan-context-e2e/`：已独立重读全部 9 份实际请求，只有各自随机 planId 归一后逐份深度相等，9 项预算估算也逐项相等。该比较使用各版本实际请求，不把旧 E2E 中更早的“旧规则Token”列冒充本轮基线。
- 当前样例为 9 次请求、2 次可靠压缩，计划上下文读取 **11 → 3**；当前产生 2 个恢复 ID，分别在压缩后生成，普通续写沿用。实际最终 Redis 快照字节 **83,666 → 85,222**；这两项已独立读取各自 source-final.json 的字节长度确认。计划本身仍进入请求，收益是减少重复读盘与定位，不是减少恢复正文 Token。
- 受控 E2E 使用真实 Java 流式链、摘要来源/提交、计划与文件工具、工程状态及构建闸门；隔离工程实际写入 3 个文件，计划 v2 最终为 BUILT，闸门通过后调用一次构建进程替身。请求与 Token 估算输入一致，摘要输入不含恢复正文。
- 已独立执行当前 `git diff --check`，退出码 0。

路径均相对于 `.codex/plan-restore-l0/`。运行证据的代码状态覆盖 src 与 pom.xml：regression-01 后仅新增真实 CLI 测试所在文件变化，storage-03 已覆盖该变化且其 code-state 与当前文件逐一匹配；业务代码没有在测试后变化。

中间失败如实保留：unit-04 的新增证据测试漏登记 sourceCommitted，修正后所在测试类在 race-01 和最终回归通过；storage-01 的无写入相容副本问题按 IR-U3-1 修复后，在 storage-02 和最终 storage-03 通过。未固定 Map 字段顺序的早期比较不作为本轮收益证据；最终两边排序一致，不改业务代码以取得比较结果。

## 剩余限制与交接

当前代码质量和必需交错、真实存储及受控 E2E 证据满足独立评审要求，可以由主执行者完成最终 strict 验收清单、验收报告和机器记录。没有需要用户新增决定的范围变化，也没有未解决阻塞。

预算 Token 来自同一 ConservativeChatTokenEstimator，不是模型提供商实测账单；结果属于本次固定样例，不外推统一节省百分比。真实 MySQL/Redis 测试使用已授权隔离随机库和应用键；维护命令只对测试键运行。生成模型和 npm 构建进程使用本地替身，不能据此宣称真实模型效果、完整 npm 构建或生产部署已验证。

v1/v2 新旧写入不能混用，升级/回退遵循已交付停写流程；持久恢复消息不等于服务重启续跑。后续若实现或依赖变更，应重评对应证据；本报告 PASS 不授权提交、推送、重启、部署或归档。
