# 独立代码评审

- 变更：`guard-stalled-build-progress`
- 风险等级：`strict`
- 代码评审结论：`PASS`。本轮发现的两项问题已修复，当前受评代码未发现剩余阻塞缺陷；测试样本缺失已解除，最终后端与前端回归通过。
- 评审者：独立执行上下文 `/root/planning_review`，未编写受评业务源码、测试或规划；直接读取当前差异、新增文件与执行日志复核。未递归分派，未修改业务或规划。
- 范围：诊断、保护器、词法观察、结果提交、三条请求路径、反馈送达、批次停止、唯一终态、预算、记忆和相关测试；不扩展为模型语义理解或新的外部协议。

## 发现与处置

1. **已修复：在未确认合法响应前把纠偏标为送达。** 初版在原始 partial/complete 回调及仅 name 非空的工具片段到达时提前确认，伪工具正文可能随后进入协议恢复，但 pendingFeedback 已消失。当前 `AiServiceStreamingResponseHandler` 在可信正文实际释放、合法普通完成通过恢复检查后，以及工具规范化/注册/执行认领通过后才确认；新增“携纠偏的响应若为伪工具正文恢复仍须携带未送达票据”用例证明恢复请求仍含票据。证据：`logs/16-feedback-validity.log`，70 项通过。
2. **已修复：停滞终态绕过统一信号披露队列。** 初版 `shouldDispatchAfterSignals` 只包含构建成功/失败，可能先关闭 Facade 流再披露最后拒绝。当前将 BUILD_STALLED 纳入相同发布边界。新增“统一发布队列延迟时最后拒绝和跳过记录必须先于终态”使用真实 GenerationSignalPublisher/DisclosureBuffer 暂停披露，确认 memory 已提交而信号与终态仍等待；恢复后拒绝和批次跳过信号均先于终态。证据：`logs/17-publication-boundary.log`，85 项通过。

未通过放宽测试断言或关闭既有保护掩盖上述问题。最终代码仍限制回调代次和工具身份，停滞结果保持普通 REJECTED，批次剩余工具只记录受控跳过，不执行真实变更。

## 关键边界复核

- BuildBlockDiagnostic 同时记录待修订及底层文件/依赖阻塞，比较使用排序去重后的完整条目，文本另行限制数量和码点；正常构建门禁与真实三次构建上限未被替换。
- 观察数据由调用级 ScopedValue 捕获，并在成功工具状态写回后取得；没有共享 lastResult，也没有增加工具 JSON 字段。词法作用域结束自动解绑，取消先赢时不生成可推进观察。
- 响应处理器只在工具结果可靠持久化、commit decision 为 PROVIDED 且发布未失败时观察；控制器再核对当前有效代次。按代次和工具 ID 去重，持久化失败、旧回调和取消不会计数。
- 计划进展同时要求本次成功操作前后严格减少及相对最近拒绝集合严格减少，替换文件/状态、纯版本变化及无关成功变更不能清零；代码失败沿用 mutationRevision。fail-open 转换移除哨兵不单独算进展，后续无关工具也不能借此清零。
- 第二次拒绝安排非破坏性票据；三条请求路径均合并临时消息，SDK Runnable 实际执行且实际消息含票据后绑定代次，合法响应才确认。同步回调、启动无回调抛错、异步准备、恢复及旧票据失效均有针对性覆盖。
- BUILD_STALLED 经现有 FAILED/refreshPreview=false 通道收口；冻结诊断和真实工具事实进入记忆，不假造真实构建次数。回合资源关闭清理保护器；取消/超时/删除仍沿既有最终化竞争。
- 动态终态最大 1200 码点已纳入 VueTurnFinalizer 最大值和 reserve 计算。日志先保存不可变快照，再于控制器 monitor 之外计算 SHA-256 和输出，避免日志 I/O 占用取消锁。

## 验证证据与限制

下列日志位于 `.codex/guard-stalled-build-progress/`。本评审读取真实日志及当前测试代码，没有把主代理摘要当作唯一证据，也未重复执行已覆盖的测试。

