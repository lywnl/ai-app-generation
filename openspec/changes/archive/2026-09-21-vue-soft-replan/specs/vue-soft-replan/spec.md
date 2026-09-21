## Purpose

为 Vue 在线变更回合提供可追踪的执行计划和软 Replan 能力，使模型能够在工具执行发现计划偏差时修订后续步骤，同时保持现有工具协议、回合终态和执行作用域不变。

## ADDED Requirements

### Requirement: 统一工具流不依赖内部输出检测

系统 SHALL 物理删除内部输出标记检测、工具参数原文一致性扫描和专属恢复流程，同时保留软 Replan、统一业务信号、工具参数校验及通用终态保护。

#### Scenario: 参数与展示中包含内部标记文本

- **WHEN** 普通正文或合法工具参数字符串包含服务端保留标记文本
- **THEN** 系统 SHALL 不因内部标记启动专属回滚、恢复或终止，最终工具调用仍须经过既有 JSON 归一化、作用域、路径及预算检查

#### Scenario: 统一工具流完成构建

- **WHEN** 无内部输出安全策略的在线工具循环完成文件操作和构建
- **THEN** 系统 SHALL 保持正文、工具请求和执行结果的有序发布，先发布工具结果再通知构建终态，保留 P/E 的偏差观察及下一次请求反馈

#### Scenario: 移除封口后的取消与协议失败

- **WHEN** 生成取消或发生仍受支持的通用工具协议错误
- **THEN** 系统 SHALL 维持取消静默和唯一收尾，不因缺失标记封口而改写取消终态，也不得把通用协议错误当成成功保存

### Requirement: 在线变更回合必须维护结构化计划

系统 SHALL 在 Vue 在线 `MUTATION_REQUIRED` 回合中维护一个带有计划标识、版本、回合标识、模式、目标、文件动作和修订历史的结构化计划。

#### Scenario: 首轮创建计划

- **WHEN** 一个在线变更回合尚不存在当前计划，并且模型请求创建计划
- **THEN** 系统 SHALL 接受 `FULL` 计划，分配计划版本并持久化计划状态

#### Scenario: 空计划被拒绝

- **WHEN** 模型提交没有任何文件动作的 `FULL` 计划
- **THEN** 系统 SHALL 返回计划工具 `REJECTED`，且不得创建可直接通过构建闸门的计划

#### Scenario: 后续回合加载计划

- **WHEN** 一个后续在线变更回合开始且存在上一版本计划
- **THEN** 系统 SHALL 根据应用和回合身份加载当前计划，并向模型提供当前目标、版本、已记录变更和待执行文件摘要

#### Scenario: 修订计划

- **WHEN** 模型提交包含非空原因的计划修订
- **THEN** 系统 SHALL 校验计划版本和文件依赖，递增计划版本，记录修订原因，并使新计划成为当前计划

#### Scenario: 后续回合把计划摘要注入模型上下文

- **WHEN** 受信 `MUTATION_REQUIRED` 回合在创建模型流前接管已有计划
- **THEN** 系统 SHALL 在当前租约回调门内完成 `turnId` 绑定，并把目标、版本、活动回合、计划级状态、已触达文件和待处理文件作为临时系统上下文注入初始请求及恢复请求，不写入聊天记忆

### Requirement: 计划状态文件必须受保护且与回合隔离

系统 SHALL 将计划状态以 JSON 形式原子持久化到应用项目目录的 `.plan.json`，记录最后修改计划的 `turnId`，并在新的受信在线回合开始时将计划绑定到当前回合；普通文件工具 SHALL 不得把该文件作为项目文件暴露给模型。

#### Scenario: 计划状态原子保存

- **WHEN** 计划创建或修订成功
- **THEN** 系统 SHALL 以不会留下半份 JSON 的方式替换计划状态，并保留可校验的版本号

#### Scenario: 普通文件工具访问计划文件

- **WHEN** 模型通过文件读取、写入、修改或删除工具访问 `.plan.json`
- **THEN** 系统 SHALL 拒绝该路径，且不得把计划文件内容作为普通项目文件返回

#### Scenario: 新回合接管上一版本计划

- **WHEN** 新的在线变更回合加载上一回合的计划
- **THEN** 系统 SHALL 在当前有效工具租约下更新活动回合绑定，保留计划版本和历史，并允许当前回合继续修订计划

#### Scenario: 过期回合写入

- **WHEN** 计划操作使用的 `turnId` 不是当前活动回合，或其租约已失效
- **THEN** 系统 SHALL 拒绝操作，并且不得覆盖当前计划

