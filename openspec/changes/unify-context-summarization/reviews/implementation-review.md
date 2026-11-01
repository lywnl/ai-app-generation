# 统一上下文摘要独立实现评审

- 评审日期：2026-11-01，Asia/Shanghai。
- 变更：`unify-context-summarization`；Profile：`strict`；schema：`spec-driven`；核验方式：`machine`。
- 本轮评审者：独立执行上下文 `/root/e2e_fix_review`，未编写本次业务代码或测试，只修改本报告。前期评审者 `/root/independent_plan_review` 的 IR-01／IR-02 关闭记录在下文保留。
- **代码质量结论：PASS。** 本轮累计写入文件清单与删除接口摘录的调整已独立复核，当前未发现尚未关闭的实现阻塞项。额外加入的删除后剔除路径规则已撤销；103／105 的核验器误判已厘清，最终 106 四组样本通过。下文保留此前事实边界、摘要内容、取消、重启及提交前清理的评审记录。本报告不代替 `Ready for Archive`，也不将摘要定向样本表述为完整端到端验收。
- 当前实现基线：`98db32892caba9aa68d12d9c67c1edcdf873279a` 及既有未提交修改，以 [104 代码状态](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/104-summary-written-files-final-regression/code-state.json) 和 [106 代码状态](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/106-summary-written-files-real-model-checked/code-state.json) 为准。分别独立重算 **776 个条目**，均与工作区一致；相较 100，本轮仅修改三个摘要提示／模板文件，其余 773 个条目一致。099／100 的整体指纹已不是当前基线，只按未变化范围复用其证据。
- 规划基线：独立执行 `evidence.py check-plan --root . --change openspec/changes/unify-context-summarization --profile strict` 返回 `valid=true`；[规划记录](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reviews/planning-review.json) 中原实施开始时间为 `2026-10-31T13:41:25.274494+00:00`。本轮没有改写规划、任务或机器记录。

## 本轮累计文件清单复核

本轮依据用户最新明确要求：已完成项包含全部已经写入文件的名字，不描述完成了哪些功能，并删除续做接口。因此，前期允许保留接口摘录的内容口径已被本次要求替代；历史记录保留用于追踪，不再作为当前输出要求。评审者只更新本报告，未修改业务代码或规划，未运行 Maven、模型调用、数据库或环境操作。

### 内容及事实边界

[MemorySummaryPromptBuilder](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/MemorySummaryPromptBuilder.java:35) 将已完成项限定为已覆盖材料中的累计成功写入／修改记录，逐一保留完整相对路径并去重；同名不同目录分别保留，旧文件即使本次未修改或后续无关也不删减。计划、仅读取、写入失败和拒绝不能作为写入依据，也不能从功能概述猜补文件名。已完成只列路径，功能验证及构建状态仍另行表达。

[SummaryCompressionPromptBuilder](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/SummaryCompressionPromptBuilder.java:15) 同步要求保留全部已有成功路径，只去重和紧凑排列，不用目录、通配符、“等文件”或数量代替具体名字。主摘要及 reducer 都删除旧续做接口和源码接口摘录，不能转移到其他段落；已有报错定位需要的标识符仍可保留。[MemorySummaryFormat](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/MemorySummaryFormat.java:10) 只调整段落提示，五标题和格式校验算法未变，3072 Token 上限保持不变。

此前的来源区分、诊断验证状态、部分覆盖、用户／结果记录身份，以及构建次数“未知／至少”的约束继续保留。本次仍经现有 L1 摘要路径生成内容，没有新增文件清单持久化或改变覆盖、裁剪与终态流程。

### WF-01 · 额外维护当前存续文件的规则 · 已撤销

初稿曾要求出现成功删除记录后从已完成清单剔除路径。独立复核指出它还存在删除与后续写回的顺序歧义；进一步按本次用户要求确认，目标是累计已写入记录，而非维护当前仍存在的文件集合。主摘要和 reducer 已撤销该额外规则，并明确这是已覆盖材料中的写入记录。当前没有发现同类范围偏差。

### 当前证据与核验器边界

