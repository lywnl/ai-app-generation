## Context

现有 Vue 在线生成由 `AiCodeGeneratorFacade` 创建 `TokenStream`，由 `AiServiceTokenStream` 和 `GenerationAwareModelRequestOrchestrator` 驱动模型请求与工具循环。工具结果经过严格协议解析后进入 `VueToolExecutionFact`，在线构建由 `BuildProjectTool` 和 `VueBuildSessionManager` 控制，`StreamingRequestController` 已承担 generation、恢复请求和重复读取校正的竞态控制。

本设计承接 [proposal.md](proposal.md) 和 [specs/vue-soft-replan/spec.md](specs/vue-soft-replan/spec.md)。核心约束是：在线模式没有 `exit` 终止工具，文件工具协议字段集合不可扩展，软 Replan 不能终止当前 generation，计划操作必须服从 `VueTurnContext` 的回合、取消和作用域边界。

## Goals / Non-Goals

**Goals:**

- 为在线变更回合提供版本化、可持久化的计划和模型驱动修订入口。
- 让计划偏差在下一次模型请求前以一次性临时系统反馈呈现，并能阻止偏差确认前的继续写入。
- 让 `buildProject` 成为在线计划完成检查点，同时保持既有构建协议和构建次数语义。
- 复用 `VueToolExecutionFact`、`AppFileStateManager`、`VueBuildSessionManager` 和现有作用域控制，避免建立第二套文件事实或工具预算。
- 保持只读、评测、取消、文件协议和非 Vue 模式的兼容性。

**Non-Goals:**

- 不实现 generation 级硬 Replan、文件回滚或自动创建新的 ReAct 回合。
- 不让计划文件成为模型可读写的普通项目文件。
- 不改变 `FileToolResult` 的 8 字段协议、状态枚举或资源限制终态判断。
- 不在本变更中增加数据库存储、计划 REST 面板或新的 SSE 消息类型。

## Decisions

### 1. 计划状态使用受保护的 JSON 旁车文件

计划状态保存为项目目录下的 `.plan.json`，由计划状态管理器使用应用根目录安全规则直接读写。文件记录最后修改计划的 `turnId`；新的在线回合开始时，只有持有当前有效生成租约的服务端流程才能把活动回合重新绑定到新的 `turnId`。保存使用临时文件和原子替换，并通过 `planId`、活动 `turnId` 和 `version` 防止旧回合或旧版本覆盖新状态。普通 `ProjectPathResolver` 将 `.plan.json` 作为受保护路径段，目录读取、文件读取、修改和删除均不可触达它；下载逻辑继续排除该文件。

选择旁车文件而不是聊天记忆或数据库，是因为计划属于当前生成项目的执行状态，需要在后续用户轮次和服务冷启动时恢复，但不应污染聊天轮次。聊天记忆只保留最终回合投影，计划历史限制长度以避免上下文无限增长。

### 2. 计划工具使用独立协议，文件协议保持不变

新增 `makePlan` 和 `updatePlan`，各自返回独立的计划工具协议，并将成功、拒绝和失败结果接入可信事实、转录展示和测试解析。`VueToolNames.ONLINE` 增加这两个工具；`VueToolNames.EVALUATION` 保持不变。

计划工具不复用 `FileToolResult`，也不向文件工具结果增加计划字段。这样可以保持 `FileToolProtocolSupport` 的严格字段集合、`VueToolExecutionFact` 的文件事实解析以及构建终态协议不变，同时让计划工具拥有可演进的版本和修订信息。

计划工具只在在线 `MUTATION_REQUIRED` 作用域执行。工具入口先校验当前作用域、`turnId`、取消状态和计划版本，再进行计划写入；任何后台异步写计划的路径都不允许存在。

### 3. 复用已有可信事实，不新增 FileFact

计划状态管理器只维护计划文件、计划文件条目和修订历史。实际工具事实继续来自 `VueToolExecutionFact`，已写文件清单和内容指纹继续来自 `AppFileStateManager`，构建次数、变更版本和构建结果继续来自 `VueBuildSessionManager`。

