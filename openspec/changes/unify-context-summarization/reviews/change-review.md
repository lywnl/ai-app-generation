# Change Review: unify-context-summarization

- 评审日期：2026-10-31；本次为 U13 授权的 B1 修订后独立复审。
- Profile：`strict`。涉及摘要覆盖及决策证据的持久化迁移、MySQL／Redis 提交边界和模型文字与可信事实的隔离；等级与 `.openspec.yaml` 的 `x-workflow_profile` 一致。
- Source Coverage：**Pass**。有效来源、替代关系和双向追踪成立；B1 所需的读取相容性、写入前提及验收安排已落实到当前规格、设计和任务，没有发现实质性范围遗漏或扩张。
- Pre-Apply Decision：**PASS**。
- Validation Confidence：重新读取当前四类规划、来源登记及原报告，重点核对 B1 的读写交错、完整旧副本、新摘要配旧窗口、epoch／旧格式、截止与取消，并复查相关现有代码和测试入口；未变化部分复用前次独立核对。本轮 CLI、依赖与输入检查通过。规划依据足够支持实施，但未执行业务测试、数据库迁移、业务服务或真实模型调用，不代表实现验收。
- 评审者与独立性：原独立执行上下文 `/root/independent_plan_review`，未参与本变更规划撰写，也未参与 U12 的规划修订；重新核对后直接更新本固定主报告。主执行者提供 CLI 和输入可用性结果，未代替本评审决定结论。
- 核验方式：`machine`。本轮输入检查及已确认的 Python 3.14／证据助手满足单根普通文件和 UTF-8 Markdown 任务清单的机器核验条件。
- 机器记录：`reviews/planning-review.json` **待主执行者成功完成 `record-plan`／`check-plan` 后才生效**。本报告给出真实规划 PASS；截至报告定稿，独立评审者未生成该记录，也未宣称机器登记已经完成。

## 评审范围与依据

项目根为 `/Users/lllyw/ai-app-generation`。实际 schema 为 `spec-driven`；完整读取 [proposal](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/proposal.md)、[design](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md)、[tasks](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/tasks.md)、元数据，以及以下三个规格文件：

- [context-summarization](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/specs/context-summarization/spec.md)。
- [conversation-decision-evidence](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/specs/conversation-decision-evidence/spec.md)。
- [vue-soft-replan](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/specs/vue-soft-replan/spec.md)。

持续约束与实现参考按用途区分，不用冻结待改源码来代替公共契约：

| 类型 | 实际采用范围 | 处理原则 |
| --- | --- | --- |
| 已确认需求及决定／持续约束 | D1：[统一摘要与计划注入优化需求](/Users/lllyw/ai-app-generation/.codex/plans/统一摘要与计划注入优化需求.md)；[来源目录](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/sources/user-request.md) 中 U1～U13、R1 及其采纳关系 | 作为 `--source`；来源目录整体登记以识别增删。R1 是整改依据，U12／U13 仅增加修订与复审授权，不新增产品要求。 |
| 工程与流程／持续约束 | D2：[AGENTS.md](/Users/lllyw/ai-app-generation/AGENTS.md)；D3：[workflow-policy.md](/Users/lllyw/ai-app-generation/.agents/shared/openspec/workflow-policy.md)、[sources.md](/Users/lllyw/ai-app-generation/.agents/shared/openspec/sources.md)；本轮实际采用的 [review-change 技能](/Users/lllyw/ai-app-generation/.agents/skills/openspec-review-change/SKILL.md)、[review-checklist.md](/Users/lllyw/ai-app-generation/.agents/shared/openspec/review-checklist.md)、[核验方式](/Users/lllyw/ai-app-generation/.agents/shared/openspec/manual-verification.md)；D4：[config.yaml](/Users/lllyw/ai-app-generation/openspec/config.yaml) | 保持授权、阶段、独立评审和证据规则；均为持续约束。 |
| 正式规范／持续约束 | D5：[vue-soft-replan](/Users/lllyw/ai-app-generation/openspec/specs/vue-soft-replan/spec.md)、[vue-build-progress-guard](/Users/lllyw/ai-app-generation/openspec/specs/vue-build-progress-guard/spec.md)、[vue-mutation-promotion](/Users/lllyw/ai-app-generation/openspec/specs/vue-mutation-promotion/spec.md) | 本变更仅定向修改已声明的计划注入及普通记忆职责条款；其余工具、构建、升级、反馈和取消约定继续约束实施。 |
| 代码、测试与待改文档／实现参考 | 来源登记 C1～C12 所列记忆、计划、回合、持久化、请求入口、实体、SQL、测试及工程清单 | 后续使用 `--reference`。`README.md`、两份 schema、迁移说明和业务代码允许按任务修改，不能与持续约束路径重叠。 |
| 补充实现参考 | [TurnRequestBoundary.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/TurnRequestBoundary.java)、[LayeredChatMemory.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/LayeredChatMemory.java)、[ContextContinuationGate.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/ContextContinuationGate.java)、[MemoryTokenProperties.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/config/MemoryTokenProperties.java)、[FileToolBudgetGuard.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/tools/FileToolBudgetGuard.java) | 沿用首次评审增加的读取竞争、请求边界和预算参考。本次复审未增加其他 reference；这些实现允许按任务修改。 |