| 证据 | 独立核对结果 |
| --- | --- |
| [104 最终回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/104-summary-written-files-final-regression/command.json) | **11 类、96 项通过**，失败／错误／跳过均为 0；覆盖提示、格式、契约、草稿、材料、覆盖、终态及请求门禁。776 个代码指纹匹配。 |
| [106 样本 01](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/106-summary-written-files-real-model-checked/tmp/files-probe/01-all-written-paths-and-deduplication/summary.md) | 570 Token，22 个路径完整且各出现一次；旧 8 路径与新增 15 次成功写入去重后恰为 22。`src/views/HomeView.vue` 与 `src/admin/HomeView.vue` 分别保留；读取、计划及拒绝文件未混入。无功能概述或旧接口摘录。 |
| [106 样本 02](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/106-summary-written-files-real-model-checked/tmp/files-probe/02-read-only-increment-preserves-old-files/summary.md) | 673 Token，只读增量后仍保留全部 22 路径，没有把旧清单误判为本轮新增写入，也未从读取内容重新摘录接口。 |
| [106 样本 03](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/106-summary-written-files-real-model-checked/tmp/files-probe/03-reducer-preserves-all-files-and-removes-interfaces/summary.md) | 格式有效的 **6830 Token** 输入压至 **722 Token**；22 路径仍完整且无重复，重复规则和追加的旧接口摘录已删除，未验证与未知状态保留。 |
| [106 样本 04](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/106-summary-written-files-real-model-checked/tmp/files-probe/04-no-successful-write-does-not-invent-files/summary.md) | 482 Token，已完成清单为空；`NOT_FOUND`、`REJECTED` 及模型自称完成均未变成成功写入依据，必要问题定位和未验证状态仍保留。 |

上述路径集合由本评审者直接从旧摘要的成功清单和新增成功工具结果重算，再与四组输出分别比较，未只采信运行器的通过字段。106 的输入、提示、请求、原始响应和摘要均已逐组读取，保存的请求与提示、摘要与响应相符。

[103 的退出码 1](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/103-summary-written-files-real-model/command.json) 来自核验器：完成条目提取越界，把后续同级错误条目和模型自述也算入；同时把拒绝写入的 `changed:false` 误判为成功读取流水。其第四组正文实际明确无成功写入，前三组也保留了完整 22 路径。[105 的退出码 1](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/105-summary-written-files-real-model-final/command.json) 则来自标签仅匹配“已完成”，漏掉等价的“已累计成功写入的文件有（已去重）”。两次失败及原始输出均保留，属于核验夹具误判，不记为业务实现失败或未运行的后续样本通过。

106 首组复用 105 的真实响应：独立逐字节比较了 `input.json`、`prompt.txt`、`request.json`、`response.json` 和 `summary.md`，五者均一致。106 记录 **3 次新增调用、1 次复用、4 组核验**；本轮实际共 **8 次模型调用**（103 四次、105 一次、106 三次），没有把复用算成新调用。

本次为同配置真实模型上的合成材料验证。文件清单仍由模型按提示生成，生产流程没有新增确定性的逐路径完整性校验；样本中的 22 路径通过不证明任意数量路径或任意多轮压缩都能无损保留，既有摘要已丢失的路径也不能凭功能描述恢复。格式与 Token 门禁不能单独证明文件清单完整。当前服务尚未重启，本次没有真实业务持久化或浏览器链路验证，不据此宣称已经上线或减少了实际读取次数。

## 上一阶段摘要事实边界复核（093～100）

以下保留该阶段发现、处置及证据。099／100 在当时对应完整基线；其事实分类、取消收尾等未变范围继续可复用，接口保留相关旧口径已由本轮用户要求替代。

本轮按已有效规划任务 4.3／6.1 修复事实边界。实现范围为三个摘要提示／模板文件，以及 `VueTurnMemoryProjection`、`JsonMessageStreamHandler`；测试范围增加了投影、收尾到摘要材料的连通、实际工具类的后处理失败分支（构建器为替身）和取消场景。评审者没有参与业务代码或测试编写，仅更新本报告；未启动服务、调用模型、访问业务库或运行 Maven。下述运行结论来自独立读取命令、JUnit XML、代码指纹、合成输入及模型原始输出。

### 服务端原因与模型诊断分离

[JsonMessageStreamHandler](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandler.java:299) 按 `ModelRequestGateException.status()` 将 `COMPRESSION_FAILED` 和 `HARD_LIMIT_REJECTED` 映射到服务端枚举。计划初始化、工具循环和资源限制也采用固定分类；其他系统异常保留“原因未记录”。[VueTurnMemoryProjection.systemError](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnMemoryProjection.java:36) 只接收枚举，既不复制异常自由文本，也不从模型说明或源码提取系统错误原因。