#### Scenario: 旧回合在新回合接管后提交

- **WHEN** 旧回合在新回合完成接管后使用旧活动 `turnId` 和旧版本提交计划
- **THEN** 系统 SHALL 拒绝该提交，且当前回合的计划版本、活动回合和历史不得改变

#### Scenario: 取消与计划提交交错

- **WHEN** 回合取消请求与计划保存同时到达
- **THEN** 系统 SHALL 以租约提交边界原子决定先后；取消先取得边界时不得写入，提交先取得边界时只允许该次提交完成后再进入取消状态

### Requirement: 计划工具必须使用独立受信结果协议

系统 SHALL 提供 `makePlan` 和 `updatePlan` 两个计划工具，并为其使用独立的结构化受信结果协议；系统 SHALL 保持现有文件工具的 8 字段协议、状态枚举和字段集合不变。

#### Scenario: 计划工具结果进入可信执行记录

- **WHEN** `makePlan` 或 `updatePlan` 执行成功或失败
- **THEN** 系统 SHALL 生成可被可信执行记录、转录展示和测试解析的计划工具事实

#### Scenario: 文件工具协议兼容

- **WHEN** 文件工具返回正常结果或资源限制结果
- **THEN** 系统 SHALL 继续使用现有 8 字段文件协议，且计划进度、计划版本和 Replan 状态不得作为新增字段写入该协议

#### Scenario: 只读和评测作用域

- **WHEN** 当前回合是 `READ_ONLY` 或评测作用域
- **THEN** 系统 SHALL 不允许其创建或修订在线计划，且评测工具集合和评测提示词不得被在线计划规则改变

### Requirement: 系统必须提供软 Replan 反馈

系统 SHALL 在可信工具事实表明当前计划可能失效时，将一次性计划校正反馈安排到下一次模型请求；软 Replan SHALL 继续当前 ReAct 循环，不得终止当前 generation 或创建新的模型执行流。

#### Scenario: 发现计划偏差

- **WHEN** 工具事实显示文件路径不在计划中、目标文件持续不存在、同一路径重复失败或计划依赖不成立
- **THEN** 系统 SHALL 记录偏差原因和证据，标记当前回合存在待处理的 Replan，并安排一次临时系统反馈

#### Scenario: 依赖声明无效

- **WHEN** 计划文件引用不存在的依赖、形成自依赖或形成依赖环
- **THEN** 系统 SHALL 拒绝计划创建或修订，并返回计划工具标准拒绝结果，不写入新版本

#### Scenario: 依赖在当前版本未完成

- **WHEN** 当前计划中的文件依赖不是 `KEEP` 且尚未在当前计划版本下可信成功触达
- **THEN** 系统 SHALL 记录依赖偏差并阻止 `buildProject`，直到计划修订或依赖完成

#### Scenario: 计划修订传播依赖状态

- **WHEN** 模型修订一个被其他计划文件依赖的动作
- **THEN** 系统 SHALL 将该文件及其传递依赖重置为 `PENDING`；删除依赖而未在同一修订中重写依赖链时 SHALL 拒绝修订

#### Scenario: 领取计划反馈

- **WHEN** 当前模型请求控制流程准备发送下一次模型请求
- **THEN** 系统 SHALL 最多领取一次当前偏差反馈，将其作为临时系统上下文发送，并不得将其伪装成用户消息或写入普通聊天历史

#### Scenario: 模型修订后恢复执行

- **WHEN** 模型在收到计划反馈后成功调用 `updatePlan`
- **THEN** 系统 SHALL 清除待处理 Replan 状态，并允许当前 ReAct 循环继续执行新计划

#### Scenario: 偏差状态跨回合恢复

- **WHEN** 检测到计划偏差并且当前计划版本被标记为 `REPLAN_PENDING`，随后新的受信在线回合加载该计划
- **THEN** 系统 SHALL 保留该持久状态并继续阻止 `buildProject`，直到 `updatePlan` 成功或本回合达到 fail-open 上限

#### Scenario: fail-open 恢复持久计划状态

- **WHEN** Replan 反馈达到上限且本回合进入 fail-open
- **THEN** 系统 SHALL 清除当前版本的持久 `REPLAN_PENDING` 标记，恢复该版本进入偏差前的计划状态，并记录超限诊断

### Requirement: 待处理 Replan 时必须阻止继续扩大文件变更

系统 SHALL 在当前回合存在待处理 Replan 时阻止新的文件写入、修改和删除，但 SHALL 允许读取工具和 `updatePlan` 执行；阻断结果必须复用现有文件工具的标准 `REJECTED` 结果和 `message` 字段。

