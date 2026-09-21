# 当前归档条件复核

- 时间：2026-09-21 09:23 +08:00。
- 风险等级：strict；核验方式：machine。
- 结论：Needs Work。旧 verification.md 的 Ready for Archive 不适用于当前工作区；本次不重写旧通过指纹，也不归档。

## 基线检查

在项目根执行 `openspec validate vue-soft-replan --strict --no-interactive`，退出码 0；任务 32/32 已勾选。
执行 `python3 .agents/shared/openspec/scripts/evidence.py check-plan --root . --change openspec/changes/vue-soft-replan --profile strict`，退出码 0。
同参数执行 `check-verify`，退出码 1：MakePlanTool、PlanToolProtocolSupport、AiCodeGeneratorFacade、AiServiceTokenStream 及两个相关测试已偏离已登记验收基线。

## 已确认缺口

1. 用户已确认物理删除内部输出检测与恢复，但当前仅取消门面策略安装并移除一处保存拦截。检测器、扫描器、协调器、策略 API 和专用消息仍存在。SimpleTextStreamHandler 的标记拦截、JsonMessageStreamHandler 的输出封口及 VueTurnFinalizer.enforceOutputSafety 仍在运行。需完成已授权删除，并保留合成记忆协议本身和其他文件/工具保护。
2. 统一工具流的事件类型仍在，但执行机制未保持不变：AiServiceTokenStream.start 仅在安装安全策略时包装 GenerationSignalPublisher；AiServiceStreamingResponseHandler.submitProviderCallback 在无策略时直接运行，绕过回调串行队列。应将统一信号队列、串行回调及工具结果先于终态的顺序与安全策略解耦，补充无安全策略场景的验证。关联原任务 3.3、5.3、6.1、6.4。
3. strict 独立代码评审尚未覆盖当前热修复和回退修改，不能复用旧 PASS 作为当前实现结论。

## 本次测试

工作目录均为项目根；使用当前工作区，未修改业务代码，未发起真实模型生成。

- `bash mvnw -q '-Dtest=JsonMessageStreamHandlerTest#客户端取消先赢时删除必须等待后台共享收尾且不得重复持久化' -Dsurefire.reportsDirectory=.codex/vue-soft-replan/archive-readiness/surefire test`：退出码 0，1 项通过。该 reportsDirectory 参数未改变实际报告目录，结果在 target/surefire-reports 核对；日志：`.codex/vue-soft-replan/archive-readiness/cancel-delete.log`。
- `bash mvnw -q -Dtest=JsonMessageStreamHandlerTest test`：退出码 0，43 项通过、无跳过。日志：`.codex/vue-soft-replan/archive-readiness/handler-class.log`。

旧全量测试的删除并发错误本次未复现，不能据此断言已修复或属于环境问题。测试在提交删除任务后立即释放收尾屏障，未等待删除进入接管；VueTurnContext.closeResources 先注销参与者再关闭租约，而 beginDeleteTakeover 在参与者缺失时抛异常，存在需要进一步验证的竞态窗口。

## 后续条件

完成已授权物理删除并修复统一工具流解耦，核实并发失败路径，执行受影响后端和前端测试，取得当前实现的独立评审，更新验收映射与基线，通过 check-verify 后方可归档。原 P/E 任务全勾选不能替代这些当前验证。

## 2026-09-21 10:06 后续实施更新

以上代码缺口已在本次实施中处理：专用组件及收尾检测已物理删除，共享信号发布与串行回调独立运行。删除并发用例已增加接管信号屏障，生产租约潜在窗口未在本次扩改。相关测试及独立评审已完成，前后端已重启且健康检查通过，详见 `internal-output-removal-evidence.md` 和 `../reviews/internal-output-removal-review.md`。

当前归档状态仍为 Needs Work：追加规划和代码已经变化，旧规划评审/验收指纹不能复用，尚未重建当前归档基线。10:14 至 10:22 用户前端三轮真实生成已完成，首次生成、计划修订和标记文本构建均通过，详见实施记录的“用户前端实测补充”。未执行归档或推送。

## 2026-09-21 当前基线收口完成

当前结论更新为 Ready for Archive。前述 Needs Work 保留为历史检查结果。

- 代码基线为 8b0e889，无业务代码修改。
- 当前规划复审、当前提交独立代码复核均 PASS，见 reviews/change-review.md、reviews/independent-planning-review-current.md、reviews/independent-code-review-current.md（相对变更根）。
- 9.5 真实重开后完成提交与证据对应、新增测试补核、动态日志冻结及映射补齐，37/37 项任务完成；未补造历史实施开始时间。
- verification-manifest.json 覆盖 10 条规格需求、39 个场景及全部任务，结构校验通过。
- record-verify 已绑定当前代码、测试、构建配置、原始日志、JUnit、实测快照及独立评审；check-plan、check-verify 均返回 valid=true。
- 四项已知基础设施配置失败、22 项跳过、真实模型自动偏差触发未单独实测，以及历史流程偏差均保留在 verification.md 中。

本轮仅更新评审和验收文档，未归档、提交、推送或发布。后续归档必须使用当前验收记录，并在写入和移动前执行归档检查点校验。
