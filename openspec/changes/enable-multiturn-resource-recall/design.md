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

按 U5 修订为目录状态识别：CurrentVueProjectReader 通过现有安全目录句柄检查是否为空，不创建目录、不递归、不打开package.json或其他工程文件。目录不存在/完全为空为ABSENT；非空安全目录为AVAILABLE；不安全、不可访问或已取消/截止为UNAVAILABLE。目录内任意文件、子目录或符号链接都算已有条目，但不跟随链接；工程目录本身及祖先路径沿用现有拒绝链接规则。CurrentVueProject仅保留Status、reason，existing()对UNAVAILABLE仍保守为true。首轮ABSENT可以召回，后续ABSENT及所有UNAVAILABLE返回无资料。清单缺失/损坏/超大/版本不同和锁文件不再阻止召回；真实构建器依赖契约继续执行。

数量集中于RagConstants：VUE_CHANNEL_TOP_K=8、VUE_FUSION_TOP_K=10、VUE_SKELETON_TOP_K=1、VUE_INITIAL_FEATURE_TOP_K=4、VUE_FOLLOWUP_FEATURE_TOP_K=2。Dense、BM25实际搜索及默认入口共用通道上限；BM25不再使用固定QUERY_TOP_K。8是子块上限，父文档聚合后允许不足，不补搜。RRF每池独立合并去重并保留前10个父模板，k=60、权重和同分规则保持。

VueHybridRetrievalService在普通混合、在线部分发布、后续功能、生产/离线Dense-only路径删除框架、语言、构建工具、依赖版本及候选依赖白名单过滤。保留类型、目录版本、父文档回查、ID去重、响应合法性；功能按排名选前K个有效唯一项，骨架选第一项。重排请求、正常交付、关闭/失败降级及结果类型共用1/4/2上限。后续只访问FEATURE_SNIPPET池。保持空查询和不可用目录的既有行为。

RagPromptAssembler沿用功能预算和原子文件块，文案不再宣称兼容确认。已存在工程的资料始终为参考，禁止据此重建或改变已有技术栈；无骨架时继续当前工程，只有新工程可用固定基础骨架，离线基线不加兜底。模板元数据保留用于检索语义和上下文，不把已删除的筛选迁移到另一检索层。

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
- 取消兼容匹配后不同技术栈模板也可能被选中 → 当前库为统一技术栈；参考不能授权重建工程，构建器继续执行固定依赖契约。8/10缩小候选池，不以本地测试宣称真实召回质量或耗时改善。
- 旧历史没有可靠成功标记会再次使用初始策略 → 仅首次成功后自然切换，不修历史，不以目录推成功，保护已有工程。
- 15/8秒可能只返回部分资料 → 显式部分结果，继续生成，按稳定顺序保证可复现；测试使用注入时钟/短预算并单独验证生产默认值。
- 主流程与取消竞态 → 双门/租约提交保护继续生效，资料注册可注销，状态关闭原子化，测试删除与迟到结果。

## Migration Plan

无数据库迁移、无新Redis字段。独立 strict 规划评审后 start-apply，依次完成 tasks。每阶段聚焦测试；最后受控Java链路、前端既有回归、独立代码评审。真实分类脚本和60条合成数据先准备，再取得≤240次调用授权。保护用户前端未暂存修改和 .DS_Store，不提交、不重启。回退必须整体撤销本变更的提示词、解析器和多轮策略，避免响应协议错配。

### U5 修订的实施和回退

本轮沿用strict独立规划和代码评审，先保存历史原件，再登记新的start-apply。实施和验收范围为tasks第8节；旧分类、图片和前端结果仅按未变相关输入核实复用，不重新调用真实模型。本地Java25验证使用现有run_tests.py及新的vue-rag-selection-*标签；组合回归加--sandbox，必须确认E2E实际执行，不能将跳过当通过。回退仅整体撤销本轮RAG数量、筛选及目录状态改造，不撤销已交付的多轮路由和图片功能。
