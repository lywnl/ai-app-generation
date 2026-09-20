# vue-soft-replan 实现验收

- 风险等级：`strict`
- 规划基线：当前 strict `record-plan` 基线、`reviews/change-review.md` 与 `reviews/independent-planning-review.md`
- 实现范围：当前工作区相对 `HEAD` 的 Vue 在线软 Replan 代码、测试、提示词和路径/下载保护
- 任务状态：`tasks.md` 共 32 项必需任务，32 项已完成
- 独立代码评审：`reviews/independent-code-review.md`，结论 `PASS`

## 需求映射

详细映射保存在 `reports/verification-manifest.json`：

- R1/R2：计划模型、版本/CAS、回合接管、`.plan.json` 安全目录和受保护路径；由计划状态、文件安全、下载排除和回合生命周期测试覆盖。
- R3：独立计划工具协议、空计划/依赖输入校验、可信事实和文件 8 字段兼容；由计划协议、计划工具和可信事实测试覆盖。
- R4/R5：软 Replan 观察、一次性临时反馈、fail-open、文件变更阻断和计划外事实先观察；由 Replan、handler、计划工具顺序测试覆盖。
- R6：`buildProject` 计划闸门、依赖阻断、成功后 `BUILT` 写回和既有构建协议；由构建计划闸门和构建回归测试覆盖。
- R7：租约提交边界、取消竞态、旧回合覆盖、只读/评测隔离；由租约、计划状态和作用域测试覆盖。
- R8/R9：现有工具展示通道、提示词、非 Vue/评测/只读模式兼容；由门面、提示词和现有链路测试覆盖。
- R10：strict 并发/路径高风险边界；由 `commitWhileActive`、SecureDirectoryStream、统一受保护路径测试覆盖。

## 真实命令

- Java 聚焦：274 个测试通过，0 失败，日志为 `.codex/vue-soft-replan/java-focused-final.log`。
- 前端 Vitest：290 个测试通过；类型检查和生产构建通过，日志为 `.codex/vue-soft-replan/frontend-*-current.log`。
- 编译、OpenSpec strict 校验、`git diff --check` 均通过。
- 完整 Maven：2098 个测试中 4 个失败、22 个跳过。4 个失败均为既有基础设施/生产 Compose 配置契约：
  `InfrastructureCredentialConfigTest.Redis启动时动态生成Acl且仓库不再保存明文Acl`、
  `InfrastructureCredentialConfigTest.生产Compose使用共享密码和独立MinIO密码注入基础设施`、
  `InfrastructureCredentialConfigTest.生产环境模板声明共享密码和独立MinIO密码且真实文件被忽略`、
  `ProductionRagDeploymentConfigTest.生产Compose仅以Milvus作为Rag向量基础设施`。
  这些失败不引用本变更实现，完整日志为 `.codex/vue-soft-replan/java-full-final.log`。

## 未覆盖与限制

未启动真实数据库、模型调用、上传、部署或其他外部运行环境；这些操作未获本次授权。SecureDirectoryStream 的进程崩溃故障注入未单独执行，但当前实现采用同目录 rename 直接替换并保留旧目标，相关正常覆盖、符号链接和临时文件测试已通过。

## 结论

必需需求、任务、聚焦真实验证和独立代码评审均已满足；全量 Maven 的失败属于既有共享基线配置契约，不构成本变更阻塞。结论：`Ready for Archive`。本报告不执行归档、提交或推送。
