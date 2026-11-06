# Implementation Review: enable-multiturn-resource-recall

- 评审日期：2026-11-06（Asia/Shanghai）。
- Profile：strict。
- 评审者与独立性：`/root/vue_rag_implementation_review`；独立执行上下文，未编写本轮业务代码、测试或规划。独立审阅当前文件、运行日志、JUnit/Vitest 结果及源码快照，只直接交付本报告，没有重复运行 Maven。
- 当前结论：`PASS`。U5 实现满足本次已批准的行为，未发现开放业务代码阻塞；一项文档准确性问题已由主执行者修正并经本评审者复核关闭。本结论是独立代码质量评审，不代替最终验收清单与验收指纹。
- 审查基线：HEAD `b8b164c348a95dbebaf0455d66eed3e26a65dc59` 加当前 U5 工作区修改。核心及组合回归的 `code-state.json` 内容一致，SHA-256 为 `570821e6264f797ee1b14bf7d3c3521fe26530ca10ee2e2b6c36e50e74bf93a3`；所登记源码、测试、构建清单及模板文件均与当前字节匹配。文档修订单独核对，最终 README SHA-256 为 `c23d436eb51d3b9cb09eb0649c69110e6685e9241661982bf94e15431610a895`。

## 评审范围与依据

项目根为 `/Users/lllyw/ai-app-generation`。依据实际 AGENTS.md、实现验收规则、已批准的 `.codex/plans/Vue-RAG检索简化执行计划.md`、当前规划评审及 tasks 第 8 节进行核查。规划等级仍为 strict，U5 只替代原有 RAG 数量、兼容筛选和清单读取约定；首次成功策略、取消隔离、已有工程保护、实际构建依赖契约及同轮上下文边界继续有效。

审查覆盖 RagConstants、VueHybridRetrievalService 的普通/在线/后续/生产与离线 Dense-only 入口、Dense/BM25/RRF、CurrentVueProjectReader/CurrentVueProject、两个结果类型、Facade 与 RagPromptAssembler 的消费路径、README 及相关测试。补查了 PlanStoragePathResolver、SecureFileAccess、VueRagExecutor、ResourcePreparationScope 和实际重排响应校验，确认本轮修改没有绕开它们。

旧独立报告已完整保存在 `.codex/enable-multiturn-resource-recall/vue-rag-selection-before/change/reviews/implementation-review.md`；写入本报告前复核其与旧主报告字节一致，备份 SHA-256 为 `95960a119bf9a32e5826323a8f39e5a5c0764f609d7aca352193ee6afc878172`。旧 7.4 评审和旧兼容性结论不作为新行为的通过依据。

## Findings 与处置

### CR-U5-1 / P2：说明未清晰区分初始和后续召回阶段（已关闭）

初审时 README 将正常混合检索统一描述为 4 次 Milvus 搜索、最多 2 次重排，关闭混合时也统一写成两池 Dense；这会误导读者理解后续功能专用路径的实际请求数量。同一文档还保留“仅首轮”的旧描述，并把不可用目录写成绝对“不注入 RAG”，未说明仍可提供保护已有工程的空资料说明。

主执行者已修正相关段落：初始完整检索为 1 次 Embedding、4 次搜索、最多 2 次重排；后续为 1 次 Embedding、2 次搜索、最多 1 次重排。Dense-only、基础骨架兜底、首次持久化成功边界、每轮资源策略、15/8 秒截止及同轮复用均已明确。不可用目录表述为跳过模板检索并保守保护已有工程，与 Facade 的实际消费行为一致。本评审者复读修订内容，确认没有改变已批准的实现或验收要求；该发现关闭，文案修订不要求重复代码测试。

## 当前检查结论