首次源码检查覆盖用户记录提交、L0 读写及来源身份、摘要草稿与持久化、可信终态及原文投影、计划读取、请求边界传播、转录采集、旧数据重建和 API 序列化。本次实际复查摘要读取、分层记忆隐式读取、Redis CAS、冷重建和回调票据入口，核对修订确实要求改造这些现有行为，而非假定新接口已经存在；未变化的工程规则、正式规范及其余业务依据复用前次检查。测试检查仍只核对入口与替身边界，没有执行新功能测试，也没有对整个业务仓库作实现代码质量评审。仅更新本报告，未修改来源、规划或业务代码。

本轮复用的辅助校验结果如下，均由主执行者在本轮实际取得，规划及来源在评审期间未再修改：

| 检查 | 结果及界限 |
| --- | --- |
| `openspec validate unify-context-summarization --type change --strict --no-interactive --json` | 退出码 0，`valid=true`、`issues=[]`；只证明 CLI 所检查的格式，不证明语义可实施。 |
| CLI schema／产物状态 | `spec-driven`，proposal、specs、design、tasks 齐备。 |
| 任务结构与依赖 | 35 项全部未勾选；92 条显式依赖均指向前项，无重复编号和依赖环，各项均有验证安排。5.4 已增加对 5.3 的依赖，使读取校验建立在窗口下界原子提交之后；未把验收结论或归档设成循环前置 checkbox。 |
| machine 输入及助手 | 12 个持续约束入口、44 个登记／补充实现参考文件及实际规划输入可读，位于同一授权根，无符号链接且两类路径无重叠；Python 3.14 与 `evidence.py` 接口沿用已确认结果。主执行者将在报告定稿后复核输入未变化；此项不等同于已运行 `record-plan`。 |

## Blocking findings

当前无未关闭阻塞。保留 B1 的根因和独立关闭依据如下。

### B1：请求读取没有闭合“旧 L1＋已被新修订裁剪的 L0”竞争窗口

- **状态**：已关闭，关闭的是规划缺口；相应代码与真实验证仍待实施。
- **整改来源**：U12 授权修订、R1 登记技术问题、U13 授权本次独立复审。
- **原根因**：旧方案可能先读 r1（仅覆盖批次 1～20），后台提交 r2 并回收至批次 35，随后请求读到仅剩 36 以后的 L0；若仍使用 r1，21～35 会同时缺失于此次摘要和原文。固定消息列表、单独的摘要原子快照和 Token 合规都不能排除这个缺口。

本轮重新确认现有 [MemorySummaryServiceImpl.java:990](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryServiceImpl.java:990)、[LayeredChatMemory.java:133](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/LayeredChatMemory.java:133) 仍是分别读取，且 [CallbackGate:1067](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnContext.java:1067) 只是允许并行的生命周期票据。修订没有将这些现有机制误当作已提供联合快照，而是明确增加以下协议及实施落点：

