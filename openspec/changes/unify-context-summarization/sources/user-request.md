# 用户需求与已确认决定

## 本次登记边界

- 变更名称：`unify-context-summarization`。
- 项目及 CLI 规划根：`/Users/lllyw/ai-app-generation`；规划根来源为 `nearest`，未选择独立 store。
- 变更目录：`openspec/changes/unify-context-summarization/`。
- Schema：`spec-driven`；已通过 `openspec schema which "spec-driven" --json` 核对，来源为 CLI 随附的 `package`，无同名覆盖项。
- 风险等级：`strict`，具体依据见下文。
- 本文件建立于骨架阶段，创建授权见 U6，proposal、specs、design、tasks 生成授权分别见 U7、U8、U9、U10；首次规划评审授权见 U11，B1 规划修订授权见 U12，本次独立复审授权见 U13。此文件是来源登记，各规划产物状态以实际文件及 CLI 状态为准，不代表规划评审或实施已完成。
- 以下编号用于来源追踪；需求继续引用 D1 中已有的 FR-01～FR-06，不另建平行需求编号。

## D1：已确认的修改需求文档

- 日期／原始位置：2026-10-31，项目内 `.codex/plans/统一摘要与计划注入优化需求.md`。
- 类型／确认状态：已确认需求文档，持续约束；用户通过 U5 要求保存修订，并通过 U6 明确要求根据已确认文档创建本变更。
- 采纳范围：采用当前修订版全文，包括目标与非目标、FR-01～FR-06、持久化与兼容要求、验收标准。优先注意 FR-06 对可信结果记录、回合收尾折叠和请求投影的区分。
- 引用方式：保留原文档作为正式来源，不在本目录复制整份需求文档。
- 替代关系：当前修订版承接 U1～U5；早期对话中与当前文档不一致的候选方案不再构成本次要求。

## U1：整体优化与需求文档请求

- 日期／原始位置：2026-10-31，本会话中请求编写修改需求文档的用户消息。
- 必要原文：

> 结合上述的讨论优化 和以下优化的两点让模型看到最新计划，并管理临时资料的生命周期。 补齐进入 L1 的关键决策信息。  现在写一份修改的需求文档 有任何不明确的需求直接问我

- 确认状态：用户明确提出；具体边界由 U3、U4、U5 及 D1 收口。
- 采纳范围：统一上下文压缩、最新计划注入、明确临时资料使用边界、补齐 L1 关键决策信息。
- 替代关系：“管理临时资料的生命周期”的范围由 U3 收窄为保持既有 RAG／图片注入行为；回合收尾和结果投影范围由 U5 修订，其余要求继续有效。

## U2：历史摘要与当前工具链采用统一方案

- 日期／原始位置：2026-10-31，本会话中关于统一摘要的讨论。
- 必要原文：

> 直接改为跟历史摘要压缩一起不行吗

> 近期窗口保留多大 按条数还是按照token deerflow是如何做的

- 确认状态：原文是方案讨论和询问；最终采纳依据为 D1、U5、U6，不把上述问句单独视为编码授权。
- 采纳范围：一套压缩器、一份 L1 会话滚动摘要、按 Token 选择的近期原文窗口；当前已完成工具批次可与旧历史共同参与摘要。具体预算采用 D1 的 FR-02。
- 替代关系：替代早期“另外维护一份本轮执行摘要”的候选建议；不改变 L2 的独立用户偏好职责。

## U3：恢复行为与临时资料保持现状

- 日期／原始位置：2026-10-31，本会话的资料回读选择题回复及后续澄清消息。
- 必要原文：

> 保持现状 每次注入到请求提示词中

> 回到第一个问题 后端重启生成中断 不能保持跟现在一样吗 还有第二个 现状是什么样子

- 确认状态：用户明确要求保持现状；经现有代码解释后写入 D1，并由 U5、U6 承接。
- 采纳范围：正常取消、超时、异常和冷重建沿用现有外部行为；不增加原任务自动续跑、完整工具事件恢复。Vue 首轮满足条件时准备 RAG 和图片，同一用户回合的模型请求复用一份资料，下一回合不自动继承整个资料列表。计划独立改为读取最新状态。
- 替代关系：收窄 U1 的临时资料生命周期要求；不采纳新增持久资源库、按需回读工具、资料自动淘汰或跨回合资源缓存的候选建议。

## U4：关键决策来源范围

- 日期／原始位置：2026-10-31，本会话“决策记录”选择题回复。
- 必要原文：

> 用户决策与执行方案 (Recommended)

