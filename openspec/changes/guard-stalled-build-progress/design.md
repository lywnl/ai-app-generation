## Context

见 proposal。现有 VueBuildSessionManager 维护真实构建和 mutationRevision；beforeBuild 只有文字诊断；构建工具对Replan和依赖有提前返回/即时反馈。现有受信 ToolExecutionGuard、控制器结果提交和 ContextContinuationGate 可承载本次能力，无需第二套 app 会话表。

## Goals / Non-Goals

**Goals:** 以可信内部诊断保护两类无进展拒绝，并在正确请求/终态边界处理送达与停止。
**Non-Goals:** 不改变三次真实构建、依赖/基础设施重试、只读/评测/非法身份处理；不新增对外协议或历史数据迁移，不重启服务。

## Decisions

1. 新增不可变 BuildBlockDiagnostic（内部原因、计划状态、规范化阻塞条目）并扩展 BuildGateDecision，保留旧 allowed/message 使用方式。条目含类型、相对路径、动作、状态、依赖路径；无计划、空计划和待修订用明确哨兵条目。内部完整集合用于比较，排序去重后输出最多20条并按码点限制摘要；不靠文案判断。所有缺失条件同时记录，REPLAN_PENDING不遮蔽底层文件/依赖阻塞。计划不匹配诊断不参与普通纠偏。
2. 用调用级 ScopedValue 捕获构建工具实际拒绝诊断，作用域结束自动解绑，不使用共享lastResult。FileToolExecutionScopeManager 新增带观察数据的调用入口，保持原 callInScope 与构造器兼容；真实成功计划/文件变更完成写回后生成进展快照。在线 Facade 将可选观察放入 GuardedToolExecution 的新增内部字段，保留二参构造。没有诊断或失败/取消的结果不伪造进展。
3. BuildProgressGuard 由 VueTurnContext 创建，以appId/turnId绑定并经 ContextContinuationGate 可选getter传给 StreamingRequestController。观察仅在工具结果提交成功后、下一次请求前发生，用请求代次+工具ID去重；身份/取消/已结束检查由控制器与回合栅栏负责。关闭回合清理保护器，不改变 Session 的真实构建计数。
4. 维护最近一次拒绝的完整阻塞集合和无进展阶段。仅成功工具产生的进展快照可清零：无计划变为非空合法计划；新阻塞集合是之前集合的真子集或为空；代码无修改阻塞由现有会话 requiresMutation 条件解除。每次无进展拒绝更新最近阻塞快照但不清零，因此换文件/换状态/版本变化不能逃逸。实际构建开始/返回代表旧门禁已放行并重置该观察阶段。读取/NO_CHANGE不生成进展；fail-open导致的集合变化仅在构建拒绝观察时更新，不清零；若fail-open与同一成功工具提交相邻，观察数据须排除非工具移除的待修订哨兵，避免误算进展。
5. 第二次拒绝创建不可变反馈票据（单调本地ID+SystemMessage），领取为非破坏性读取，直到送达或真实进展。反馈指令按原因构造，不调用 ReplanContext.observe、不创建新的REPLAN_PENDING，不借用其feedbackCount；原有真实偏差及fail-open保留。BuildProjectTool去掉依赖文字前缀判断和即时observe。
6. 普通续请求、协议恢复、未完成工具链恢复都捕获反馈票据并合并临时消息；startModelRequest返回的SDK启动动作运行时才绑定目标代次，且实际requestMessages必须包含该票据的消息。当前代次合法正文/工具/完整响应回调进入控制器回调门后确认送达；onError、准备/压缩完成、单纯领取不确认。同步有效回调可以确认，启动无回调即抛错不能确认；真实进展使旧票据失效，旧代次不能确认。无需给外部消息新增标记或扫描保留前缀。
7. 送达后无进展再拒绝，由响应处理器在提交最后一次工具结果后认领 ControlledTermination(BUILD_STALLED,null)，沿与重复读取保护同类的批次停止/发布边界收口。结果仍为标准REJECTED，不伪造terminateToolLoop字段或PROTOCOL_ERROR。JsonMessageStreamHandler将该内部原因映射FAILED，终态语句从回合保护器的冻结诊断取得，字数最大1200码点，纳入VueTurnFinalizer终态预算；记忆追加同一受限原因和原真实工具事实/构建次数，取消/删除竞争仍由原最终化器处理。
8. slf4j日志只含app/turn、工具ID、代次、原因枚举、阻塞集合SHA-256、计数和反馈阶段。复用现有日志，不新增数据库、指标存储或前端页面。

## Risks / Trade-offs

- 文案与结构解耦，标准工具协议及SSE保持不变。两类内部元数据仅服务端传递；前端可见普通诊断message，不声称隐藏message。
- 反馈送达指服务器已发起请求且收到有效回调，不声称证明模型理解或遵从。无响应走原错误/截止链路。
- 文件变更相关性不另做语义推断；代码失败沿用已有revision门禁，真实构建验证正确性。任意模型逻辑循环仍由既有总预算与截止兜底，本期针对构建拒绝。
- 回合结束会释放权限与观察数据；物理文件和计划保留，新回合计数清零。

## Validation Strategy

增加诊断/保护器单测、真实工具作用域与文件写回集成、控制器/响应处理器及请求恢复测试、终态/预算/取消竞争和前端FAILED保持工作区测试。使用当前测试框架的受控模型及真实临时文件，fork JVM user.dir和java.io.tmpdir置于本变更.codex目录，不启动真实模型/数据库；运行相关现有回归。记录旧计划哈希，不修改真实计划。真实HTTP或部署不属本期验收。

## Migration Plan

用户已授权同步正式规范，本次在规划阶段同步并绑定为持续约束；历史变更和验收保留，不引用旧通过记录证明当前新行为。无数据迁移，不自动重启、推送或归档。
