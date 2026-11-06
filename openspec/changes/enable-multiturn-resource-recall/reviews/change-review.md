# Change Review: enable-multiturn-resource-recall

- 评审日期：2026-11-06（Asia/Shanghai）；U5 请求与规划起始于 2026-11-05。
- Profile：strict。
- Source Coverage：Pass。U5 已确认的数量、兼容筛选移除、目录识别与本地验收要求均有对应规划；被替代的 U4 条款与历史任务已显式界定。
- Pre-Apply Decision：PASS。
- Validation Confidence：已独立阅读当前 proposal、specs、design、tasks、sources 及批准计划，核对关键实现与验证入口；CLI 严格校验通过。此结论仅批准按规划实施，不证明新实现或检索效果已经通过验证。
- 评审者与独立性：子代理 /root/rag_selection_review；未撰写 U5 规划或业务代码，独立执行本轮规划评审并直接交付本报告。
- 核验方式：machine。单一项目根、普通 UTF-8 规划文件及 Markdown checkbox 任务符合现有助手范围。
- 机器记录：reviews/planning-review.json。旧记录不作为 U5 有效基线；主代理在本报告定稿后执行 record-plan、check-plan、start-apply，本评审者未运行这些登记命令。

## 评审范围与依据

项目根为 /Users/lllyw/ai-app-generation，schema 为 spec-driven，元数据 x-workflow_profile 为 strict。当前代码参考 HEAD 为 b8b164c348a95dbebaf0455d66eed3e26a65dc59；评审时 src 与 README 尚无本轮业务修改。

本轮范围为 U5 修订及 tasks 第 8 节：各通道最多 8 个子块、RRF 每池最多 10 个父模板、重排与最终共用 1/4/2 上限；取消 RAG 兼容筛选；目录仅按安全的缺失/空/非空/不可用状态识别；保留现有工程、数据类型、目录版本、去重、取消截止和实际构建依赖契约。

持续约束为 AGENTS.md、共享工作流与来源规则、sources/user-request.md 的有效采纳范围、当前规格设计，以及 .codex/plans/Vue-RAG检索简化执行计划.md。D1—D4 中只读升级、计划/构建、PLAN_RESTORE、近期窗口和同轮资料范围继续有效；相应文件存在，相关条款已核对。旧 U4 的清单兼容读取、候选匹配和旧数量由 U5 替代，未将其作为新的强制要求。

实现参考核对了 VueHybridRetrievalService 的在线与后续入口、CurrentVueProjectReader、CurrentVueProject、安全目录解析器、Dense/BM25/RRF 数量路径、结果类型上限及本地 run_tests.py。源码、测试和 README 是允许按任务修改的参考，不应作为持续冻结输入登记。

第 1—7 节是既往完成记录，旧分类、图片、前端与独立评审结果未在本轮重验。主代理已在 .codex/enable-multiturn-resource-recall/vue-rag-selection-before/change/ 保存原件；本次核对原规划/代码评审报告、planning-review.json、verification.md、verification.json 与修改前当前文件的字节一致。未要求以旧兼容测试或旧整包验收记录证明新行为。

## Blocking findings

无。

规划对本次修改的全部必要选择已给出可实施定义：8 是子块上限；不足不补搜；10 是每池父模板上限；1/4/2 按阶段共用；后续不查骨架；UNAVAILABLE 不视作新工程；非空安全目录内的链接仅作为条目而不跟随；真实构建依赖限制保留。任务 8.1—8.5 未勾选，不存在把当前未实施行为标记完成的问题。

## Warnings

无需要用户重新决定的实质性警告。以下实施与验收边界已由 U5 采纳，不能在交付时遗漏：

- 8/10 减少候选覆盖，取消兼容筛选后不同技术栈的合法模板也会按排名返回。本次只授权本地逻辑与受控 Java 组合回归，不得据此宣称真实检索质量无退化或耗时改善。
- 仅修改 CHANNEL_TOP_K 不足以改变当前 BM25 固定搜索量；任务 8.1 已明确要求实际 SearchReq 透传 topK，并统一 Dense/RRF 默认入口和结果类型内部上限。
- VueMultiturnResourceRecallE2ETest 受 user.dir 条件限制；现有 run_tests.py 的 --sandbox 会创建符合条件的项目内沙箱并设置 user.dir。本轮验收必须检查测试实际执行数量，不能将跳过计为通过。
- 历史 Context/Migration 段描述最初交付，U5 专节及第 8 节限定本轮范围和回退；新的机器记录应绑定当前文件，不得复用旧 6938c2f 参考状态或旧实施开始时间。

