# 独立规划评审

- 变更：`guard-stalled-build-progress`
- 风险等级：`strict`
- 结论：`PASS`
- 评审身份：独立执行上下文 `/root/planning_review`；未撰写本变更规划、正式规格或业务代码，未递归分派。
- 范围：当前 proposal、design、tasks、两个增量规格、元数据及对应正式规格；结合真实工具作用域、构建门禁、请求控制器、响应处理器、回合上下文及终态预算代码进行可实施性核对。
- 验证边界：本轮仅静态规划评审，没有启动服务、调用模型、修改历史计划或运行实现验收。PASS 表示规划可进入获准实施，不等于该功能已经通过测试。

## 阻塞项

无。当前规格与设计已定义需要保护的两类构建拒绝、真实进展、纠偏送达和唯一终态，任务包含相应生命周期与兼容验证。

## 核对结论

1. **诊断不依赖文案。** 当前 `BuildProjectTool` 确有 `replanPending` 提前拒绝和 `message.startsWith("计划依赖尚未完成")` 分支。设计 Decisions 1、5 将这些拒绝纳入内部类型化诊断，保留完整阻塞集合、有限展示摘要和非目标身份拒绝的排除，能防止只覆盖 `beforeBuild` 而遗漏提前返回。
2. **观察数据与单次工具调用绑定。** Decisions 2 使用词法作用域捕获实际诊断，生成不可变观察数据并经 `GuardedToolExecution` 返回，不通过可被另一调用覆盖的共享 `lastResult`。保留旧构造和直接工具执行路径，任务 2.1 验证非 Vue/评测及协议字段不变。
3. **提交成功是观察前提。** 当前响应处理器先写工具结果到 memory，再由 `StreamingRequestController.commitToolResult` 确认并发布。Decisions 3、7 及任务 2.2 把新观察安排在可靠提交之后、下一模型请求之前，并限定当前代次及有效回合；持久化失败、取消及重复结果不推进。停滞时沿既有批次受控终止路径补齐受控跳过记录，禁止剩余真实工具执行。
4. **两类进展规则明确。** 计划以完整规范化阻塞集合的真子集或清空判定，无计划到合法非空计划为明确例外；替换条目、版本变化和读取不能逃逸计数。代码失败沿用 `VueBuildSessionManager` 的实际 mutationRevision 与 requiresMutation 条件，没有新增真实构建计数。实际构建放行重置旧阶段，依赖/基础设施正常重试不纳入本保护。
5. **fail-open 不冒充模型进展。** Decisions 4 明确仅构建拒绝观察到的集合变化不清零，成功工具相邻的服务端待修订哨兵消失也不能被计为进展。任务 1.2 对该规则安排单测；成功工具后的观察数据必须保留足够来源信息实施这一要求。
6. **送达不是领取。** Decisions 5、6 使用非破坏性反馈票据，普通续行、协议恢复、未完成工具链恢复均带入临时消息；在 SDK 启动 Runnable 真正运行时绑定目标代次，并检查实际请求消息含票据。当前合法响应回调才确认送达，压缩/准备/领取/onError 均不算；同步有效回调可确认，无回调抛错不可确认。进展、旧代次及回合关闭使票据失效，任务 2.3 覆盖这些时序。
7. **终态保持真实语义。** `BUILD_STALLED` 是内部受控终止原因，标准构建结果仍是拒绝。映射既有 FAILED 且不刷新预览，不假造三次真实构建失败或协议错误；冻结诊断供稳定正文与记忆一致使用，并进入原有取消/超时/删除唯一终态竞争。
8. **任务与约束可交接。** 九项必需任务按诊断/回合状态、作用域与提交、反馈请求、终态、联合验证排序，无循环验收 checkbox。保留真实构建三次上限、不写旧真实计划、不新增对外协议、不自动重启推送归档的限制。风险涉及请求和终态生命周期，`strict` 合理。

## 实施提醒

以下为现规划要求在当前代码中的具体落点，不新增需求或实施范围：

- `AiServiceStreamingResponseHandler.commitToolResult` 可能返回 `REJECTED` 或 `CANCELLED`，而现有结果观察代码并非为新保护器设计。新观察入口必须检查实际提交 decision、当前代次和回合门，不能仅以“没有 persistenceFailed”认定可观察；发布异常也不得造成失败结果被当成新进展。
- 现有 `prepareRecoveryRequest`、`prepareIncompleteRecoveryRequest` 与 `submitNextModelRequest` 的临时消息合并方式不同。三条路径必须共用本次票据规则；不能只给普通续行加消息。有效回调确认须在实际合法性检查和代次门通过后发生，不能仅因 SDK 调用开始就标送达。
- `VueTurnFinalizer.maxTerminalMessageCodePoints` 与 reserve 当前从固定终态文案求最大值。1200 码点动态停滞摘要必须显式影响最大值与预留量校验，并验证高码点字符、超长路径及临界预算；只截短正文不足以落实任务 3.1。
- 成功工具与 fail-open 相邻时必须有用例证明不能通过无关成功变更清零。实际文件确实消除了其他底层阻塞则按真子集规则处理，不能反过来把 fail-open 状态永久当成无法解除的阻塞。
- 测试应以受控 SDK 同步/异步回调和真实临时文件确认行为；模型理解提示、实际 npm 构建和真实服务端到端效果不属于本次模拟测试可以证明的结论。

## 当前规划基线

SHA-256 为本执行上下文读取的当前文件，不替代主评审机器记录。

| 文件 | SHA-256 |
| --- | --- |
| `openspec/changes/guard-stalled-build-progress/.openspec.yaml` | `f7bf7a430509a881176fee4aa3de54366db7c5e7825469918cc3b0179b3b4bdd` |
| `openspec/changes/guard-stalled-build-progress/proposal.md` | `1fe53fcfbf0e895d8c7844218cbb0e01aaa095c10d3c6e0af097d47ed185727c` |
| `openspec/changes/guard-stalled-build-progress/design.md` | `f083685fd739ab88dfb82c983aac894bbdefd3d9fa3dfecb9e82dbee19412857` |
| `openspec/changes/guard-stalled-build-progress/tasks.md` | `673b75e268bfdbdaf7f5881778ffcca3afd9ac6afbf3ec32c19755c0eda3b065` |
| `openspec/changes/guard-stalled-build-progress/specs/vue-soft-replan/spec.md` | `ee9b4e4c26967163eb1eba41baef2715581316178c027d915b1cb4c6064aaa9e` |
| `openspec/changes/guard-stalled-build-progress/specs/vue-build-progress-guard/spec.md` | `6444e42a324f04b7f88be43a68733cd72943854ca11f36817775053ae3f87673` |
| `openspec/specs/vue-soft-replan/spec.md` | `a4c6c55c0c7932158b1d6247b2d94d212f83a854351baf11e91bce185d7c91a5` |
| `openspec/specs/vue-build-progress-guard/spec.md` | `598ff9649d69d75800390515f2fbb545e93eb113228ccc4871469e0563999d2f` |

## 交接

主评审可聚合当前结论后登记并检查规划基线。当前正式规格与工程规则作为持续约束；需修改的业务、协议内部类型和测试作为 reference，不能把参考源码冻结成不可改约束。获准实施仍须 start-apply；实现完成后进行实际测试、独立代码评审及 verify。本执行上下文没有调用 record-plan，没有修改业务或规划正文。
