# Proposal

## Why
当前 Vue 在线生成把 RAG 与图片增强限制在首条修改消息，后续新增功能或更换图片无法获得新资料。每轮全量重跑会增加外部调用与上下文；需要联合判断、首次成功边界和有截止的增量准备。

## What Changes
- Vue 回合路由返回 mode、needRag、needImageSearch，保留模式规则与选中元素的服务端优先级。
- 只读跳过；无持久化 SUCCEEDED 前强制准备；成功后按资源标记，最多 2 功能片段和 2×3 搜索图片，Logo仍最多1。
- 资料准备共用单调截止：初始15秒、后续8秒，超时交付本次已完成部分，取消/删除拒绝迟到注入。
- 回合级临时 SystemMessage 覆盖候选，工具续调与压缩复用；不新增缓存、持久化或检索工具。
- strict 规划与代码独立评审、受控真实 Java 链路测试以及单独获准后的60条×旧新各2轮分类对照。

## Capabilities

### New Capabilities
- `vue-turn-resource-recall`：Vue 用户回合的联合模式/资源判断、首次成功策略、增量资料获取和本轮注入生命周期。

### Modified Capabilities
无。保留正式规范中的只读真实修改升级、计划与构建行为；新能力承接未归档的图片限额实现，不改旧验收字节。

## Impact
影响 VueTurnModeRoutingService/Router、AppServiceImpl/ChatHistoryService、AiCodeGeneratorFacade、RAG/图片收集与受管执行器、回合取消注册和测试。内部返回类型改变，HTTP/SSE及工具白名单、L0/L1编码、非Vue行为、数据库结构不变。基线6938c2f；既有前端未提交修改不纳入。
Profile：strict，原因是影响模式分流和取消/共享执行器边界；schema：spec-driven。无真实外部调用的默认执行授权，真实分类对照先提交具体样例和上限。

## Source Inputs
| ID | 位置 | 类型／确认状态 | 采纳范围 | 替代关系 |
| --- | --- | --- | --- | --- |
| U1—U4 | sources/user-request.md | 用户需求与实施授权／已确认 | 三字段、首次及后续策略、覆盖、截止、验收 | 来源中已登记 |
| D1 | ../../specs/vue-mutation-promotion/spec.md | 持续约束 | 原模式及真实修改升级不重跑资源 | 无 |
| D2 | ../../specs/vue-soft-replan/spec.md、../../specs/vue-build-progress-guard/spec.md | 持续约束，按有效条款采纳 | 既有计划工具、偏差、构建、取消及协议；不采纳已被替代的每轮临时计划注入条款 | 计划注入条款以D3为准 |
| D3 | ../reuse-plan-context/specs/vue-soft-replan/spec.md、../reduce-recent-context-window/specs/context-summarization/spec.md | 已交付行为的持续边界 | 当前PLAN_RESTORE热窗口、8,192 Token近期窗口；本轮不改其存储与摘要行为 | 替代D2中旧计划临时注入方式及旧窗口额度，不重写旧报告 |
| C1 | src/main/java 的路由、服务、图片、RAG、回合上下文及相关测试 | 已核实事实／实施参考 | 允许按任务修改 | 只替代受影响行为 |
| C2 | ../limit-image-context/specs/image-material-collection/spec.md | 旧实现参考 | 3张、80码点、完整URL、调用内去重保持 | 本轮Vue截止与后续配额取代旧约定 |
| D4 | ../unify-context-summarization/specs/context-summarization/spec.md 的“临时资料必须保持既有回合范围” | 部分持续约束、部分被替代 | 保留同回合复用、不进L0/L1及不跨回合继承 | 本轮新能力仅替代该条的“Vue首轮准备条件”，旧报告保留 |
