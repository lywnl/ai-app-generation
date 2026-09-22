## Why

2026-09-22 12:30 的只读回合真实修改文件后，requiresBuild 已要求构建，但作用域权限仍检查初始 READ_ONLY，导致两次构建拒绝并以未完成工具链结束。需要统一有效执行状态并补齐 P/E 上下文。

## What Changes

- 真实文件 APPLIED 且 changed 后启用构建、计划工具和构建进展观察，保留初始分类。
- 共用一次性计划初始化，绑定活动回合并检测首次修改；工具结果提交后发送可靠的临时升级反馈。
- 初始化失败保留真实写入结果，受控系统异常结束；取消仍服从租约竞争。
- 保留计划状态规则，补计划已完成事项明确 KEEP，不自动回填 TOUCHED。

## Capabilities

### New Capabilities
- `vue-mutation-promotion`: 只读回合真实修改后的执行升级、反馈与异常收口。

### Modified Capabilities
- `vue-soft-replan`: 只读限制仅适用于没有真实变更的回合。
- `vue-build-progress-guard`: 升级后的回合纳入构建保护，首次变更不漏观察。

## Impact

只涉及后端回合上下文、文件工具作用域、生成 facade、流控制器与内部终止映射。无新依赖、对外协议字段或前端改动。

来源：用户本轮批准的执行计划与 KEEP 决策；当前会话截图；`.codex/runtime-restart-20260921/backend.log` 1753 行起的实际工具调用记录；现有 requiresBuild、mutationAllowed 和计划绑定实现。无外部需求文档。

不迁移旧计划，不修改数据库，不调用真实模型，不重启、不提交、不归档。风险 strict：涉及回合权限、真实文件落盘与取消/终止边界。
