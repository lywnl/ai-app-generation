# 统一上下文摘要实现验收

- 变更：`unify-context-summarization`；日期：2026-11-01（Asia/Shanghai）。
- 风险等级：`strict`；schema：`spec-driven`；核验方式：`machine`。
- 结论：**Ready for Archive**。35 项必需任务全部完成，真实存储、真实模型及真实构建验证通过，独立代码评审的两项发现均已关闭。此结论不代表已经归档、部署或推送。

## 当前范围与基线

沿用有效的 [规划基线](../reviews/planning-review.json) 和已登记的实施开始记录，未修改已确认的需求、预算、验收标准或非目标范围。规划持续约束已通过 `check-plan`；实际 schema 已通过 `openspec validate unify-context-summarization --strict`。

本次实现统一历史与当前完整工具批次的 L1 摘要，保留当前用户原话和按 Token 选择的近期原文；通过摘要修订、来源 epoch、消息序号及读取下界保证请求完整衔接。每次请求只读刷新当前计划，临时资料保持原回合生命周期，关键决策按原始来源独立留存。可信终态继续由后端保存，已覆盖的旧结果不再重复独立注入。

实现涉及 `src/main/java` 的记忆、计划、流式请求、收尾与历史服务，相关测试位于 `src/test/java`。初始化 SQL、增量迁移及 README 已同步。没有新增框架、全量工具事件数据库、独立决策模型或跨实例分布式协调。

当前运行基线为 [078 代码状态](../../../../.codex/unify-context-summarization/runs/078-real-model-final/code-state.json)：776 个已登记源码、测试、SQL 和构建清单条目的内容哈希与当前工作区一致，包含最后的空路径及迟到启动修复。最终机器记录另绑定全部相关源码、资源、依赖清单、规划输入、原始证据与两份评审报告。

## 需求与场景覆盖

[结构化验收清单](verification-manifest.json) 逐名覆盖 **22 个 Requirement、76 个 Scenario、35 项任务及 2 项缺陷修复**，共 135 个条目。每个条目关联实际实现、存在的测试文件和原始运行证据；详细对应关系只在该清单维护，本报告不重复规格正文。

已重点核对以下边界：完整工具配对和当前用户保护；部分前沿及完整历史游标不倒退；MySQL 先可靠提交、Redis 后按来源回收；旧 L1／新窗口整对重读；同一副本估算与发送；终态、证据和摘要覆盖的职责分离；旧格式及新 epoch 重建；用户归属、删除栅栏与迟到回填；临时反馈有效送达；非 Vue 模式及独立评估链隔离。

## 实际验证

所有下列成功运行退出码均为 0，JUnit 失败、错误及跳过均为 0。命令完整参数、工作目录、时间及代码状态见各运行的 `command.json` 和旁边的原始日志。

| 验证层 | 本次采用的证据 | 结果 |
| --- | --- | --- |
| 真实迁移 | [005](../../../../.codex/unify-context-summarization/runs/005-mysql-migration/command.json)，`UnifiedSummaryMysqlMigrationTest` | 3 项通过；随机测试库验证新旧数据、可重入执行及不匹配结构。迁移实现未再改变，保留原证据，不重跑既有业务库。 |
| 当前真实 MySQL／Redis | [073](../../../../.codex/unify-context-summarization/runs/073-final-real-storage/command.json)，`UnifiedSummaryStorageIntegrationTest,UnifiedContextStorageOnlineIntegrationTest` | 20 项通过，含 8 项在线跨回合场景；验证提交／回滚／确认丢失、回收重试、r1/r2 读取交错、完整旧副本、新 epoch、截止和取消。 |
| 应用兼容 | [066](../../../../.codex/unify-context-summarization/runs/066-application-compatibility/command.json) | 31 类、510 项通过；覆盖 HTML／MULTI_FILE、工厂与流式、只读升级、计划、取消／删除、构建判定、API/SSE。 |
| 来源和计划协议补验 | [074](../../../../.codex/unify-context-summarization/runs/074-source-plan-contracts/command.json) | 5 类、30 项通过；包括空计划拒绝、版本与依赖检查、计划构建闸门和来源配对。 |
| 计划证据修复 | [071](../../../../.codex/unify-context-summarization/runs/071-plan-evidence-null-fix/command.json) | 6 类、45 项通过；读取、计划与构建事实不会冒充文件变更，也不会因空路径导致存证异常。 |
| 摘要启动竞态及相关回归 | [077](../../../../.codex/unify-context-summarization/runs/077-late-summary-start-fix/command.json) | 7 类、210 项通过；许可等待后超过原截止或收到中断均无迟到模型调用、无调用计数增加。 |
| 当前真实模型与 npm／Vite | [078](../../../../.codex/unify-context-summarization/runs/078-real-model-final/command.json) | 1 项完整长任务通过；实际生产请求链、真实模型、真实存储及文件／构建工具，随后执行下一用户回合。 |
| OpenSpec 结构检查 | [079](../../../../.codex/unify-context-summarization/runs/079-final-planning-contracts/command.json) | `openspec validate unify-context-summarization --strict` 通过。它只作为结构检查，不代替独立评审或实现验收。 |