计划文件条目只表示计划动作及其执行触达状态，不引入无法从现有工具链可靠推导的逐步骤验证字段。构建是否成功仍由现有构建结果决定，计划管理器只记录计划级构建状态。

### 4. Replan 反馈接入 StreamingRequestController 的领取路径

工具执行完成后，可信事实在模型请求控制侧被观察。`ReplanDetector` 根据当前计划和事实生成带有唯一触发标识的偏差记录，并将一次性反馈交给当前回合的请求控制器。下一次模型请求提交前，控制器领取并消费该反馈，将其作为临时 `SystemMessage` 注入请求。

这条路径与已有重复读取校正机制保持一致，避免在转录层收到 `GenerationStreamSignal.ToolExecuted` 后异步修改下一次请求而产生竞态。反馈不能写成 `UserMessage`，不能写入聊天历史，也不能包含内部服务标记。反馈文本要求模型先调用 `updatePlan`，并要求模型不要复述或解释内部提示。

### 5. 待处理 Replan 通过标准文件拒绝结果阻断写入

`replanPending` 保存于当前 `VueTurnContext`，并通过在线工具作用域提供给文件变更策略。`FileToolExecutionScopeManager.rejectForbiddenMutation(...)` 同时检查既有构建故障禁写原因和计划待修订原因。

计划待修订时，写入、修改、删除工具返回现有 `FileToolResult` 的 `REJECTED` 结果，仅在 `message` 中说明先调用 `updatePlan`；读取工具和 `updatePlan` 仍可执行。这样不会增加文件状态枚举，也不会触发 `ToolLoopTerminationProtocol` 的错误终态。

Replan 反馈次数达到回合上限后，回合状态转为本轮 Replan 抑制，解除写入阻断并记录诊断信息。该 fail-open 设计避免模型忽略反馈时永久卡死；最终构建仍接受现有 `buildProject` 构建状态和项目实际结果约束。

### 6. buildProject 承担在线计划闸门

在线工具集合没有 `exit`，因此在 `BuildProjectTool` 真正领取构建尝试票据之前调用计划状态检查。检查失败返回现有 `BuildProjectToolResult.mutationRequired(...)` 形式的可修复拒绝，不消耗构建次数。

计划就绪判定固定为：`KEEP` 文件不要求变更；`CREATE`、`MODIFY` 和 `DELETE` 文件必须有当前计划版本下的可信成功变更记录；`OUT_OF_PLAN` 文件和 `replanPending` 会阻止构建。计划修订后，新增或动作发生变化的文件重新回到待处理状态，未受影响的已记录变更可以保留。通过后继续现有构建流程。构建失败的反思仍复用 `BuildProjectToolResult.reflectionRequired`、`BuildNextAction` 和 `VueBuildFailureKind`；只在反思提示中说明计划假设错误时先调用 `updatePlan`，不新增构建反馈执行器。

### 7. 通过现有工具事件展示计划

计划工具的展示摘要通过现有工具执行事件和前端工具消息渲染链路发送。第一阶段不增加新的 SSE 类型和 REST 查询接口，避免为了展示计划扩大协议改动面。固定计划面板可以在后续独立变更中使用 `AppPlanStateManager` 提供只读 DTO，但不作为本变更依赖。

### 8. 后续回合接管和计划上下文注入的唯一挂钩

受信回合初始化仍由现有 `VueTurnContext` 和精确 `VueBuildLease` 负责。`AiCodeGeneratorFacade.generateVueProjectStream` 在确认回合为 `MUTATION_REQUIRED`、创建 `TokenStream` 前，必须在当前租约回调门内调用 `AppPlanStateManager.loadForTurn(appId, turnId)`。该调用完成后才安装 `ReplanDetector` 和创建工具作用域；没有计划时保留首轮 `makePlan` 路径，有计划时把 `toPromptContext` 生成的摘要作为回合级临时 `SystemMessage` 注入初始请求和所有恢复/续请求，不写入聊天记忆。

