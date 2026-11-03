# Proposal

## Why

首页允许进入他人的“查看对话”，历史服务却仅授权作者和管理员；页面又以历史至少两条作为预览条件，普通用户因此同时看不到历史和页面。用户已确认仅对同时精选且已成功部署的应用开放只读查看，需使读取范围与写权限清晰一致。

## What Changes

- 已登录用户可分页查看同时精选且已成功部署应用的展示对话；其余应用维持作者／管理员可读。
- 普通非作者只收到展示所需历史，不暴露内部记忆投影和决策证据。
- 普通非作者仅在同时精选且已部署时，使用服务端返回的部署 URL 预览；与历史条数及历史请求成功与否解耦。
- 页面显示只读说明，收紧生成、暂停、部署、编辑等前端入口，后端原有写权限继续生效。
- 增加历史加载失败、无历史及部署页面不可用的明确状态和重试入口，保留作者的生成、计划和工作区行为。

## Capabilities

### New Capabilities

- `app-conversation-view`: 应用历史与部署页面的只读访问，以及作者／访客操作隔离。

### Modified Capabilities

无。当前正式规范仅包含 Vue 构建、修改升级和计划偏差能力，本变更不改变这些约定。

## Impact

- 后端：`ChatHistoryServiceImpl.listAppChatHistoryByPage` 的访问判断与展示投影，相关控制器／服务权限测试。保持 `/chatHistory/app/{appId}` 的登录要求、参数及分页形状。
- 前端：`AppChatPage.vue` 的初始化、权限守卫、历史提示和预览来源；增加可单测的查看策略辅助代码及浏览器验证夹具。
- 数据：复用 `app.priority`（精选值 99）、`app.deployKey`、`app.deployedTime`；不迁移、不访问真实业务数据库、不改变 L0/L1/L2。
- 风险：strict，原因是新增跨用户历史读取边界。要求独立规划评审、独立代码评审和真实页面运行验证；本机替身不能被称为生产验证。
- 基线：`09c0284`。保护既有 `generationSession.test.ts` 修改及未跟踪 `.DS_Store`，不混入本次修改。

## Source Inputs

| ID | 位置 | 类型／确认状态 | 采纳范围 | 替代关系 |
| --- | --- | --- | --- | --- |
| U1 | sources/user-request.md#u1查看他人的应用 | 用户需求／已确认 | 能看历史与页面，普通非作者不能改 | 开放范围由 U2 明确，再由 U3 收紧 |
| U2 | sources/user-request.md#u2开放范围为已部署应用 | 用户决定／历史范围已被替代 | 保留原始确认和替代关系 | 被 U3 收紧 |
| U3 | sources/user-request.md#u3收紧为精选且已部署 | 用户决定／已确认 | 精选且已部署同时成立才对普通非作者开放 | 替代 U2 的不限精选范围 |
| D1 | AGENTS.md；.agents/shared/openspec/workflow-policy.md | 持续约束 | 中文、访问与授权边界、strict 流程及证据 | 无 |
| C1 | src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java；AppServiceImpl.java | 实施参考 | 当前历史权限、成功部署字段和写接口保护 | 允许按任务修改 |
| C2 | ai-app-generation-frontend/src/pages/app/AppChatPage.vue；src/config/env.ts | 实施参考 | 历史驱动预览、作者交互和 URL 构造 | 允许按任务修改 |
