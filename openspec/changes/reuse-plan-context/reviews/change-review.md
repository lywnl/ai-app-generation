# Change Review: reuse-plan-context

- 评审日期：2026-11-01
- Profile：strict（用户明确指定，元数据 `x-workflow_profile: strict`）
- Source Coverage：Pass。用户批准计划及实施授权已保存，全部有效要求可追踪；旧统一摘要变更仅计划供给方式被明确替代。
- Pre-Apply Decision：PASS
- Validation Confidence：已检查当前磁盘规划、必要工程规则、来源与关键真实调用链；独立执行 `openspec status --change reuse-plan-context --json` 确认四件套齐备，`openspec validate reuse-plan-context --strict` 退出码为 0。此结论证明规划可实施，不证明尚未编写的实现及测试已通过。
- 评审者与独立性：独立执行上下文 `/root/plan_context_review`，未撰写本变更规划或业务代码，直接形成本主报告；主执行者按评审意见修订了引用路径与清空入口说明，本评审者已复核。
- 核验方式：machine。当前为单根项目，Python 3.14.8 可用，规划输入未发现符号链接；6 项任务均为 UTF-8 Markdown checkbox，尚未勾选。
- 机器记录：`reviews/planning-review.json`。本报告不手工生成指纹；主执行者在报告定稿后运行 `record-plan`、`check-plan`，实施前运行 `start-apply`。

## 评审范围与依据

实际 schema 为 `spec-driven`，规划根为 `/Users/lllyw/ai-app-generation/openspec/changes/reuse-plan-context`。本次阅读并核对 `proposal.md`、`design.md`、`tasks.md`、`specs/vue-soft-replan/spec.md`、`sources/user-request.md` 和用户批准计划；使用当前磁盘文件，不以文件名或 CLI 完成状态代替内容检查。

持续约束应作为 `source` 登记：

- 本变更 `sources/` 及 `.codex/plans/计划上下文按需恢复与复用.md` 中的 U1、U2。
- `AGENTS.md` 与实际采用的 `.agents/shared/openspec/` 工作流、来源、评审、验收规则。
- `openspec/specs/vue-soft-replan/spec.md`。
- `openspec/changes/unify-context-summarization/specs/vue-soft-replan/spec.md` 和 `openspec/changes/unify-context-summarization/specs/context-summarization/spec.md`。前者每请求额外完整快照及同版本状态重建要求由 U1 与新 delta 替代；其他行为继续有效。旧评审、验收记录不改写。

实施参考应作为 `reference` 登记，允许按已批准任务修改，不与 `source` 范围重叠：

- `src/main/java/com/lyw/appgeneration/ai/memory/` 及对应测试：已重点核对 `UnifiedContextRequestPreparer`、`CompressionAwareChatMemory`、`LayeredChatMemory`、`TrustedTurnRequestViewProjector`、`L0SourceParser`、`L0SourceOperations`、`SummaryCoverage` 和请求门禁。
- `src/main/java/com/lyw/appgeneration/ai/plan/`、`src/main/java/com/lyw/appgeneration/ai/tools/` 及相关测试：计划定义、完整结果协议、实时触达记录与构建闸门。公共协议的保留义务来自规格，不把整个代码目录冻结为需求来源。
- `src/main/java/com/lyw/appgeneration/ai/AiGeneratorServiceFactory.java`、`src/main/java/com/lyw/appgeneration/core/handler/VueTurnContext.java`、`src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java`：服务缓存、回调／提交边界和原始工具续调。
- `src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryServiceImpl.java` 及摘要测试入口：核实终态登记只增加修订号、真实覆盖推进及受控组合验证的装配条件。

上述参考用于核实方案和登记本次实际输入；不要求改动所有参考文件。未检查与本变更无关的前端、真实线上服务及业务数据库，没有启动环境或付费模型。

## Blocking findings

无未解决阻塞。

评审中发现的两项表述问题已关闭：

1. `proposal.md` 的 C2 原使用不能直接按项目根解析的短路径；已补成真实项目相对路径，引用可追踪。
2. `design.md` 原“记忆清空”说明可能误导实施去调用普通 `clear()`。实际已对齐 L0 的 `TokenAwareChatMemory.clear()` 会拒绝破坏摘要依赖；现设计已明确沿用工厂显式失效／重建入口，新实例从空附件开始，不绕过原拒绝约束。

## Warnings

以下是已确认范围内的实施与验证注意事项，不需要新的产品决策，也不构成暂停条件：

- 生命周期短提交必须使用现有有效边界。`tryRun/tryCallCallback` 提供回调票据；与取消提交严格定序时，应使用既有 `commitPlanState/lease.commitWhileActive` 等实际提交边界并复检应用栅栏。计划读取、L2 召回和估算不能放到这些票据内。任务 1.2、2.1 已要求原子代次与迟到拒绝，验收须用交错测试证明。
- 冷实例与自然缓存淘汰不能被解释成重放原生成或为本功能新增取消行为。生产显式重建、协议恢复及删除继续沿现有调用路径；附件只在仍有效的应用记忆实例内复用。
- 受控端到端的合格范围是实际 Java 门禁、摘要来源选择／覆盖、计划与文件工具、构建闸门及发出的 `ChatRequest`。模型和数据库／Redis 适配器可以是本地替身，构建进程替身须核对隔离目录的真实文件并走真实闸门；不得把直接返回预制成功结果或直接拼装最终消息当成整条链已验证。主执行者已说明拟使用真实 `MemorySummaryServiceImpl`、草稿引擎、选择器与覆盖规划器，符合该边界。通过后仍不等于 npm、真实模型或外部存储验收。
- Token 比较必须固定相同原文、工具定义及临时资料，以旧额外追加规则与新最终请求对比；恢复场景允许仍有计划 Token，不据样例推断线上节省比例。

