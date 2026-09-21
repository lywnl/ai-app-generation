# 实施证据

- 分支 master；风险 strict；新规划评审 PASS，2026-09-21T08:19:43Z 完成 start-apply。
- 所有 Maven 命令在项目根执行，fork JVM 参数统一为 `-Djava.io.tmpdir=$PWD/.codex/fix-plan-dependency-reset/tmp -Duser.dir=$PWD/.codex/fix-plan-dependency-reset/run-root`，不使用真实生成目录或服务。前端命令在前端目录执行。
- 真实计划文件的初始哈希保存在 `.codex/fix-plan-dependency-reset/plans-before.json`，用于最终比较，不编辑任何旧计划。

## 已完成

- 3.3：正式规格与增量 Requirement 全文一致；严格 CLI 校验、差异检查和真实计划哈希比较通过，结构化映射7项（1个Requirement含其场景、6个任务）已建立。可复核命令 `node .codex/fix-plan-dependency-reset/verify-scope.cjs` 退出码0，日志 `logs/10-scope-final.json`；此前检查原始记录为 `logs/08-openspec.log`、`logs/09-consistency.json`。

- 3.2：根目录 Maven 指定 `AppPlanStateManagerTest,PlanToolTest,PlanToolProtocolSupportTest,AppPlanReadOnlyTest,AppPlanQueryServiceTest,AppControllerPlanTest,ReplanDetectorTest,ReplanContextTest,StreamingRequestControllerReplanTest,BuildProjectPlanGateTest,GenerationSseEncoderTest`，沿用隔离参数，退出码0，54项/0失败/0错误/0跳过；日志 `logs/05-backend-regression.log`。
- 前端目录 `npm run test -- src/utils/planSnapshot.test.ts src/utils/planPresentation.test.ts src/utils/toolOperationDisplay.test.ts src/utils/generationSession.test.ts src/utils/planObservation.test.ts src/composables/useGenerationPlan.test.ts`，退出码0，6文件169项通过；日志 `logs/06-frontend-regression.log`。未修改任何前端文件。
- 真实计划核对：对项目生成目录内7份 `.plan.json` 的路径集合和 SHA-256 与实施前比较完全相同；日志 `logs/07-plans-unchanged.json`。仅只读计算哈希，无历史计划修复或迁移。

- 2.2、3.1：隔离目录下 `-Dtest=PlanToolTest,BuildProjectPlanGateTest`，退出码0，16项通过；日志 `logs/04-tool-gate.log`。包含真实 FileWriteTool → recordAppliedMutation → 计划写回 → beforeBuild，路由变更后入口字节和 mtime 均不变且门禁放行。追加验证取消在已进入可信作用域后生效，以及新增条目 PENDING，将随最终回归重新核验。

- 隔离方式基线：`bash mvnw -q -DargLine="<上述参数>" -Dtest=AppPlanReadOnlyTest,PlanToolTest test`，退出码0，10项通过；日志 `logs/01-isolation-baseline.log`。
- 1.1、2.1：`bash mvnw -DargLine="<上述参数>" -Dtest=AppPlanStateManagerTest,PlanToolProtocolSupportTest test`，退出码0，14项通过；日志 `logs/03-state-protocol.log`。此前新测试因缺少新方法/重载而编译失败，红灯日志 `logs/02-red.log`，退出码1；未改变测试预期绕过问题。

所有日志位于 `.codex/fix-plan-dependency-reset/`。新实现当前未提交；后续由最终报告与机器指纹绑定。未进行模型、数据库调用或环境重启。
