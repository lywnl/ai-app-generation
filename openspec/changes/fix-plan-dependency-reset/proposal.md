## Why

新增登录路由时，已有 `main.js` 因依赖路由而被后端传递重置为 `PENDING`，尽管本轮没有明确要求修改入口。构建门禁因此要求一次不必要的文件变更。本变更仅修复状态误重置的起因，不承诺修复所有重复构建循环。

## What Changes

- `updatePlan` 只重置显式新增/修改的非 KEEP 条目，间接依赖文件继承原状态与元数据。
- 反向依赖分析只生成模型检查提示，经既有计划工具 `message` 返回，不新增 Replan 触发或持久状态。
- 按用户明确授权同步正式规格、增量规格、实现与回归测试；不修改旧真实计划或归档历史。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `vue-soft-replan`：修改计划修订状态传播语义，允许未修订条目继承可信状态，并新增非阻断影响提示。

## Impact

后端 AppPlanStateManager、UpdatePlanTool、PlanToolResult 及邻近测试；保持文件成功状态写回、构建门禁算法、协议字段、前端及运行环境不变。采用 spec-driven / strict / machine。

## Sources

用户本轮批准的完整执行计划及前一轮两个明确选择为需求来源，无外部需求文档。已核实事实：旧正式规格要求传递重置，状态管理器的反向 BFS 将种子及所有依赖者置为 PENDING，旧单测据此断言。`vue-plan-workspace-ui` 的评审与验收是历史基线，本变更独立评审，不重写旧结论。
