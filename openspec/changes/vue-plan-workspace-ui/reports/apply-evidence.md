# 实施证据

- 分支：master；实施入口：`start-apply --profile strict` 于 2026-09-21T05:12:55Z 通过。
- 范围：只按任务实施；无数据库、模型、上传或部署调用。测试临时目录统一使用项目 `.codex/vue-plan-workspace-ui/tmp`，测试替身仅用于隔离依赖，不宣称真实模型验收。
- 当前代码状态：已登记评审后的未提交实现，最终代码快照与完整需求映射在 verify 阶段绑定。

## 已完成任务

| 任务 | 实现及验证 | 原始证据 |
| --- | --- | --- |
| 1.1 | `PlanStoragePathResolver.withExistingProjectDirectory` 与 `SecureFileAccess`，真实目录句柄读取；`PlanStoragePathResolverReadOnlyTest` 六项通过，无跳过 | `.codex/vue-plan-workspace-ui/logs/01-readonly-green.log`，JUnit 同名报告 |

## 命令记录

- 最终评审补充修复：计划工具卡片保留该次结果中的版本、摘要和文件清单；文件工具状态校验继续限定原六种状态，`CONFLICT` 仅允许计划工具。最终全套前端日志 `logs/32-frontend-protocol-final.log` 为 21 文件 / 281 项通过；最终 `npm run build` 退出码 0，日志 `logs/33-build-protocol-final.log`。包含构建执行记录的最终桌面/手机浏览器验收见 `logs/34-browser-build-final.log`。这些记录覆盖前述同范围旧证据，未重复调用真实模型。

- 任务 4.3、5.2、5.3、6.1、6.3、6.4：已接入原页面，复用消息 DOM，生成时隐藏 iframe；最终计划查询与工具记录独立于已清理会话。桌面 1440×900、手机 390×844 的实际 Vue 页面受控浏览器流程通过，命令 `node .codex/vue-plan-workspace-ui/browser.cjs`，退出码 0，最终日志 `logs/31-browser-final.log`，截图 `browser/desktop-replan.png`、`browser/mobile-replan.png`、`browser/desktop-success.png`、`browser/mobile-success.png`。路径换行、文本转义、计划外显示、修订回退、展开状态保留、单次成功刷新、只读/失败不刷新、刷新不伪造会话、账号切换清空均通过；真实模型调用计数为 0。
- 任务 6.4 边界及 7.4 降级：`node .codex/vue-plan-workspace-ui/browser-edge.cjs`，退出码 0，日志 `logs/26-browser-review-regression.log`。九个场景覆盖已加载 iframe 保留、页面内导航离开/返回、隐藏 iframe 消息拒绝、资源 error、真实 JS 404、HTTP 404 空体、15 秒加载超时、计划接口 404 与首次失败无上一版按钮；所有浏览器上下文无 pageerror。同源只通过 HEAD 确认文档与已有资源失败，跨源明确无法确认资源完整，不声称历史恢复。
- 独立评审发现的三项前端问题已修复：初始资源失败漏判、无上一版时仍可点击、计划 40100 导致全页跳转。`logs/27-browser-auth-regression.log` 明确断言 40100 不导航且保留停止按钮。计划请求仅通过专用 Axios 配置跳过登录跳转，其他接口维持既有行为，由 `planLoginBehavior.test.ts` 验证。
- 任务 7.1 最终后端回归：按任务全套并加 DTO 测试执行，退出码 0，127 项 / 0 failures / 0 errors / 0 skipped；日志 `logs/17-backend-final.log`。新增 MVC 编码 URI GET/HEAD 及编码 deployKey 正向案例，关闭后端评审可选建议。
- 任务 7.2 最终前端回归：前端目录 `npm run test`，退出码 0，21 文件 / 280 项通过，日志 `logs/29-frontend-final.log`；`npm run build`，退出码 0，类型检查和构建通过，日志 `logs/30-build-final.log`。仅保留产物体积告警。
- 任务 7.4 差异检查：`git diff --check` 退出码 0；后端只新增查询及静态保护，P/E 判定、文件协议、构建存储和数据库未修改。计划接口缺失/读取错误时原生成流可继续，静态旁路保护独立于前端发布与回退。

- 任务 3.2、3.3、4.1、4.2、5.1：`planObservation`、`planPresentation`、`useGenerationPlan` 聚焦测试 15 项通过，退出码 0，日志 `logs/10-plan-sync-green.log`。初次观察测试使用 Node 环境不存在的 window 相对地址导致失败，已改为完整测试 URL，未放宽协议；保留 `logs/10-plan-sync.log`。验证串行合并、同版本与进度回退、三种上下文切换、权限丢失、无自动轮询、终态查询不依赖已清理会话。
- 任务 6.2：`useGenerationPreview.test.ts` 验证成功且正常收口才刷新一次、只读不刷、失败/取消/超时/异常保持工作区以及旧加载身份拒绝；前端全套 `npm run test` 退出码 0，20 文件 / 278 项通过，日志 `logs/12-frontend-suite.log`。页面和 iframe 浏览器验收仍待完成。
- 类型检查与首次生产构建 `npm run build` 退出码 0，日志 `logs/11-frontend-build.log`，保留打包体积告警，未增加第三方依赖。最终页面调整后另行运行构建。
- 后端交付单元已提交：`b1cfff3`，中文提交，未推送。浏览器本地前端启动已获用户明确许可；使用 5174 端口避免占用已有 5173 服务。

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

25 项实施任务已有相应证据，等待最终独立代码报告与 verify 指纹核验。未启动真实后端或依赖服务；浏览器为真实页面加受控 HTTP/SSE，不等同真实模型、数据库或部署端到端验证。日志 16 首次浏览器失败为带图标按钮的精确名称定位错误，修正定位后通过；其余历史失败处置见上文。
