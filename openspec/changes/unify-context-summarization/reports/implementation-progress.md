# 实施证据索引

本文件记录实施事实，不表示整个变更已验收。规划仍以有效的 strict 基线为准，详细任务／需求映射逐步维护在 `verification-manifest.json`。

| 任务 | 实现与验证 | 原始证据 |
| --- | --- | --- |
| 1.1 | 摘要实体、组合覆盖及旧构造兼容、召回格式／Token 校验、内部证据 JSON 隔离；5 个聚焦测试类共 16 项通过，无跳过 | `.codex/unify-context-summarization/runs/001-contracts/command.json`、`maven.log`、JUnit XML；同目录 `code-state.json` 记录已提交及未提交源码状态 |
| 1.2 | 两份初始化结构与条件加列迁移一致；10 项迁移契约测试通过，无跳过，尚未连接真实数据库。 | `.codex/unify-context-summarization/runs/002-migration-contract/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.1 | 实际用户记录 ID 在提交门内绑定并传至请求边界；109 项回合、服务与 Facade 回归通过。 | `.codex/unify-context-summarization/runs/003-turn-identity/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.2 | 版本封装及旧数组兼容、原始工具 JSON、Unicode、未知读取下界和空窗口依赖；12 项测试通过。 | `.codex/unify-context-summarization/runs/004-l0-codec/command.json`、原始日志、JUnit 与代码内容哈希 |
| 1.3 | 真实 MySQL 8.0.40 的 3 项测试通过，无跳过；初始化、旧库升级及重入逐字段保持数据，随机库已清理，DDL 与 DML 失败边界已验证。 | `.codex/unify-context-summarization/runs/005-mysql-migration/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.3 | 同锁追加、系统替换、关闭、覆盖裁剪及恢复保留依赖；92 项聚焦回归通过。首次测试辅助类编译问题已在本次修复，原失败日志保留于 006-source-operations。 | `.codex/unify-context-summarization/runs/007-source-operations/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.4 | 共享纯工具配对校验，按后端边界判断结束，保留 AI 中途说明；25 项解析及回归测试通过。 | `.codex/unify-context-summarization/runs/008-source-parser/command.json`、原始日志、JUnit 与代码内容哈希 |

## 环境边界

任务 1.3 已获用户明确授权并在 `127.0.0.1:3406` 的现有 MySQL 8.0.40 容器执行；只新建并清理本次随机测试库，没有修改既有业务库，容器原已运行。

`ENVIRONMENT_NOTE`：后续真实 MySQL／Redis 组合验证及真实模型长任务尚未执行，其对应任务保持未完成；本地替身测试不替代这些真实验收。
