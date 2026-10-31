# 统一上下文摘要独立实现评审

- 评审日期：2026-11-01，Asia/Shanghai。
- 变更：`unify-context-summarization`；Profile：`strict`；实际 schema：`spec-driven`。
- 评审者：独立执行上下文 `/root/independent_plan_review`。未编写本变更的业务代码或测试；本轮只写本报告。问题定位与修复建议属于评审反馈，修复和测试执行由主执行者完成。
- 代码质量结论：**PASS**。本轮确认的两项缺陷均已修复并完成独立复核；当前没有未关闭的代码阻塞项。本结论不代替整个变更的 `Ready for Archive` 验收结论。
- 实现基线：已提交差异 `9bd7a4c..65ea234e7a8c179b695cadad9034695c99b47123`，并包含当前工作区的两处业务修复、相应测试及真实环境夹具。当前业务文件内容以 [078 运行代码状态](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/code-state.json) 为可核对基线；独立重算其 776 个条目的 SHA-256，全部与工作区一致。
- 规划基线：本轮已执行 `evidence.py check-plan --root . --change openspec/changes/unify-context-summarization --profile strict`，结果 `valid=true`；[规划记录](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/planning-review.json) 已登记 `implementation_started=2026-10-31T13:41:25.274494+00:00`。未把实现修改当作重新授权规划的依据。

## 范围与方法

依据当前 [design](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/design.md)、[tasks](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/tasks.md)、三个变更规格及已通过的 [规划评审](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/change-review.md)，审查数据来源、覆盖推进、L1／L0 相容读取、完整请求预算、决策证据、终态、冷重建、权限删除、事务和失败恢复。复用未变化的项目规则与技能规则；详细需求／场景／任务映射由 [verification-manifest.json](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reports/verification-manifest.json) 统一维护。

当前未提交但已纳入评审的业务修复是 `DecisionEvidenceAssembler.java` 和 `MemorySummaryDraftEngine.java`；相关验证包含 `DecisionEvidenceTest.java`、`UnifiedMemorySummaryDraftEngineTest.java`、`UnifiedMemoryStorageFixture.java`，以及新增的 `UnifiedContextStorageOnlineIntegrationTest.java`、`UnifiedContextRealModelIntegrationTest.java`、`WorkspaceRealVueBuilder.java`。未处理无关 `.gitignore`、`.DS_Store`、`prod/sql/migrations/`。

本评审直接读取实现、测试、命令记录、JUnit XML、必要原始日志、模型请求／响应和构建结果，并核对代码指纹。未运行 Maven，未启动服务、数据库、迁移或模型，避免与主执行者共享的编译目录竞争。下表中的运行结果是对主执行者已保存证据的独立核对，不是本评审者重新执行的测试。

## 关键边界核查

