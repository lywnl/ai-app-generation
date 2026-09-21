# 独立代码评审

- 日期：2026-09-21
- 风险等级：`strict`
- 评审执行上下文：`/root/independent_code_review`，未参与本变更业务实现。
- 后端部分结论：`PASS`。
- 前端部分结论：`PASS`。
- 完整独立代码质量评审结论：`PASS`；本报告不替代 OpenSpec verify，不登记完整变更最终验收通过。
- 后端基线：提交 `b1cfff392c3a710a03caa3c4a98bce6c801e39b2`，对照当前变更的 `design.md`、`specs/vue-plan-read-api/spec.md` 和任务范围。
- 前端基线：提交 `4876272`，与本次最终复核的工作区源码相同。已修改 `src/pages/app/AppChatPage.vue`、`src/components/ToolOperationCard.vue`、`src/request.ts`、`src/utils/generationSession.ts`、`src/utils/toolOperationDisplay.ts` 及展示测试；新增 `src/api/appPlan.ts` 及查询/登录行为测试、`src/composables/useGenerationPlan.ts`、`useGenerationPreview.ts` 及测试、`src/components/GenerationPlanPanel.vue`、`GenerationExecutionTimeline.vue`、`src/utils/planSnapshot.ts`、`planPresentation.ts` 及解析/展示/事件观察测试。以上路径位于 `ai-app-generation-frontend/`。
- 后端增量：`StaticResourceControllerTest` 新增真实 MVC 编码 URI 的 GET/HEAD 拒绝及编码应用目录兼容测试；后端业务源码相对 `b1cfff3` 未新增变化。

## 后端检查

未发现可确认的 Critical 或 Required 问题。

- `AppPlanQueryServiceImpl.java:32`：文件读取前验证应用 ID、身份、所有者和 Vue 类型；取得生命周期许可后二次核对，正常和异常路径均释放许可，管理员无所有者旁路。
- `AppPlanStateManager.java:57`、`PlanStoragePathResolver.java:47`：只读入口不创建目录、不重绑定回合；在应用锁内读取完整计划并校验依赖，区分不存在与不安全路径/损坏计划。
- `AppPlanVO.java:19`：投影保留计划、文件和受限修订字段，排除回合令牌与完整历史差异。
- `SecureFileAccess.java:26`、`:58`：通过目录句柄逐段使用 `NOFOLLOW_LINKS`，实际文件只以 `READ` 和 `NOFOLLOW_LINKS` 打开。
- `StaticPreviewResourceResolver.java:37`、`:61`、`:100`：一次严格 UTF-8 解码，拒绝保留计划文件名、点段、残留转义和歧义分隔符；响应流与元数据读取均重新经过安全目录边界，不误用文件工具的 `dist` 禁用规则。
- `StaticResourceController.java:36`：保留公开资源访问、默认首页、根重定向和既有缓存/内容类型行为，HEAD 不保留待消费流。

## 前端检查及发现处置

最终未发现未解决的 Critical 或 Required 问题。

- `useGenerationPlan.ts:19`、`:47`：按用户、应用和本地回合隔离，单上下文串行查询，观察序号改变后丢弃旧结果并合并后续查询；权限丢失清空且停止查询，普通失败保留标旧快照，无轮询。
- `AppChatPage.vue`：终态清理 SSE 会话前保留最后快照与查询上下文；账号/路由切换撤销查询、解绑监听并清空计划。布局与计划状态分离，成功业务终态和流收口才刷新，失败保留工作区，只读不刷新。
- `generationSession.ts`：相关工具结果和终态推进观察计数，重复同工具结果不重复推进；原有工具流和正文来源校验保留。
- `planSnapshot.ts`、`planPresentation.ts`、两项新展示组件：计划进度只由查询快照确认，KEEP、OUT_OF_PLAN、文件触达和工具执行结果分别展示，用户文本通过插值呈现。
- 已修复初始 iframe 资源错误及空体 404 被当作可用上一版的问题：同源已失败图片/资源检查及主文档 HEAD 核验，取消和身份检查防止旧探测更新新 iframe；跨源保留可观测性限制提示。
- 已修复首次失败无旧 iframe 仍显示可点击上一版入口的问题：无可用保留实例仅显示不可用提示。
- 已修复计划查询 40100 触发全局登录导航的隔离问题：同一 Axios 实例仅为计划请求设置 `skipLoginRedirect`，其他接口保持原行为，当前 SSE 与停止按钮不因计划查询失效而消失。
- `ToolOperationCard.vue:21`、`toolOperationDisplay.ts:74`：成功计划工具结果经结构校验后只投影该次版本、摘要和文件清单；查询失败也保留该次操作说明，不将工具结果写回 `planQuery.snapshot`。
- 已修复共用展示状态表让文件协议接受计划专属 CONFLICT 的回归：`toolOperationDisplay.ts:12`、`:50` 使用独立文件状态集合，`toolOperationDisplay.test.ts:70` 确认非法文件 CONFLICT 返回 unknown。

