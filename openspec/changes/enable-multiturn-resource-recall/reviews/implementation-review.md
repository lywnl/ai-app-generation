# Implementation Review: enable-multiturn-resource-recall

- 评审日期：2026-11-02（Asia/Shanghai）
- Profile：strict
- 评审者与独立性：`/root/multiturn_code_review`；未实施本轮业务代码、未修改正式测试或规划。只写本报告及 `.codex` 下的独立复现材料。
- 当前结论：`PASS`。两项兼容问题均已修复；最终差异、连续 Java 调用链、压缩与协议恢复、取消及相关回归证据已复核，没有开放代码阻塞。该结论为独立代码质量评审，不代替本变更正式验收记录。
- 基线：Git `6938c2f` 加当前未提交实现；最终源码快照见 [reviewed-sources.json](../../../../.codex/enable-multiturn-resource-recall/independent-review/reviewed-sources.json)。已确认当前源码与最新 `reuse-sandbox/code-state.json` 无哈希差异；较 `regression-v2` 的唯一代码差异是复用测试的沙箱启用注解，该测试已单独重测通过。

## 评审范围与依据

按本变更 proposal、specs、design、tasks 及 `.agents/shared/openspec/verification-evidence.md` 核对：联合路由解析、持久化成功资格、固定资源策略、现有工程兼容读取、后续功能专用召回、图片显式配额、单一资料截止、排队与物理调用寿命、取消和删除、Facade 临时资料组装以及预算观测。

具体检查了 ResourcePreparationScope、VueRagExecutor、VueTurnContext、VueHybridRetrievalService、CurrentVueProjectReader、VueProjectBuilder、ImageCollectionService/Policy/Quota/PromptBuilder、ImageSearchTool、VueTurnModeRouter/DecisionParser/ResourcePolicy、AppServiceImpl/ChatHistoryServiceImpl、AiCodeGeneratorFacade 及 UnifiedContextRequestPreparer 的本轮修改，并核对相关维护性测试与已保留执行记录。读取 AppPlanStateManager、PlanStoragePathResolver、SecureFileAccess 以及通用操作租约作为调用链依据；这些来源与允许修改的代码不是新的产品需求。

未执行真实供应商、数据库或业务服务操作，未重复运行主执行者已获授权的分类评测。没有改动用户已有 generationSession.test.ts 内容或暂存状态，也没有改动 `.DS_Store`。

## Findings 与处置

### CR-1 / P1：计划初始化空目录使真实新应用跳过完整 RAG（已关闭）

初始实现中，Facade 先执行 initializePlanContext，AppPlanStateManager.loadForTurn 经带副作用的计划读取创建空工程目录；CurrentVueProjectReader 随后把缺少 package.json 的目录一律识别为已有不可用工程。prepareOnline 因此提前降级，真实新应用首轮不会进行骨架和功能池召回。仅在 Facade 中使用空 plan manager 的测试无法发现此问题。

独立本地复现先得到 ABSENT，再调用真实 loadForTurn 后得到 UNAVAILABLE/PACKAGE_MISSING。复现使用当时冻结的本轮字节码，没有外部调用；记录见 [empty-project-probe.log](../../../../.codex/enable-multiturn-resource-recall/independent-review/empty-project-probe.log) 和 [执行记录](../../../../.codex/enable-multiturn-resource-recall/independent-review/empty-project-probe-command.json)。该早期字节码用于保留故障证据，不能用于判断当前修复是否生效。

当前修复在同一安全目录句柄内区分完全空目录与任何已有文件/子目录：完全空目录为 ABSENT/EMPTY_PROJECT_DIRECTORY；已有部分文件但清单缺失继续保守拒绝。已核对 CurrentVueProjectReaderTest 的真实 AppPlanStateManager 初始化场景、partial.js 保护场景及 `review-fixes` 通过记录。最终 VueMultiturnResourceRecallE2ETest 使用同一目录的真实计划管理器与兼容读取，验证首次失败、重试成功两轮均执行完整召回，该分支已在最终组合测试通过。

### CR-2 / P2：工程兼容读取未复用完整依赖契约（已关闭）

初始读取只检查依赖名及非空字符串，忽略固定版本和 overrides、optionalDependencies、peerDependencies、workspaces 等可改变依赖图的字段。这样可能把顶层声明 Vue 3、但依赖图已被覆盖的工程当作兼容基准，违反已确认的现有工程依赖约束。

当前修复抽取并复用 VueProjectBuilder.dependencyContractViolation，保留原构建的固定版本校验和禁用字段，同时检查既有禁止的解析文件是否存在。模板文档之间的原版本范围比较保持原样。已核对 `latest`、`*`、范围、其他固定版本、禁用字段与解析文件测试，并读取 CurrentVueProjectReaderTest、VueProjectBuilderTest、AppServiceImplVueTurnTest 的 `review-fixes` 成功记录。

## 当前检查结论

