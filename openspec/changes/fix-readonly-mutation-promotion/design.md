## Context

问题证据见 proposal。现有 requiresBuild 已依赖 mutationRevision；FileToolScope.mutationAllowed 仍只检查初始枚举。计划初始化只在 facade 初始 mutation 分支，recordAppliedMutation 又先检测后记变更序号。工具循环已有调用级 GuardedToolExecution、结果提交栅栏与按请求代次确认的构建反馈。

## Goals / Non-Goals

恢复真实修改后的构建能力并保持 P/E、可信结果和生命周期完整；不改变分类规则、不全局放开只读计划操作、不回填状态、不增加外部字段。

## Decisions

1. facade 作用域 supplier 改为 context::requiresBuild。保留 turnMode 初始化一次的语义和初始工具选择。
2. VueTurnContext 持有幂等计划初始化入口，共用 AppPlanStateManager/ReplanDetector 装配。scope 增加内部初始化/反馈回调（保留已有 online 重载），避免工具包依赖 facade。租约先记录成功变更，再执行初始化、首次检测、计划状态写回；整个提交保持当前活动身份。
3. GuardedToolExecution 增加可选调用级升级元数据，包含最新摘要的读取器或初始化失败标志；保持旧构造器。失败只在内部映射为新内部原因 PLAN_INITIALIZATION_FAILED，外部仍 SYSTEM_ERROR。最后真实成功结果先按原流程提交，认领终止后跳过剩余真实工具。
4. 控制器拥有一份当前回合升级反馈票据，结果提交后按 generation/toolId 去重登记。构建反馈既有请求启动/有效回调挂钩同时驱动升级送达；三条续行路径都取未送达票据并在构造请求时生成最新计划摘要，携带消息的实际请求才关联票据。准备失败、未携带、过期回调不消费；已送达后不重复注入。反馈不使用 Replan 的计数与 pending 槽位。
5. 权限在工具执行后重新读取。首次升级的 before 诊断为空，不把上下文重绑定当进展；after 和成功事实进入既有 BuildProgressGuard。正常已初始化工具保持原 before/after 比较。
6. 反馈限制路径和摘要长度，提示无计划 makePlan、待修订 updatePlan、无需继续修改明确 KEEP，且不复述内部提示。同批后续计划工具变化通过摘要读取器反映，不自动重新执行 RAG、分类或图像增强。
7. 初始化失败捕获在调用级元数据，保留标准 APPLIED 结果，停止前向执行；取消先赢遵循现有结果提交与终态竞争。记录受限 appId/turnId、事件、失败类型日志，不记录参数或源码。

## Risks / Trade-offs

- 文件落盘与计划不是跨文件事务：失败保留事实并停止，不伪造回滚或已构建。
- KEEP 依赖模型确认：通过提示和既有构建保护纠偏，不能偷偷改状态以通过门禁。
- 当前计划工具明确修订会重置 PENDING：补计划场景通过 KEEP 解决，保留用户选择。
- 反馈生命周期与并发：复用控制器同步与有效代次判定，测试准备失败、同步回调、迟到、压缩和恢复。

## Migration Plan

无持久格式迁移，仅影响部署新代码后的回合。按用户后续授权启动服务；回退代码不会改写项目计划。现有失败项目留待用户正常后续请求，不进行批量修复。