- 确认状态：用户已选择；具体约束写入 D1 的 FR-05。
- 采纳范围：保留用户明确约束、否决原因，以及模型有原文依据且实际执行的方案理由，区分来源、实施状态和验证结果；决策证据独立于可信工程结果保存。
- 替代关系：未选择“仅用户明确决策”的更窄方案；不采纳无依据补写理由或把未执行建议记成已完成的行为。

## U5：保留可信结果，修订折叠和请求投影

- 日期／原始位置：2026-10-31，本会话讨论回合结束后的结果投影后，用户要求更新计划文件的消息。
- 必要原文：

> 按照这个修改一下计划文件

- 确认状态：用户明确要求按前一条已澄清建议修订；修订结果为 D1 的当前版本。
- 采纳范围（对承接内容的概括，不作为用户逐字原话）：保留并精简可信结果生成与持久化；取消合法完整回合结束时以一条结果投影替换全部工具历史；请求投影保留近期窗口内的工具原文；已被摘要覆盖的历史结果不逐轮重复注入；保留异常与工具协议边界。
- 替代关系：替代早期文档中“回合收尾继续原样折叠工具链”的处理，不替代可信事实生成和落库职责，也不授权删除全部结果记录。

## U6：创建骨架阶段的授权

- 日期／原始位置：2026-10-31，本会话创建骨架阶段显式调用 `openspec-new-change` 的用户消息。
- 必要原文（按原消息内容分行，省略命令续行符）：

> 根据已确认的 需求文档创建变更骨架，登记范围、需求来源和风险等级。
> 遵循当前项目规则与 schema，创建完成后停止。

- 确认状态：骨架阶段的明确授权，该阶段已完成。
- 采纳范围：创建一个变更骨架、登记风险与必要来源，然后停止。
- 替代关系：不改变 D1 的业务需求；明确限定本轮阶段，不包含 proposal、specs、design、tasks、评审、业务编码、数据库迁移执行、真实模型调用、提交推送、部署或归档。

## U7：继续生成一份就绪规划产物的授权

- 日期／原始位置：2026-10-31，本会话生成 proposal 阶段显式调用 `openspec-continue-change` 的用户消息。
- 必要原文（按原消息内容分行，省略命令续行符）：

> 继续 unify-context-summarization变更，按 schema 依赖生成下一份就绪规划产物。
> 复用已确认结论，不重复抄写已有文档。
> 需要我决定的需求或更改先询问；完成本份后停止，不自动评审。

- 确认状态：proposal 阶段的明确授权，该阶段已完成；当时 CLI 状态与产物指令确认生成产物为 `proposal`。
- 采纳范围：沿用 D1 和既有确认结论，生成一份 proposal 并补充必要来源登记；范围、行为、兼容性、成本或验收的新决定须先澄清。
- 替代关系：在 U6 已完成骨架的基础上增加该阶段的单份规划生成授权，不改写 U6 的历史授权，也不改变 U1～U5 与 D1 的业务范围；该授权不包含后续产物、评审或实施。

## U8：继续生成下一份 specs 的授权

- 日期／原始位置：2026-10-31，本会话在 proposal 完成后要求继续的用户消息。
- 必要原文：

> 继续下一份

- 确认状态：specs 阶段的明确授权，该阶段已完成；承接 U7 的单份产物约定，生成 schema 就绪产物 `specs`。
- 采纳范围：沿用 D1、既有来源和 proposal，按其三个能力完成同一份 specs 产物，并检查规格格式、完整性与依赖状态；需要用户决定的新需求或行为变更仍先澄清。
- 替代关系：在 U7 已完成 proposal 的基础上增加本次 specs 生成授权，保留 U6、U7 的历史含义及全部有效业务边界；完成本份后停止，不自动生成 design、tasks，不自动评审或实施。

## U9：继续生成下一份 design 的授权

- 日期／原始位置：2026-10-31，本会话在 specs 完成后要求继续的用户消息。
- 必要原文：

> 继续

- 确认状态：design 阶段的明确授权，该阶段已完成；当时 CLI 状态确认下一份就绪产物为 `design`。
- 采纳范围：复用 D1、proposal 和完整 specs，结合实际代码展开来源身份、摘要覆盖、原子提交、计划刷新、决策存证、兼容迁移及验证边界；补充必要实施参考，不改写已确认业务要求。
- 替代关系：在 U8 已完成 specs 的基础上增加本次 design 生成授权，保留前序授权的历史含义；完成本份后停止，不自动生成 tasks、评审或实施。