| 原关闭条件 | 当前规划依据与独立复核结果 |
| --- | --- |
| 已回收来源必须由此次所用摘要承接 | [规格:121](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/specs/context-summarization/spec.md:121) 增加独立 Requirement 及六个可观察场景；[design.md:76](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:76) 约束同一修订唯一、后继覆盖不撤销；[design.md:90](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:90) 将原文回收与不倒退的 `appliedSummaryRevision` 同次 CAS。任务 2.2、2.3、5.2、5.3 已承接。 |
| 拒绝旧摘要配新回收窗口，并整对重读 | [design.md:94](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:94) 定义 `CompatibleMemorySnapshot`：新已提交数据库视图读取 S，一次 Redis 读取取得原文及元数据，验证 `S.summaryRevision >= L0.appliedSummaryRevision` 及来源关系，不相容则废弃整对候选。任务 5.4 明确依赖 5.3，不再把版本比较当作来源校验的替代。 |
| 新摘要配旧窗口、完整旧副本仍可使用 | [design.md:104](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:104) 明确修订无需相等；新摘要可以投影尚未物理回收的旧原文，完整旧副本可在回合／请求代次有效时继续使用。普通回收不会改变本地副本，不能借此绕过协议恢复导致的来源失效。 |
| 后续组装不能再次混入活动窗口 | [design.md:100](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:100)、[design.md:125](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:125) 和 [design.md:174](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:174) 要求固定 payload 与来源元数据，分层视图只消费该对快照；任何换对均重新组装并计量完整请求。任务 6.3、7.2 覆盖初始、续调和两类恢复入口。 |
| epoch／旧格式／冷重建不能清零所需摘要依赖 | [design.md:91](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:91)、[design.md:235](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:235) 和 [Migration Plan:274](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:274) 要求以实际重建所用摘要建立下界，未知下界受控处理；新 epoch 不解除旧省略来源依赖，旧序号不授权裁剪新消息。任务 8.1、8.4 与 2.2、2.3 对齐。 |
| 重读受原截止、取消、删除及反馈规则约束 | [design.md:102](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:102) 沿用同一 `AdmissionDeadline` 和有界读取执行器，不重置 60 秒、不触发摘要／裁剪、不重复初始化计划或领取反馈；失效后停止重读和启动。规格六场景中的截止、回合失效及任务 5.4、7.2、9.1 已覆盖。 |
| 读取竞争进入真实验收，而非只查最终写入状态 | [tasks.md:52](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/tasks.md:52) 的 5.5 在真实 MySQL／Redis 中控制读 r1、提交 r2 并回收、再继续读的交错；[tasks.md:85](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/tasks.md:85) 的 9.1 同时断言存储、实际发送消息和重新估算。完整旧副本、新摘要配旧窗口、epoch、截止和取消也有对应验证。 |

**关闭理由**：写入下界与摘要覆盖的单调关系保证通过版本及来源校验的 S 能承接所捕获窗口因摘要而省略的来源；不通过时整对重读或失败关闭。捕获成功后禁止再次读取活动窗口，消除了“先判断相容、后拼入新 L0”的第二个窗口。旧副本、新摘要配旧窗口和冷重建例外均有明确边界，且不增加跨存储长锁或重置截止。修订充分满足原 B1 的设计与验收关闭条件，本轮未发现新的实质问题。

此处为对当前规格、设计和任务的独立判断。相关验证尚未执行；不能据此宣称读取竞争已经在实现中修复或真实存储测试已经通过。

## Warnings

没有需要用户重新选择业务范围的实质性警告；B1 已在原要求内完成规划修订并经本次独立复审关闭。

以下已声明限制不构成额外规划阻塞，但实施与交付须保留：

- 新覆盖或新 L0 格式写入后，旧二进制不能直接接管；[design.md:274](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md:274) 已要求停入流、处理在途回合及兼容回退，任务 8.4 应落实操作说明和夹具验证。不得将“新增列”误解为全过程可直接降级。
- 本次语义保留效果仍须由任务 9.2 的真实长任务验证。现有 [MemorySummarySnapshotIntegrationTest.java:63](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/MemorySummarySnapshotIntegrationTest.java:63) 明确替代数据库／Redis IO，不能作为真实存储证据；[UserMemoryMysqlIntegrationTest.java:38](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/UserMemoryMysqlIntegrationTest.java:38) 只供复用隔离测试方式，不是本变更的既有验收结果。

## Change Source Coverage 检查结果

以 D1 当前修订版及来源登记的有效采纳关系为准。U2 是讨论输入，最终采纳由 D1、U5、U6 承接；U3 仅收窄 U1 的资料生命周期范围，U5 仅替换早期“收尾继续整轮折叠”的处理，均不使其他要求失效。U4 保留用户决定与有依据的已执行模型方案；U6～U13 是逐阶段授权，U12 授权整改 B1，U13 授权本轮复审及真实 PASS 后的基线登记，均未授权编码。R1 记录首次发现及整改范围，其原始问题和必要关闭依据保留在本报告 B1。