已核对门禁编排器将有类型的拒绝异常传到流处理器的实际调用链。新 [JsonMessageStreamHandlerTest](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandlerTest.java:162) 使用实际收尾组件和 `SummaryMaterialReader`，确认分类进入既有 `memoryMessage` 并被后续摘要读取，伪造异常正文不进入记忆，展示文案、终态、预览刷新条件及单次保存保持原约定。该连通测试的存储依赖为替身，不冒充真实数据库验收。

### FB-01 · P2 · 缺失构建结果被误写为未调用或精确次数 · 已关闭

本轮最初把无 `buildAttempt` 时的默认状态改为“未调用”，仍输出次数 0。独立复核发现实际反例：[BuildProjectTool](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/tools/BuildProjectTool.java) 已调用构建器并提交尝试后，若错误净化等后处理失败，会返回 `REJECTED` 且 `attempt=null`。它可以与租约中的真实尝试 1 同时存在。取消也可能使 `AiServiceStreamingResponseHandler.commitToolResult` 将已执行结果替换为取消说明，事实采集因而没有该次构建的可解析结果；等待静默并不会补回缺失次数。已有尝试 1、后继尝试结果缺失时，也不能把 1 宣称为整轮总次数。

当前 [投影聚合](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnMemoryProjection.java:43) 在无构建结果时记录“尚无构建结果记录”，没有真实尝试序号时记录“未知（未记录真实尝试）”。有明确序号但缺少可靠成功终态时，记录“至少 n 次（依据已记录结果）”，构建状态也限定为已记录结果；后继 `buildProject` 即使 `attempt=null`，其拒绝或取消状态仍被保留。这样不把结果缺失解释为没有执行，也不需要新增租约或数据库查询。

只有回合终态为 `SUCCEEDED` 且最后一条构建结果确认为成功时，保留原来的精确成功次数。该分支有现有租约状态机支持：成功后 `beginBuild()` 拒绝后继构建，当前结果携带该回合的尝试序号，不能与上述中途取消或结果缺失分支混用。

[BuildProjectToolTest](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/ai/tools/BuildProjectToolTest.java:431) 的实际工具后处理失败分支现同时验证租约尝试为 1、返回拒绝结果以及投影次数未知；[VueTurnMemoryProjectionTest](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/core/handler/VueTurnMemoryProjectionTest.java:180) 覆盖已记录失败 1 后再收到无次数的拒绝结果；取消测试确认保留已记录次数的下界。099 全部通过，关闭本项阻塞。修复没有补造缺失结果，仍不保证恢复已丢失的精确整轮次数。

### 摘要的事实、覆盖与身份约束

[主摘要提示](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/MemorySummaryPromptBuilder.java:32) 与 [二次压缩提示](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/SummaryCompressionPromptBuilder.java:11) 均明确：摘取已有接口不等于进行代码审查；不能据源码自行诊断、验证功能、认定修复成功或增加整改及构建前置任务。已有诊断继续保留来源和验证状态，模型怀疑不能升级为服务端确认。构建次数的“未知”“至少”“依据已记录结果”等限定语不得丢失，并列终态与构建状态不得改写成因果。

覆盖说明只保留用户回合与材料截止，不列成功读取路径、次数或流水；接口路径和已有未解决错误的定位信息仍可保留。“用户记录”确定回合，“真实终态记录”确定该回合的 AI 结果记录，两者不能互换。部分回合只能声明已覆盖材料内的情况，尚无终态时说明“尚未覆盖回合结果”，不能填造已经结束的原因。

五标题、3072 Token 上限及格式校验算法保持不变；覆盖推进、裁剪、持久化结构、终态赢家、API／SSE 结构和客户端结束文案未改变。新增提示仍进入原完整请求预算检查，历史材料仍保持引用身份。

### 本轮运行与中间发现