#### Scenario: Replan 未确认时修改文件

- **WHEN** `replanPending` 为真且模型调用写入、修改或删除工具
- **THEN** 系统 SHALL 返回标准文件拒绝结果，消息要求先调用 `updatePlan`，并且不得产生文件变更

#### Scenario: Replan 未确认时读取文件

- **WHEN** `replanPending` 为真且模型调用读取文件或目录工具
- **THEN** 系统 SHALL 允许读取，以便模型收集修订计划所需事实

#### Scenario: Replan 反馈超过上限

- **WHEN** 当前回合的 Replan 反馈次数达到配置上限且模型仍未修订计划
- **THEN** 系统 SHALL 清除写入阻断并以 fail-open 方式继续当前回合，同时记录超限状态和诊断日志

### Requirement: 在线构建必须检查计划状态

系统 SHALL 使用在线 `buildProject` 作为计划完成闸门；在线模式 SHALL 不依赖 `exit` 判断计划是否完成。

#### Scenario: 计划不存在时构建

- **WHEN** 在线回合尚未创建计划且模型调用 `buildProject`
- **THEN** 系统 SHALL 返回现有构建协议中的可修复拒绝结果，并要求先创建计划

#### Scenario: 计划存在未处理变更时构建

- **WHEN** 当前计划仍有必要文件变更、未解释的计划外变更或待处理 Replan
- **THEN** 系统 SHALL 拒绝构建，且不得消耗一次真实构建尝试

#### Scenario: 计划闸门通过后构建

- **WHEN** 当前计划已创建、必要变更已记录、没有未处理 Replan 且不存在未解释的计划外变更
- **THEN** 系统 SHALL 按现有构建协议执行 `buildProject`，并继续使用现有构建次数、失败类型、反思和终态语义

#### Scenario: 构建成功写回计划状态

- **WHEN** 计划闸门通过且 `buildProject` 成功
- **THEN** 系统 SHALL 在同一租约提交边界将计划状态写为 `BUILT`；后续成功文件变更或计划修订 SHALL 使其回到未构建状态

### Requirement: 计划操作必须遵守作用域和取消边界

系统 SHALL 让计划工具和计划文件写入遵守当前在线工具作用域、回合租约、执行模式和取消状态。

#### Scenario: 只读回合尝试创建计划

- **WHEN** `READ_ONLY` 回合调用 `makePlan` 或 `updatePlan`
- **THEN** 系统 SHALL 拒绝计划变更，并不得创建或修改 `.plan.json`

#### Scenario: 回合取消期间写计划

- **WHEN** 当前回合已取消、租约失效或工具作用域已撤销
- **THEN** 系统 SHALL 拒绝计划写入，并不得在取消后启动后台计划保存

### Requirement: 计划摘要必须沿用现有工具展示通道

系统 SHALL 通过现有工具执行事件向前端展示 `makePlan` 和 `updatePlan` 的计划摘要和修订原因；本变更 SHALL 不要求新增计划专用 SSE 消息或 REST 查询接口。

#### Scenario: 创建计划后的展示

- **WHEN** `makePlan` 执行成功
- **THEN** 前端 SHALL 能通过现有工具执行消息看到计划摘要、版本和文件清单

#### Scenario: 修订计划后的展示

- **WHEN** `updatePlan` 执行成功
- **THEN** 前端 SHALL 能通过现有工具执行消息看到修订原因和新计划版本

### Requirement: 软 Replan 不得改变非目标模式和现有终态协议

系统 SHALL 保持 HTML、MULTI_FILE、评测模式、普通聊天记忆、文件工具协议和现有构建终态协议的既有行为；本能力 SHALL 不执行文件回滚或硬 Replan。

#### Scenario: 非 Vue 模式生成

- **WHEN** 请求使用 HTML 或 MULTI_FILE 生成模式
- **THEN** 系统 SHALL 不启用 Vue 软 Replan 计划工具和计划闸门

#### Scenario: 软 Replan 执行期间

- **WHEN** 当前回合触发软 Replan
- **THEN** 系统 SHALL 继续当前 generation，且不得把 Replan 当作取消、协议错误、构建失败或其他最终终态

#### Scenario: 计划外事实先于状态归档被观察

- **WHEN** 文件工具成功修改了当前计划之外的路径
- **THEN** 系统 SHALL 在追加 `OUT_OF_PLAN` 状态前观察原始可信工具结果并安排 Replan 反馈，不得因状态归档顺序丢失该偏差
