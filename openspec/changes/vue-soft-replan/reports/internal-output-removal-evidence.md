# 内部输出安全检测删除实施记录

- 用户授权：2026-09-21 确认物理删除，保留 P/E、统一工具流及其他行为；本次“实施计划”。
- 风险等级：strict。
- 代码基线：HEAD `e9b6a0d` 与当前未提交差异，未提交或推送。
- 状态：物理删除与本地回归已完成；不登记 Ready for Archive。旧验收指纹不能覆盖本次追加范围。

## 删除与保留

删除五个框架专用组件 InternalOutputLeakDetector、InternalOutputRecoveryPolicy、InternalOutputRecoveryCoordinator、InternalOutputProtocolException、ToolArgumentLeakScanner，以及两个专用消息类。删除 TokenStream 策略 API、扫描/参数对比状态、专属恢复请求、OutputSafetySeal、普通/工具/最终收尾中的标记拦截、专用 SSE 与前端状态。专属单测删除，InternalOutputMessageTest 中正常消息校验迁移为 GenerationMessageTest。

保留四类 GenerationStreamSignal、发布器共享队列、串行回调、工具结果/构建终态顺序及通用 PROTOCOL_ERROR 失败收口。保留 P/E 状态与反馈、makePlan/updatePlan/buildProject、原文件路径/白名单/预算/租约、取消静默、删除栅栏、重复读取、工具协议恢复和未完成工具链恢复。合成记忆标记和完整消息分类不删除，仅删除已无生产调用者的 containsReservedMarker 扫描方法。

P/E 核心、计划工具及另外两套恢复策略相对 HEAD 无差异。源码删除清单和具体代码差异：`.codex/vue-soft-replan/archive-readiness/removal-code.patch`，SHA-256 `065e5c946a593b555b6956b813aed07904210d895ca418f88dc60af0dfefcf0a`。新消息测试文件由本记录及工作区源码补充，未跟踪文件不包含在 git diff 中。

## 行为与验证映射

| 行为 | 测试与证据 |
| --- | --- |
| partial/complete 不同仍使用最终工具参数，包含标记正常执行，保留 JSON 归一化 | AiServiceStreamingResponseHandlerTest.参数分片与完整回调不同也按最终响应执行一次，三组输入 |
| 统一工具信号顺序、监听器失败熔断、终态排序 | AiServiceTokenStreamTest、AiServiceStreamingResponseHandlerTest、GenerationSignalPublisherTest、GenerationDisclosureBufferTest、GenerationCallbackSequencerTest |
| 普通标记正文按成功回答保存；通用协议错误不得保存 | SimpleTextStreamHandlerTest、AiCodeGeneratorFacadeTest |
| 取消/超时/删除静默与唯一持久化 | VueTurnCancellationCoordinatorTest、VueTurnFinalizerTest、JsonMessageStreamHandlerTest、AppServiceImplVueTurnTest |
| P/E 计划、偏差、修订、构建闸门不变 | ReplanContextTest、StreamingRequestControllerReplanTest、ReplanDetectorTest、PlanToolTest、BuildProjectPlanGateTest、AppPlanStateManagerTest |
| 前端正文/工具参数增量/展示、其他恢复和终态 | generationSession.test.ts、AppChatPageGeneratingStatus.test.ts 及前端全套测试 |

## 实际执行

命令工作目录为项目根，前端命令工作目录为 `ai-app-generation-frontend`。本轮使用本地模拟和单元测试，没有主动发起真实模型生成或上传。

- `bash mvnw -q -DskipTests compile`：退出码 0，日志 `removal-compile.log`。
- `bash mvnw -q '-Dtest=AiServiceTokenStreamTest,AiServiceStreamingResponseHandlerTest,Generation*Test,JsonMessageStreamHandlerTest,SimpleTextStreamHandlerTest,VueTurn*Test,AiCodeGeneratorFacadeTest,AppServiceImplVueTurnTest,StreamingRequestController*Test,Replan*Test,Plan*Test,AppPlan*Test,BuildProjectPlanGateTest,VueToolExecutionFactTest,SyntheticMemoryMessageProtocolTest' test`：最终退出码 0，日志 `removal-focused.log`。
- `bash mvnw -q clean test`：退出码 1；2022 项，4 失败、0 错误、22 跳过。四项失败均为旧报告已记录的 InfrastructureCredentialConfigTest（三项）及 ProductionRagDeploymentConfigTest（一项）配置契约，相关实现和配置本次未修改。日志 `removal-clean-full.log`，JUnit 快照 `clean-surefire/`。
- `npm test`：退出码 0，14 个测试文件、244 项通过；日志 `removal-frontend-test.log`。
- `npm run type-check`、`npm run build`：退出码 0；日志 `removal-typecheck.log`、`removal-frontend-build.log`。构建保留 chunk 大小警告。
- `git diff --check`、`openspec validate vue-soft-replan --strict --no-interactive`：通过。
- 2026-09-21 10:06 +08:00 前后端重启，后端新进程 17854；`curl --max-time 3 http://127.0.0.1:9025/api/actuator/health` 与前端 5173 均返回 HTTP 200。运行日志为同目录 `backend-runtime.log` 和 `frontend-runtime.log`，不涉及中间件重启。

以上日志均位于 `.codex/vue-soft-replan/archive-readiness/`。干净构建后，产物中无已删除的专属检测类。

## 限制与后续

旧删除并发测试缺少“删除已接管”的同步条件，现增加可观测屏障并通过全量复核；没有据此宣称修复生产租约潜在竞态。该生产关闭/接管窗口继续作为单独风险记录，本次不扩大修改。

独立代码评审见 `reviews/internal-output-removal-review.md`。追加规划已经更新，但旧 planning-review/verification JSON 仍对应历史输入，没有补造实施开始记录或重写旧 PASS；归档前仍须形成追加范围的有效规划评审、当前验收映射及指纹。

## 用户前端实测补充

2026-09-21 10:14 至 10:22，用户从前端连续发起三轮真实生成，本执行上下文前台监听后端日志；未创建定时任务，未代替用户发起付费调用。应用为 `459465893899010048`，证据为上述 `backend-runtime.log` 及对应项目产物。

- 首次生成，turnId `9b1dd2b7-4565-4b71-9f0c-3b42aaff943b`：两次 readSkill、一次 makePlan、21 次 writeFile、一次 buildProject；计划 version=1、status=BUILT，dist/index.html 已生成。
- 增加联系我们，turnId `f2d3868f-cbd5-443f-94cc-da4c91daf282`：调用 updatePlan，新增 ContactSection.vue 并修改首页，计划 version=2、status=BUILT。该证据证明计划修订可用，不单独证明自动偏差 Replan 被触发。
- 显示内部标记，turnId `e3d267dd-86cc-4e45-89a7-478a94f93d87`：readFile、modifyFile、buildProject 完成；AppFooter.vue 与 dist/assets/index-a7014944.js 均包含字面文本 `[[server.synthetic-memory/test]]`，计划保持 BUILT。

三轮日志未出现 StreamingResponseConsistencyException 或 Vue 回合流处理异常。本次核对的是后端链路与构建产物，未通过浏览器截图单独验证视觉效果。实测不替代当前 OpenSpec 归档指纹核验。
