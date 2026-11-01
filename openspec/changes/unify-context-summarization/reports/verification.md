# 统一上下文摘要实现验收

- 变更：`unify-context-summarization`；日期：2026-11-01（Asia/Shanghai）。
- 风险等级：`strict`；schema：`spec-driven`；核验方式：`machine`。
- 当前结论：**Ready for Archive**。本轮 96 项聚焦回归、4 组文件清单输出复核、当前源码核对和独立代码评审全部通过。前次 E2E-01／02／03、PC-01、SF-01／02／03 的证据仅按未变化范围复用；35 项必需任务的持续要求保持不变。
- 前次修复证据：[修复复验报告](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/修复复验.md)。[上轮失败报告](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-082443-c772a7/端到端验收.md) 保留，已由本轮修复与复验关闭。此结论不表示已经归档或部署。

- 本次证据：[104 聚焦回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/104-summary-written-files-final-regression/command.json)、[106 文件清单输出复核](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/106-summary-written-files-real-model-checked/semantic-review.md)。

## 当前范围与基线

沿用有效的 [规划基线](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/planning-review.json) 和已登记的实施开始记录，未修改已确认的需求、预算、验收标准或非目标范围。规划持续约束已通过 `check-plan`；实际 schema 已通过 `openspec validate unify-context-summarization --strict`。

本次实现统一历史与当前完整工具批次的 L1 摘要，保留当前用户原话和按 Token 选择的近期原文；通过摘要修订、来源 epoch、消息序号及读取下界保证请求完整衔接。每次请求只读刷新当前计划，临时资料保持原回合生命周期，关键决策按原始来源独立留存。可信终态继续由后端保存，已覆盖的旧结果不再重复独立注入。

实现涉及 `src/main/java` 的记忆、计划、流式请求、收尾与历史服务，相关测试位于 `src/test/java`。初始化 SQL、增量迁移及 README 已同步。没有新增框架、全量工具事件数据库、独立决策模型或跨实例分布式协调。

当前代码基线为 HEAD `98db328` 加 5 个 Java 实现与 4 个测试文件的修改；相对 099／100，本轮只修改三个摘要模板。104 与 106 各记录 776 项当前代码状态。错误分类、可信收尾、覆盖、裁剪、数据库提交与构建执行控制流未在本轮改变，旧证据只在对应未变化范围复用；当前提示词由新回归和定向模型样本补验。最终机器记录绑定当前代码、前端、配置、规划、原始证据和独立评审。

## 当前累计写入文件清单与接口删除（任务 4.3）

用户本轮明确要求：“已完成要包含所有已经写入文件的名字 跟检查点一样 不要写完成了什么什么 把续做接口删除了”。本轮细化已有进度段的内容取舍，不恢复旧检查点，也不改变五段结构、3,072 Token 上限、覆盖或存储契约。

- 主摘要和 reducer 的“已完成”累计保留已覆盖材料中确认成功写入或修改的全部相对路径，按完整路径去重；同名不同目录分别保留。只读增量和后续不再涉及不能作为省略旧路径的理由，不以目录、“等文件”或数量替代具体路径。
- 删除旧“续做接口”条目及导出名、签名、字段等源码摘录，也不将它们移到其他段落。“已完成”不写功能完成叙述；已有用户要求、关键决策、必要报错标识和未验证状态仍按来源保留。
- 计划、仅读取、失败和拒绝不能冒充写入。清单表示累计写入记录，不新增当前磁盘存续清单或自动删除历史路径的规则。
- 104：11 类、96 项全部通过，失败／错误／跳过均为 0。106：四组真实输出为 570／673／722／482 Token，准确文件集合为 22／22／22／0；6,830 Token 的二次压缩输入缩至 722，22 个路径全部保留。
- 106 的首组严格比较请求与提示词后复用 105 的真实响应，另外三组为新调用。103 四次、105 一次、106 三次，本轮累计 8 次实际模型调用。103／105 的失败源于核验器跨条目提取、将拒绝写入信息误判为读取流水及未识别同义标签；原始非零退出码和输出保留，未回改为通过。

