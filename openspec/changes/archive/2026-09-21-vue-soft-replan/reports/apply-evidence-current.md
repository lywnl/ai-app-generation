# vue-soft-replan 当前实施证据

## 基线与范围

- 变更：`vue-soft-replan`
- 风险等级：`strict`
- 分支：`master`
- 实施入口：已执行 `evidence.py start-apply --profile strict`，时间为 2026-09-20 11:06:25 +00:00。
- 规划基线：当前 `.openspec.yaml`、`proposal.md`、`specs/vue-soft-replan/spec.md`、`design.md`、`tasks.md` 及 strict 规划评审报告。
- 外部环境：未启动真实数据库、模型调用、上传、部署或生产服务。

## 已完成实现

- 计划状态使用安全目录句柄原子保存，保存 CAS 同时校验版本和 `activeTurnId`；旧回合覆盖、父目录/目标符号链接和安全目录能力缺失均有 fail-closed 边界。
- 在线回合开始接管已有计划，计划摘要进入初始、普通续请求和 incomplete recovery 的临时系统消息，不写入聊天记忆。
- `VueBuildLease.commitWhileActive` 与 `AppOperationLease` 提供取消/计划保存原子提交点。
- `makePlan`/`updatePlan` 拒绝空计划、悬空依赖、自依赖和循环依赖；依赖修订沿反向图传播 `PENDING`。
- 文件成功事实先观察 Replan，再写入 `TOUCHED`/`OUT_OF_PLAN`；偏差持久化为 `REPLAN_PENDING`，`updatePlan` 和 fail-open 清理；构建成功写回 `BUILT`。
- incomplete recovery 合并 Replan 临时反馈；失败计数在成功变更和计划版本切换后重置。
- 文件工具 8 字段协议、在线无 `exit` 闸门、只读/评测/HTML/MULTI_FILE 隔离保持不变。

## 真实验证

Java 聚焦命令：

```text
bash mvnw -Dtest='AppPlanTest,AppPlanStateManagerTest,ReplanDetectorTest,ReplanContextTest,StreamingRequestControllerReplanTest,PlanToolProtocolSupportTest,PlanToolTest,FileToolSecurityTest,BuildProjectPlanGateTest,BuildProjectToolTest,ProjectDownloadServiceImplTest,AiGeneratorServiceFactoryTest,AiCodeGeneratorFacadeTest,VueProjectSystemPromptTest,UnfinishedToolChainCheckpointProjectorTest,VueToolExecutionFactTest,AiServiceStreamingResponseHandlerTest,AppOperationLeaseManagerTest' test
```

结果：274 个测试通过，0 个失败，0 个错误。原始日志：`.codex/vue-soft-replan/java-focused-final.log`。

前端命令：

```text
npm test -- --run
npm run type-check
npm run build-only
```

结果：Vitest 14 个文件、290 个测试通过；类型检查退出码 0；生产构建成功。原始日志：`.codex/vue-soft-replan/frontend-vitest-current.log`、`.codex/vue-soft-replan/frontend-typecheck-current.log`、`.codex/vue-soft-replan/frontend-build-current.log`。

完整 Maven：

```text
bash mvnw test
```

结果：2098 个测试中 4 个失败、22 个跳过。失败均为既有基础设施/生产部署契约，与本变更无关：

- `InfrastructureCredentialConfigTest.Redis启动时动态生成Acl且仓库不再保存明文Acl`
- `InfrastructureCredentialConfigTest.生产Compose使用共享密码和独立MinIO密码注入基础设施`
- `InfrastructureCredentialConfigTest.生产环境模板声明共享密码和独立MinIO密码且真实文件被忽略`
- `ProductionRagDeploymentConfigTest.生产Compose仅以Milvus作为Rag向量基础设施`

原始日志：`.codex/vue-soft-replan/java-full-final.log`。该结果不能表述为全量 Maven 通过。

其他检查：

```text
bash mvnw -DskipTests compile
openspec validate vue-soft-replan --type change --strict --no-interactive
git diff --check
```

均通过。

## 当前限制

未执行真实数据库、模型、上传或部署验收；这些操作未获本次授权。完整 Maven 的 4 个既有配置失败仍需在项目基线修复后单独处理，不阻塞本变更的聚焦验收，但在 strict verify 报告中保留为共享基线限制。
