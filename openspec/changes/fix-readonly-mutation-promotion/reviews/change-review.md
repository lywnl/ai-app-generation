# 规划评审

- 风险等级：strict；结论：PASS；核验方式：machine。
- 聚合评审：主执行上下文；独立评审见 independent-plan-review.md，评审者未参与规划撰写。
- 基线：当前 proposal、specs、design、tasks 与元数据；用户确认 KEEP 决策及批准实施。
- 持续约束：AGENTS.md、共享工作流及变更规划。允许修改的参考：src/main/java、src/test/java、提示词、相关正式规格，不将它们冻结为来源。
- 无阻塞；独立评审列出的锁序、工具事实保留和请求票据约束纳入实施检查。
- 验证：隔离 Maven Java 25 测试，受控流响应，前端原有回归；无真实模型、数据库或旧计划修改。按任务依赖执行，不自动重启、提交或归档。
