## MODIFIED Requirements

### Requirement: 计划摘要必须沿用现有工具展示通道

系统 SHALL 通过现有工具执行事件向前端展示 `makePlan` 和 `updatePlan` 的计划摘要和修订原因，并保持既有工具结果协议与 SSE 类型。系统 SHALL 同时支持通过仅登录应用所有者可访问的只读计划查询接口，为用户计划面板提供当前计划及已有修订记录；查询 SHALL 不改变 P/E 执行、计划状态写回或构建闸门语义。

#### Scenario: 创建计划后的展示

- **WHEN** `makePlan` 执行成功
- **THEN** 前端 SHALL 能通过现有工具执行消息看到计划摘要、版本和文件清单

#### Scenario: 修订计划后的展示

- **WHEN** `updatePlan` 执行成功
- **THEN** 前端 SHALL 能通过现有工具执行消息看到修订原因和新计划版本

#### Scenario: 用户页面恢复当前计划

- **WHEN** 登录的应用所有者刷新或重新进入 Vue 应用页面
- **THEN** 系统 SHALL 允许只读查询当前计划以恢复面板，同时保持普通文件工具对 `.plan.json` 的访问限制，不新增用户直接修改计划的入口
