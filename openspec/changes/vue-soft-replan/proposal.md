## Why

当前 Vue 在线生成依赖单一 ReAct 工具循环推进，模型在执行中发现文件路径、依赖关系或前置假设错误时，没有统一的计划状态和修订入口，容易重复修改、扩大计划外变更，直到构建阶段才暴露问题。现有工具结果和构建终态协议已经承担严格的受信边界，因此需要在不破坏这些协议的前提下，为在线变更回合增加可追踪的计划、偏差反馈和软 Replan 能力。

## What Changes

- 为 Vue 在线 `MUTATION_REQUIRED` 回合增加计划生命周期：首轮创建计划，执行中记录计划文件变更，发现偏差后要求模型修订计划并继续当前 ReAct 循环。
- 增加 `makePlan` 和 `updatePlan` 计划工具，并为计划工具建立独立的受信结果协议；不扩展现有文件工具的严格 8 字段协议。
- 将计划状态原子持久化到项目目录的受保护 `.plan.json`，绑定当前 `turnId`，并沿用现有作用域、取消和租约边界。
- 通过 `StreamingRequestController` 的偏差观察与临时反馈领取路径，把一次性计划校正提示注入下一次模型请求；不终止当前 generation，不创建新的 `TokenStream`。
- 当计划存在未处理偏差时，文件变更工具沿用标准 `REJECTED` 文件协议阻止继续写入；`updatePlan` 成功后解除阻断。Replan 反馈达到上限后按已确认策略 fail-open 并记录状态。
- 将 `buildProject` 作为在线计划完成闸门，保留现有构建结果、构建次数和反思机制；不使用在线 `exit`，不新增自动构建修复回合。
- 保护 `.plan.json` 不被普通文件工具读取、修改、删除或打包下载；只读回合和评测作用域不启用计划工具。
- 接入现有工具白名单、可信事实解析、工具展示和相关测试；计划摘要继续通过现有工具执行事件展示，暂不增加计划 REST 面板。
- 2026-09-21 用户追加确认：物理删除内部输出标记检测及专属恢复，保留 P/E、统一工具流、其他保护及前端业务展示。此前只关闭入口的阶段性回退不作为最终交付。

## Capabilities

### New Capabilities

- `vue-soft-replan`: 为 Vue 在线生成回合提供结构化计划、计划偏差检测、临时反馈、模型驱动计划修订和构建前计划闸门。

### Modified Capabilities

- 无。当前 `openspec/specs/` 没有既有 capability spec；本变更新增上述能力。

## Impact

- AI 工具与协议：`VueToolNames`、计划工具、计划协议解析、`VueToolExecutionFact`、工具展示和 `ToolManager`。
- 在线执行链路：`VueTurnContext`、`StreamingRequestController`、模型请求临时消息路径以及 `FileToolExecutionScopeManager`。
- 文件和构建边界：`ProjectPathResolver`、文件工具的标准拒绝分支、`BuildProjectTool` 与现有构建状态管理。
- 提示词与测试：Vue 在线系统提示词、工具协议测试、只读/评测/取消边界测试、软 Replan 和构建闸门集成测试。
- 持久化文件：项目目录新增受保护的 `.plan.json`；不新增数据库表，不修改聊天历史协议。计划功能不改变 HTML、MULTI_FILE 和评测模式；追加删除范围同时移除普通生成的内部输出标记拦截，其他行为保持不变。
- 需求来源：用户确认的 P/E Replan 方案及本项目已核实的在线工具白名单、文件工具严格协议、回合租约、取消机制、可信工具事实和构建终态实现。