| 边界 | 实际实现与判断 |
| --- | --- |
| 用户、回合与 L0 来源 | [AppServiceImpl.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/AppServiceImpl.java:628) 在保存用户记录时绑定实际 ID；[L0SourceOperations.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/L0SourceOperations.java) 与 [SpringRedisChatMemoryStore.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/SpringRedisChatMemoryStore.java:89) 将消息、epoch、序号、回合边界和摘要依赖放在同一次比较替换中。裁剪不重新编号，当前用户原话单独保护，未知旧数组不能伪造零下界。 |
| 规划 B1 的读取缺口 | [MemorySummaryServiceImpl.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryServiceImpl.java:539) 先用独立 `READ_COMMITTED` 事务读取完整 L1，再取得原子 L0 副本；摘要修订落后于 `appliedSummaryRevision` 时整对重读。应用、回合、epoch 和覆盖关系继续由 [CompatibleMemorySnapshot.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/CompatibleMemorySnapshot.java) 校验；下界比较不代替来源证明。完整旧副本、新摘要配旧窗口、未知重置、截止及取消均有相应测试和真实存储证据。 |
| 原文与增量覆盖 | [SummaryMaterialReader.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/SummaryMaterialReader.java)、[SummaryWindowSelector.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/SummaryWindowSelector.java)、[SummaryCoveragePlanner.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/SummaryCoveragePlanner.java) 分别承担来源合并、完整单元窗口选择与覆盖推进。12,288 Token 是近期原文软目标；当前用户、最新完整工具批次和未完成批次受保护。选择不提前改写 L0，完整历史游标与部分前沿不能越过未覆盖材料，终态和证据修订单独表达。 |
| 摘要提交与回收 | [commitUnifiedDraft](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryServiceImpl.java:609) 在模型返回后才取得回合、生命周期、L0 和摘要一致性边界，在短事务中核对持久来源并按修订写完整快照。`commitAndReclaim` 只在可靠提交或精确重读确认后回收；Redis 失败保留已提交摘要，重试不回写旧窗口或追加模型补偿。真实回滚、首次唯一键竞争、失败元数据条件更新及 COMMIT 确认丢失均有对应证据。 |
| 单次实际请求与截止 | [UnifiedContextRequestPreparer.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/UnifiedContextRequestPreparer.java:53) 使用同一相容快照准备 L1、L0、L2、当前可信状态、最新计划与已领取临时资料，之后统一估算。同步等待后重新组装；异步路径保留完整副本。49,152／57,344／65,536 阈值及原 60 秒截止沿用，最终 SDK 调用消费同份消息；后续分层组装不会隐式重读活动 L0／L1。摘要启动等待后的截止缺口已按 IR-02 修复。 |
| 决策来源与信任隔离 | [VueDecisionSourceCollector.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueDecisionSourceCollector.java) 在可靠来源和工具结果提交后采集，不依赖展示队列先消费；共享已有转录，额外来源有资源上限。[ConversationDecisionEvidenceService.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/ConversationDecisionEvidenceService.java) 按应用、用户及用户记录校验并保存独立字段。原始理由不可改写，实施依据与构建验证分开；未从代码结果补造理由，未把同一代次全部建议都标为已实施。空变更路径缺陷已按 IR-01 修复。 |
| 终态与跨回合 | [persistTurnOutcome](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryServiceImpl.java:220) 同事务合并最终证据、AI 可信结果与部分前沿结束身份；结束登记不提前声称语义覆盖。合法终态保留近期工具原文且不固定触发 L1；[TrustedTurnRequestViewProjector.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/TrustedTurnRequestViewProjector.java) 只把带持久身份的后端终态当作可信工程状态，已覆盖结果不再重复独立注入。协议异常仍走原终止／恢复边界。 |
| 重启、权限与删除 | [loadSourceHistoryToMemory](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java:377) 使用实际历史 ID、已保存证据及所用摘要修订建立新 epoch；旧 epoch 不能按序号裁剪新消息。孤立用户修复把新终态绑定旧用户记录，不自动续跑工具。新增证据排除对外序列化，不进入 L2 权威输入；删除沿原生命周期栅栏清理历史行、L0 及新旧 L1 缓存键，未新增跨用户召回入口。 |
| 迁移与非目标兼容 | [增量 SQL](/Users/lllyw/ai-app-generation/sql/migrations/2026-10-31-unify-context-summarization.sql) 只条件新增并核对四列，不回填旧正文或 L2，说明 DDL 隐式提交及新格式写入后的回退限制。旧五段摘要、旧完整历史和空证据兼容；HTML／MULTI_FILE 继续完整历史路径，旧入口不能覆盖部分前沿。已核对迁移测试、工厂／流式／API／SSE／生命周期回归的范围。 |

## 发现与修复复核

### IR-01 · P1 · 计划证据遇到无变更路径的工具事实会抛空值异常 · 已关闭

位置：[DecisionEvidenceAssembler.java:59](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/DecisionEvidenceAssembler.java:59)；关联任务 3.2、3.3、4.3、9.2 及“短回合及原文裁剪前必须留存必要证据”。

触发路径：成功计划已产生 `PLAN_SUMMARY`／理由／用途条目，之后出现 `readFile`、`makePlan` 或 `buildProject` 等 `changedRelativePath=null` 的事实。原代码先对 `List.copyOf` 保存的 `relatedFiles` 调用 `contains(null)`，之后才判断路径非空，因此正常工具链会在存证准备时抛 NPE。统一摘要失败并保留原文，长任务继续增长后可能受门禁终止。

修复把路径非空检查移到 `contains` 之前：`fact.changedRelativePath() != null && entry.relatedFiles().contains(fact.changedRelativePath())`。有路径的真实文件变更继续关联，读取、计划和构建事实不会冒充文件修改；构建验证仍按原独立分支采集。

独立复核了 [新增用例](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/ai/memory/DecisionEvidenceTest.java:84)、[070 失败复现](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/070-plan-evidence-null-reproduction/output.log) 和 [071 修复回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/071-plan-evidence-null-fix/output.log)。070 为 5 项中 1 项 NPE；071 为 6 类 45 项通过、无跳过。078 最终真实长任务也经过相同计划、读取及构建采集路径并成功完成。

### IR-02 · P2 · 启动许可等待超过截止后仍可能新发摘要调用 · 已关闭

位置：[MemorySummaryDraftEngine.java:339](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngine.java:339)；关联任务 4.4、5.1、7.5 及“摘要失败与终止必须保持执行边界”。此项由本独立评审发现。

原代码只在 `modelStartPermit.getAsBoolean()` 之前检查截止。生产许可通过 `VueTurnContext.tryCallCallback → VueBuildSessionManager.enterCallback → AppOperationLeaseManager.enterCallback` 获取回调票据，后者使用同步 monitor，可能等待同一状态的提交动作。等待中截止耗尽后，外层虽已返回 `TIMED_OUT` 并取消 Future，worker 仍可能在许可迟到返回 `true` 后执行 `summarizationModel.chat`，同时增加模型调用计数。该缺陷不会使迟到草稿绕过持久化门禁，但会在本次准备已结束后新发模型请求。