## Change Source Coverage 检查结果

| 来源／有效要求 | proposal | specs | design | tasks | 结论与缺口 |
| --- | --- | --- | --- | --- | --- |
| U5：8 子块、10 父模板、重排与最终 1/4/2 | What Changes；U5 行 | 初始和后续 RAG 按相关性与统一上限选取；请求和交付使用同一上限 | Decisions 3 数量集中与各路径 | 8.1、8.2、8.5 | 覆盖，单位与候选不足行为明确 |
| U5：删除候选兼容与依赖白名单筛选 | What Changes | 不再按元数据兼容性淘汰候选 | Decisions 3 普通/在线/后续/Dense-only | 8.2、8.4 | 覆盖，结构与响应校验继续保留 |
| U5：仅安全目录识别，不读取清单 | What Changes | 仅按安全目录识别工程；清单内容不影响RAG资格；空工程和不可用工程的边界 | Decisions 3 状态和类型简化 | 8.3、8.5 | 覆盖，目录链接和已有文件保护可验证 |
| U5 与 U1—U4 保留部分：阶段、兜底、截止取消、同轮冻结、既有构建约束 | 修订范围与既有条目 | 后续资料专用语义、检索渠道失败及既有生命周期 Requirements | Decisions 3、4、6 | 8.2—8.5 | 仅复评本轮影响，其他历史行为继续作为约束 |
| U5：本地核心及沙箱组合验收，真实调用另行授权 | 2026-11-05 修订范围；U5 行 | 相关新 Scenario；既有受控 Java 验收 | U5 修订的实施和回退 | 8.5、交付条件 | 覆盖，历史分类对照不扩大为本次付费调用 |
| D1—D4 有效条款与原记录保留 | Source Inputs 替代关系 | 既有模式、生命周期和资料范围 Requirements | Goals/Non-Goals、Decisions 4/6、U5 回退 | 第 8 节说明及交付条件 | U5 未扩张为模式/图片/存储重构，源路径可读 |

反向追踪：常量集中、BM25 实际请求透传、内部状态类型简化、提示词与日志更新、真实临时目录测试均为实现 U5 所必需；未引入新服务、模型调用、迁移或新的兼容筛选层。

跨产物一致性：proposal/specs/design 对 8/10/1/4/2、目录三态及现有工程保护一致。旧任务 3.1、3.2、5.1 已通过任务首页与 U5 来源说明作部分替代；历史完成状态不代表新行为通过。新的独立实现评审和最终核验作为交付条件，不构成第 8 节任务的循环前置。

## 实际校验

工作目录：/Users/lllyw/ai-app-generation。

- openspec validate enable-multiturn-resource-recall --type change --strict --json：退出码 0；1 项通过、0 项失败、issues 为空。
- git diff --check -- openspec/changes/enable-multiturn-resource-recall：退出码 0。
- 已检查规划文件普通文件类型、有效来源路径与历史快照原件；未运行业务测试、真实 Milvus、模型、部署或环境切换。

CLI 通过仅证明其结构检查范围；本报告的 PASS 同时依据独立的语义、来源与实施可行性审阅。

## Apply handoff

可按已授权范围进入第 8 节。主代理先对当前规划、有效持续约束及本报告登记 record-plan/check-plan，再在业务修改前执行 start-apply；strict 的 report 与 independent 使用本报告路径。

实现顺序建议为 8.1 统一参数 → 8.2 与 8.3 完成选择和状态简化 → 8.4 更新消费侧说明 → 8.5 核心与沙箱组合回归。新回归必须覆盖实际请求参数、部分结果配额、异常降级、目录检查期间取消、危险路径和已有工程提示；实际构建白名单仍要通过既有测试。

验收必须取得当前实现的独立代码质量评审，并按当前相关输入判定历史证据能否复用。旧结果不匹配时应补测授权内相关项或明确列出缺口，不重新执行未授权的真实调用。报告不能把本次规划 PASS 写成实现或效果验收通过。