## U10：继续生成下一份 tasks 的授权

- 日期／原始位置：2026-10-31，本会话在 design 完成后要求继续的用户消息。
- 必要原文：

> 继续

- 确认状态：tasks 阶段的明确授权，该阶段已完成；当时 CLI 状态确认下一份就绪产物为 `tasks`。
- 采纳范围：按既有规格和设计拆分实现、依赖、聚焦测试、文档及跨模块真实验证，补充必要来源，并检查任务结构和规划格式；不把任务生成视为实施完成。
- 替代关系：在 U9 已完成 design 的基础上增加本次 tasks 生成授权，保留全部既有需求与阶段授权的历史含义；规划齐备后停止，不自动评审或实施。

## U11：规划评审与基线登记授权

- 日期／原始位置：2026-10-31，本会话显式调用 `openspec-review-change` 的用户消息。
- 必要原文（按原消息内容分行，省略命令续行符）：

> 评审 变更的规划，核对需求、设计、任务依赖、验收安排和风险等级。
> 区分持续约束与允许实施修改的参考代码，按实际结论记录规划基线。
> 有阻塞明确指出；需要我决定的事项先询问。本轮不写业务代码。

- 确认状态：用户明确授权首次评审，该次评审已形成 BLOCK 结论，具体技术缺口见 R1；变更等级保持 strict。
- 采纳范围：由独立评审者核对当前来源、规划、相关实现及验收安排，保存真实评审结论；仅在真实 PASS 后登记并复核可实施规划基线。遇到需求选择先询问，不将评审授权扩展为业务编码。
- 替代关系：在 U10 已完成 tasks 的基础上增加本次评审授权，不改变既有业务要求、前序授权或实施与发布边界。

## U12：纠正 B1 的规划修订授权

- 日期／原始位置：2026-10-31，本会话收到独立评审 BLOCK 及 B1 说明后的用户消息。
- 必要原文：

> 纠正一下

- 确认状态：用户明确要求纠正 B1，该阶段已完成规划修订；是否关闭独立发现由复审判断。
- 采纳范围：在 D1 既有上下文完整性和截止要求内，修订 proposal、相关规格、design 及 tasks，补齐相容快照读取、整对重读、窗口摘要依赖和竞争验证；不修改业务代码，不增加产品功能或改变预算。
- 替代关系：补齐 U11 评审指出的技术缺口，保留前序业务决定与阶段授权。修订文件本身不关闭独立发现或生成 PASS，结论需独立复审。

## U13：B1 修订后的独立复审授权

- 日期／原始位置：2026-10-31，本会话在 B1 规划修订完成后要求再检查的用户消息。
- 必要原文：

> 再检查一下、

- 确认状态：用户明确要求复审当前规划。
- 采纳范围：由独立评审者重新核对 B1、受影响的规格／设计／任务及来源一致性，更新同一主报告；仅在真实 PASS 后登记并复核规划基线，不启动业务服务、模型、迁移或实施。
- 替代关系：在 U12 已完成修订的基础上继续 U11 的评审流程，不预先接受修订、不改变原业务范围或操作授权。

## R1：独立规划评审的 B1

- 日期／原始位置：2026-10-31，`reviews/change-review.md` 的 B1“请求读取没有闭合‘旧 L1＋已被新修订裁剪的 L0’竞争窗口”。
- 类型／状态：独立技术评审及本次整改依据；首次结论为 BLOCK，针对修订前的规划。
- 采纳范围：请求不得使用旧摘要搭配已按新修订回收的窗口；保留完整旧副本和新摘要配旧窗口的合法路径，补齐 epoch、截止、取消及真实存储读取交错验证。
- 处理方式：本次选择基于摘要修订下界和来源校验的整对重读方案，落实于相关规格、design 及任务 2.2～2.3、5.2～5.5、6.3、7.2、8.1、8.4、9.1；该选择是满足原要求的技术修订，不作为新的用户产品要求。
- 评审边界：U12 修订期间原独立报告保持原样；本次按 U13 由独立评审者更新同一主报告，不以规划作者自查覆盖结论。通过记录须绑定独立复审后的实际规划与报告。

## 本次有效范围登记