| 证据 | 独立核对结果 |
| --- | --- |
| [099 最终回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/099-summary-facts-final-regression/command.json) | **17 类、242 项通过**，失败／错误／跳过均为 0；覆盖投影、工具、收尾、取消、提示、摘要材料与请求门禁。776 个代码指纹匹配。 |
| [100 样本 01](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/100-summary-facts-real-model-final/tmp/facts-probe/01-cause-without-code-audit/summary.md) | 638 Token；提取 `cases`／`useI18n()`，没有据源码缺口自行派发修复任务；保留压缩失败原因及构建次数未知。 |
| [100 样本 02](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/100-summary-facts-real-model-final/tmp/facts-probe/02-unknown-cause-and-partial-coverage/summary.md) | 418 Token；正确区分用户记录 10 与 30，后者仅描述部分覆盖；旧摘要中的“因未构建而系统错误”未被沿用，未列读取流水。该用例显式提供旧终态的“未调用／0”，验证兼容及不补因果，不是当前投影生成的文案。 |
| [100 样本 03](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/100-summary-facts-real-model-final/tmp/facts-probe/03-preserve-existing-diagnosis-as-unverified/summary.md) | 663 Token；保留已记录构建错误、成功写入与未验证的模型怀疑；明确重新构建结果尚未记录、尚未覆盖回合结果，没有把写入成功当成修复已验证。 |
| [100 样本 04](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/100-summary-facts-real-model-final/tmp/facts-probe/04-reducer-keeps-cause-separate/summary.md) | 格式有效的 **6636 Token** 输入压至 **532 Token**；保留压缩失败原因、“至少 2 次”以及整轮实际次数未知，未将下界缩成精确总次数。 |

本轮事实边界验证累计 **12 次真实合成模型调用**：096、098、100 各 4 次。[096 样本 02](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/096-summary-facts-real-model/tmp/facts-probe/02-unknown-cause-and-partial-coverage/summary.md) 仍列两次成功读取及其 `APPLIED/changed:false`，[样本 03](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/096-summary-facts-real-model/tmp/facts-probe/03-preserve-existing-diagnosis-as-unverified/summary.md) 在未覆盖终态时填“结束原因未记录”；[098 样本 02](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/098-summary-facts-real-model-final/tmp/facts-probe/02-unknown-cause-and-partial-coverage/summary.md) 又把结果记录 20 误写成“回合20”。这些中间发现均按未通过项保留，不能因命令退出码为 0 就宣称语义全部通过。100 对应输出已修正。[093-summary-facts-regression](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/093-summary-facts-regression/output.log) 和 [094-summary-facts-regression](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/094-summary-facts-regression/output.log) 则是测试夹具 `int` 不能转换为 `Long` 的编译失败，不能算已执行测试或业务缺陷复现；095／097 的中间回归通过后，最终以 099 为准。

100 四组输入、提示、请求、响应及摘要正文已独立逐项核对，保存的请求与提示、响应与摘要分别一致，格式、预算和语义检查通过。模型调用真实，材料为合成数据，使用生产提示构造器、格式校验器及 Token 估算器，直接调用同配置模型。此证据不包含真实业务库、Redis 或完整浏览器／工具循环，也不证明实际业务读取次数减少或任意摘要无损。本轮未部署；旧端到端证据仅复用未变化控制流，本报告未生成机器验收通过记录。

## 历史摘要内容复核（089～092）

以下保留该阶段结论及来源边界。091／092 的 776 个代码条目在当时全部匹配；本轮后续修改由上文 099／100 重新验证，不能把 092 输出当作新事实约束的语义证据。

本次按已有效规划任务 4.3 细化内容保留规则，业务差异仅涉及 [MemorySummaryPromptBuilder.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/MemorySummaryPromptBuilder.java:22)、[SummaryCompressionPromptBuilder.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/SummaryCompressionPromptBuilder.java:10) 和 [MemorySummaryFormat.java](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/MemorySummaryFormat.java:10)。本评审者读取完整差异、相关契约与预算调用链、091 命令和 JUnit XML、090 的输入及摘要正文、092 的输入／请求／响应及摘要正文；没有修改业务代码或测试，也没有运行 Maven、数据库、Redis、浏览器或模型调用。

主摘要允许从成功读写材料提炼精确路径、导出名、函数签名和字段，保留成功写入、待完成事项、验证状态及未解决错误，并明确禁止历史读取清单。用户要求、项目／Skill 规则和模型方案分别标明来源，计划或被拒绝调用不能冒充已落盘接口；较新的成功修改替代旧接口约定。二次压缩同步保护这些续做信息，先合并重复规则和已解决细节，避免笼统删除进度段。

五个固定标题和 3072 Token 上限未变，`MemorySummaryFormat.isValid()` 的校验算法未变；只更新了段落提示。仍禁止复制大段源码和完整日志。历史材料继续通过 JSON 字符串转义，不能提升为执行指令。新增提示仍由 [MemorySummaryDraftEngine 的完整提示预算检查](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngine.java:421) 计入请求，没有改变覆盖推进、持久化、模型参数、最新计划或可信状态注入。

