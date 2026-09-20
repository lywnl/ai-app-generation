# 规划评审

- 风险等级：`strict`
- 结论：`PASS`
- 评审者与独立性：当前 `/root` 执行上下文完成聚合规划评审；strict 独立规划评审由 `/root/independent_code_review` 完成，见 `reviews/independent-planning-review.md`。
- 评审时间：2026-09-20
- 基线：`openspec/changes/vue-soft-replan/.openspec.yaml`、`proposal.md`、`specs/vue-soft-replan/spec.md`、`design.md`、`tasks.md`；持续约束为 `AGENTS.md`、`.agents/shared/openspec/workflow-policy.md`、`.agents/shared/openspec/review-checklist.md`；需求来源为用户在当前对话确认的 P/E Replan 最终方案，已由 `proposal.md` 摘要承接。
- 预检：`openspec context --json`、`openspec status --change vue-soft-replan --json`、`openspec schema which spec-driven --json` 和 `openspec validate vue-soft-replan --type change --strict --no-interactive` 均可执行并通过。未启动业务服务、未调用真实模型；这符合规划评审边界。

## 持续约束与允许修改的参考代码

持续约束只包括用户确认的范围和协议边界、`AGENTS.md`、OpenSpec 工作流规则，以及本变更规划文件。允许实施修改的参考代码是规划中列出的现有工具、回合、构建、路径、下载和提示词模块，例如 `src/main/java/com/lyw/appgeneration/ai/tools/`、`src/main/java/com/lyw/appgeneration/core/handler/`、`src/main/java/dev/langchain4j/service/` 及对应测试；这些路径用于理解接入点，不冻结其实现，也不作为规划通过证据。

## 阻塞项

无。独立评审已确认 strict 风险登记、后续回合接管、取消/CAS 原子边界、依赖传播、路径 fail-closed、`REPLAN_PENDING`/`BUILT` 生命周期和任务交付条件均已写入当前规划。

## 警告及处置

- `reports/apply-evidence.md` 和 `reports/verification-manifest.json` 是旧规划的历史证据，已标记 `SUPERSEDED`，不得用于当前验收。
- 任务清单中已完成的旧任务不替代新增边界任务的真实实现和测试；实现阶段必须按依赖完成未勾选任务并保留原始日志。
- 规划 PASS 只表示可以进入实施，不表示当前代码已经满足需求；严格验收仍需独立代码评审、真实测试和 `openspec-verify-change`。

## 实施交接

- 先执行 `evidence.py record-plan --profile strict`，绑定当前规划、持续约束、参考代码和本报告，再执行 `check-plan` 与 `start-apply`。
- 按任务依赖优先修复计划状态/CAS和路径边界，再修复回合摘要、依赖检测、文件事实观察顺序、构建状态和恢复请求，最后运行聚焦 Java/前端验证。
- 不得扩展文件工具 8 字段协议，不得把在线 `exit` 当计划终止闸门，不得启用硬 Replan，不得改变 HTML、MULTI_FILE、只读或评测模式。
- 任务完成后执行 `openspec-verify-change`，重建 strict 验收映射并取得独立代码质量评审；本报告不授权归档、提交或推送。
