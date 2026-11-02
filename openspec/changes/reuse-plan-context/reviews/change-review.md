# Change Review: reuse-plan-context

- 评审日期：2026-11-01
- Profile：strict。沿用用户指定等级；本轮增加 Redis 值编码迁移及来源隔离风险，等级适用。
- Source Coverage：Pass。U3 明确替代 U1 的存放位置、逐请求读盘和 Redis 编码不变约定；其余来源、协议、预算与生命周期约束继续有效。
- Pre-Apply Decision：PASS
- Validation Confidence：已独立核对当前四件套、来源、已确认计划和关键调用链，执行 `openspec status --change reuse-plan-context --json` 与 `openspec validate reuse-plan-context --strict`，后者退出码为 0。此结论仅证明规划可实施，不证明 U3 实现或测试已完成。
- 评审者与独立性：独立执行上下文 `/root/l0_plan_independent_review`，未编写本轮规划或业务代码，直接形成本报告；主执行者修订了本次指出的两处表述，本评审者已重新读取并复核。
- 核验方式：machine。Python 3.14.8；单根普通文件，当前规划输入无符号链接。任务 1.1—3.2 明确为历史完成记录，本轮 4.1—7.2 为 8 项未完成任务。
- 机器记录：`reviews/planning-review.json`。旧记录仅适用于历史实现；本报告定稿后由主执行者登记新的 `record-plan`、`check-plan`，首次业务修改前执行 `start-apply`。本评审未手写或覆盖机器记录。

## 评审范围与依据

实际 schema 为 `spec-driven`，规划根为 `/Users/lllyw/ai-app-generation/openspec/changes/reuse-plan-context`，本轮实现基线为 `fc4685e`。

已核对当前 `proposal.md`、`specs/vue-soft-replan/spec.md`、`design.md`、`tasks.md`、`sources/user-request.md` 及 `.codex/plans/计划恢复消息进入热窗口.md`。历史目录 `history/fc4685e/` 中的 10 份规划、规格及评审/验收文件逐一与 `git show fc4685e:<原路径>` 对比，字节一致；不把历史 PASS 作为 U3 的通过证据。

持续约束应按 `source` 绑定：本变更 `sources/` 与已确认新计划，`AGENTS.md` 及实际采用的工作流/来源/评审/证据规则，正式 `openspec/specs/vue-soft-replan/spec.md`，以及 `unify-context-summarization` 中的 `vue-soft-replan`、`context-summarization` 规格。计划供给和恢复缓存编码的替代范围由 U3 与当前 delta 明确，其余约束保持。

允许修改的实施参考按 `reference` 绑定，不能当作持续冻结规则：

- L0 类型、codec、`SpringRedisChatMemoryStore`、`AtomicChatMemoryStore`、来源操作/解析、`TokenAwareChatMemory`、`PlanContext`、分层请求投影及准备门禁。
- `SummaryMaterialReader`、摘要服务、决策证据和终态来源路径；相关记忆/摘要测试。
- `AppPlanStateManager`、计划工具和文件成功变更记录路径，`AiCodeGeneratorFacade` 的请求边界装配、`AiGeneratorServiceFactory` 的冷重建和缓存失效路径。
- `VueTurnContext` 的回调票据、`commitSummaryWhileActive`/租约提交边界，应用删除栅栏，以及流式工具原文提交与协议恢复入口。
- 现有 `PlanContextRequestGateTest`、`UnifiedMemoryStorageFixture` 等测试入口与项目 Maven 配置，作为新增验证的实际可用基础。

不要求改动每个参考文件。未运行业务测试、真实服务、模型或维护命令，也未检查无关前端和业务数据；规划验收条件与实现验收严格区分。

## Blocking findings

无未解决阻塞。

两项表述缺口已由主执行者修正并经本评审者复核：

1. 规格“恢复条目不得进入摘要新增材料”原只写不进 L1；现在明确不进 L1/L2、不推进原文 sequence、不占 12,288 Token 原文保留额度，与设计、任务 5.1 和 U3 一致。
2. proposal 的 D2 第二个引用原为不明确的 `specs/context-summarization/spec.md`；现在已补全为真实项目相对路径，持续约束可定位。

## Warnings

以下均为既定范围内的实现与验收重点，不构成新的产品决定或暂停条件：

- **CAS 必须验证真实旧编码。** v1 解码不能把 expected 悄然升级为 v2；测试应使用从旧版 codec 得到的实际字节，而非仅用新 codec 自造一个标称 v1 的样例。降级须保留原文、epoch、摘要依赖和 TTL，精确 CAS 冲突不得被当作成功。现有正常 Redis 写入会按配置重置 TTL，停机降级不能直接假定复用它即可保持原 TTL。
- **原文视图必须贯通所有来源消费者。** 采用 `window()` 保存异构有序条目、`entries()`/`originalMessages()` 提供 ORIGINAL 派生视图，是满足设计的合理内部 API 选择。`SourceCommitScope`、fingerprint、containsPrefix、摘要材料、用户计数及协议恢复均不得从 User/Ai 包装反推内部类型；不能让恢复资料触发真实用户回合或进入决策证据。
- **定义事件不能过早消费。** `AppPlanStateManager.save` 成功与计划外新增路径必须同步更新代次；活动回合重绑定、普通文件触达与构建状态不得发布定义变化。完整批次尚未入窗时必须保留待检查状态，避免只见一条成功计划结果就撤下恢复，或后续批次补齐后永远不再检查。
- **有界提交和失效必须具备明确先后顺序。** 读盘、L2 召回与估算仍在回调票据之外；恢复写入须使用现有 writer、租约/终态提交门、原绝对截止与 L0 CAS。不能只做提交前的布尔检查再无界写入。取消/删除后迟到读取、并发摘要裁剪及原文追加均需要交错测试。
- **旧冷重建与升级边界保持事实一致。** 工厂现状是新实例按 MySQL 完整历史重建新 epoch，不是重放 Redis 中所有工具轨迹；本轮不能把持久恢复消息宣传为重启续跑。v1/v2 新旧后端同时写同一键不受支持，停机切换与显式降级是交付要求。
- **收益以实际度量为准。** 普通续调减少额外计划读盘/扫描，计划正文仍参与每次请求；Redis 快照变大是已确认取舍。读盘次数仅统计请求上下文路径，不能把文件/计划工具自己的正常读盘算作本方案未生效，或宣称所有工具不再读盘。