### SC-01 · 将任务描述写成设计理由 · 对应样本复核关闭

090 的 02 输入只要求改绿色、统一 `faqItems` 和修复引用，未表达这些选择的因果理由；输出却写“理由为统一命名并调整主色”。03 将该句作为旧摘要继续保留，属于二次压缩未清理已有冗余理由，不能记为独立新增理由。090 命令虽为退出码 0，格式、预算和字面接口检查通过，但不足以证明语义全部通过。

主摘要现明确要求：无原始因果说明时省略理由或写“理由未记录”，任务描述不能改写成设计理由；二次压缩不得补理由，并可清理重复任务描述的“理由”。092 的 02／03 均明确记录绿色及 `faqItems` 的选择理由未记录。02 的“理由依据用户要求修复联系页引用及既有本地演示规则”可追溯至具体用户要求和规则，表达的是实施依据，没有为颜色或命名补造动机；本报告不将其认定为用户曾经表达过的因果说明。原轮播因遮挡移动端正文被否决的真实理由仍保留。

### SC-02 · 工具错误进入用户否决段 · 对应样本复核关闭

090 的 04 在“已否决的方案”中写入 `NOT_FOUND` 并解释“并非用户否决”。主摘要和二次压缩现均要求：没有用户明确否决时只写“暂无用户明确否决”，工具错误留在进度段。092 的 04 符合该规则，`NOT_FOUND` 仍作为未解决问题保留；`plannedFaqProvider` 明确属于尚未实施的计划，没有变成现有接口。该输出只保留本地演示要求，未再从本地演示推导出禁止后端的额外约束。

### 夹具与终态边界

090 的 02 合成模板仅显示 `faqItems.length / offices.length`，旧摘要中的 `toggleLang` 为空实现。模型指出功能尚未完成有材料依据，不能用它证明已结束任务被误复活。092 改用展示 FAQ 与办公地点详情的合成模板，并提供可切换语言字段的实现；输出承认最新成功写入与构建结果，不再将旧引用错误列为待修复，同时明确没有浏览器功能验证记录。这里的成功构建和终态是合成输入事实，不能当作本轮实际执行过构建或浏览器验收。

### 当前运行证据

| 证据 | 独立核对结果 |
| --- | --- |
| [091 最终回归命令](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/091-summary-content-final-regression/command.json) | **11 类、103 项通过**，失败／错误／跳过均为 0。覆盖提示、格式、契约、草稿、材料、覆盖、分层和请求门禁；776 个代码状态条目全部匹配。 |
| [092 样本 01](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/092-summary-content-real-model-final/tmp/content-probe/01-incremental-failure/summary.md) | 846 Token；保留已写入的接口、轮播否决理由及输入中的失败结果，区分用户与项目规则，未将被拒绝的写入当成已实现接口。 |
| [092 样本 02](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/092-summary-content-real-model-final/tmp/content-probe/02-newer-interface-and-terminal/summary.md) | 965 Token；采用绿色及 `faqItems` 新约定，承认较新成功结果，保留缺少功能验证的边界，没有补造两项选择的理由。 |
| [092 样本 03](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/092-summary-content-real-model-final/tmp/content-probe/03-reduction-retains-interfaces/summary.md) | 输入格式有效且估算为 **6276 Token**，压缩后为 **884 Token**；去除重复通用规则和历史读取清单，保留精确接口、来源区别、否决理由及待验证状态。 |
| [092 样本 04](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/092-summary-content-real-model-final/tmp/content-probe/04-unimplemented-plan-is-not-fact/summary.md) | 500 Token；未实施计划与现有接口分开，没有把模型声称的验收通过当成事实；否决段与工具错误归类正确。 |

092 共四次真实摘要模型调用，使用生产提示构造器、格式校验器和 Token 估算器，经直接 HTTP 调用同配置的摘要模型。四组保存的请求正文分别匹配提示文件，响应正文分别匹配摘要文件；格式和 3072 Token 预算检查全部通过。上述 Token 均为项目对摘要正文的估算，响应的 `completion_tokens` 包含 reasoning tokens，不据其判断正文是否超限。

本次证据支持这些定向内容行为，不包含真实业务库、Redis、完整生成请求或浏览器链路，也不证明真实生成中的重读次数下降、任意历史都能无损压缩或多轮语义永不偏移。本次没有生成新的机器验收通过记录；总体交付结论仍由主执行者结合完整验收映射登记。

