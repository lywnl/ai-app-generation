# 统一上下文摘要独立实现评审

- 评审日期：2026-11-01，Asia/Shanghai。
- 变更：`unify-context-summarization`；Profile：`strict`；schema：`spec-driven`；核验方式：`machine`。
- 本轮评审者：独立执行上下文 `/root/e2e_fix_review`，未编写本次业务代码或测试，只修改本报告。上阶段评审者为 `/root/independent_plan_review`，其 IR-01／IR-02 关闭记录在下文保留。
- **代码质量结论：PASS。** 当前未发现尚未关闭的实现阻塞项。提交前取消的重复清理问题已修复并独立复核；真实浏览器和真实模型复验仍由主执行者完成，本报告不代替 `Ready for Archive`。
- 当前实现基线：`c4285f71d76ffb4ad4a7920b94ee1b43fcdb6b88` 及本次未提交修复，以 [087 代码状态](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/087-cancellation-memory-final-regression/code-state.json) 为准。定稿前独立重算其中 **776 个条目**，全部与工作区一致。
- 规划基线：独立执行 `evidence.py check-plan --root . --change openspec/changes/unify-context-summarization --profile strict` 返回 `valid=true`；[规划记录](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/planning-review.json) 中原实施开始时间为 `2026-10-31T13:41:25.274494+00:00`。本轮没有改写规划、任务或机器记录。

## 范围与方法

本轮依据 [任务](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/tasks.md) 6.1、6.3、7.5、9.2、9.3，以及“可信工程结果必须独立保留”“已覆盖的历史结果不得重复独立注入”“摘要失败与终止必须保持执行边界”“旧历史与冷重建必须保持兼容边界”，复核 [上轮端到端发现](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-082443-c772a7/端到端验收.md) 对应的修复。

业务变更限于 `AppServiceImpl.java`、`VueTurnCancellationCoordinator.java`、`VueTurnContext.java`、`TrustedTurnRequestViewProjector.java`。已读取四者完整相关调用链、终态保存、应用租约、预提交状态机、工具事实收集、原文解析及预算组装，并核对 `AppServiceImplVueTurnTest`、`AppServiceSimpleTurnLifecycleTest`、`VueTurnCancellationCoordinatorTest`、`TrustedTurnRequestViewProjectorTest` 的新增断言。未修改代码、测试或用户无关变更，未运行 Maven、服务、数据库或模型，避免与主执行者共享编译与运行目录竞争。

下列测试结果来自独立读取主执行者保存的命令、JUnit XML、原始日志和内容指纹，不冒充本评审者重新执行。需求／场景的完整映射继续由 [verification-manifest.json](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reports/verification-manifest.json) 维护；本报告只说明本次代码差异与复核。

## 本轮修复与边界核对

### E2E-01 · 取消终态订阅被首条正文淘汰 · 实现修复通过

原 `Flux.firstWithSignal` 选择业务流后不再监听取消终态，导致数据库已取消而 SSE 继续只有心跳。当前 [mergeGenerationWithTerminal](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/AppServiceImpl.java:569) 同时订阅终态和业务流，收到 `TurnOutcome`／`SimpleTurnOutcome` 后结束；首条普通消息不会淘汰终态订阅。

这里不仅替换一个操作符：正常业务结束时用 `normalCompletion` 释放终态等待，防止普通完成流反而悬挂；取消或超时已经接管时，业务先结束仍继续等待可靠收尾。Vue 的共享收尾异常沿错误通道结束原流，正常失败仍由原处理器生成结果。HTML／MULTI_FILE 使用同一合并规则；删除接管保留原处理器分支和单赢家终态，不新增工具执行或放宽归属检查。

[Vue 取消回归](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/AppServiceImplVueTurnTest.java:155) 验证已经输出正文后仍收到唯一 `CANCELLED`、业务先结束时等待持久化，以及收尾自身失败可以终止流；[原生模式回归](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/AppServiceSimpleTurnLifecycleTest.java:66) 覆盖 HTML 和 MULTI_FILE。082 在旧实现复现四项取消流超时；087 当前代码全部通过。原 `AppController` 的末尾 `done` 编码和前端协议没有更换，浏览器收到完整结束事件仍需实际 HTTP/SSE 复验。

### E2E-02 · 默认取消结果漏记真实工具事实 · 实现修复通过

[默认取消与超时入口](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnCancellationCoordinator.java:69) 将 `List.of()` 改为在 supplier 执行时读取 `List.copyOf(context.decisionSources().facts())`。该 supplier 仍在 `awaitQuiescence` 成功之后由原收尾流程读取，因而不会在取消发起时提前冻结空事实。事实容器为 `CopyOnWriteArrayList`，其内容来自可信工具协议解析，不从模型普通正文推断执行状态。