修复在进入许可检查前检查线程中断，并在许可返回后再次检查**原**截止和中断；拒绝分支位于 `started.set`、模型调用指标和实际 `chat` 之前，没有延长截止或把回收失败转换成额外摘要调用。

独立复核了 [两个竞争用例](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/UnifiedMemorySummaryDraftEngineTest.java:97)：许可忽略中断且时钟已过截止，以及许可保留中断而测试时钟未过截止。两者均先等调用方超时，再放行 worker，断言零模型调用和零调用计数。[076](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/076-late-summary-start-reproduction/output.log) 在修复前两项均失败，明确记录了实际 `chat` 与计数；[077](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/077-late-summary-start-fix/output.log) 为 7 类 210 项通过、无跳过，涵盖草稿、协调、服务、观测与请求门禁。075 是测试编译失败，未用作缺陷复现依据。

## 已核对的运行证据及限制

| 证据 | 独立核对结果与适用范围 |
| --- | --- |
| [066 应用兼容回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/066-application-compatibility/command.json) | 31 类、510 项通过，失败／错误／跳过均为零。覆盖工厂、流式、控制器、SSE、计划读取、取消、删除及非目标模式等既有行为；使用替身的部分不作为真实模型或真实数据库证据。后续两项修复由对应聚焦回归补验，旧结果仅按相关输入未变化的部分复用。 |
| [073 真实存储组合](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/073-final-real-storage/command.json) | 2 类、20 项通过，无跳过，包含重新执行的 8 项在线跨回合场景。直接使用真实 MySQL、Redis Lua、生产 Mapper 与 Spring 事务；在真实操作后注入提交确认丢失、更新失败或交错暂停。模型输出可控。已读取夹具清理逻辑和测试结果，测试只清理自己创建的随机库及随机应用键。065 的旧夹具证据已由此补齐；IR-02 后续只改变草稿模型启动守卫，存储提交／读取／Lua 路径未改，其实际正向模型链路另由 078 重验。 |
| [074 来源与计划协议](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/074-source-plan-contracts/command.json) | 5 类、30 项通过，无跳过。补验计划工具、构建计划门禁、L0 来源解析及完整回合解析；不以测试类名称代替实际断言范围。 |
| [078 最终真实模型长任务](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/command.json) | 两处修复后的当前版本，1 项通过，无跳过；实际 `AiCodeGeneratorService`／`TokenStream`／完整请求门禁／文件工具／真实 npm、Vite／MySQL、Redis 链路。`deepseek-flash` 主模型 23 次请求、摘要 6 次调用；6 次已提交修订的覆盖序号为 17、19、25、27、33、35，完整历史游标保持零。第一次构建因缺 `MissingBanner.vue` 失败，补齐组件后构建成功。 |
| [078 原始语义材料](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/acceptance.json) | 独立读取当前测试装配、最后摘要、[跨回合回答](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/cross-turn-answer.txt)、实际请求与响应登记方式。下一回合正确复述禁用轮播及移动端理由、保留品牌组件的理由、v1→v2 计划变化、真实失败原因与最终成功，并保留批次 15 原文标记。使用生产预算及真实估算器，没有降低阈值；最终估算均小于 65,536，最高为 53,252。响应编号使用独立原子计数，29 份完整响应对应 23 次主请求和 6 次摘要请求，未因并发覆盖记录。 |
| [077 修复回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/077-late-summary-start-fix/command.json) | 210 项通过，包含 IR-02 新竞争用例及成功摘要路径。独立初次核对时 776 个记录文件均一致；其后只修正真实模型测试的并发响应编号并新增记录完整性断言，由 078 验证；业务实现与这 210 项测试未再修改。该运行是替身／单元组合回归，不把它当作真实环境重跑。 |

真实长任务使用专门的合成诊断文件和受控测试需求，`L2` 与工厂收尾依赖有明确替身，主模型关闭并行工具调用；它证明本次指定链路及语义样例，不证明任意摘要都无损、不证明整个 HTTP／浏览器流程，也不承诺重启后恢复未持久化的工具原文。重启、协议并行配对和隔离分别由对应代码、单元及真实存储场景支撑。

最后复核未发现新的代码问题或必须增加的业务测试。已逐名核对清单包含全部 35 个任务、22 个 Requirement、76 个 Scenario 和两项修复，无缺失或多余的规格名称；独立执行 `evidence_manifest.py --root . --manifest openspec/changes/unify-context-summarization/reports/verification-manifest.json` 返回 `valid=true`、135 个条目、46 个命令。该检查证明登记结构和引用有效，不独立证明每条测试语义。

上述旧运行仅按已说明且相关路径未变化的范围复用，真实模型以 078 为当前结果。主执行者仍需完成最终验收报告及 `record-verify`／`check-verify`；本报告确认当前代码问题已经关闭，未宣布最终验收登记已经完成，也不包含部署、推送或归档授权。