## 证据及限制

- 已读取 `.codex/vue-plan-workspace-ui/logs/08-backend-regression.log`。日志采用 Maven `-q` 输出，没有最终测试总计；损坏计划和删除缓存失败的日志来自故障注入用例。
- 首次后端评审对照当时 Surefire 报告，`b1cfff3` 的 8 个新增测试类共 31 项测试，失败、错误和跳过均为 0：`PlanStoragePathResolverReadOnlyTest` 6 项、`AppPlanReadOnlyTest` 4 项、`AppPlanViewTest` 2 项、`AppPlanQueryServiceTest` 5 项、`AppPlanQueryLifecycleTest` 2 项、`AppControllerPlanTest` 4 项、`StaticPreviewResourceResolverTest` 4 项、`StaticResourceControllerTest` 4 项。追加编码 URI 用例后，最后一类为 5 项。
- 测试包含实际文件读取、MVC/消息转换器消费响应体、Controller 预检后文件替换为链接、父目录链接、中文/空格/加号资源、越权拒绝、删除与查询交错及查询无目录创建。
- 本次评审未重新执行测试，未引用其他变更的测试计数。应用数据库和登录依赖为替身，未核验真实数据库、模型、上传或部署环境。
- 后端增量回归：`.codex/vue-plan-workspace-ui/logs/17-backend-final.log` 及对应 Surefire 报告记录 127 项聚焦测试通过，其中上述 8 个新增测试类最终共 32 项，`StaticResourceControllerTest` 为 5 项，新增 MVC 编码 URI 用例已纳入。
- `.codex/vue-plan-workspace-ui/logs/26-browser-review-regression.log`：9 项浏览器边界场景通过，包括真实 HTTP 404 空体、主文档 200 但入口 JS 404、从未创建 iframe 的首次失败、初始和延迟资源错误、导航、超时、接口缺失及实例保留。
- `.codex/vue-plan-workspace-ui/logs/27-browser-auth-regression.log`：桌面和移动端通过；已读脚本断言 40100 不导航且停止按钮仍存在、账号切换清空计划、硬刷新无旧实例、iframe 保留与成功切换。
- 计划工具摘要补齐后，`29-frontend-final.log` 为 21 文件 280 项通过，`30-build-final.log` 的类型检查和生产构建通过，`31-browser-final.log` 桌面及移动场景通过；以上日志均位于 `.codex/vue-plan-workspace-ui/logs/`。
- 最后文件协议状态隔离修复后，`32-frontend-protocol-final.log` 为 21 文件 281 项通过，`33-build-protocol-final.log` 的类型检查和生产构建通过。已读取当前最终代码及相应新增测试。
- `34-browser-build-final.log`：桌面和移动端均通过；已核对浏览器脚本从既有 `vue-build-tool/v1` 成功结果样本发送 `buildProject` 工具事件，验证执行记录与成功收口。该结果为受控结构化样例，不是真实构建调用。
- 浏览器数据来自可控 HTTP/SSE 样例；日志 `realGeneration: 0`，未作真实模型端到端成功声明。跨源 iframe 和浏览器不可观测的资源健康不在保证范围内；本期不增加构建产物版本存储。

## 非阻塞建议

- 原编码 URI 的 MVC 测试建议已落实；真实部署容器仍可在获准集成验收时补充核验。
- 普通文件属性检查与通道打开之间仍存在非符号链接类型替换窗口；现有 `NOFOLLOW_LINKS` 已覆盖本期明确要求的符号链接旁路。可补充两步之间替换为目录或 FIFO 的确定性测试，作为后续加固输入，不扩大本期为未批准的构建或存储改造。

## 后续交接

本变更前后端独立代码质量评审通过，可交给 verify 聚合当前实现、任务和真实证据。仍须绑定最终实现基线并完成项目验收流程；本报告不构成归档、推送或部署授权。