下表表示规划落实关系，不表示任务完成或测试已通过：

| 来源／有效要求 | proposal | specs | design | tasks | 结论与缺口 |
| --- | --- | --- | --- | --- | --- |
| D1 FR-01；U1、U2、U5：单份 L1，当前完整批次与历史共同摘要，当前用户原话受保护 | What Changes 1～3；context-summarization | 单份滚动摘要、工具配对、近期原文、增量复用、摘要与原文完整衔接 | 来源身份；组合覆盖；相容读取；统一草稿；两阶段回收 | 2.1～2.4、4.1～4.4、5.1～5.4、6.2～6.3、7.3 | 覆盖；B1 对应的读取竞争已落实为可执行协议和验证。 |
| D1 FR-02：12,288／3,072 Token、三段阈值、60 秒、分块及实际请求预算 | What Changes 2；Impact 验证范围 | 完整请求门禁、近期软窗口、分块失败与输出上限、相容重读截止 | 相容读取；请求快照；统一草稿和绝对截止 | 4.2～4.3、5.1、5.4、7.2～7.4、9.2 | 覆盖；数值不变，重读保留原截止并重新计量，不以 Token 合规代替完整性。 |
| D1 FR-03：每次读取最新计划，同版本状态变化、只读快照、一次绑定 | What Changes 4；Modified Capabilities | 在线变更回合必须维护结构化计划及新增各请求场景 | 请求时读取并冻结最新计划，移出固定资料 | 7.1～7.2、7.5、9.2 | 覆盖；未把只读计划查询扩张为写权限或重复初始化。 |
| D1 FR-04；U3：RAG、图片、Skill 和运行反馈保持现有范围 | Non-Goals；What Changes 2 | 临时资料必须保持既有回合范围；软 Replan 反馈兼容 | 请求资料冻结及反馈送达边界 | 7.4～7.5、9.3 | 覆盖；没有保留被排除的资源库、自动淘汰、回读或跨回合缓存。 |
| D1 FR-05；U4：原始决策、来源、实施／验证区分，短回合与裁剪前存证 | What Changes 5；conversation-decision-evidence | 全部决策证据 Requirements，含无依据理由、未执行建议、否决替代和资源限制 | 用户记录独立证据；共享采集边界；按来源修订合并 | 1.1、3.1～3.4、4.1、4.3～4.4、6.1、8.1～8.2 | 覆盖；保存用户记录是满足首轮提前留存的技术选择，未冒充用户原话。 |
| D1 FR-06；U5：可信结果保留，合法收尾不强制折叠，实际请求保留原文，旧结果去重 | What Changes 3、6 | 近期原文、可信工程结果、历史结果去重、异常边界、摘要与原文完整衔接 | 可信收尾与历史投影共同改造，仅消费相容快照 | 6.1～6.3、7.2、7.5、9.1、9.3 | 覆盖；正常／异常路径和双重投影均有任务，捕获后不再隐式读取活动窗口。 |
| D1 第四部分：游标零、覆盖修订、来源变化、原子提交、迟到回调 | What Changes 6；Impact 持久化与风险 | 摘要与覆盖一致提交；摘要与原文完整衔接；摘要失败与终止 | 来源 epoch、修订 CAS、读取下界、整对重读、提交后回收、完整快照缓存 | 1.1、2.2～2.3、4.4、5.1～5.5、6.1 | 覆盖；B1 的读写前提、相容判断、失败路径和真实竞争验收均已补齐。 |
| D1 兼容边界；U3：旧历史、冷重建、无自动续跑、隔离、删除和 L2 保持 | Non-Goals；Impact 兼容 | 旧历史与冷重建；决策隔离及旧证据缺失 | 冷重建对齐持久身份；旧格式适配；迁移与回退 | 1.2～1.3、8.1～8.4、9.3 | 覆盖；不补造工具轨迹，不把应用决策自动写入 L2。 |
| D1 对外接口、监控和第五部分交付 | Impact 验证范围；Non-Goals | 上下文监控；全部可观察场景，含新增六个相容读取场景 | 实体验证、真实请求、存储读取交错、故障、迁移和真实模型验证层 | 1.1、1.3、3.4、5.5、8.3～8.4、9.1～9.3 | 覆盖；5.5／9.1 明确真实存储交错及最终发送断言，不把测试名称当真实证据。 |
| D5：计划工具、构建保护、只读升级、反馈与终态兼容 | Modified Capabilities；其余两能力保持 | vue-soft-replan 仅两项 Requirement 定向修改；其余沿正式规范 | 请求计划读取与初始化分离；终态／反馈沿原生命周期 | 6.1～6.2、7.4～7.5、8.2、9.3 | 覆盖；现有计划场景保留，未引入新的 SSE 类型或工具字段。 |
| D2～D4；U6～U13：strict 工作流、来源、工程及阶段授权 | Source Inputs 及链接来源登记 | 不适用：阶段授权不应变成业务行为规格 | strict、Migration Plan、非目标和执行边界 | 前言；Workflow follow-up | 覆盖。proposal 的 U7 是该产物生成阶段记录；当前修订／复审分别以 U12／U13 为准。 |
| U12、R1：在既有要求内纠正 B1 | What Changes 6 | 请求中的摘要与原文必须完整衔接及六场景 | 读取下界、单调覆盖、CompatibleMemorySnapshot、原截止及旧格式适配 | 2.2～2.3、5.2～5.5、6.3、7.2、8.1、8.4、9.1 | 覆盖；未改预算、未扩展产品功能，独立关闭依据见 B1。 |