## 上阶段范围与方法（取消与重启修复）

上阶段依据 [任务](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/tasks.md) 6.1、6.3、7.5、9.2、9.3，以及“可信工程结果必须独立保留”“已覆盖的历史结果不得重复独立注入”“摘要失败与终止必须保持执行边界”“旧历史与冷重建必须保持兼容边界”，复核 [上轮端到端发现](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/e2e/20261101-082443-c772a7/端到端验收.md) 对应的修复。以下保留当时结论及证据边界，不将历史运行升级为本次新提示词的完整验收。

上阶段业务变更限于 `AppServiceImpl.java`、`VueTurnCancellationCoordinator.java`、`VueTurnContext.java`、`TrustedTurnRequestViewProjector.java`。已读取四者完整相关调用链、终态保存、应用租约、预提交状态机、工具事实收集、原文解析及预算组装，并核对 `AppServiceImplVueTurnTest`、`AppServiceSimpleTurnLifecycleTest`、`VueTurnCancellationCoordinatorTest`、`TrustedTurnRequestViewProjectorTest` 的新增断言。未修改代码、测试或用户无关变更，未运行 Maven、服务、数据库或模型，避免与主执行者共享编译与运行目录竞争。

下列测试结果来自独立读取主执行者保存的命令、JUnit XML、原始日志和内容指纹，不冒充本评审者重新执行。需求／场景的完整映射继续由 [verification-manifest.json](/Users/lllyw/ai-app-generation/openspec/changes/unify-context-summarization/reports/verification-manifest.json) 维护；本报告只说明本次代码差异与复核。

## 上阶段修复与边界核对

### E2E-01 · 取消终态订阅被首条正文淘汰 · 实现修复通过

原 `Flux.firstWithSignal` 选择业务流后不再监听取消终态，导致数据库已取消而 SSE 继续只有心跳。当前 [mergeGenerationWithTerminal](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/AppServiceImpl.java:569) 同时订阅终态和业务流，收到 `TurnOutcome`／`SimpleTurnOutcome` 后结束；首条普通消息不会淘汰终态订阅。

这里不仅替换一个操作符：正常业务结束时用 `normalCompletion` 释放终态等待，防止普通完成流反而悬挂；取消或超时已经接管时，业务先结束仍继续等待可靠收尾。Vue 的共享收尾异常沿错误通道结束原流，正常失败仍由原处理器生成结果。HTML／MULTI_FILE 使用同一合并规则；删除接管保留原处理器分支和单赢家终态，不新增工具执行或放宽归属检查。

[Vue 取消回归](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/AppServiceImplVueTurnTest.java:155) 验证已经输出正文后仍收到唯一 `CANCELLED`、业务先结束时等待持久化，以及收尾自身失败可以终止流；[原生模式回归](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/AppServiceSimpleTurnLifecycleTest.java:66) 覆盖 HTML 和 MULTI_FILE。082 在旧实现复现四项取消流超时；087 当前代码全部通过。原 `AppController` 的末尾 `done` 编码和前端协议没有更换，浏览器收到完整结束事件仍需实际 HTTP/SSE 复验。

### E2E-02 · 默认取消结果漏记真实工具事实 · 实现修复通过

[默认取消与超时入口](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnCancellationCoordinator.java:69) 将 `List.of()` 改为在 supplier 执行时读取 `List.copyOf(context.decisionSources().facts())`。该 supplier 仍在 `awaitQuiescence` 成功之后由原收尾流程读取，因而不会在取消发起时提前冻结空事实。事实容器为 `CopyOnWriteArrayList`，其内容来自可信工具协议解析，不从模型普通正文推断执行状态。

[主动取消入口](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/AppServiceImpl.java:528) 统一进入 `cancelVuePreparation`：提交前只清理，提交后使用现有转录作为展示前缀、真实事实作为独立记忆结果。实际文件变更和构建失败、次数继续由后端协议决定，展示文本与权威记忆没有混合。

[新增事实回归](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/core/handler/VueTurnCancellationCoordinatorTest.java:110) 在取消请求后、允许收尾之前补入读文件、写文件和第 2 次构建失败事实，验证默认取消与默认超时均保存完整事实。083 在旧代码两项失败，084／086／087 通过。081／082 中事实夹具的 final 类 mock 和不合法构建协议错误不算真实缺陷复现，已由 083 的有效协议输入补齐。