- **数量与请求一致。** RagConstants 是 8/10/1/4/2 的共享来源。Dense 的 `maxResults(topK)` 和 BM25 经真实 SearchReq 构造的 `topK` 均透传；BM25 固定 10 的独立常量已移除。RRF 默认 10，k=60、双路权重和质量分/ID 同分排序保持。子块聚合后不足不补搜、不凑父模板。
- **筛选完整移除，结构校验保留。** 普通混合、在线部分发布、后续功能、生产/离线 Dense-only 均按排名选取；不存在框架、语言、构建工具、共同依赖或候选依赖白名单筛选残留。目录版本和类型仍在检索通道校验，父文档仍由当前目录回查并去重；重排仍验证数量、index 唯一性/范围及有限分数。模板元数据仍进入语义描述，源码不发送给重排。
- **各阶段与降级受同一上限约束。** 正常重排、关闭重排、重排失败、任一通道失败及在线提前发布使用骨架 1、初始功能 4、后续功能 2；结果类型再次限制功能数量。后续不访问骨架池。当前工程没有骨架候选时继续现状；仅新工程允许固定基础骨架，离线基线不引入兜底。
- **目录识别不读取工程清单。** 通过既有安全目录句柄只调用 `iterator().hasNext()`，不递归、不打开 package.json、不跟随目录条目链接。缺失/空为 ABSENT，安全非空为 AVAILABLE；路径拒绝、目录读取失败、截止或取消为 UNAVAILABLE。活跃状态在目录访问前后核对，`existing()` 对未知状态保守为 true。真实目录测试覆盖部分文件、子目录、悬空内部链接、工程/祖先链接、文件替代目录、读取权限和计划初始化空目录。
- **取消和已有工程保护保留。** 在线渠道回调参与最终链组合，已完成重排不会再被迟到渠道覆盖；发布前检查 scope，Slot 冻结与发布共用关闭边界。执行器继续在排队、许可、执行和接纳结果时检查截止，物理任务退出后才释放许可。Facade 继续通过同一回合边界冻结资料；不因首轮失败重试或缺少清单而授权重建已有工程。
- **实际构建契约未放宽。** VueProjectBuilder 和依赖管理实现未在 U5 修改；当前回归仍验证白名单外依赖、非注册表依赖来源、解析配置与锁文件等在安装前被拒绝。检索参考资格与真实构建权限保持各自职责。

## 已读取证据与限制

原始记录均位于 `.codex/enable-multiturn-resource-recall/`，本评审者核对了 command.json 的退出码、报告实际执行项和当前源码哈希：

| 记录标签 | 实际范围与数量 | 结果 |
| --- | --- | --- |
| `vue-rag-selection-core` | 18 类、205 项：请求参数、RRF、重排 HTTP、编排、目录、提示词、执行器、原生检索、构建器与资料取消 | 退出码 0，失败/错误/跳过均 0 |
| `vue-rag-selection-composed` | E2E 1 项、上下文复用 3 项、Facade 59 项，共 63 项；使用 `--sandbox` 对应的独立 user.dir | 退出码 0，失败/错误/跳过均 0 |
| `vue-rag-selection-boundaries` | 成功终态查询 2 项、AppService Vue 回合 38 项、资源策略 3 项，共 43 项 | 退出码 0，失败/错误/跳过均 0 |
| `vue-rag-selection-frontend` | generationSession 与 AppChatPageGeneratingStatus 两文件，共 116 项 | 退出码 0；Vitest success=true，失败/待执行/todo 均 0，登记的 94 个前端文件均与当前源码匹配 |

初轮 `vue-rag-selection-resume-check` 为 84 项、1 项失败，原因是旧 RRF 测试仍期望 15；该失败原件保留。当前测试改用两路各 8 个不重合候选，验证默认融合前 10 个，同时覆盖显式上限、重复父模板和空结果；后续核心回归已实际通过，不能把初轮失败本身算作成功。

组合测试确实运行了连续六回合、工具续调、同步摘要、协议恢复和候选替换。它串联真实 Java 服务、资料编排、请求门禁、文件/计划工具和终态流程，但数据库、分类/生成/摘要模型、检索提供方、图片提供方及 npm 执行器使用本地替身；不证明真实 Milvus、DashScope 或 npm 构建质量。重排 HTTP 测试只使用本地环回服务。

本次没有新增真实检索质量、召回率、费用或耗时改善的实测结论。历史分类对照仅保留其当时输入和模型条件下的结果；不宣称旧整包验收证据全部仍与当前代码匹配。主执行者已对当前受影响的成功边界和前端行为补测，本报告仅据实引用上述新证据。

## 交接

独立代码质量评审通过，文档发现已关闭。最终 `git diff --check` 返回 0。由主执行者完成当前需求/任务到实现和证据的结构化映射、最终验收报告及机器指纹；本报告不自动归档、提交、推送或部署。
