## 1. 状态与影响分析

- [x] 1.1 分离直接状态重置与间接依赖分析，更新 AppPlanStateManager 及单测；依赖：规划评审通过；验证：三节点链、多种原状态和 KEEP、多个显式节点、稳定顺序与去重、空种子、环校验保持。

## 2. 计划工具接入

- [x] 2.1 为 PlanToolResult.applied 增加自定义 message 的兼容重载；依赖：无；验证：PlanToolProtocolSupportTest 严格往返、字段集合不变、默认文案不变。
- [x] 2.2 UpdatePlanTool 接入新顺序和最多20路径的非阻断提示；依赖：1.1、2.1；验证：PlanToolTest 覆盖提示/无提示/截断、版本与历史、合法新增修改删除、悬空/自依赖/环拒绝且字节不变、取消无成功提示、反馈次数和状态不新增偏差。

## 3. 回归与交付证据

- [x] 3.1 新增隔离的依赖修订到文件成功写回再到构建门禁的重现测试；依赖：2.2；验证：原 router/main 已触达，updatePlan 只重置 router，文件工具真实成功变更 router 后 main 不再写入也可通过 beforeBuild；修改前仍拒绝，真实项目计划不被触碰。
- [x] 3.2 执行后端计划/Replan/查询及前端计划解析/工具流回归；依赖：3.1；验证：Maven 指定本次测试与 AppPlanReadOnlyTest、AppPlanQueryServiceTest、AppControllerPlanTest、ReplanDetectorTest、ReplanContextTest、StreamingRequestControllerReplanTest、BuildProjectPlanGateTest，前端 Vitest 指定 planSnapshot、planPresentation、toolOperationDisplay、generationSession、planObservation、useGenerationPlan；保存日志和真实退出码。
- [x] 3.3 核对正式规格与增量规格一致、作用域及证据完整性；依赖：3.2；验证：openspec validate、git diff --check，确认没有业务项目计划写入、模型/数据库调用或前端/构建/循环策略改动，形成结构化映射和验证记录。

## 4. 流程交付条件

严格流程须独立规划评审并登记 start-apply；实现后须独立代码评审及 verify 指纹通过。这些为交付条件，不是验收的循环前置任务。旧规格与旧验收保留历史含义，本变更新语义由独立当前基线证明，不自动归档、推送或重启运行服务。