[主动取消入口](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/AppServiceImpl.java:528) 统一进入 `cancelVuePreparation`：提交前只清理，提交后使用现有转录作为展示前缀、真实事实作为独立记忆结果。实际文件变更和构建失败、次数继续由后端协议决定，展示文本与权威记忆没有混合。

[新增事实回归](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/core/handler/VueTurnCancellationCoordinatorTest.java:110) 在取消请求后、允许收尾之前补入读文件、写文件和第 2 次构建失败事实，验证默认取消与默认超时均保存完整事实。083 在旧代码两项失败，084／086／087 通过。081／082 中事实夹具的 final 类 mock 和不合法构建协议错误不算真实缺陷复现，已由 083 的有效协议输入补齐。

### E2E-03 · 相邻诊断回合的进度混淆 · 代码对齐通过，真实模型语义仍需复验

[请求结果投影](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/TrustedTurnRequestViewProjector.java:43) 现在明确写出当前用户记录、L1 部分前沿所属用户记录、未覆盖历史终态的归属及原始用户消息顺序。上一回合从同一相容快照内有序的持久用户边界确定，没有重新查询“最后一条记录”，也没有修改原始用户消息的 payload。

对 `SYSTEM_ERROR` 且本次窗口没有该回合工具批次的情况，程序说明轨迹缺失，要求只依据归属于该回合的保存结果或摘要判断，不能确认时说明未知。它没有把缺失轨迹伪装成“未执行工具”，也没有增加完整工具轨迹持久化、自动重放或固定收尾摘要。

去重检查仍先执行，已由 L1 覆盖的旧结果不会因增加身份文字再次进入 System 状态。新增文字位于 [UnifiedContextRequestPreparer](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/UnifiedContextRequestPreparer.java:146) 的同一请求组装链中，随后与原消息、最新计划和临时资料一起估算并冻结，未绕过完整请求 Token 门禁。确定性用例验证同名诊断任务分别属于用户记录 10／30，摘要进度不复制进状态投影，原始用户消息保持相等；087 的分层、门禁、工厂和冷重建测试通过。

代码层的来源关联和未知边界符合已确认设计。模型是否在实际回答中正确采用这些信息仍需本轮真实模型／浏览器样例证明，不能仅凭提示文字与单元测试宣布自然语言错误已经彻底解决。

### PC-01 · 提交前取消完成后再次清理已关闭租约 · 已关闭

本项由主执行者在 085 的真实测试执行中发现：主动取消准备中的回合先完成清理，随后准备异常分支再次等待清理；协调器的 pending 条目已经删除，第二次清理重新调用已关闭的 Vue 租约，返回“Vue 构建租约已经关闭”而不是原取消状态。

[VueTurnContext.preCommitCleanupOnce](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnContext.java:930) 用 CAS 只安装并启动一个共享清理结果，成功、失败均保留在同一上下文；[协调器公共入口](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnCancellationCoordinator.java:104) 通过该入口调用原清理实现。每次等待返回 `CompletableFuture.copy()`，一个请求或 Reactor 等待者取消不会取消其他等待者依赖的共享结果。该状态只保存在当前运行对象，不改变数据库或重启恢复格式。

独立复核了成功后重复、失败且排空后重复、等待者取消三个测试，以及通过真实服务状态机触发的提交前取消：它们分别确认只启动一次、保留原失败、互不取消、没有用户／AI 记录和迟到模型启动。086 的 93 项、087 的 657 项均通过；这关闭了 085 的唯一未通过项。

## 上一阶段发现保留

下列 IR-01／IR-02 来自上一阶段独立评审。相关业务文件和测试未被本次改动，当前直接核对仍保留原修复；旧运行只支撑各自说明的范围，不代替本次取消与重启复验。

### IR-01 · P1 · 计划证据遇到无变更路径的工具事实会抛空值异常 · 已关闭

位置：[DecisionEvidenceAssembler.java:59](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/DecisionEvidenceAssembler.java:59)；关联任务 3.2、3.3、4.3、9.2 及“短回合及原文裁剪前必须留存必要证据”。

触发路径：成功计划已产生 `PLAN_SUMMARY`／理由／用途条目，之后出现 `readFile`、`makePlan` 或 `buildProject` 等 `changedRelativePath=null` 的事实。原代码先对 `List.copyOf` 保存的 `relatedFiles` 调用 `contains(null)`，之后才判断路径非空，因此正常工具链会在存证准备时抛 NPE。统一摘要失败并保留原文，长任务继续增长后可能受门禁终止。