这是模型提示词的内容规则，不是确定性的全量文件名校验。旧摘要已丢失且本次材料未提供的路径不能凭空恢复；四组样本不能保证任意规模清单都能在固定预算内无损保留。本轮未重写业务摘要、未运行完整浏览器或持久化链路，也未证明业务回读次数已经下降。

## 前次摘要事实边界与结束原因（099／100，历史证据）

用户已确认：摘要应记录已有事实而不自行诊断或派发整改；后端具体错误原因应进入现有可信记忆，终态、原因和构建状态分别表达。实现不新增schema、资料回读、自动构建、固定收尾摘要或业务数据改写。

- SF-01：有类型的上下文压缩失败与输入上限拒绝写入固定白名单原因，异常自由文本隔离；未知原因写未记录。新增测试经过实际处理器、收尾器、历史保存接口和后续摘要材料读取，客户端文案与终态协议保持兼容。历史服务使用替身，不宣称本轮运行了真实数据库。
- SF-02：摘要及reducer禁止自行审查代码、新增整改和构建前置条件；已有模型诊断保留来源和验证状态，用户/结果记录编号不混用。读取失败可作为已有问题保留，成功读取不列为历史流水；部分覆盖不能宣称整轮状态。
- SF-03：没有完整构建结果时不宣称未调用或实际0次。`BuildProjectToolTest` 的真实工具类后处理失败反例证明租约已有attempt=1而返回attempt=null；投影保留拒绝事实并显示未知。有部分已知次数时保留下界，取消或后继缺失结果不能把下界当总次数。成功终态且末条构建结果成功时保留兼容的精确次数。
- 099：17类、242项，失败/错误/跳过均为0。100：四组真实合成样本638/418/663/532 Token；6,636 Token的二次压缩输入缩至532，仍保留“至少2次、总次数未知”、错误原因及接口。
- 096/098的机器检查通过但存在人工发现，原始记录保留，最终由100关闭。本轮累计12次合成模型调用；未跑完整浏览器/业务生成链路，不证明业务回读次数已下降或企业官网已构建完成。

## 前次摘要内容细化（091/092，历史证据）

当时承接用户在本会话确认的内容优化与“去掉历史读取”，没有新增五段标题或改变 3,072 Token 上限。当时允许保留后续所需精确接口，这一内容策略已被本轮“删除续做接口、完整保留写入文件名”的要求替代。来源区别、真实终态、历史覆盖范围和验证边界继续沿用。

- 091：11 类、103 项全部通过，失败/错误/跳过均为 0；覆盖提示词构造、格式、预算、草稿、来源、分层和请求门禁。
- 092：4 组真实摘要样本分别覆盖失败/成功读写、新旧接口与终态、超限二次压缩、只有未实施计划。输出为 846、965、884、500 Token；6,276 Token 的摘要压到 884 后保留接口与验证边界。
- 090：机器结构检查通过但人工发现“任务重述为理由”和“否决段解释工具错误”，后继规则及 092 关闭；原始记录保留，不记为全部语义通过。
- 独立评审复核 091/092、源文件哈希与实际输入输出；代码审查结论见现有固定评审报告。

这些样本直接使用生产提示词构造器、格式校验和估算器，并调用同配置模型；没有运行完整持久化/浏览器链路，不证明真实应用的重读次数已下降。前次真实迁移、存储、取消和跨回合控制流证据只按未变化范围保留。此处采用定向验证，不扩大费用或运行环境范围。

## 需求与场景覆盖

[结构化验收清单](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reports/verification-manifest.json)逐名覆盖 **22 个 Requirement、76 个 Scenario、35 项任务、9 项缺陷修复和 1 项摘要内容细化**，共 143 个条目。本轮累计文件清单与接口删除补充既有任务 4.3 的用户要求及证据，不改任务正文。每个条目关联实际实现、测试文件和原始运行证据；详细对应关系只在该清单维护。

已重点核对以下边界：完整工具配对和当前用户保护；部分前沿及完整历史游标不倒退；MySQL 先可靠提交、Redis 后按来源回收；旧 L1／新窗口整对重读；同一副本估算与发送；终态、证据和摘要覆盖的职责分离；旧格式及新 epoch 重建；用户归属、删除栅栏与迟到回填；临时反馈有效送达；非 Vue 模式及独立评估链隔离。

