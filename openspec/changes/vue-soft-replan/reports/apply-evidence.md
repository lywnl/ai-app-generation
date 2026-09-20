# vue-soft-replan 历史实施证据

> 状态：`SUPERSEDED`。本报告基于旧的 `standard` 规划和旧的 25 项任务；当前变更已升级为 `strict`，且任务清单包含尚未完成的边界修复任务。本文件仅保留历史命令和结果，不得用于当前 apply、verify 或归档结论。

## 范围

- 变更：`vue-soft-replan`
- 分支：`master`
- 历史风险等级：`standard`（当前规划已升级为 `strict`）
- 实施入口：已通过 `evidence.py start-apply` 登记
- 外部环境：未启动真实数据库、模型调用、上传或部署环境

## 任务与聚焦验证

旧版 `tasks.md` 的 25 项任务曾全部完成并勾选。与本变更直接相关的 Java 聚焦回归命令为：

```text
bash mvnw -Dtest='AppPlanTest,AppPlanStateManagerTest,ReplanDetectorTest,ReplanContextTest,StreamingRequestControllerReplanTest,PlanToolProtocolSupportTest,PlanToolTest,FileToolSecurityTest,BuildProjectPlanGateTest,BuildProjectToolTest,ProjectDownloadServiceImplTest,AiGeneratorServiceFactoryTest,AiCodeGeneratorFacadeTest,VueProjectSystemPromptTest,UnfinishedToolChainCheckpointProjectorTest,VueToolExecutionFactTest,AiServiceStreamingResponseHandlerTest' test
```

结果：238 个测试通过，0 个失败，0 个错误。后续评审修复后的只读构建闸门和计划外变更闸门也包含在该聚焦套件中。

覆盖的关键行为包括：计划 JSON 往返和原子状态、上一回合接管、版本冲突、路径保护、计划工具协议、只读/评测/取消作用域、可信事实、偏差检测、一次性临时反馈、文件变更阻断、构建计划闸门、构建协议兼容和软 Replan handler 链路。

前端验证：

```text
npm test -- --run
npm run type-check
npm run build-only
```

结果：Vitest 14 个文件、290 个测试通过；类型检查退出码 0；生产构建退出码 0。

## 全量 Maven 结果

执行：

```text
bash mvnw test
```

结果：2083 个测试中 4 个失败、22 个跳过。4 个失败均来自既有基础设施/生产部署契约：

- `InfrastructureCredentialConfigTest.Redis启动时动态生成Acl且仓库不再保存明文Acl`
- `InfrastructureCredentialConfigTest.生产Compose使用共享密码和独立MinIO密码注入基础设施`
- `InfrastructureCredentialConfigTest.生产环境模板声明共享密码和独立MinIO密码且真实文件被忽略`
- `ProductionRagDeploymentConfigTest.生产Compose仅以Milvus作为Rag向量基础设施`

这些失败不引用本次变更文件，聚焦套件和 Spring 应用测试均未发现 `vue-soft-replan` 回归。本结果不能表述为全量 Maven 通过，后续验收需保留该基线限制。

## 其他核验

```text
openspec validate vue-soft-replan --type change --strict --no-interactive
evidence.py check-plan --root . --change openspec/changes/vue-soft-replan --profile standard
git diff --check
```

结果：OpenSpec 变更严格校验通过，规划指纹检查通过，Git diff 空白检查通过。
