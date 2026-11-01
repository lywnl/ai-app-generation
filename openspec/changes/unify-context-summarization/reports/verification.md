# 统一上下文摘要实现验收

- 变更：`unify-context-summarization`；日期：2026-11-01（Asia/Shanghai）。
- 风险等级：`strict`；schema：`spec-driven`；核验方式：`machine`。
- 当前结论：**Ready for Archive**。浏览器发现的 E2E-01／02／03 及补验发现的 PC-01 均已修复，657 项当前回归、20 项真实浏览器证据核对及独立代码评审通过；35 项必需任务全部完成。
- 当前证据：[修复复验报告](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/修复复验.md)。[上轮失败报告](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-082443-c772a7/端到端验收.md) 保留，已由本轮修复与复验关闭。此结论不表示已经归档或部署。

## 当前范围与基线

沿用有效的 [规划基线](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/planning-review.json) 和已登记的实施开始记录，未修改已确认的需求、预算、验收标准或非目标范围。规划持续约束已通过 `check-plan`；实际 schema 已通过 `openspec validate unify-context-summarization --strict`。

本次实现统一历史与当前完整工具批次的 L1 摘要，保留当前用户原话和按 Token 选择的近期原文；通过摘要修订、来源 epoch、消息序号及读取下界保证请求完整衔接。每次请求只读刷新当前计划，临时资料保持原回合生命周期，关键决策按原始来源独立留存。可信终态继续由后端保存，已覆盖的旧结果不再重复独立注入。

实现涉及 `src/main/java` 的记忆、计划、流式请求、收尾与历史服务，相关测试位于 `src/test/java`。初始化 SQL、增量迁移及 README 已同步。没有新增框架、全量工具事件数据库、独立决策模型或跨实例分布式协调。

当前代码基线为 HEAD `c4285f71d76ffb4ad4a7920b94ee1b43fcdb6b88` 加工作区修复。[087 代码状态](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/087-cancellation-memory-final-regression/code-state.json) 的 776 项及 [浏览器代码状态](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/code-state.json) 的 537 项与当前工作区一致。此次改动涉及取消流合并、取消／超时真实事实、提交前清理幂等和回合来源说明。最终机器记录同时绑定后端、前端源码、构建配置、依赖清单与锁文件，以及规划、原始证据和评审报告。

## 需求与场景覆盖

[结构化验收清单](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reports/verification-manifest.json) 逐名覆盖 **22 个 Requirement、76 个 Scenario、35 项任务及 6 项缺陷修复**，共 139 个条目。每个条目关联实际实现、存在的测试文件和原始运行证据；详细对应关系只在该清单维护，本报告不重复规格正文。

已重点核对以下边界：完整工具配对和当前用户保护；部分前沿及完整历史游标不倒退；MySQL 先可靠提交、Redis 后按来源回收；旧 L1／新窗口整对重读；同一副本估算与发送；终态、证据和摘要覆盖的职责分离；旧格式及新 epoch 重建；用户归属、删除栅栏与迟到回填；临时反馈有效送达；非 Vue 模式及独立评估链隔离。

## 实际验证

所有下列成功运行退出码均为 0，JUnit 失败、错误及跳过均为 0。命令完整参数、工作目录、时间及代码状态见各运行的 `command.json` 和旁边的原始日志。