计划摘要至少包含目标、当前版本、活动回合、计划级状态、已触达文件和待处理文件。接管失败、计划文件格式错误或租约在加载期间失效时，回合在创建模型流前失败并沿用现有回合错误收口，不启动后台重试。`prepareIncompleteRecoveryRequest` 与普通续请求使用同一临时消息合并函数，不能丢失计划反馈。

### 9. 计划提交与取消的原子边界

计划创建、修订和成功文件事实写回都必须在现有在线工具回调票据内调用 `VueBuildLease.commitWhileActive(Supplier<T>)`。该方法取得 `AppOperationLease` 的提交锁，在同一锁内确认回合仍未取消，再执行计划状态管理器的版本 CAS；取消请求只能在提交完成后取得取消状态，提交之后的取消不得撤销已提交文件事实。若取消先取得门，则提交抛出 `ScopeCancelledException` 且不得调用计划文件写入。

锁顺序固定为“操作租约提交锁 → 计划管理器应用锁 → 安全目录句柄”，计划管理器不得在应用锁内再次调用租约 API，取消回调不得反向取得计划管理器应用锁。`AppPlanStateManager.save` 同时校验期望版本和期望活动 `turnId`；`loadForTurn` 将新的 `activeTurnId` 写入计划，`activeTurnId` 本身就是接管令牌，旧回合不能使用相同版本覆盖新回合，不新增隐藏的绑定代次字段。计划路径保存使用 `SecureDirectoryStream` 或项目现有等价的不跟随符号链接目录句柄；若运行环境不提供等价安全边界则 fail-closed 拒绝保存并返回受信失败，不得回退到绝对路径写入。父目录、目标 `.plan.json` 和临时文件的检查与替换必须在同一安全目录访问边界内完成。

### 10. 依赖判定与偏差观察顺序

`PlanFile.dependsOn` 只允许引用同一计划中存在的文件，禁止自依赖和环；计划创建、修订时拒绝悬空或循环依赖。依赖在当前计划版本中满足的条件是：依赖动作是 `KEEP`，或依赖文件已在该版本下以可信成功变更标记为 `TOUCHED`。计划修订先以 `path -> dependsOn` 构造当前计划图，校验所有引用存在且图无环；再把新增、删除和修改路径作为种子，沿“被修改文件 → 直接依赖者”的反向邻接表做 BFS，得到受影响集合，将种子及其传递依赖重置为 `PENDING`。若删除路径仍被下一版本文件引用，或修改后依赖列表引用了本次删除路径，必须在写入前拒绝整个修订；只有在同一修订中同步删除/重写所有引用时才允许提交。依赖未满足时 `buildProject` 拒绝并由检测器生成一次偏差。验收输入至少包含 A→B→C 三节点链、删除 B 未重写 C、重写 B 后 C 的状态和环 A→B→A，输出分别为传播到 C、拒绝且版本不变、按新图重算和拒绝。

文件工具成功结果必须先由 `ReplanDetector` 观察原始可信结果，再由 `AppPlanStateManager` 将计划条目标记为 `TOUCHED` 或追加 `OUT_OF_PLAN`；这样计划外路径不会因先写入状态而失去偏差信号。计划版本更新后清理该版本作用域内的失败计数和已处理触发标识，避免旧版本去重屏蔽新偏差。

### 11. 计划状态生命周期和空计划语义

`FULL` 计划必须至少包含一个文件动作；空文件数组返回计划工具 `REJECTED`，不创建可直接通过构建闸门的空计划。计划偏差由回合级 `ReplanContext` 保存，并在同一计划版本的提交边界把持久化计划标记为 `REPLAN_PENDING`；新的回合加载该状态后仍阻止构建。模型成功 `updatePlan` 后清除待处理偏差并恢复为 `PLANNED`，fail-open 后恢复到该版本原有的 `PLANNED` 或 `READY_TO_BUILD` 状态。`buildProject` 成功后在同一租约提交边界把计划状态写为 `BUILT`；任何新增文件事实或计划修订都会离开 `BUILT`。

不完整工具链恢复与普通续请求共用计划摘要和未领取反馈的合并函数；失败计数在成功变更、`updatePlan` 成功和回合取消时清零。上述状态和计数都不改变文件工具 8 字段协议。

