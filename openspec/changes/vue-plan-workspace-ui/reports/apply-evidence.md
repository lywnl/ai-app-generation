# 实施证据

- 分支：master；实施入口：`start-apply --profile strict` 于 2026-09-21T05:12:55Z 通过。
- 范围：只按任务实施；无数据库、模型、上传或部署调用。测试临时目录统一使用项目 `.codex/vue-plan-workspace-ui/tmp`，测试替身仅用于隔离依赖，不宣称真实模型验收。
- 当前代码状态：已登记评审后的未提交实现，最终代码快照与完整需求映射在 verify 阶段绑定。

## 已完成任务

| 任务 | 实现及验证 | 原始证据 |
| --- | --- | --- |
| 1.1 | `PlanStoragePathResolver.withExistingProjectDirectory` 与 `SecureFileAccess`，真实目录句柄读取；`PlanStoragePathResolverReadOnlyTest` 六项通过，无跳过 | `.codex/vue-plan-workspace-ui/logs/01-readonly-green.log`，JUnit 同名报告 |

## 命令记录

- 任务 2.5、2.6、7.1：新增静态解析器、延迟安全 Resource 和 HEAD 元数据分支；解析器及真实 MVC/消息转换器共 8 项通过。`logs/07-static.log` 记录 HEAD 首次仍写响应体的失败，修正后 `logs/07-static-green.log` 通过。随后按 tasks 7.1 全部指定类并增加 `AppPlanViewTest` 运行后端聚焦回归，退出码 0，原始日志 `logs/08-backend-regression.log`；计划损坏和删除缓存失败的 ERROR 日志是故障注入用例，不是未处置测试失败。
- 任务 3.1：受限计划解析与 Axios 查询封装完成；在前端目录执行 `npm run test -- src/utils/planSnapshot.test.ts src/api/appPlan.test.ts src/utils/generationSession.test.ts src/utils/toolOperationDisplay.test.ts`，退出码 0，157 项通过。新增 DTO/接口 12 项，验证路径/依赖/枚举、空态、长整数 ID、取消和超时配置。日志 `logs/09-frontend-foundation.log`。

- 任务 2.4：`AppPlanQueryLifecycleTest` 的真实文件读取、删除等待与生成并行查询两项通过，退出码 0；命令采用同一 JVM 临时目录参数，`-Dtest=AppPlanQueryLifecycleTest`，日志 `logs/06-plan-lifecycle-green.log`。首次测试 Callable 推断编译失败保留于 `logs/06-plan-lifecycle.log`，已修正。数据库仍为替身，没有真实删除业务应用。

- 任务 2.3：Controller GET 路由、no-store、BaseResponse 接入完成。`bash mvnw -q -DargLine="-Djava.io.tmpdir=$PWD/.codex/vue-plan-workspace-ui/tmp" -Dtest=AppControllerPlanTest test` 退出码 0，4 项通过；通过 MVC 消息转换器和真实服务/计划文件验证响应，登录和应用数据库依赖使用替身。日志 `logs/05-plan-controller-green.log`；初次测试构造器参数编译错误记录于 `logs/05-plan-controller.log`，已修正。

- 任务 2.2：`AppPlanQueryServiceImpl` 权限、许可与错误边界完成；`bash mvnw -q -DargLine="-Djava.io.tmpdir=$PWD/.codex/vue-plan-workspace-ui/tmp" -Dtest=AppPlanQueryServiceTest test` 退出码 0，5 项通过，无跳过；日志 `logs/04-plan-service-green.log`。仅应用数据读取为数据库替身，计划文件和生命周期为真实实现；此前 final 类 mock 不受当前 Mockito 配置支持的测试错误保留于 `logs/04-plan-service.log`，已替换为真实计划管理器和禁止读取断言。

- 任务 2.1：`AppPlanVO` 与 `AppPlanViewTest` 完成，执行同一测试 JVM 临时目录参数的 `bash mvnw -q -Dtest=AppPlanViewTest test`，退出码 0，2 项通过。验证受限字段、修订截断/顺序、不可变集合和不伪造原因；日志 `.codex/vue-plan-workspace-ui/logs/03-plan-view.log`。

- 任务 1.2：`AppPlanStateManager.loadReadOnly` 已完成；执行 `bash mvnw -q -DargLine="-Djava.io.tmpdir=$PWD/.codex/vue-plan-workspace-ui/tmp" -Dtest=AppPlanReadOnlyTest,PlanStoragePathResolverReadOnlyTest test`，退出码 0，10 项通过无跳过。新测试验证真实落盘、并发版本替换、同版本进度及读取无写入；日志 `.codex/vue-plan-workspace-ui/logs/02-plan-read.log`。

- 2026-09-21T05:17Z，工作目录为项目根：`bash mvnw -q -DargLine="-Djava.io.tmpdir=$PWD/.codex/vue-plan-workspace-ui/tmp" -Dtest=PlanStoragePathResolverReadOnlyTest test`，退出码 0，6 tests / 0 failures / 0 errors / 0 skipped。
- 初次测试声明新 API 尚未实现，编译退出码 1：`logs/01-readonly-red.log`。实现后的首次运行未将临时目录传入 fork JVM，macOS `/var` 链接被安全目录策略拒绝，退出码 1：`logs/01-readonly.log`；更正测试 JVM 参数后通过，未放宽链接防护。

## 待完成

其他任务按 tasks.md 推进；当前结果不等于完整验收，尚未启动业务服务或浏览器验收。