| 验证层 | 本次采用的证据 | 结果 |
| --- | --- | --- |
| 真实迁移 | [005](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/005-mysql-migration/command.json)，`UnifiedSummaryMysqlMigrationTest` | 3 项通过；随机测试库验证新旧数据、可重入执行及不匹配结构。迁移实现未再改变，保留原证据，不重跑既有业务库。 |
| 当前真实 MySQL／Redis | [073](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/073-final-real-storage/command.json)，`UnifiedSummaryStorageIntegrationTest,UnifiedContextStorageOnlineIntegrationTest` | 20 项通过，含 8 项在线跨回合场景；验证提交／回滚／确认丢失、回收重试、r1/r2 读取交错、完整旧副本、新 epoch、截止和取消。 |
| 当前应用与记忆回归 | [087](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/087-cancellation-memory-final-regression/command.json) | 38 类、657 项通过；覆盖 Vue／HTML／MULTI_FILE、取消／超时／删除、前置失败、终态与 SSE、工厂、冷重建、计划、分层与完整请求门禁。 |
| 来源和计划协议补验 | [074](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/074-source-plan-contracts/command.json) | 5 类、30 项通过；包括空计划拒绝、版本与依赖检查、计划构建闸门和来源配对。 |
| 计划证据修复 | [071](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/071-plan-evidence-null-fix/command.json) | 6 类、45 项通过；读取、计划与构建事实不会冒充文件变更，也不会因空路径导致存证异常。 |
| 摘要启动竞态及相关回归 | [077](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/077-late-summary-start-fix/command.json) | 7 类、210 项通过；许可等待后超过原截止或收到中断均无迟到模型调用、无调用计数增加。 |
| 上阶段真实模型与 npm／Vite | [078](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/command.json) | 1 项完整长任务通过；实际生产请求链、真实模型、真实存储及文件／构建工具，随后执行下一用户回合。 |
| 当前真实浏览器／模型 | [本轮审计](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/command.json) | 7 个浏览器请求对应 20 项证据核对通过；真实 HTML 交互、Vue 长任务、取消与重启复验。审计命令核对已执行的浏览器动作及保存证据。 |
| 预提交清理修复 | [086](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/086-precommit-cancellation-idempotence/command.json) | 3 类、93 项通过；清理完成／失败后重复等待、等待者取消隔离与提交前取消保持正确。 |
| OpenSpec 结构检查 | [079](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/079-final-planning-contracts/command.json) | `openspec validate unify-context-summarization --strict` 通过。它只作为结构检查，不代替独立评审或实现验收。 |

[088 当前格式检查](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/088-e2e-fix-format-check/command.json) 已确认工作区与暂存区的 `git diff --check` 均通过。

这些测试层存在重叠，不相加为独立场景总数。071、077、078 保留上一阶段修复范围的证据；本次取消、清理与来源身份变化由 087 和本轮真实浏览器补验。其他旧结果仅在相关实现／测试／配置输入未变化的范围内复用，073 的事务／存储和 005 的迁移实现未变。073 使用可控草稿，不充当真实摘要模型调用证据；078 不再充当当前完整端到端通过记录。

### 当前真实浏览器修复复验

本轮 [20 项核对](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/checks.json) 全部通过，详细过程和三项修复在 [修复复验报告](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/修复复验.md) 统一维护。核心结果如下：

- 压缩中取消后 0.288 秒观察到输入恢复；SSE 收到唯一 `CANCELLED` 与 `done`，实际 10 次 `readFile` 正确保存在可信结果，取消后无迟到主模型／摘要请求。
- 重启后观察 101.735 秒，模型请求数 64 → 64、文件未变；新请求修复旧孤立回合，保留新 epoch 和摘要依赖 revision 13，正确区分相邻回合。追问缺失进度时回答“无法确认”“未知”，没有重读文件；这是本次样例的语义结论，不是任意回答保证。
- Vue 长任务包含计划 v1 → v2、缺组件的真实构建失败与补齐后的成功；22 次主请求、6 次实际 L1 摘要调用、3 组压缩事件，六次工具来源终点 19 → 21 → 27 → 29 → 35 → 37。收尾后 revision 7 含终态登记，旧历史夹具游标为 20。近期原文保留 38～49，下一回合可复述第 15 批标记及关键决策。
- 7 个浏览器请求共产生 72 次真实模型请求：主流式 42、L1 13、L2 7、路由／命名 10。7 次真实 L2 输出均为 `operations: []`，相关记忆表为空、抽取游标无失败。取消／中断阶段摘要调用不全部计为成功提交。

### 上阶段真实模型长任务（078，历史证据）

[078 验收数据](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/acceptance.json) 和同目录的原始请求／响应证明：