| D1 章节 | 本变更采纳范围 |
| --- | --- |
| FR-01：统一滚动摘要 | 历史与当前完整工具批次共同进入单份 L1；增量合并，移除本轮检查点文本投影与专用重建分支 |
| FR-02：窗口和 Token 预算 | 12,288 Token 近期原文软目标；3,072 Token 摘要上限；49,152／57,344／65,536 Token 门禁及现有 60 秒截止时间；完整请求统一计量 |
| FR-03：每次请求使用最新计划 | 只读快照、每次请求一份，包含同版本内文件与计划状态变化；不重复初始化或绑定回合 |
| FR-04：临时资料边界 | RAG、图片和 Skill 目录沿用既有注入条件与同回合复用；运行反馈保留既有送达机制 |
| FR-05：关键决策证据 | 有来源的用户决策和已执行模型方案理由独立留存，供统一摘要读取；不新增独立决策模型调用或决策工具 |
| FR-06：可信结果与回合收尾 | 可信结果记录保留；合法完整回合不再无条件整轮折叠；请求层不二次移除近期原文，已覆盖旧结果不重复注入 |
| 第四、五部分 | 原子覆盖边界、旧数据与冷重建兼容、用户隔离和删除边界，以及既定真实验收要求 |

本次以“生成模型上下文的一致性”为一个整体变更登记。能力划分沿用 proposal，三个能力的规格文件共同构成完整 specs；design 和 tasks 在同一范围内展开实现方案及验证工作，不批量创建其他变更。

非目标：不重做 L2；不增加自动断点续跑或全量工具事件数据库持久化；不增加资料回读与淘汰系统；不引入新框架、数据库或服务；不改变真实构建、取消、超时、删除接管和异常工具链的既有边界；不扩大独立评估链的生产记忆职责。

## 风险等级与 schema 依据

- 等级：`strict`，登记于本变更 `.openspec.yaml` 的 `x-workflow_profile`。
- 原因：D1 要求摘要覆盖位置及修订版本原子提交，并增加独立决策证据字段，涉及持久化迁移；还会调整正常收尾和请求投影，必须避免历史丢失、重复、越界覆盖，以及把模型说明升级为可信工程事实。
- Schema：`spec-driven`，与项目根配置一致，满足 strict 后续完整规划的要求；未降低等级或使用 fast-change。
- 后续约束：strict 所需的独立规划评审、独立代码质量评审和高风险真实验收在相应阶段执行，本轮不提前宣称通过。

## 适用规范与实施参考

以下路径均相对于项目根。持续约束与实施参考分开登记，后续评审按共享来源规则绑定，不冻结整个源码目录。

