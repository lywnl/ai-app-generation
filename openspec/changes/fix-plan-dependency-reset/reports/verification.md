# 实现验收

- 变更：fix-plan-dependency-reset；等级 strict；核验方式 machine；日期 2026-09-21。
- 结论：`Ready for Archive`。六项任务完成，独立代码评审 PASS，无未解决阻塞；不自动归档、推送或重启服务。
- 当前基线：master 的未提交差异；本次业务修改限定为 AppPlanStateManager、UpdatePlanTool、PlanToolResult，另有三项后端测试和正式规格。指纹绑定当前文件而非仅 Git HEAD。

## 需求与实现

详见 `verification-manifest.json`：一个修改的 Requirement（含新旧场景）及六项任务，共七条映射。新语义仅重置显式条目，间接依赖继承原状态与元数据；检查提示只在成功提交的既有 message 中返回，路径去重排序并限制20条。检查提示不设置 REPLAN_PENDING，不增加反馈计数，不修改构建门禁算法。

正式 `openspec/specs/vue-soft-replan/spec.md` 与本次完整 Requirement 增量一致。归档历史、上一期规划/验收记录均保持原样；正式规格已变，上一期指纹不用于证明当前新语义。

## 真实证据

所有日志位于 `.codex/fix-plan-dependency-reset/`，命令与具体工作目录见结构化清单和 apply-evidence.md。

| 检查 | 结果 | 日志 |
| --- | --- | --- |
| 后端计划、Replan、查询、SSE 和门禁回归 | 54项通过，无失败/错误/跳过 | logs/05-backend-regression.log |
| 前端计划解析、展示、查询调度与统一工具流 | 6文件169项通过 | logs/06-frontend-regression.log |
| 正式与增量规格一致、差异空白检查、真实计划哈希 | 通过，7份旧计划未变 | logs/10-scope-final.json |
| OpenSpec 严格校验 | 通过 | logs/08-openspec.log |

路由/入口重现测试使用真实 FileWriteTool、FileToolExecutionScopeManager、计划持久化和 beforeBuild：修订后只有路由待处理，写回前门禁拒绝，路由成功变更后门禁放行，入口文件内容与 mtime 均未改变。该测试不运行 npm 构建，不调用模型或数据库。

Maven fork JVM 的 user.dir 和 java.io.tmpdir 均指向本变更的 .codex 隔离目录；查询测试使用数据库/登录替身。先前新测试缺少新方法时的编译红灯保留于 logs/02-red.log，已通过后续实现修复。未用跳过、旧报告或模拟模型冒充真实模型验收。

## 评审与基线

独立上下文 `/root/planning_review` 未编写业务源码，报告 `independent-code-review.md` 为 PASS，已独立检查哈希和测试隔离参数。

原独立规划报告记录了 design 澄清前的哈希；其非阻塞提醒已明确解释原有 message 可以沿 SSE 传递。该说明在 record-plan/start-apply 前落实为文案澄清，机器规划记录绑定澄清后的 design。独立复核附录 `planning-clarification-review.md` 确认不改变需求、接口或验收，原报告和实施开始记录未倒填或覆盖。当前 check-plan 通过。

## 交付边界

- 不修改或迁移真实旧计划；旧项目中的误置 PENDING 不会自动恢复。
- 本次只修状态误重置，不实现重复构建检测、预算或循环收口。
- TOUCHED 是可继承的可信变更事实，不代表新依赖兼容性已验证；后续正常构建及行为验证仍必需。
- 没有新增协议字段、工具、状态、SSE 或前端代码改动。运行服务未重启；新代码须下次启动后生效。