### E2E-03 · 相邻诊断回合的进度混淆 · 代码对齐通过，真实模型语义仍需复验

[请求结果投影](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/TrustedTurnRequestViewProjector.java:43) 现在明确写出当前用户记录、L1 部分前沿所属用户记录、未覆盖历史终态的归属及原始用户消息顺序。上一回合从同一相容快照内有序的持久用户边界确定，没有重新查询“最后一条记录”，也没有修改原始用户消息的 payload。

对 `SYSTEM_ERROR` 且本次窗口没有该回合工具批次的情况，程序说明轨迹缺失，要求只依据归属于该回合的保存结果或摘要判断，不能确认时说明未知。它没有把缺失轨迹伪装成“未执行工具”，也没有增加完整工具轨迹持久化、自动重放或固定收尾摘要。

去重检查仍先执行，已由 L1 覆盖的旧结果不会因增加身份文字再次进入 System 状态。新增文字位于 [UnifiedContextRequestPreparer](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/UnifiedContextRequestPreparer.java:146) 的同一请求组装链中，随后与原消息、最新计划和临时资料一起估算并冻结，未绕过完整请求 Token 门禁。确定性用例验证同名诊断任务分别属于用户记录 10／30，摘要进度不复制进状态投影，原始用户消息保持相等；087 的分层、门禁、工厂和冷重建测试通过。

代码层的来源关联和未知边界符合已确认设计。模型是否在实际回答中正确采用这些信息仍需本轮真实模型／浏览器样例证明，不能仅凭提示文字与单元测试宣布自然语言错误已经彻底解决。

### PC-01 · 提交前取消完成后再次清理已关闭租约 · 已关闭

本项由主执行者在 085 的真实测试执行中发现：主动取消准备中的回合先完成清理，随后准备异常分支再次等待清理；协调器的 pending 条目已经删除，第二次清理重新调用已关闭的 Vue 租约，返回“Vue 构建租约已经关闭”而不是原取消状态。

[VueTurnContext.preCommitCleanupOnce](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnContext.java:930) 用 CAS 只安装并启动一个共享清理结果，成功、失败均保留在同一上下文；[协调器公共入口](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/core/handler/VueTurnCancellationCoordinator.java:104) 通过该入口调用原清理实现。每次等待返回 `CompletableFuture.copy()`，一个请求或 Reactor 等待者取消不会取消其他等待者依赖的共享结果。该状态只保存在当前运行对象，不改变数据库或重启恢复格式。

独立复核了成功后重复、失败且排空后重复、等待者取消三个测试，以及通过真实服务状态机触发的提交前取消：它们分别确认只启动一次、保留原失败、互不取消、没有用户／AI 记录和迟到模型启动。086 的 93 项、087 的 657 项均通过；这关闭了 085 的唯一未通过项。

## 上一阶段发现保留

下列 IR-01／IR-02 来自上一阶段独立评审。相关业务文件和测试未被本次改动，当前直接核对仍保留原修复；旧运行只支撑各自说明的范围，不代替本次取消与重启复验。

### IR-01 · P1 · 计划证据遇到无变更路径的工具事实会抛空值异常 · 已关闭

位置：[DecisionEvidenceAssembler.java:59](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/ai/memory/DecisionEvidenceAssembler.java:59)；关联任务 3.2、3.3、4.3、9.2 及“短回合及原文裁剪前必须留存必要证据”。

触发路径：成功计划已产生 `PLAN_SUMMARY`／理由／用途条目，之后出现 `readFile`、`makePlan` 或 `buildProject` 等 `changedRelativePath=null` 的事实。原代码先对 `List.copyOf` 保存的 `relatedFiles` 调用 `contains(null)`，之后才判断路径非空，因此正常工具链会在存证准备时抛 NPE。统一摘要失败并保留原文，长任务继续增长后可能受门禁终止。

修复把路径非空检查移到 `contains` 之前：`fact.changedRelativePath() != null && entry.relatedFiles().contains(fact.changedRelativePath())`。有路径的真实文件变更继续关联，读取、计划和构建事实不会冒充文件修改；构建验证仍按原独立分支采集。

上一阶段评审独立复核了 [新增用例](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/ai/memory/DecisionEvidenceTest.java:84)、[070 失败复现](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/070-plan-evidence-null-reproduction/output.log) 和 [071 修复回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/071-plan-evidence-null-fix/output.log)。070 为 5 项中 1 项 NPE；071 为 6 类 45 项通过、无跳过。078 最终真实长任务也经过相同计划、读取及构建采集路径并成功完成。