| ID | 位置与类型 | 用途 |
| --- | --- | --- |
| D2 | `AGENTS.md`；项目规则／持续约束 | 中文协作、授权范围、现有工程入口、无关改动保护、OpenSpec 阶段边界 |
| D3 | `.agents/shared/openspec/workflow-policy.md`、`.agents/shared/openspec/sources.md`；工作流规则／持续约束 | strict 分级、spec-driven、`x-workflow_profile`、来源采纳与替代关系 |
| D4 | `openspec/config.yaml`；项目 OpenSpec 配置／持续约束 | 项目默认 schema、规划语言、各产物规则 |
| D5 | `openspec/specs/vue-soft-replan/spec.md`、`openspec/specs/vue-build-progress-guard/spec.md`、`openspec/specs/vue-mutation-promotion/spec.md`；现行正式规范／兼容约束 | 保持真实修改、计划一次初始化、构建保护、可靠反馈和取消生命周期；后续规划核对需要明确修订的交叉条款 |
| C1 | `src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinator.java`、`src/main/java/com/lyw/appgeneration/ai/memory/MemorySummaryPromptBuilder.java`；已核实实现参考 | 现状只选完整历史回合、临时资料另行追加、旧摘要提示词排除中间诊断 |
| C2 | `src/main/java/com/lyw/appgeneration/ai/memory/ToolMessageCollapser.java`、`src/main/java/com/lyw/appgeneration/ai/memory/TrustedTurnRequestViewProjector.java`；已核实实现参考 | 收尾替换整轮工具消息与请求侧再次移除原文的现有行为 |
| C3 | `src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java`、`src/main/java/com/lyw/appgeneration/ai/plan/AppPlanStateManager.java`；已核实实现参考 | 固定本轮资料、初始计划快照、最新计划的无副作用读取与状态更新 |
| C4 | `src/main/java/com/lyw/appgeneration/core/handler/VueTurnMemoryProjection.java`、`src/main/java/com/lyw/appgeneration/core/handler/VueTurnFinalizer.java`；已核实实现参考 | 可信事实生成、终态落库与现有异常收尾边界 |
| C5 | `src/main/java/com/lyw/appgeneration/model/entity/AppMemorySummary.java`、`src/main/java/com/lyw/appgeneration/model/entity/ChatHistory.java`；实现参考 | 现有摘要游标、可信记忆字段与待扩展的持久化契约 |
| C6 | `README.md`、`pom.xml`、`ai-app-generation-frontend/package.json`；工程事实／实现参考 | Java 25、Spring Boot、LangChain4j、MyBatis-Flex、MySQL／Redis、Vue 3；后端 Maven 与前端现有测试脚本 |
| C7 | `src/main/java/com/lyw/appgeneration/service/MemorySummarySnapshot.java`、`src/main/java/com/lyw/appgeneration/ai/memory/MemorySummaryContract.java`、`src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryServiceImpl.java`、`src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngine.java`、`src/main/java/com/lyw/appgeneration/service/impl/AppMemorySummaryConsistencyCoordinator.java`；已核实实现参考 | 首轮游标零的旧限制、单任务协调、分块草稿、数据库短事务、正文缓存和待增加的修订校验 |
| C8 | `src/main/java/com/lyw/appgeneration/ai/memory/TokenAwareChatMemory.java`、`src/main/java/com/lyw/appgeneration/ai/memory/AtomicChatMemoryStore.java`、`src/main/java/com/lyw/appgeneration/ai/memory/SpringRedisChatMemoryStore.java`、`src/main/java/com/lyw/appgeneration/ai/memory/ConversationTurnSnapshotParser.java`、`src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java`、`src/main/java/com/lyw/appgeneration/ai/AiGeneratorServiceFactory.java`；已核实实现参考 | L0 来源身份缺口、共享锁与 Redis 截止比较替换、完整回合配对及冷重建 |
| C9 | `src/main/java/com/lyw/appgeneration/service/impl/AppServiceImpl.java`、`src/main/java/com/lyw/appgeneration/core/handler/VueTurnContext.java`、`src/main/java/com/lyw/appgeneration/core/handler/VueTurnTranscriptAccumulator.java`、`src/main/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandler.java`、`src/main/java/com/lyw/appgeneration/controller/ChatHistoryController.java`；已核实实现参考 | 用户提交身份、回合内转录与可信事实、裁剪前存证的共享访问，以及实体字段的对外序列化边界 |
| C10 | `src/main/java/dev/langchain4j/service/ModelRequestGate.java`、`src/main/java/dev/langchain4j/service/AiServiceTokenStream.java`、`src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java`；已核实实现参考 | 初始、续调与恢复请求已经传递同一请求边界，门禁返回不可变消息快照，运行反馈另有送达机制 |
| C11 | `sql/schema.sql`、`prod/sql/schema.sql`、`sql/migrations/2026-08-18-chat-history-memory-projection.sql`、`src/test/java/com/lyw/appgeneration/sql/MemorySchemaMigrationContractTest.java`、`src/test/java/com/lyw/appgeneration/service/impl/MemorySummarySnapshotIntegrationTest.java`；已核实实现参考 | 现有表、条件迁移与 MySQL 8.0.40 约定，以及本地替身测试和真实验收的区别；不执行或冻结现有迁移 |
| C12 | `sql/migrations/README.md`、`src/test/java/com/lyw/appgeneration/service/impl/UserMemoryMysqlIntegrationTest.java`；已核实工程参考 | 增量迁移以 `sql/migrations/` 为唯一来源，运行说明随新迁移更新；真实数据库测试采用显式启用及专用测试库，复用隔离方式，不把 L2 测试当成本变更验收 |

当前分支为 `master`。本轮保留已有无关工作区修改，不切分支、不创建 worktree。工程测试入口只做文档与清单核对，未执行业务测试、真实服务调用或模型验收。

## 未决项与下一阶段交接

- B1 已按 U12 补充技术规则和验收安排，当前按 U13 独立复审；是否关闭及是否有新问题以复审实际结论为准，实际生效范围仍以 D1 和上述采纳关系为准。
- 来源身份、覆盖字段、提交顺序、计划刷新、存证和迁移边界由 design 约束，tasks 按依赖拆分实现与验证；真实环境及执行授权在相应执行阶段核实，不得借此改变已确认业务边界。
- 历史讨论中的额外本轮摘要、自动续跑、资料回读、全量工具事件持久化和删除全部可信结果，均不是本次候选实施范围。
- 骨架及四份规划已按 U6～U10 完成，U11 的首次评审形成 BLOCK，U12 已完成规划修订；本次按 U13 独立复审，仅在真实 PASS 后登记并检查规划基线，不自动进入实施。