修复把路径非空检查移到 `contains` 之前：`fact.changedRelativePath() != null && entry.relatedFiles().contains(fact.changedRelativePath())`。有路径的真实文件变更继续关联，读取、计划和构建事实不会冒充文件修改；构建验证仍按原独立分支采集。

上一阶段评审独立复核了 [新增用例](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/ai/memory/DecisionEvidenceTest.java:84)、[070 失败复现](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/070-plan-evidence-null-reproduction/output.log) 和 [071 修复回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/071-plan-evidence-null-fix/output.log)。070 为 5 项中 1 项 NPE；071 为 6 类 45 项通过、无跳过。078 最终真实长任务也经过相同计划、读取及构建采集路径并成功完成。

### IR-02 · P2 · 启动许可等待超过截止后仍可能新发摘要调用 · 已关闭

位置：[MemorySummaryDraftEngine.java:339](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngine.java:339)；关联任务 4.4、5.1、7.5 及“摘要失败与终止必须保持执行边界”。此项由上一阶段独立评审者 `/root/independent_plan_review` 发现。

原代码只在 `modelStartPermit.getAsBoolean()` 之前检查截止。生产许可通过 `VueTurnContext.tryCallCallback → VueBuildSessionManager.enterCallback → AppOperationLeaseManager.enterCallback` 获取回调票据，后者使用同步 monitor，可能等待同一状态的提交动作。等待中截止耗尽后，外层虽已返回 `TIMED_OUT` 并取消 Future，worker 仍可能在许可迟到返回 `true` 后执行 `summarizationModel.chat`，同时增加模型调用计数。该缺陷不会使迟到草稿绕过持久化门禁，但会在本次准备已结束后新发模型请求。

修复在进入许可检查前检查线程中断，并在许可返回后再次检查**原**截止和中断；拒绝分支位于 `started.set`、模型调用指标和实际 `chat` 之前，没有延长截止或把回收失败转换成额外摘要调用。

上一阶段评审独立复核了 [两个竞争用例](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/UnifiedMemorySummaryDraftEngineTest.java:97)：许可忽略中断且时钟已过截止，以及许可保留中断而测试时钟未过截止。两者均先等调用方超时，再放行 worker，断言零模型调用和零调用计数。[076](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/076-late-summary-start-reproduction/output.log) 在修复前两项均失败，明确记录了实际 `chat` 与计数；[077](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/077-late-summary-start-fix/output.log) 为 7 类 210 项通过、无跳过，涵盖草稿、协调、服务、观测与请求门禁。075 是测试编译失败，未用作缺陷复现依据。

## 当前证据与交付限制

| 证据 | 独立核对结果 |
| --- | --- |
| [084 聚焦回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/084-e2e-defect-fixes/command.json) | 8 类、162 项通过，失败／错误／跳过为零。覆盖最初取消合并、真实事实与回合归属修复；其后预提交幂等修复由 086／087 补验。 |
| [085 应用回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/085-e2e-fixes-regression/command.json) | 621 项中 1 项提交前取消失败，其余 620 项通过。该运行作为 PC-01 复现保留，不记为全量通过。 |
| [086 预提交幂等回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/086-precommit-cancellation-idempotence/command.json) | 3 类、93 项通过，失败／错误／跳过为零；涵盖完成后重复、失败后重复和等待者取消隔离。 |
| [087 当前代码回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/087-cancellation-memory-final-regression/command.json) | **38 类、657 项通过**，失败／错误／跳过为零；覆盖 Vue／HTML／MULTI_FILE、取消／超时／删除、前置失败、终态与 SSE、工厂与冷重建、计划、分层及完整请求门禁。776 个代码状态条目与当前工作区一致。 |
| [073 真实存储组合](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/073-final-real-storage/command.json) | 保留上一阶段 20 项真实 MySQL／Redis 证据。此次没有改写提交、回收、Lua、迁移、事务和存储配对实现；它不证明新增取消流或模型语义。 |
| [078 真实长任务](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/command.json) | 保留 IR-01／IR-02 之后的历史长任务证据：真实模型、六次摘要和真实构建。当前请求身份文字及取消逻辑已经改变，本轮需以新的真实模型／浏览器记录补充验证，不能把 078 当作当前完整端到端通过。 |

本轮独立执行了规划基线检查、内容哈希核对和 `git diff --check`，均通过。未更改需求或验收标准，未生成 `record-verify` 或 `check-verify` 的新通过记录。当前正式验收仍由主执行者根据剩余任务、真实环境证据及本报告汇总；代码审查通过不替代浏览器取消收尾、重启后语义复盘和隔离环境清理。