### IR-02 · P2 · 启动许可等待超过截止后仍可能新发摘要调用 · 已关闭

位置：[MemorySummaryDraftEngine.java:339](/Users/lllyw/ai-app-generation/src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngine.java:339)；关联任务 4.4、5.1、7.5 及“摘要失败与终止必须保持执行边界”。此项由上一阶段独立评审者 `/root/independent_plan_review` 发现。

原代码只在 `modelStartPermit.getAsBoolean()` 之前检查截止。生产许可通过 `VueTurnContext.tryCallCallback → VueBuildSessionManager.enterCallback → AppOperationLeaseManager.enterCallback` 获取回调票据，后者使用同步 monitor，可能等待同一状态的提交动作。等待中截止耗尽后，外层虽已返回 `TIMED_OUT` 并取消 Future，worker 仍可能在许可迟到返回 `true` 后执行 `summarizationModel.chat`，同时增加模型调用计数。该缺陷不会使迟到草稿绕过持久化门禁，但会在本次准备已结束后新发模型请求。

修复在进入许可检查前检查线程中断，并在许可返回后再次检查**原**截止和中断；拒绝分支位于 `started.set`、模型调用指标和实际 `chat` 之前，没有延长截止或把回收失败转换成额外摘要调用。

上一阶段评审独立复核了 [两个竞争用例](/Users/lllyw/ai-app-generation/src/test/java/com/lyw/appgeneration/service/impl/UnifiedMemorySummaryDraftEngineTest.java:97)：许可忽略中断且时钟已过截止，以及许可保留中断而测试时钟未过截止。两者均先等调用方超时，再放行 worker，断言零模型调用和零调用计数。[076](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/076-late-summary-start-reproduction/output.log) 在修复前两项均失败，明确记录了实际 `chat` 与计数；[077](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/077-late-summary-start-fix/output.log) 为 7 类 210 项通过、无跳过，涵盖草稿、协调、服务、观测与请求门禁。075 是测试编译失败，未用作缺陷复现依据。

## 历史运行证据及适用边界

| 证据 | 独立核对结果 |
| --- | --- |
| [084 聚焦回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/084-e2e-defect-fixes/command.json) | 8 类、162 项通过，失败／错误／跳过为零。覆盖最初取消合并、真实事实与回合归属修复；其后预提交幂等修复由 086／087 补验。 |
| [085 应用回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/085-e2e-fixes-regression/command.json) | 621 项中 1 项提交前取消失败，其余 620 项通过。该运行作为 PC-01 复现保留，不记为全量通过。 |
| [086 预提交幂等回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/086-precommit-cancellation-idempotence/command.json) | 3 类、93 项通过，失败／错误／跳过为零；涵盖完成后重复、失败后重复和等待者取消隔离。 |
| [087 当时代码回归](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/087-cancellation-memory-final-regression/command.json) | **38 类、657 项通过**，失败／错误／跳过为零；覆盖 Vue／HTML／MULTI_FILE、取消／超时／删除、前置失败、终态与 SSE、工厂与冷重建、计划、分层及完整请求门禁。776 个代码状态条目与当时工作区一致；后续摘要及事实边界修改的受影响验证分别由 091／092、099／100 补充。 |
| [073 真实存储组合](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/073-final-real-storage/command.json) | 保留上一阶段 20 项真实 MySQL／Redis 证据。此次没有改写提交、回收、Lua、迁移、事务和存储配对实现；它不证明新增取消流或模型语义。 |
| [078 真实长任务](/Users/lllyw/ai-app-generation/.codex/unify-context-summarization/runs/078-real-model-final/command.json) | 保留 IR-01／IR-02 之后的历史长任务证据：真实模型、六次摘要和真实构建。当前请求身份文字及取消逻辑已经改变，本轮需以新的真实模型／浏览器记录补充验证，不能把 078 当作当前完整端到端通过。 |

上阶段与本次均独立执行了规划基线检查、内容哈希核对和 `git diff --check`，均通过。未更改需求或验收标准，未生成 `record-verify` 或 `check-verify` 的新通过记录。正式验收仍由主执行者根据当前任务、真实环境证据及本报告汇总；代码审查和摘要定向样本不替代浏览器取消收尾、重启后语义复盘及隔离环境清理。
