# 独立规划评审

- 变更：`fix-plan-dependency-reset`
- 风险等级：`strict`
- 结论：`PASS`
- 独立评审身份：子代理执行上下文 `/root/planning_review`；未编写本变更 proposal、design、specs、tasks 或正式规格修订，未修改业务代码。
- 范围：仅直接状态重置、间接依赖检查提示、对应协议兼容及测试，不处理循环收口、旧真实计划、前端功能或环境启停。
- 方法：读取当前规划、正式 `vue-soft-replan` 规格及状态管理器、计划工具、结果协议、展示链路与相关单测进行静态复核。本报告不代替实施测试或独立代码评审。

## 阻塞项

无。当前范围、状态语义、提示输出和验证安排足以支撑本次小修复。

## 关键核对

1. **根因与改动一致。** 当前 `AppPlanStateManager.resetAffectedStates` 在反向 BFS 后把种子及所有依赖者都重置，旧单测明确断言三节点均为 `PENDING`。设计 Decisions 1、2 将直接状态处理与影响分析分离，未修订条目原样继承，能够避免仅因上游修订而要求入口重复写入；没有把已有 `PENDING` 自动升级为完成。
2. **影响提示不会丢掉跨显式节点的传递路径。** 设计以已校验的新图遍历，仍穿过本次明确修改的节点，仅在输出时排除显式路径，去重后按计划顺序取最多 20 个。移除依赖引用未重写时先拒绝，避免输出建立在非法图上。任务 1.1、2.2 覆盖链、多种状态、KEEP、稳定顺序、截断及非法图。
3. **提交边界保持原契约。** 补丁合并、图校验、直接状态重置和提示构造在保存前完成；版本 CAS、租约提交、历史裁剪及原有 acknowledge 流程保留。提示不调用 Replan 偏差入口，因此本身不会新建阻断或反馈次数。任务 2.2 包含取消、版本、历史及拒绝时字节不变的验证。
4. **八字段协议保持兼容。** `PlanToolResult` 已有非空 `message` 字段，自定义消息重载无需新增字段或枚举；旧重载保留默认文案。状态与展示仍按结构化结果解析，不把检查提示当成计划状态。任务 2.1 安排严格协议往返，任务 3.2 安排前端既有解析回归。
5. **重现测试触及实际门禁。** 任务 3.1 要求 router/main 原本已触达，修订后仅 router 待处理，真实文件工具成功写回 router 后使用真实 `beforeBuild` 核验 main 不必重写；同时验证 router 尚未重新触达时仍拒绝，能够区分正确修复与误放宽门禁。
6. **规格同步与任务依赖闭合。** 已比较正式规格中整个“系统必须提供软 Replan 反馈” Requirement 和本次增量，正文一致，仅区段尾部空行不同。六项必需任务可按依赖排序；独立评审与验收指纹位于交付条件，不是循环 checkbox。已核实任务列出的前端测试文件存在。
7. **授权与数据边界清晰。** 不读取或修复历史真实 `.plan.json`，不更改构建、循环策略、前端或运行服务；测试使用 `.codex/fix-plan-dependency-reset/` 隔离输出。无法证明 JVM 工作目录隔离有效时不运行会覆盖真实项目的测试，此前置检查必须落实。

## 非阻塞提醒

- `design.md:15` 的“提示只给模型，不扩展用户面板”应理解为提示的消费目的与常规界面保持不变，不是新增客户端保密保证：当前 `JsonMessageStreamHandler.realtimeToolExecutedChunk` 对计划工具结果沿用原文，`ToolStreamMessageRedactor.safeResult` 也未裁剪其 message；当前 `toolOperationDisplay` 则为计划工具使用固定文案。实施应保持这一既有传输与展示行为，不额外引入脱敏协议或前端功能。
- 正式规格同时保留“取消与计划提交交错”规则。测试“取消无成功提示、不写入”应覆盖取消先取得提交边界的场景；提交先获边界时已允许写入，后续取消不要求回滚。新提示必须服从现有胜出顺序，不因本次规格的失败描述扩大成提交后回滚。
- 可信 `TOUCHED` 只说明此前确实发生变更，不保证与新上游兼容。当前规格明确提示检查、构建与行为验证继续负责兼容性，本次不承诺修复所有重复构建或模型遗漏。

## 当前基线

以下 SHA-256 绑定本次独立读取的实际内容；不代表已经通过业务测试。

| 文件 | SHA-256 |
| --- | --- |
| `openspec/changes/fix-plan-dependency-reset/.openspec.yaml` | `189fd3a044808909f43519471b606ad19d2373a68f93e03ecf4a4d01e3639c64` |
| `openspec/changes/fix-plan-dependency-reset/proposal.md` | `6659e72054adcb7e82a347640f9578d25bf9a7a422ff12938e867269ba56f68a` |
| `openspec/changes/fix-plan-dependency-reset/design.md` | `67b528a95d87d46331346f0b8c0045009dfdccf710adf4ca74fdb4961d452e13` |
| `openspec/changes/fix-plan-dependency-reset/tasks.md` | `3c7522d3c7eb918ffc42c72bd9232eefae9d3f89a4a79619344e01beb95a36a5` |
| `openspec/changes/fix-plan-dependency-reset/specs/vue-soft-replan/spec.md` | `d57a5f32abb1860711a9c20f2fa9b66bd3d49c94161027e8dae0a28e0b5ceab3` |
| `openspec/specs/vue-soft-replan/spec.md` | `998cafcc3d07cd314d5dcde5b43ac1be84229f49430423634a9586503a257a84` |

## 实施交接

主评审可在聚合后记录并核验当前规划基线。源代码与测试应作为允许实施修改的 reference，已同步正式规格与工程规则按持续约束登记；不以旧变更验收替代本次结果。获准实施仍先执行 `start-apply`，实施后运行任务中的隔离验证、独立代码评审与验收。本评审没有调用 `record-plan`，没有修改业务或规划正文。