**反向追踪**：版本化 L0、`userHistoryId`、部分前沿、修订 CAS、独立证据字段、只读计划读取器及新缓存键，分别用于实现 D1 的身份、覆盖、并发、存证和请求一致性要求。本次明确 `appliedSummaryRevision` 的读取下界及 `CompatibleMemorySnapshot`，直接用于消除 R1 所述缺口，受 U12 授权且不改变 D1 范围。没有发现独立资源系统、额外决策模型调用、全量工具事件数据库或其他未经确认的实质功能扩张。C1～C12 提供现状与兼容依据，公共行为已写入规划，源码本身不被当成永久约束。

**跨产物一致性**：proposal、三份 specs、design、tasks 的范围、预算、可信结果职责和非目标一致；旧“收尾整轮折叠”及“计划固定一轮”没有继续作为新目标。`vue-soft-replan` 对原普通记忆不变条款作了显式定向修改。本轮读取下界、单调覆盖、整对重读与完整旧副本规则在规格、设计、任务和迁移说明中相互对应；没有发现新旧修订要求并存的矛盾。

## Apply handoff

当前规划可交给 `openspec-apply-change`；先由主执行者完成机器基线登记与复核，实际实施仍须明确实施授权。B1 已关闭，无待用户选择的新产品决定。本轮只开展复审并更新报告，不开始业务编码或环境操作。

实施依现有依赖图推进：数据契约与身份／L0 接口 → 决策存证与统一材料 → 摘要提交、回收及相容读取 → 可信收尾／请求投影 → 最新计划及门禁 → 冷重建、隔离和跨模块验证。尤其保留 5.3 → 5.4 → 6.3／7.2 的依赖，不能只在发送端补一个版本判断而遗漏写入下界与组装入口。真实存储测试依赖明确测试目标；未经执行的真实任务继续保持未完成。

必需验证包括任务 1.3 的迁移重入及旧数据保留，5.5 的真实事务／Redis 故障、修订边界和 B1 读取交错，9.1 的 L0／数据库／实际模型请求三处核对，9.2 的生产预算下连续三次真实摘要与跨回合检查，以及 9.3 的非 Vue、L2、取消／删除和协议兼容。还须核验旧数组、新 epoch 的摘要依赖、完整旧副本及新摘要配旧窗口的合法路径；不得以替身、跳过状态或降低预算代替真实验证。

执行前确认 Java 25／Maven 和实际测试目标；数据库、Redis、模型及迁移操作复用已有授权，未覆盖的具体环境操作按项目规则处理。本轮不启动这些环境。完成实现后仍需 strict 独立代码质量评审和实现验收；规划通过、实施授权、真实环境操作和发布授权相互独立。

本报告定稿且输入复核无变化后，主执行者可按上述分类登记实际规划、持续约束、44 个实现参考和本独立报告；strict 的 `--report`／`--independent` 使用本报告同一路径，并立即执行 `check-plan`。首次获准实施且存在可推进任务时再核对并登记实施入口。机器记录只证明文件基线新鲜度，不能替代后续实现、独立代码质量评审或真实验收。