[080 格式检查](../../../../.codex/unify-context-summarization/runs/080-final-format-check/command.json) 已确认工作区与暂存区的 `git diff --check` 均通过。

这些测试层存在重叠，不把相加后的数量当作独立场景总数。此前各组聚焦检查按清单引用；受最后两项修复影响的草稿、证据与在线模型路径已用 071、077、078 补验。066 中未使用变更夹具且相关输入未变的检查继续有效；065 的旧夹具结果已由 073 的当前执行补齐。073 使用可控草稿，未把它当作草稿模型调用证据。

### 真实模型长任务的结果

[078 验收数据](../../../../.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/acceptance.json) 和同目录的原始请求／响应证明：

- 使用项目配置的 `deepseek-flash`、生产 `ConservativeChatTokenEstimator`，保持 49,152／57,344／65,536 阈值、12,288 原文软目标、3,072 摘要上限及每次准入原 60 秒截止；未降低阈值或替换估算器。
- 6 次有效语义摘要分别提交 r1～r6，覆盖序号递进为 **17、19、25、27、33、35**，均属于同一尚未结束的持久用户回合，完整历史游标保持零。共 6 次实际摘要调用，未把缓存或任务复用计成新调用。
- 23 次主模型请求的完整估算范围为 6,744～53,252 Token，发送消息与同次门禁决定逐项相等；当前计划只出现一份，创建、修订、文件状态及只读下一回合读取均反映最新提交状态。
- 实际创建计划 v1、修改标题、执行构建；第 1 次在 `NPM_BUILD` 阶段因缺少 `MissingBanner.vue` 失败（退出码 1）。修订为 v2 并补齐组件后，第 2 次真实构建成功（退出码 0），生成 `dist/index.html`。
- 收尾不额外调用摘要模型。下一用户回合的 [原始回答](../../../../.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/cross-turn-answer.txt) 正确复述不使用轮播图及移动端遮挡理由、保留品牌提示的理由、v1→v2、首次失败和最终成功，并引用第 15 批原文标记。实际请求保留该工具结果，可信终态进入后端状态通道，没有重复作为独立 AI 结果追加。
- 主模型与摘要模型共 29 份响应分别保存，与实际请求次数一致。工程、npm 缓存、构建命令和原始证据均在项目 `.codex/` 下。

### 失败记录与处置

[独立实现评审](../reviews/implementation-review.md) 结论为 **PASS**，评审者未编写被审查业务代码或测试。两个实际发现均已关闭：

1. **IR-01**：计划证据对空 `changedRelativePath` 查询不可变列表导致异常。070 已真实复现；修复为先判路径非空再关联文件，071 与最终真实链路通过。
2. **IR-02**：等待启动许可期间耗尽截止，返回后仍可能新发摘要调用。076 的截止和中断两个用例均在旧实现复现；修复在实际调用及计数之前复检原截止和中断，077 与最终真实链路通过。

064 的通用 record 序列化和未知 epoch 放行预期、069 的 npm 配置文件复用，以及 067、075 的测试编译错误，均保留原日志并由后继运行纠正；未通过放宽来源校验、降低预算、跳过测试或伪造模型结果换取通过。早期失败不计作当前成功证据。

## 环境、交付与回退边界

- 本机 MySQL `127.0.0.1:3406` 只创建并清理 `context_summary_it_*` 随机库；Redis `127.0.0.1:6379`、数据库 0 只使用随机测试应用的三个键。每项测试结束都核对删除结果，没有修改既有业务库。
- 真实模型场景使用本次合成诊断材料；L2 偏好服务和工厂失效通知为明确替身，相关隔离、触发及失败语义另由本地回归覆盖。它验证指定生产记忆链路，不把合成资料当作生产用户数据，也不把该结果宣称为全站浏览器验收或任意摘要无损保证。
- 保留单后端实例边界；重启只使用已持久化历史、证据、L1/L2 和当前工程文件重建，不自动续跑工具，不承诺恢复未摘要的完整工具轨迹。
- 发布时必须避免新旧写入器混用。只加列但未写入新格式，与已经写入新覆盖／证据／L0 格式，是不同回退阶段；按 [迁移说明](../../../../sql/migrations/README.md) 执行，不能靠清空摘要或证据回退。
- 原始证据保留在 `.codex/unify-context-summarization/runs/`，不因本地提交自动进入 Git；跨机器复核需要这些原始文件。未自动推送、归档或发布，保留用户的无关修改。

最终 [机器验收记录](verification.json) 由标准助手生成并用 `check-verify` 核对。其输入包含本报告、独立评审、完整映射、相关代码及原始证据；后续修改这些输入时必须重新评估受影响验证。