## Change Source Coverage 检查结果

U1 的完整行为以批准计划为准，U2 仅解除原规划阶段的“本轮仅输出计划”限制。未采纳无来源的功能扩张；本次无数据库迁移、工具瘦身、新通知、窗口／摘要提示词调整或前端改动。

| 来源／有效要求 | proposal | specs | design | tasks | 结论与缺口 |
| --- | --- | --- | --- | --- | --- |
| U1 实现安排 1：每次只读核实，成功完整历史优先，附件其次，缺失恢复，无计划／读失败分开 | What Changes 第 1 项 | “在线变更回合必须维护结构化计划”；“完整计划可见性必须基于真实来源” | 决策 1、6 | 1.1、2.1、2.2 | 覆盖。判定采用可靠裁剪后的实际来源，排除参数、摘要、失败及未完整批次；不是按字符串或版本号猜测 |
| U1 实现安排 2、3：不可变正文，应用／定义／epoch／实际覆盖／位置绑定，复用与撤下，不持久化 | What Changes 第 2、3 项 | “计划恢复附件必须可复用且保持来源顺序”及覆盖、终态、跨回合、隔离场景 | 决策 2、3、4 | 1.2、2.1、2.2 | 覆盖。覆盖键排除单纯 summaryRevision 和终态登记；附件不赋予摘要覆盖或回收权限 |
| U1 实现安排 3、4：固定完整单元后的位置、快照语义、一行文件定义、真实进度来源 | What Changes 第 3 项与非目标 | 同版本状态、已触达未验证、新工具顺序、恢复正文场景 | 决策 4、5 | 1.2、2.1、2.2、3.1 | 覆盖。原始工具参数与结果完整保留，无新增状态通知；锚点只属于完整可见原文单元 |
| U1 实现安排 5：所有入口冻结后计量、同序列发送、超限与迟到受控 | What Changes 第 4 项 | 请求预算场景；取消删除与隔离场景 | 决策 6、Risks | 2.1、2.2、3.1 | 覆盖。初始、续调、同步重新准备、协议恢复共用门禁，附件计入最终预算 |
| U1 验收：正常／修订／压缩／生命周期、真实请求捕获、受控链路与 Token 对比 | Impact | 各成功、失败和恢复场景 | Validation | 1.1～3.2 | 覆盖。分层验证与组合验证有依赖关系，受控替身边界公开，不要求未获授权的环境或付费操作 |
| U1 非目标、D1、D2：保留计划磁盘写入、工具协议、构建、摘要窗口及临时资料，旧记录不改 | 非目标、Capabilities、Source Inputs | 继承既有计划能力；本次 delta 只改计划上下文供给并新增附件约束 | Goals / Non-Goals、Migration Plan | 2.2、3.1、3.2、交付条件 | 覆盖。无需数据迁移；旧记录与本变更记录分离，不把新行为倒写成旧验收事实 |
| U2、D3：独立变更、strict、正式来源、独立评审与真实验收 | Impact、Source Inputs | 不增加产品行为 | Validation、Migration Plan | 交付条件 | 覆盖。6 项 checkbox 只包含可在正式验收前完成的工作，没有验收指纹／归档的循环前置任务 |

反向追踪：定义值对象、原子代次、覆盖键、来源锚点、专用正文和请求冻结均是落实 U1 的必要技术选择，未新增独立产品功能。依赖顺序为 1.1 → 1.2 → 2.1 → 2.2 → 3.1 → 3.2，每组有自己的验证条件。

跨产物一致性：新规格对旧“每次注入最新完整快照”有明确替代依据；“每次仍只读磁盘”与“附件正文复用”不矛盾，前者核实定义和缺失，后者保留历史时序。现有预算中的计划材料按本变更选择后统一计量，窗口、阈值和摘要来源约束继续有效。

## Apply handoff

可在本报告定稿、机器规划基线核验及实施入口登记成功后，从任务 1.1 开始编码。独立评审身份只由本报告事实描述，机器助手用于输入新鲜度核验，不能替代语义检查。

实施必须兑现来源可信度、附件顺序、覆盖／终态区别、跨应用隔离及取消／删除交错测试；组合测试需捕获实际发送请求并验证 `.plan.json` 和文件状态。严格实现验收还需要当前代码的独立评审、完整结构化需求／场景／任务映射及本轮真实运行证据。当前没有业务代码或新测试通过结论。

基线为 `dd51e25`，保留无关 `.DS_Store`。依用户既有授权继续范围内实施，不自动提交、推送、重启、调用线上模型或归档。