## 实际验证

所有下列成功运行退出码均为 0，JUnit 失败、错误及跳过均为 0。命令完整参数、工作目录、时间及代码状态见各运行的 `command.json` 和旁边的原始日志。

| 验证层 | 本次采用的证据 | 结果 |
| --- | --- | --- |
| 当前聚焦回归 | [104](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/104-summary-written-files-final-regression/command.json) | 11 类、96 项通过；覆盖摘要格式、预算、增量草稿、来源、终态及请求门禁。 |
| 当前文件清单真实输出 | [106](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/106-summary-written-files-real-model-checked/semantic-review.md) | 4 组输出通过，文件集合 22／22／22／0；3 次新调用、1 份相同请求的既有响应。 |
| 前次事实边界与可信收尾 | [099](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/099-summary-facts-final-regression/command.json)、[100](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/100-summary-facts-real-model-final/semantic-review.md) | 17 类、242 项及 4 组样本的历史证据；收尾控制流未变，旧接口保留策略不再作为当前要求。 |
| 真实迁移 | [005](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/005-mysql-migration/command.json)，`UnifiedSummaryMysqlMigrationTest` | 3 项通过；随机测试库验证新旧数据、可重入执行及不匹配结构。迁移实现未再改变，保留原证据，不重跑既有业务库。 |
| 历史真实 MySQL／Redis | [073](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/073-final-real-storage/command.json)，`UnifiedSummaryStorageIntegrationTest,UnifiedContextStorageOnlineIntegrationTest` | 20项通过，按未变化的提交、回滚、回收和读取交错范围复用，不证明新版记忆文案已在真实存储链路重跑。 |
| 前次应用与记忆回归 | [087](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/087-cancellation-memory-final-regression/command.json) | 38 类、657 项通过；覆盖 Vue／HTML／MULTI_FILE、取消／超时／删除、前置失败、终态与 SSE、工厂、冷重建、计划、分层与完整请求门禁。 |
| 来源和计划协议补验 | [074](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/074-source-plan-contracts/command.json) | 5 类、30 项通过；包括空计划拒绝、版本与依赖检查、计划构建闸门和来源配对。 |
| 计划证据修复 | [071](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/071-plan-evidence-null-fix/command.json) | 6 类、45 项通过；读取、计划与构建事实不会冒充文件变更，也不会因空路径导致存证异常。 |
| 摘要启动竞态及相关回归 | [077](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/077-late-summary-start-fix/command.json) | 7 类、210 项通过；许可等待后超过原截止或收到中断均无迟到模型调用、无调用计数增加。 |
| 上阶段真实模型与 npm／Vite | [078](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/command.json) | 1 项完整长任务通过；实际生产请求链、真实模型、真实存储及文件／构建工具，随后执行下一用户回合。 |
| 前次真实浏览器／模型 | [本轮审计](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/command.json) | 7 个浏览器请求对应 20 项证据核对通过；真实 HTML 交互、Vue 长任务、取消与重启复验。审计命令核对已执行的浏览器动作及保存证据。 |
| 预提交清理修复 | [086](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/086-precommit-cancellation-idempotence/command.json) | 3 类、93 项通过；清理完成／失败后重复等待、等待者取消隔离与提交前取消保持正确。 |
| OpenSpec 结构检查 | [079](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/079-final-planning-contracts/command.json) | `openspec validate unify-context-summarization --strict` 通过。它只作为结构检查，不代替独立评审或实现验收。 |

[088 前次格式检查](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/088-e2e-fix-format-check/command.json) 已确认工作区与暂存区的 `git diff --check` 均通过。

[107 当前一致性与格式检查](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/107-summary-written-files-final-check/command.json)核对 104／106 的 776 项源码状态、96 项 JUnit、四组文件清单检查以及复用响应的请求／正文原字节；同时检查工作区、暂存区差异格式和 OpenSpec 结构。[101](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/101-summary-facts-final-check/command.json) 与 [094](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/094-summary-content-final-check/command.json) 作为各自旧版本的历史证据保留，不称其指纹与当前一致。