- 普通模式无可靠 JSON/mode 时失败，资源字段独立降级；选中元素固定修改先于只读资源清零，真实异常 cause 保留。未发现额外字段取得执行权限的路径。
- 成功资格查询仅取本应用未删除 ai/SUCCEEDED 的 id，原话提交与权限入口保留；只读策略不会启动资料，后续范围和配额由服务端决定。
- 资料截止只创建一次并受回合剩余期限限制。任务开始、依赖提交和结果发布均检查该范围，冻结后不可注入迟到结果；图片按任务索引汇总，RAG 可发布已完成渠道结果。
- 取消登记使用通用租约且与主模型取消独立。队列项可以移除，RAG 许可依靠物理任务集合归还；没有把 Future 取消直接当成外部调用退出。
- 恢复和续调沿用既有不可变临时消息入口，没有新增 L0/L1 候选存储或跨回合结果缓存。完整请求与两类临时资料 Token 观测仍采用项目本地估算口径。
- 全局模型 JSON 配置及非 Vue 生成入口保留。后续功能格式化不会使用缺骨架初始化提示，文件参考仍按原子块预算处理。

## 已读取证据与限制

早期 t1-routing、t2-policy-fixed、t3-features、t4-images、t5-executor-core、t5-image-cancel、t5-rag-deadline、review-fixes 用于定位实现与修复过程，记录仍保留。最终核对以下原始证据，均为退出码 0、失败 0、错误 0、跳过 0：

| 范围 | 实际执行 | 证据 |
| --- | --- | --- |
| 后端主要回归及连续资源调用链 | 35 类、536 项 | [regression-v2/command.json](../../../../.codex/enable-multiturn-resource-recall/regression-v2/command.json) 及同目录 JUnit |
| 原计划热窗口和恢复事件边界 | 2 类、17 项 | [memory-boundary/command.json](../../../../.codex/enable-multiturn-resource-recall/memory-boundary/command.json) 及同目录 JUnit |
| 新增沙箱注解后的资料复用复测 | 1 类、3 项；属于前述 536 项的重复验证 | [reuse-sandbox/command.json](../../../../.codex/enable-multiturn-resource-recall/reuse-sandbox/command.json) 及同目录 JUnit |
| 前端会话解析、错误与结束状态 | 2 个测试文件、116 项 | [frontend/command.json](../../../../.codex/enable-multiturn-resource-recall/frontend/command.json) 和 vitest.json |

后端不重复统计为 37 类、553 项。`regression-v2` 命令列有 36 个选择器，其中 CompressionAwareChatMemoryTest 在项目中不存在，未产生执行记录，不能计入已执行类数；实际 JUnit 为 35 类。随后补充实际存在的 PlanRestoreWindowTest 和 PlanRestoreEventGateTest 验证了保留的记忆边界。

连续 E2E 使用真实 AppService、Facade、RAG 混合编排、图片收集、AiServices、统一请求门禁、计划/文件/构建工具、流解析和 Finalizer；文件实际写入隔离沙箱。已核对六轮终态 ANSWERED→SYSTEM_ERROR→SUCCEEDED→SUCCEEDED→SUCCEEDED→SUCCEEDED、两次骨架池召回、三次功能池召回、6/6/2 主题的图片请求、同回合资料一致与请求计量一致。后续回合未附加上轮候选，候选标记未直接进入 L0 或模拟数据库，原始工具调用与结果保持配对。

VueResourceContextReuseTest 分别驱动工具续调、协议恢复、未完成工具链恢复，使用真实 Token 估算触发同步摘要；摘要替身输入的来源快照不含候选资料，压缩后仍携带各一份相同资料，两类资料各准备一次。沙箱注解已补齐并复测，避免普通测试运行读取本机应用路径。取消、删除、队列清理及物理调用许可回收继续由聚焦测试和最终回归覆盖。

已读取真实分类 `classification/summary.json`：旧/新各 120 次，共 240 次，模式误判和非法输出均为 0，新版资源字段错误为 0，关键否定样例新增误判为 0；平均耗时约为旧版 733ms、新版 772ms。该结果仅限固定合成样例和当次 deepseek-flash，不等同于所有线上表达均无退化。未重新发起付费调用。

## 最终差异与交接

已复核阶段性报告后的业务差异：Facade 在付费资料准备前检查持久用户边界；OnlineSelection 尊重 hybrid 开关，Dense-only 路径不执行 RRF；准备池保留原线程命名和既有容量；预算观测维持低基数指标。新增测试和夹具运行器均在项目内隔离，模板夹具纳入运行哈希。`git diff --check` 通过，受保护前端测试的内容哈希和暂存状态与实施前一致。

没有开放阻塞，可将本报告与最终源码、测试及原始证据绑定到正式验收。当前验证中的数据库、外部检索/图片和主生成模型均为替身，npm 构建执行器也是替身；不能据此声称已验证真实供应商延迟、真实 npm 构建或部署后的生产环境。真实分类效果另有上述授权范围内的 240 次证据。最终交付判断由 reports/verification.md 汇总，不扩大为重启、提交、推送、部署或数据库操作授权。