- 使用项目配置的 `deepseek-flash`、生产 `ConservativeChatTokenEstimator`，保持 49,152／57,344／65,536 阈值、12,288 原文软目标、3,072 摘要上限及每次准入原 60 秒截止；未降低阈值或替换估算器。
- 6 次有效语义摘要分别提交 r1～r6，覆盖序号递进为 **17、19、25、27、33、35**，均属于同一尚未结束的持久用户回合，完整历史游标保持零。共 6 次实际摘要调用，未把缓存或任务复用计成新调用。
- 23 次主模型请求的完整估算范围为 6,744～53,252 Token，发送消息与同次门禁决定逐项相等；当前计划只出现一份，创建、修订、文件状态及只读下一回合读取均反映最新提交状态。
- 实际创建计划 v1、修改标题、执行构建；第 1 次在 `NPM_BUILD` 阶段因缺少 `MissingBanner.vue` 失败（退出码 1）。修订为 v2 并补齐组件后，第 2 次真实构建成功（退出码 0），生成 `dist/index.html`。
- 收尾不额外调用摘要模型。下一用户回合的 [原始回答](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/cross-turn-answer.txt) 正确复述不使用轮播图及移动端遮挡理由、保留品牌提示的理由、v1→v2、首次失败和最终成功，并引用第 15 批原文标记。实际请求保留该工具结果，可信终态进入后端状态通道，没有重复作为独立 AI 结果追加。
- 主模型与摘要模型共 29 份响应分别保存，与实际请求次数一致。工程、npm 缓存、构建命令和原始证据均在项目 `.codex/` 下。

### 失败记录与处置

[独立实现评审](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/implementation-review.md) 结论为 **PASS**，评审者未编写被审查业务代码或测试。IR-01／IR-02 和本轮 E2E-01／02／03、PC-01 均已关闭。独立评审先于真实浏览器复验完成，其待补环境证据已由上述本轮记录补齐；主执行者未改写独立评审结论。上一阶段两项发现：

1. **IR-01**：计划证据对空 `changedRelativePath` 查询不可变列表导致异常。070 已真实复现；修复为先判路径非空再关联文件，071 与最终真实链路通过。
2. **IR-02**：等待启动许可期间耗尽截止，返回后仍可能新发摘要调用。076 的截止和中断两个用例均在旧实现复现；修复在实际调用及计数之前复检原截止和中断，077 与最终真实链路通过。

064 的通用 record 序列化和未知 epoch 放行预期、069 的 npm 配置文件复用，以及 067、075 的测试编译错误，均保留原日志并由后继运行纠正；未通过放宽来源校验、降低预算、跳过测试或伪造模型结果换取通过。早期失败不计作当前成功证据。

本轮 081／082 保留取消流及来源身份的旧实现复现，但其中事实夹具的 final 类 mock／不合法协议错误不算事实缺陷证据；083 用合法协议复现两项空事实错误。085 的 621 项中 1 项暴露提交前重复清理，已通过 `preCommitCleanupOnce` 修复，并由 086／087 关闭。原始失败与后继通过记录均纳入清单，不把失败运行计作当前通过。

## 环境、交付与回退边界

- 本机 MySQL `127.0.0.1:3406` 只创建并清理 `context_summary_it_*` 随机库；Redis `127.0.0.1:6379`、数据库 0 只使用随机测试应用的三个键。每项测试结束都核对删除结果，没有修改既有业务库。
- 本轮真实浏览器使用合成诊断材料、真实项目模型／L2／存储及隔离工程，不使用 L2 替身；078 历史夹具的 L2 与工厂失效通知采用替身，其证据边界仍保留。本轮覆盖指定的 HTML、Vue 生成与记忆链路，未覆盖 Vue 首轮 RAG／图片准备或部署，MULTI_FILE 取消由确定性回归覆盖，不宣称全站验收或任意摘要无损。
- 本轮隔离前后端、代理、随机 MySQL 库和临时 Redis 容器已按 [清理记录](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/cleanup-result.json) 清理，账号凭据已删除。日常 5173／9025 服务未重启，3406／6379 服务保持正常；没有清空共享 Redis。
- 保留单后端实例边界；重启只使用已持久化历史、证据、L1/L2 和当前工程文件重建，不自动续跑工具，不承诺恢复未摘要的完整工具轨迹。
- 发布时必须避免新旧写入器混用。只加列但未写入新格式，与已经写入新覆盖／证据／L0 格式，是不同回退阶段；按 [迁移说明](/Users/lllyw/ai-app-generation/sql/migrations/README.md) 执行，不能靠清空摘要或证据回退。
- 原始证据保留在 `.codex/unify-context-summarization/runs/` 和 `e2e/`，不因本地提交自动进入 Git；跨机器复核需要这些原始文件。未自动推送、归档或发布，保留用户的无关修改。

最终 [机器验收记录](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reports/verification.json) 由标准助手生成并用 `check-verify` 核对。其输入包含本报告、独立评审、完整映射、相关代码及原始证据；后续修改这些输入时必须重新评估受影响验证。