这些测试层存在重叠，不相加为独立场景总数。071、077、078 保留上一阶段修复范围的证据；本次取消、清理与来源身份变化由 087 和本轮真实浏览器补验。其他旧结果仅在相关实现／测试／配置输入未变化的范围内复用，073 的事务／存储和 005 的迁移实现未变。073 使用可控草稿，不充当真实摘要模型调用证据；078 不再充当当前完整端到端通过记录。

### 前次真实浏览器修复复验（历史证据）

本节“本轮”指98db328之前完成的取消与记忆修复复验，不表示采用当前提示词重新运行了浏览器。当前文件清单提示词语义以106的定向样本补验。

本轮 [20 项核对](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/checks.json) 全部通过，详细过程和三项修复在 [修复复验报告](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/修复复验.md) 统一维护。核心结果如下：

- 压缩中取消后 0.288 秒观察到输入恢复；SSE 收到唯一 `CANCELLED` 与 `done`，实际 10 次 `readFile` 正确保存在可信结果，取消后无迟到主模型／摘要请求。
- 重启后观察 101.735 秒，模型请求数 64 → 64、文件未变；新请求修复旧孤立回合，保留新 epoch 和摘要依赖 revision 13，正确区分相邻回合。追问缺失进度时回答“无法确认”“未知”，没有重读文件；这是本次样例的语义结论，不是任意回答保证。
- Vue 长任务包含计划 v1 → v2、缺组件的真实构建失败与补齐后的成功；22 次主请求、6 次实际 L1 摘要调用、3 组压缩事件，六次工具来源终点 19 → 21 → 27 → 29 → 35 → 37。收尾后 revision 7 含终态登记，旧历史夹具游标为 20。近期原文保留 38～49，下一回合可复述第 15 批标记及关键决策。
- 7 个浏览器请求共产生 72 次真实模型请求：主流式 42、L1 13、L2 7、路由／命名 10。7 次真实 L2 输出均为 `operations: []`，相关记忆表为空、抽取游标无失败。取消／中断阶段摘要调用不全部计为成功提交。

### 上阶段真实模型长任务（078，历史证据）

[078 验收数据](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/acceptance.json) 和同目录的原始请求／响应证明：

- 使用项目配置的 `deepseek-flash`、生产 `ConservativeChatTokenEstimator`，保持 49,152／57,344／65,536 阈值、12,288 原文软目标、3,072 摘要上限及每次准入原 60 秒截止；未降低阈值或替换估算器。
- 6 次有效语义摘要分别提交 r1～r6，覆盖序号递进为 **17、19、25、27、33、35**，均属于同一尚未结束的持久用户回合，完整历史游标保持零。共 6 次实际摘要调用，未把缓存或任务复用计成新调用。
- 23 次主模型请求的完整估算范围为 6,744～53,252 Token，发送消息与同次门禁决定逐项相等；当前计划只出现一份，创建、修订、文件状态及只读下一回合读取均反映最新提交状态。
- 实际创建计划 v1、修改标题、执行构建；第 1 次在 `NPM_BUILD` 阶段因缺少 `MissingBanner.vue` 失败（退出码 1）。修订为 v2 并补齐组件后，第 2 次真实构建成功（退出码 0），生成 `dist/index.html`。
- 收尾不额外调用摘要模型。下一用户回合的 [原始回答](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/tmp/real-model/cross-turn-answer.txt) 正确复述不使用轮播图及移动端遮挡理由、保留品牌提示的理由、v1→v2、首次失败和最终成功，并引用第 15 批原文标记。实际请求保留该工具结果，可信终态进入后端状态通道，没有重复作为独立 AI 结果追加。
- 主模型与摘要模型共 29 份响应分别保存，与实际请求次数一致。工程、npm 缓存、构建命令和原始证据均在项目 `.codex/` 下。

### 失败记录与处置

