# 规划措辞澄清复核

- 评审者：独立执行上下文 `/root/planning_review`，未编写本变更规划或源码。
- 结论：`PASS`，当前澄清保持原独立规划结论，不构成需求、接口或验收范围变化。
- 原记录保留：不修改 `reviews/independent-planning-review.md`、规划机器记录或实施开始时间；本文件作为当前验收证据附录。

## 实际变化与依据

`design.md` Decisions 4 将“提示只给模型，不扩展用户面板”明确为“提示用于指导模型，不新增用户面板展示，原有工具事件仍可携带完整 message”。本执行上下文已读取当前设计及 `JsonMessageStreamHandler.realtimeToolExecutedChunk`、`ToolStreamMessageRedactor.safeResult`、前端 `toolOperationDisplay`：计划工具原始结果确实沿现有事件传输，前端计划工具采用固定展示文案。该澄清落实了原独立报告的第一条非阻塞提醒，没有新增或删除传输能力，也不要求前端变更。

- 原设计 SHA-256：`67b528a95d87d46331346f0b8c0045009dfdccf710adf4ca74fdb4961d452e13`。
- 当前设计 SHA-256：`097aacdfc427b91a5437ff3fbf212bf2c99e1ab9b0cff2d05e9d91e4a7b07ea3`。
- 已核对 `reviews/planning-review.json` 绑定的是当前设计哈希，实施开始记录为 `2026-09-21T08:19:43.549194+00:00`。本附录不倒填此前独立报告读取时点，不将旧手工哈希称作当前哈希。

## 历史计划边界澄清

原独立报告使用“不读取或修复历史真实 .plan.json”的措辞过宽。用户批准的边界是“不修改旧真实计划”，允许在当前工作区只读计算哈希确认未变。本次核对不解析或输出历史计划正文，也不修复或迁移文件。已独立按 `.codex/fix-plan-dependency-reset/plans-before.json` 的七份清单计算当前 SHA-256，结果为 `checked=7, mismatches=[]`。

隔离参数另由当前 Surefire `PlanToolTest` 报告确认：`user.dir` 指向 `.codex/fix-plan-dependency-reset/run-root`，`java.io.tmpdir` 指向本变更 `.codex` 下 `tmp`。规划中“先核验隔离方式再执行涉及文件写入测试”的条件有实际证据，仍不得把这项核验解释为对真实用户计划进行变更的授权。