| 证据 | 实际结论 |
| --- | --- |
| `logs/14-lifecycle.log` | 128 项通过，0 失败/错误/跳过 |
| `logs/15-frontend.log` | 4 文件、182 项通过，含 FAILED 工作区与不刷新预览回归 |
| `logs/16-feedback-validity.log` | 70 项通过，含违规正文不提前确认 |
| `logs/17-publication-boundary.log` | 85 项通过，含统一队列延迟披露 |
| `logs/18-full-regression.log` | 529 项、0 failures、1 error；BuildProjectToolTest.javaFactoriesMatchSharedFrontendGoldenCases 缺少相对路径 `ai-app-generation-frontend/src/test-fixtures/vue-build-tool-v1-cases.json`，不能称完整回归通过 |
| `logs/19-full-green.log` | 隔离目录补齐原共享样本后重新验证，529 项、0 失败/错误/跳过，BUILD SUCCESS |
| `logs/20-frontend-all.log` | 前端完整测试 21 文件、282 项通过 |
| 当前 Surefire BuildProgressStreamingTest XML | `user.dir` 和 `java.io.tmpdir` 均位于本变更 `.codex` 隔离目录 |
| `plans-before.json` 与当前文件 | 本评审独立只读计算清单内 8 份真实计划当前 SHA-256，全部一致 |

完整回归的样本路径错误已通过向隔离目录复制既有样本解决，没有改回真实生成目录或改写业务源码。本评审读取 19、20 号最终日志，并重新核对下表九个核心文件哈希均未变化；原样本和隔离副本的 SHA-256 同为 `8f90ce11ed1ffdc875157f690224a5ad68072e662292942c7b932d7d0297c1c7`。原失败记录保留以说明处置，当前已无该项验收证据缺口。`git diff --check` 通过。未执行真实模型、数据库、部署、运行服务重启或真实 npm 工程构建，不宣称已证明模型理解提示或所有循环都已消除。

## 核心实现基线

SHA-256 对应本轮最终静态复核内容；其余受评源码和测试由主流程的完整实现指纹绑定。

| 文件 | SHA-256 |
| --- | --- |
| `src/main/java/com/lyw/appgeneration/ai/plan/BuildBlockDiagnostic.java` | `5c59a6a06285a52c9c14a8484c328080a3751ed9cab81c8e0f8f17bcbee7d56b` |
| `src/main/java/dev/langchain4j/service/BuildProgressGuard.java` | `6c11b53d82a66a8bf3908b23325c197a37f2fd52f19128447f79bbe0d079a652` |
| `src/main/java/com/lyw/appgeneration/ai/tools/FileToolExecutionScopeManager.java` | `732886c3e20ee870ab991c4a3cf5c802bca483fa53f8b7c9ae7dfed7c2d3182a` |
| `src/main/java/dev/langchain4j/service/StreamingRequestController.java` | `26e7bd93931b7c58a64476d786cfc779961dacfb30835944904b0e8732a35c59` |
| `src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java` | `263dee676692f87968886d29d09da87498a7e18a51e967f203f9e66bbb0e98a4` |
| `src/main/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandler.java` | `eb4a6914aebb7f1e82c28988757747dbb7d50eb058911308b41730341f8d032c` |
| `src/main/java/com/lyw/appgeneration/core/handler/VueTurnFinalizer.java` | `518545721f0a1e9ab62c9a205268ec5c672425fe59d211bebc8de029502364e4` |
| `src/main/java/com/lyw/appgeneration/core/handler/VueTurnMemoryProjection.java` | `9d763dbb7209b576543ccd9f52a6ba112cbf16d8d4f8386d74ec10524c109d17` |
| `src/test/java/dev/langchain4j/service/BuildProgressStreamingTest.java` | `784f19ffa6b57f9f3f7d725e9675fa664e377b9e81e9db67fdbd1d7bcbd91e0a` |

## 交接

代码独立评审通过，18 号回归的样本问题已由 19 号完整后端通过结果闭合，可交给 verify 核对当前任务及实现指纹。本报告不授权自动重启、推送、归档或修改历史计划，也不替代 verify 的完整任务与证据核对。
