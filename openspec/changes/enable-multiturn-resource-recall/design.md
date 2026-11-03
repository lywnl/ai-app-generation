# Design

## Context

动机和范围见 proposal.md、行为契约见 specs/vue-turn-resource-recall/spec.md。当前基线 6938c2f：VueTurnModeRouter 返回枚举，选中元素零分类调用；AppServiceImpl 在保存用户消息之前分类；Facade 以 isFirstMessage 限制资料；RAG 已有受管池和请求许可，图片规划同步且每 Future 独立等待。临时资料已经沿 TurnRequestBoundary 和 TokenStream 传给续调及统一计量入口，可以复用。

## Goals / Non-Goals

将模式、策略、资料任务和最终消息分层：每层只能消费上一层校验后的不可变值。隔离本轮截止和回合取消，不把资料超时误记为取消。保留现有基础设施、持久化和模型连接，不扩大线程池、不引入缓存。公共 HTTP/SSE、非 Vue 调用、计划与摘要语义均保持；不包含提交、重启或付费调用授权。

## Decisions

### 1. 三字段解析与服务端模式优先级

VueTurnModeRoutingService 只对 Vue 入口改为 String JSON 返回；Router 用项目现有 Jackson 先校验完整对象、合法 mode，再逐个校验布尔字段。拒绝尾随文本、数组、缺失或非法 mode，不使用枚举自动映射掩盖字段差异。保留原枚举模式含义，其他路由配置不变。

选中元素仍调用一次同入口，但固定修改优先于 READ_ONLY 的资源清空；异常降级固定修改及 false/false。普通请求异常保留 cause。日志只含固定来源、模式、标记、耗时和降级原因，不输出响应正文。返回内部 VueTurnDecision；保留兼容性的旧方法时必须委托新实现，主链仅调用一次。

### 2. 持久化成功查询和本轮策略

ChatHistoryService.hasSucceededGeneration(appId) 使用现有 appId 索引，只查满足 ai/SUCCEEDED/未删除的 id，limit 1；错误传播到前置流程。AppServiceImpl 在修复孤立记录与准备服务后、分类和用户提交前查询。CommittedVueTurn 携带 decision、VueTurnResourcePolicy 及初始化背景，原始消息保存不变。

VueTurnResourcePolicy 固定初始阶段、RAG 范围（NONE/FULL/FEATURES）、图片开关、显式图片配额和等待预算。RAG 开关由后端应用。后续只读升级不重建策略。初始 RAG 以 initPrompt 为主并追加不同本轮要求；后续以本轮为主。图片输入有清晰的当前需求/背景边界及背景长度限制，不把背景当新修改指令。

### 3. 安全的功能专用路径

新增当前工程兼容读取服务，复用安全路径解析/读取边界，限制 package.json 为 64 KiB；取得 Vue/Vite 与实际依赖，沿用已有版本比较及工程允许依赖规则。存在工程时初始路径也不能将首次资格解释为空工程授权；提示保留当前技术栈，缺少可靠兼容信息时不注入无法验证模板。

VueHybridRetrievalService 增加仅功能入口和结果类型，复用 embedding、BM25/dense、RRF、可配置重排及请求许可。仅提交 FEATURE_SNIPPET 任务，最终按当前目录版本/id去重、兼容过滤、最多2个。RagPromptAssembler 增加纯功能资料入口，沿用原 feature 字符预算和完整文件块裁剪，不走 skeleton=null 的最小工程提示。初始无资料的明确最小约束降级不授权覆盖已有工程。

### 4. 单一截止与任务归属

建立 Vue 回合资料准备上下文：注入 LongSupplier 单调时钟、固定开始/截止时间、回合活跃检查及独立取消注册。截止取 policy预算与 turn剩余时间的较小值。从资料准备开始只建一次；子任务接收同一实例，提交前、实际开始前、外部调用前、接纳结果前均检查。截止返回部分，用户取消传播到整个任务组并阻止生成。

资料协调者在现有生成准备线程上等待，RAG/图片规划在现有 preparation executor 上并行，图片网络任务在 image executor，RAG子任务在 VueRagExecutor；不让协调者和所等待的子任务占满同一池。RAG 请求许可使用剩余时间有界等待，取消/关闭 scope 保留真正运行任务的许可直到退出；清除取消的排队 FutureTask。对支持的接口传剩余超时，其他由可取消任务和既有网络超时结束，不调整共享客户端属性。

每个任务完成时在截止内发布不可变结果，发布和关闭状态需原子互斥，稳定顺序由规划时索引决定。外层在单一截止等待，截止时先冻结已完成结果再取消未完成任务；不能顺序 get 每个任务后错过其他完成项。RAG 可在缺少重排预算时交付已有RRF结果，图片可交付已完成主题/Logo。没有截止前完成证据的迟到结果不能交付。

资料取消通过通用 AppOperationLease 登记，必要时 VueTurnContext 封装可注销注册；不使用 registerModelCancellation。无真实lease的单测上下文也要执行本地回调。取消与删除应唤醒等待、清理队列、注销回调；发送前仍经过既有 callback gate/turn boundary，防止检查后取消的竞态。

### 5. 图片阶段限额

图片服务及格式化器显式接收 ImageCollectionQuota，默认重载保留非 Vue 行为。规划先归一去重、有效主题截取再调用外部API，汇总再次限制；per_page=3、80码点、完整URL不变。Logo仍由图片规划返回且≤1，后续规划提示默认复用已有Logo。

### 6. 冻结临时资料与可观测性

Facade 消费 policy 和资料上下文，生成 List.copyOf 的 SystemMessage 列表并复用已有 createVueCodeStream 绑定入口；不改 L0/L1。未召回类别不输出旧清单；空后续功能无骨架提示。统一请求预备器计量的是包含资料的同一冻结消息；必要时添加两个类别的本地 Token 观测，不保存资料正文。

资料任务结果显式区分 SUCCESS/PARTIAL/TIMED_OUT/CANCELLED/REJECTED/EMPTY/FAILED，用应用/回合/阶段、查询哈希、排队/执行/总时间及有效数量记录。哈希不代替访问边界；日志避免泄露用户原文与凭据。取消是回合状态，资料超时只是准备状态。

## Risks / Trade-offs

- 联合输出可能影响模式准确率 → 保留分类规则，60条固定人工标注、旧新各2轮，真实对照不过门禁不能宣称无退化；解析测试不能替代评测。
- 底层客户端不能立即中断 → 有界池与许可保持到真正退出，截止后不提交子任务或接纳结果，日志记录剩余任务，不扩池。
- 后续兼容判断保守会减少模板命中 → 缺少可靠信息宁可无资料；不授权新模板改技术栈。
- 旧历史没有可靠成功标记会再次使用初始策略 → 仅首次成功后自然切换，不修历史，不以目录推成功，保护已有工程。
- 15/8秒可能只返回部分资料 → 显式部分结果，继续生成，按稳定顺序保证可复现；测试使用注入时钟/短预算并单独验证生产默认值。
- 主流程与取消竞态 → 双门/租约提交保护继续生效，资料注册可注销，状态关闭原子化，测试删除与迟到结果。

## Migration Plan

无数据库迁移、无新Redis字段。独立 strict 规划评审后 start-apply，依次完成 tasks。每阶段聚焦测试；最后受控Java链路、前端既有回归、独立代码评审。真实分类脚本和60条合成数据先准备，再取得≤240次调用授权。保护用户前端未暂存修改和 .DS_Store，不提交、不重启。回退必须整体撤销本变更的提示词、解析器和多轮策略，避免响应协议错配。