## Change Source Coverage 检查结果

U1/U2 保留历史原义，U3 明确当前采纳及替代关系；用户已确认恢复随 L0 持久化、不感知手工编辑，并要求执行及测试。没有把候选功能、线上模型、重启续跑、额外通知或业务数据操作加入实施范围。

| 来源／有效要求 | proposal | specs | design | tasks | 结论与缺口 |
| --- | --- | --- | --- | --- | --- |
| U3：恢复消息进入有序 L0、独立类型/ID、位置稳定、最多一份、随 Redis 持久化 | What Changes 1、2 | “计划恢复消息必须可复用且保持来源顺序”及缺失恢复/新工具顺序场景 | 决策 1、5、6 | 4.1、5.2、6.2 | 覆盖；没有为恢复分配真实来源身份 |
| U3：原文、覆盖、证据、用户计数隔离，不占原文额度但计完整预算 | What Changes 4、非目标 | 恢复正文/摘要材料、协议恢复、请求预算场景 | 决策 1、2、6 | 5.1、5.2、6.2 | 覆盖；原始工具记录仍正常进入原文压缩 |
| U3：旧数组/v1/v2、v1 CAS 升级、单键停机降级、保留 TTL 与原文 | What Changes 2、Impact | “计划热窗口编码必须兼容升级与停机回退”全部场景 | 决策 3、Migration Plan | 4.1、4.2、7.1 | 覆盖；不新增表/列/键，不清空或全库扫描 |
| U3：初始/epoch/覆盖/定义事件检查，无计划只检查一次，普通状态不触发，不监听手工编辑 | What Changes 3、非目标 | 有效窗口连续续调、服务端定义变化、无计划与读取失败场景 | 决策 2、4 | 6.1、6.2 | 覆盖；同步落盘标记及未完成批次补查有明确约束 |
| U1 保留＋U3：完整成功工具批次优先，失败/冲突/参数/摘要不是证明，匹配完整定义 | What Changes 3、5；Source Inputs | “完整计划可见性必须基于真实来源”全部场景；计划修订场景 | 决策 4 | 5.2、6.1 | 覆盖；不缩减原始工具参数与结果 |
| U1 保留＋U3：覆盖推进/终态修订区分，同步等待、异步下次，跨回合/冷重建/协议恢复 | What Changes 4、5 | 可靠覆盖、仅终态、跨回合、协议恢复场景 | 决策 2、4、5 | 5.1、5.2、6.2、7.1 | 覆盖；不改变既有冷重建范围 |
| U1 保留＋U3：取消、删除、隔离、截止、并发 CAS、冻结计量与发送一致 | What Changes 5 | 取消删除、并发提交、请求预算场景 | 决策 5、6 | 6.2、7.1、7.2 | 覆盖；恢复持久化成功后才放行 |
| U1 保留、D1/D2：真实工程状态、TOUCHED 语义、工具/计划协议、构建和非目标 | What Changes 5、6 | 继承计划能力、同版本状态、已触达未验证及恢复正文场景 | 决策 6、Risks | 6.1、7.1、7.2 | 覆盖；不新增文件状态通知，不改摘要/阈值/RAG/前端 |
| U3、D3：同变更修订、旧证据原样保留、strict、真实存储和受控 E2E、指标比较 | Impact、Source Inputs | 可检验的成功/失败/持久化场景 | Validation、Migration Plan | 4.2、7.1、7.2、交付条件 | 覆盖；原六项明确为历史，独立代码评审/验收不是循环前置任务 |

反向追踪：异构窗口、显式原文视图、定义指纹与代次、CAS 兼容、降级及受控 E2E 都用于满足 U3 或保留现有约束，没有独立功能扩张。任务依赖可推进：4.1 → 5.1 → 5.2 → 6.1 → 6.2；4.2 依赖 4.1，7.1 汇合 4.2/6.2，7.2 完成回归。不存在把正式验收作为其自身前置任务的循环。

跨产物一致性：已由 U3 替代的“独立附件不进 L0、编码不变、每次读盘”仅在明确标识的历史记录出现；当前规格、设计和新增任务使用热窗口方案。正文仍按每次请求计量与“减少重复装配检查”并不矛盾。

## Apply handoff

本报告定稿后，可登记新规划基线并在实施入口核验成功后从任务 4.1 开始。现有 `.DS_Store` 保留；不自动提交、推送、部署、重启或归档。

真实验收须使用已授权的本机隔离 `context_summary_it_*` 随机库及隔离应用三个 Redis 键，验证并清理；规划通过不扩大权限。模型替身测试应驱动真实 Java 门禁、工具、来源/摘要与构建流程，捕获实际发送请求，公开模型及构建替身边界。必需测试、原始日志和当前实现独立质量评审完成后，方可形成 U3 的最终验收结论。