本轮[独立实现评审](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/implementation-review.md)结论为 **PASS**，由未编写本轮业务代码或测试的评审者更新固定报告，主执行者未改写其结论。评审者从原始成功写入材料独立重算四组文件集合并核对 104／106、105 响应复用及 103／105 误判；WF-01 的额外删除规则已撤销。前次 SF-03（独立评审 FB-01）由 099 确定性回归及 100 未知／下界样本关闭；历史 IR-01／IR-02、E2E-01／02／03 及 PC-01 的关闭证据保留。上一阶段两项发现：

1. **IR-01**：计划证据对空 `changedRelativePath` 查询不可变列表导致异常。070 已真实复现；修复为先判路径非空再关联文件，071 与最终真实链路通过。
2. **IR-02**：等待启动许可期间耗尽截止，返回后仍可能新发摘要调用。076 的截止和中断两个用例均在旧实现复现；修复在实际调用及计数之前复检原截止和中断，077 与最终真实链路通过。

064 的通用 record 序列化和未知 epoch 放行预期、069 的 npm 配置文件复用，以及 067、075 的测试编译错误，均保留原日志并由后继运行纠正；未通过放宽来源校验、降低预算、跳过测试或伪造模型结果换取通过。早期失败不计作当前成功证据。

本轮 081／082 保留取消流及来源身份的旧实现复现，但其中事实夹具的 final 类 mock／不合法协议错误不算事实缺陷证据；083 用合法协议复现两项空事实错误。085 的 621 项中 1 项暴露提交前重复清理，已通过 `preCommitCleanupOnce` 修复，并由 086／087 关闭。原始失败与后继通过记录均纳入清单，不把失败运行计作当前通过。

前次093/094为新增测试夹具的Long字面量编译失败，095修正；096/098虽然机器格式检查通过，但存在读取流水、无终态原因及结果编号误称等内容问题，分别保留语义复核记录并由099/100关闭。本轮103/105的核验器误判另有原始输出和语义复核，最终文件清单通过依据为104/106，不隐藏中间失败。

## 环境、交付与回退边界

- 本轮仅继续修改三个摘要模板，没有重启现有后端或改写业务摘要。重新加载程序后，后续生成和压缩使用新实现；已保存的 L1 不批量重写。

- 本机 MySQL `127.0.0.1:3406` 只创建并清理 `context_summary_it_*` 随机库；Redis `127.0.0.1:6379`、数据库 0 只使用随机测试应用的三个键。每项测试结束都核对删除结果，没有修改既有业务库。
- 本轮真实浏览器使用合成诊断材料、真实项目模型／L2／存储及隔离工程，不使用 L2 替身；078 历史夹具的 L2 与工厂失效通知采用替身，其证据边界仍保留。本轮覆盖指定的 HTML、Vue 生成与记忆链路，未覆盖 Vue 首轮 RAG／图片准备或部署，MULTI_FILE 取消由确定性回归覆盖，不宣称全站验收或任意摘要无损。
- 本轮隔离前后端、代理、随机 MySQL 库和临时 Redis 容器已按 [清理记录](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-102302-cceb33/cleanup-result.json) 清理，账号凭据已删除。日常 5173／9025 服务未重启，3406／6379 服务保持正常；没有清空共享 Redis。
- 保留单后端实例边界；重启只使用已持久化历史、证据、L1/L2 和当前工程文件重建，不自动续跑工具，不承诺恢复未摘要的完整工具轨迹。
- 发布时必须避免新旧写入器混用。只加列但未写入新格式，与已经写入新覆盖／证据／L0 格式，是不同回退阶段；按 [迁移说明](/Users/lllyw/ai-app-generation/sql/migrations/README.md) 执行，不能靠清空摘要或证据回退。
- 原始证据保留在 `.codex/unify-context-summarization/runs/` 和 `e2e/`，不因本地提交自动进入 Git；跨机器复核需要这些原始文件。未自动推送、归档或发布，保留用户的无关修改。

最终 [机器验收记录](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reports/verification.json) 由标准助手生成并用 `check-verify` 核对。其输入包含本报告、独立评审、完整映射、相关代码及原始证据；后续修改这些输入时必须重新评估受影响验证。