## Risks / Trade-offs

- **[协议兼容风险]** 计划字段误加入文件工具 JSON 会导致严格解析、资源限制终态和可信事实失效 → 使用独立计划协议，文件协议只增加测试覆盖，不改字段集合。
- **[请求竞态风险]** 在转录回调中直接触发计划反馈可能与下一次模型请求并发 → 只通过 `StreamingRequestController` 的观察/领取机制传递反馈。
- **[模型忽略反馈]** 模型可能重复调用文件工具而不修订计划 → `replanPending` 时阻止变更工具；超过反馈上限后按明确策略 fail-open 并记录日志。
- **[计划状态过时]** 旧回合或旧版本可能覆盖当前计划 → 计划写入校验 `turnId` 和 `version`，并使用原子替换。
- **[取消期间写入]** 回合取消与计划保存可能交错 → 计划工具必须经过当前工具作用域和租约校验，禁止后台保存；在提交前再次确认回合仍有效。
- **[计划完成不可细粒度验证]** 现有构建结果是回合级事实，不能可靠证明每个计划步骤单独完成 → 计划只记录文件触达和计划级构建状态，不伪造逐步骤验证状态。
- **[协议与工具接入遗漏]** 新工具若未加入白名单、事实解析或展示链路，会出现模型可调用但系统不可观测 → 将工具白名单、独立协议、可信事实、展示和作用域测试作为同一交付切片。
- **[计划文件泄漏]** 路径保护遗漏会让模型把内部状态当项目文件操作 → 在路径解析、目录遍历、文件工具和下载路径分别加入保护测试。

## Migration Plan

1. 先增加计划数据结构、受保护路径和独立计划协议，不改变现有文件工具和构建协议。
2. 接入在线计划工具、作用域/取消检查、可信事实和现有工具展示链路；评测工具集合保持不变。
3. 接入请求控制侧的偏差观察、一次性临时反馈和文件变更阻断。
4. 在 `buildProject` 前增加计划闸门，并补充只读、取消、协议兼容、重复偏差和构建失败测试。
5. 对已有项目首次在线变更时按 `makePlan` 创建计划；已有项目没有 `.plan.json` 不需要迁移数据库或聊天历史。

回退方式是回退本次代码部署；已有 `.plan.json` 作为受保护、可忽略的项目旁车文件保留，不参与普通文件工具和下载包。回退不需要数据迁移。

## 2026-09-21 已确认的输出检测删除补充

本补充依据用户明确选择的“物理删除”及执行计划，覆盖先前的运行时停用方案。删除普通文本和工具参数中的内部标记扫描、工具参数 partial/complete 原文一致性扫描、一次内部恢复、专属回滚事件及 OutputSafetySeal 收尾封口。不删除合成记忆协议常量、完整合成消息分类、工具 JSON 归一化和受信结果校验。

统一信号模式独立安装 GenerationSignalPublisher，并独立启用 GenerationCallbackSequencer。GenerationDisclosureBuffer 保留暂停、批量顺序发布、单一发布者及异常收口，移除仅用于扫描候选的未决披露接口。四类正常 GenerationStreamSignal、工具结果先于构建终态、批次完成后才启动后续请求均保持。

普通生成、Vue 正常完成、取消、超时、删除接管和入口失败不再执行内部标记封口，但仍执行原有唯一终态、租约静默、文件/数据栅栏和记忆持久化。通用 PROTOCOL_ERROR 仍作为错误收口，不得变成成功保存。前端删除专用恢复状态、提示和两个回滚/恢复 SSE 处理器，保留普通工具卡及其事件契约。

移除标记检测不等于保证模型不会复述提示；计划反馈仍不得包含服务端标记且仍要求模型不要复述。旧评审与验收记录保留为历史，后续归档需要对补充范围形成当前评审和验收基线。

## 补充后的未决事项

无。当前方案中的范围、协议边界、软 Replan 语义、构建闸门、作用域隔离和前端展示范围均已由用户确认。
