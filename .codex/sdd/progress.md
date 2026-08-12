# Vue RAG 混合检索执行进度

## 2026-08-12 最新真实门禁与终审收口

- 用户确认删除计划外的 **ECharts 专项意图解析、ECharts 骨架硬过滤和拟议的 `EchartsIntentParser`**。当前职责恢复为：Hybrid 将 BM25 与 Dense 候选交给 RRF，再把全部父文档候选交给 Rerank；Dense-only 保留 Dense 原排名；最终功能片段仍执行计划内的 Vue 主版本、语言、构建工具和共享依赖主版本兼容性过滤。代码中不存在 `EchartsIntentParser`，也不存在按 ECharts 肯定、否定或未决措辞预删骨架的生产分支。
- 删除专项规则的行为回归完成 RED→GREEN：RED 为 2 项、2 failure；GREEN 为 2/2，完整 `VueHybridRetrievalServiceTest` 为 29/29。两条永久用例分别锁定“Hybrid 在 Rerank 前不得按 ECharts 依赖删候选”和“Dense-only 不得按 ECharts 肯定/否定语义覆盖原排名”；证据为 `.codex/runtime/echarts-hard-filter-delete-red.log`、`.codex/runtime/echarts-hard-filter-delete-green.log`、`.codex/runtime/echarts-hard-filter-delete-service-green.log`。
- 其余终审修复保留：23 条 embedding 按 `10/10/3` 批量获取且全部成功后一次写库；生成评测上下文关闭时仅在静态值仍指向本上下文时恢复旧值；评测报告完整脱敏带空格、异种引号、转义引号及角括号的命名秘密。
- 最新正式摄取：11/11，`BUILD SUCCESS`；PGVector 当前目录版本 23/23、历史版本 0、向量维度 1024，目录版本为 `bd799d82b3f00151016246ff0228009b1ef8e84dd0a66e999bbdc3b1e4942af0`。日志为 `.codex/runtime/final-real-ingestion-after-echarts-delete-2026-08-12.log`。
- 最新 30 条真实检索：3/3，`BUILD SUCCESS`；Hybrid 的 Skeleton Hit@1 为 0.9333、Feature Recall@4 为 0.9528，相对 Dense 差值分别为 0.0000、-0.0389，均满足计划门槛。日志为 `.codex/runtime/final-real-retrieval-after-echarts-delete-2026-08-12.log`。
- DeepSeek 充值后重新执行十条首次真实生成：固定 10 个用例全部 `生成完成=true`，逐条完成 `npm install` 和 `npm run build`，阶段均为 `SUCCESS`、退出码 0、未超时；测试类 10/10、0 failure、0 error、0 skipped，`BUILD SUCCESS`，总耗时 10 分 31 秒。报告副本为 `.codex/runtime/vue-generation-build-report-after-echarts-delete-2026-08-12.md`，日志为 `.codex/runtime/final-real-generation-build-after-echarts-delete-2026-08-12.log`。
- 首次生成重跑最初被本机 JVM SOCKS 代理错误接管 `127.0.0.1`，JDBC 以 `UnknownHostException` 在认证前失败；A/B 探测确认 `JAVA_TOOL_OPTIONS='-DsocksNonProxyHosts=localhost|127.*|[::1]'` 后 JDBC 直连成功。该问题属于本机运行参数，不需要修改生产代码。生成期间主 MySQL 未启动只产生历史记忆读写的降级告警，没有中断文件生成或构建，最终成绩仍严格来自每个工程的真实 npm 结果。
- 最新无外部门禁扩大定向回归：198 项、0 failure、0 error、1 个既有显式跳过，`BUILD SUCCESS`；其中 `VueHybridRetrievalServiceTest` 为 29/29。日志为 `.codex/runtime/final-expanded-targeted-regression-after-echarts-delete-2026-08-12.log`。
- 最新无外部门禁完整 Maven：443 项、0 failure、0 error、7 个显式外部测试跳过，`BUILD SUCCESS`；日志为 `.codex/runtime/final-full-maven-after-echarts-delete-2026-08-12.log`。该运行按生命周期把 `target/rag-eval/` 报告改写为“未执行”，不能覆盖已保存的真实外部门禁副本。
- 真实生成报告、保存副本和完整运行日志对 DashScope Key、DeepSeek Key、PGVector 密码的精确字面值扫描均为 0，常见 `sk-...` 模式计数也为 0；私密环境文件仍被 Git 忽略且权限为 0600。生产 `RAG_HYBRID_ENABLED` 继续默认 `false`，本轮只提交、不 push、不 merge；最终独立全分支复审尚待最新差异完成后回传。
- 最新独立复审发现 Critical：模型请求/响应正文日志默认开启会把新增的 RAG 提示词、模板源码和生成内容写入日志，真实生成日志已证明该数据流可达。现已完成 TDD 修复：配置测试 RED 为 2 项、2 failure；四类模型的 `log-requests` / `log-responses` 改为仅由显式本地环境开关控制且默认 `false`，生产 Compose 额外硬编码二者为 `false`，GREEN 为 2/2。日志为 `.codex/runtime/ai-model-body-logging-config-red-2026-08-12.log` 与 `.codex/runtime/ai-model-body-logging-config-green-2026-08-12.log`。
- 模型正文日志修复后的 fresh 定向回归为 82 项、0 failure、0 error、1 个既有显式跳过；完整 Maven 为 445 项、0 failure、0 error、7 个显式外部测试跳过，均 `BUILD SUCCESS`。日志为 `.codex/runtime/final-targeted-after-model-logging-fix-2026-08-12.log` 与 `.codex/runtime/final-full-maven-after-model-logging-fix-2026-08-12.log`。该配置修复没有再次调用 DeepSeek，不改变此前十条真实生成构建 10/10 的功能成绩；最终提交仍等待修复后的独立复审确认 Critical/Important 清零。
- 修复后独立复审确认原 Critical 已关闭，Critical=0、Important=0，并指出一个非阻断 Minor：生产 `.env.example` 列出的模型日志变量会被 Compose 硬编码 `false` 覆盖。现已删除这两个误导项，并由配置测试锁定“本地应用可显式开启、生产 Compose 强制关闭且生产 `.env` 不暴露无效开关”；该项 RED 2 项中 1 failure，GREEN 2/2，日志为 `.codex/runtime/ai-model-production-env-contract-minor-red-2026-08-12.log` 与 `.codex/runtime/ai-model-production-env-contract-minor-green-2026-08-12.log`。
- 末轮独立全差异复审：Spec Compliance 与 Task quality 均通过，Critical=0、Important=0、Minor=0，`Ready to commit=Yes`。本轮保持生产 Hybrid 默认关闭，最终只创建本地中文提交，不 push、不 merge。
- 包含最后 Minor 清理的提交前 fresh 完整 Maven：445 项、0 failure、0 error、7 个显式外部测试跳过，`BUILD SUCCESS`；日志为 `.codex/runtime/final-full-maven-before-commit-2026-08-12.log`。

## 最终收口（2026-08-11，当前结论）

- 当前分支：`codex/vue-rag-hybrid-retrieval`；代码范围：`5850ef9..b05e7aa`，共 60 个提交；未 push、未 merge，工作树继续保留。
- 全分支终审修复提交：`cfab860` 收紧五个 Vue 文件工具的路径边界并固定七个批准依赖版本；`3dc3b4d` 使目录安全遍历在进入前跳过 `node_modules` 等忽略目录；`cdd06f7` 将 Vue 系统提示词与构建器的精确依赖版本契约对齐；`b05e7aa` 改为真实写盘成功后记账、合并规范路径状态键，并用固定条带锁保证同一 appId 下的分类、I/O、提交、重置和计数一致。
- TDD 与定向验证：文件工具、状态管理器、构建器及进程执行相关 fresh 回归 63/63；Vue RAG/构建扩大定向历史门禁 231/231；均为 0 failure、0 error、0 skipped。五骨架显式真实门禁 6/6，五个骨架均完成真实 npm 安装和可信 Vite 构建。
- 最终 fresh 完整 Maven：项目 JDK 25.0.4，显式 unset `RAG_VUE_INGEST`、`RAG_EVAL`、`RAG_BUILD_EVAL`、`RAG_SKELETON_BUILD`、两个模型密钥和 PG 密码；实际执行 432 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。三个报告均在本轮重写为“状态：未执行”，没有正式摄取数、Hit@1、Recall@4、Dense 相对退化或 10/10 伪成绩。
- 最新独立全分支终审：评审包 `.codex/sdd/review-5850ef9..b05e7aa.diff`，SHA-256 为 `3e4aa9fb824727f6075cd5a9ebf4841e727efa7dac5405bfc202b29e389587dc`；Spec Compliance 与 Task quality 通过，Critical=0、Important=0、Minor=0，`Ready to merge=Yes`。
- 代码合并判断与发布判断严格分离：当前代码可以合并，但不可发布。仍须依次取得“正式 23 条 `text-embedding-v4` 摄取及 PGVector 物理核验 → 30 条真实 Hybrid/Dense 检索达标 → 十条首次真实生成 10/10 构建”三项外部门禁，生产 `RAG_HYBRID_ENABLED` 必须保持 `false`。
- 已知平台限制：标准 JVM `Path` API 没有可移植的 `openat` 式原子路径操作，当前实现已阻断模型可直接提供的绝对路径、`..` 和稳定符号链接逃逸，但无法彻底消除本地高权限进程在校验与 I/O 之间交换符号链接的 TOCTOU 竞争。

## 全分支终审修复（2026-08-11）

- 修复任务 1：完成（`badb3a9..55160e5`）。目录只接受原始 JSON 整数 `schemaVersion: 1`，补齐工程元数据和依赖键值校验；三类 RAG 门禁统一只使用 `RAG_PGVECTOR_PASSWORD`。
- TDD：目录契约 RED 44 项中 21 项失败、GREEN 44/44；严格 schema 类型追加 RED 50 项中 2 项失败、GREEN 50/50；密码环境 RED 20 项中 4 项失败、GREEN 20/20；真实摄取入口参数捕获补强后 11/11。
- 回归：项目 JDK 25 定向 78/78，0 failure、0 error、0 skipped；`git diff --check` 通过。
- 任务级独立复审：Spec Compliance 通过，Task quality 通过，Critical/Important/Minor 均为 0；原“只测密码提取、不捕获 store/verifier 实参”的 Important 已由 `55160e5` 关闭。
- 真实模型、正式 PGVector 摄取、30 条真实检索和十条首次生成均未执行，不改变“不可发布”结论。
- 修复任务 2：完成（`e6e8379..ae4744a`）。新增原子报告写入与本轮生命周期、结构化生成前置 Runner、严格 Hybrid/Dense 健康检查，并使同一 `TemplateCatalog`、规范化 `VuePgVectorTarget` 与 PG 密码贯穿物理核验、检索和生成 Spring。
- TDD 与回归：报告/Runner/入口生命周期分别完成 RED→GREEN；历次独立审查 finding 均有回归锁定。最终项目 JDK 25 默认 unset Vue RAG 回归 129/129，0 failure、0 error、0 skipped；`git diff --check` 通过。
- 任务 2 正式独立复审：Spec Compliance 通过，Task quality 通过，Critical=0、Important=0。两个非阻断 Minor 为独立检索入口仍有两次不可变 JVM 环境读取、生成环境保留一个无调用的 `inspectSystemEnvironment()`；留待最终全分支审查重新分级。
- 任务 2 未读取历史 Markdown 作为程序状态；未执行正式模型、数据库评测或十条 npm 生成，真实发布结论不变。
- 修复任务 3 步骤 1～5：完成（`bbf9893..2371204`）。生产 Compose 与 `.env.example` 均默认关闭 `RAG_HYBRID_ENABLED`；README 固定真实摄取、真实检索、十条生成、人工开启、重启 backend 的顺序。
- TDD：生产配置 RED 1 项中 1 项失败、GREEN 1/1；独立审查发现服务顺序硬编码后追加 RED 2 项中 1 项失败、GREEN 4/4。静态测试现验证全文件唯一精确行且只位于 `services.backend.environment`。
- 任务 3 独立复审：Spec Compliance 通过，Task quality 通过，Critical/Important/Minor 均为 0；可以进入 fresh 全量验证。
- 修复任务 3 步骤 6：项目 JDK 25、三个 RAG 开关、两个模型密钥和 `RAG_PGVECTOR_PASSWORD` 均显式 unset，回环地址通过 `MAVEN_OPTS` 直连；fresh 定向回归实际执行 133 项，0 failure、0 error、0 skipped，`BUILD SUCCESS`。UTF-8 原始日志保存于 `.codex/sdd/vue-rag-final-targeted-2026-08-11.log`，该运行证据目录按仓库规则忽略，不强行纳入版本控制。
- 修复任务 3 步骤 7：相同环境边界下 fresh 完整 Maven 实际执行 377 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`；`AiAppGenerationApplicationTests` 使用 Java 25.0.4 启动真实 Spring 上下文并通过。7 项跳过来自五骨架真实 npm 构建、网页截图、旧 RAG 联网评测和四项真实模型外部测试；UTF-8 原始日志为 `.codex/sdd/vue-rag-final-full-maven-2026-08-11.log`。
- 修复任务 3 报告核验：三份报告修改时间均落在本轮完整 Maven 内，摄取、检索、生成分别由 `RAG_VUE_INGEST`、`RAG_EVAL`、`RAG_BUILD_EVAL` 未启用而写为“状态：未执行”；报告中没有“状态：通过”、Hit@1、Recall@4、Dense 相对退化、正式摄取数量或 10/10 伪成绩。
- 首轮全分支终审：范围 `5850ef9..2371204`，发现 Critical=1、Important=2、Minor=3，结论为不可合并、不可发布。问题分别是模型生成工程的构建子进程继承宿主秘密、固定报告与生成目录存在跨 JVM 竞争、目录允许依赖跨 scope 冲突、null 依赖 Map 未归一化、门禁未冻结环境快照及生成环境死入口。
- 统一修复提交：`7fa30d2` 关闭六项 finding；`7c602af` 记录完整 TDD 证据；`6fca9e5` 只修复双 JVM 进程树测试在完整套件负载下 200ms 启动预算不足的时序抖动，未修改生产代码。构建边界现清空子进程环境并只保留清洗 PATH，不执行模型 build script，拒绝可控锁文件、`.npmrc`、预存 `node_modules`、非受控版本源和依赖图扩展字段，使用可信 Vite 配置；报告完整生命周期使用 JVM 锁与跨进程文件锁，appId 通过双目录原子领取。
- 修复后主代理 fresh 定向：项目 JDK 25，三个 RAG 开关、五骨架开关、两个模型密钥与 PG 密码显式 unset；实际执行 213 项，0 failure、0 error、0 skipped，`BUILD SUCCESS`。原始日志为 `.codex/sdd/vue-rag-findings-fix-targeted-2026-08-11.log`。
- 修复后主代理五骨架真实构建：`RAG_SKELETON_BUILD=true`，实际执行 6 项，0 failure、0 error、0 skipped；五个骨架均完成真实 npm 安装和可信 Vite CLI 构建，未残留可信临时配置。原始日志为 `.codex/sdd/vue-rag-findings-fix-five-skeletons-2026-08-11.log`。
- 修复后首次完整 Maven 暴露测试时序问题：406 项中 1 failure，真实父 JVM 在 200ms 内尚未写出子 PID 即被超时清理；失败日志为 `.codex/sdd/vue-rag-findings-fix-full-maven-2026-08-11.log`。`6fca9e5` 将该双 JVM 启动预算改为 2 秒、专用外层有界上限改为 8 秒，随后五个独立 Maven 进程均为 8/8。
- 修复后主代理最终 fresh 完整 Maven：406 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`；`AiAppGenerationApplicationTests` 使用 Java 25.0.4 启动 Spring 上下文并通过。三份报告均在本轮写为“状态：未执行”且没有成功指标。原始日志为 `.codex/sdd/vue-rag-findings-fix-full-maven-final-2026-08-11.log`。
- 该阶段历史结论：当时修复后独立复审尚在进行；当前结论已由文档顶部“最终收口”替代。正式 23 条 `text-embedding-v4` 摄取、30 条真实检索指标和十条首次生成 10/10 仍无本轮成绩，发布结论始终为“不可发布”。

## Vue 知识摄取物理门禁（2026-08-11，本轮）

### 任务 1～5 提交范围与门禁

- 任务 1（`aff0895`）：新增 `VuePgVectorTarget`、`VueIngestionEnvironment` 及环境测试；只在 `RAG_VUE_INGEST=true` 且模型、数据库环境变量存在时探测数据库端口，不保存或输出秘密。
- 任务 2（`e7cb551`、`afa530f`、`1b6b5ff`）：新增可信目录快照及测试，固定当前目录为 18 个父文档、23 个知识块、1024 维、严格五项 metadata 和稳定 UUID。
- 任务 3（`6885591`、`fbf578e`、`d00c300`）：新增 PGVector 物理行、核验结果、JDBC 核验器及测试；固定读取 `templates_vue`，参数化目录版本，核验列协议、23 条当前版本数据、维度、文本、稳定 UUID 与严格五键，并隔离数据库脏数据标识。
- 任务 4（`86c0e1f`、`37b0c23`、`79e4a87`）：新增三态摄取报告和 `VueKnowledgeIngestionQualityGateTest`；显式开关为 `RAG_VUE_INGEST=true`，报告为 `target/rag-eval/vue-ingestion-report.md`。
- 任务 5（`3223701`、`016888e`）：真实检索门禁在创建模型与检索服务前强制核验同一 PGVector 目标的正式摄取；显式开关为 `RAG_EVAL=true`，报告为 `target/rag-eval/vue-hybrid-retrieval-report.md`。
- 十条真实生成构建的既有入口为 `VueGenerationBuildQualityGateTest`，显式开关为 `RAG_BUILD_EVAL=true`，报告为 `target/rag-eval/vue-generation-build-report.md`。本轮承载代码的验证基线为 `016888e`；任务 1～5 均只改测试门禁/计划，没有新增生产接口。

### 本轮 fresh 验证

- 11 类定向命令：使用项目 `.codex/runtime/jdk25`，显式 unset `RAG_VUE_INGEST`、`RAG_EVAL`、`RAG_BUILD_EVAL`、`DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，并设置 `MAVEN_OPTS='-DsocksNonProxyHosts=localhost|127.*|[::1]'`，执行简报指定的 11 类测试；结果为 51 项、0 failure、0 error、0 skipped，`BUILD SUCCESS`。
- 定向测试 fresh 生成的摄取报告与检索报告均为“状态：未执行”，原因分别为 `RAG_VUE_INGEST 未设置为 true`、`RAG_EVAL 未设置为 true`；两份报告没有正式摄取计数、Hit@1、Recall@4 或 Dense 相对退化伪指标。
- 本地容器 `ai-codegen-rag-eval-pg` 实测为 `running/healthy`，`vector` 扩展版本为 `0.8.6`。无模型探针使用 PID 后缀的独立一次性夹具表，由 trap 和显式 `DROP TABLE` 清理；实测列二元类型为 `embedding_id data_type=uuid, udt_name=uuid`、`embedding data_type=USER-DEFINED, udt_name=vector`、`text data_type=text, udt_name=text`、`metadata data_type=json, udt_name=json`，实际 `vector_dims(embedding)=1024`；项目 Jackson 成功读取且确认 metadata 恰好为 `chunkId`、`documentId`、`documentKind`、`chunkKind`、`catalogVersion` 五个字符串键。夹具表已删除，`templates_vue` 未被写入，故该结果只证明协议，不代表正式摄取通过。
- 完整 Maven 按简报命令 fresh 执行：项目 JDK 25、三个 RAG 门禁开关和两个模型变量均 unset，并使用回环直连 JVM 参数；结果为 317 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。Spring `contextLoads` 实际启动并通过；7 个跳过项均为既有显式外部测试，其中五骨架来源校验执行、真实 npm 动态构建按开关跳过。
- 完整 Maven fresh 生成的三份报告均为“未执行”：摄取、真实检索、十条生成分别由 `RAG_VUE_INGEST`、`RAG_EVAL`、`RAG_BUILD_EVAL` 未启用而短路，报告中没有外部真实成绩。默认测试通过只证明短路语义和代码回归通过，不等于外部真实门禁通过。

### 外部条件与最终完成审计

- 只按环境变量存在性审计：`DASHSCOPE_API_KEY=UNSET`、`DEEPSEEK_API_KEY=UNSET`；未读取变量值，也未搜索钥匙串、Shell 历史或其他凭据。由于前者 unset，本轮没有执行正式 `text-embedding-v4` 摄取和 30 条真实 Hybrid/Dense 检索；由于前两项没有通过且后者也 unset，没有执行十条首次真实生成构建。
- 原总计划第 1 项：任务 1～7 的生产实现、单元测试和默认 Maven 有当前分支证据；本轮未发现需代码修复的规格、安全、资源或异常语义问题。
- 原总计划第 2 项：未完成。正式 `templates_vue` 当前不存在；没有当前目录版本的 23 条真实 `text-embedding-v4` 物理核验成绩。
- 原总计划第 3 项：未完成。没有 30 条真实检索的 `Skeleton Hit@1 >= 0.90`、`Feature Recall@4 >= 0.85` 或相对 Dense 退化不超过 `0.05` 的成绩。
- 原总计划第 4 项：未完成。没有十条固定需求首次生成后的 10/10 `npm install` 与 `npm run build` 成绩。
- 原总计划第 5 项：已有 2026-08-11 历史显式门禁证据证明五个策展骨架真实构建 5/5；本轮按简报不重复高成本 npm，完整 Maven 只执行固定五来源校验并跳过显式真实构建。
- 原总计划第 6 项：完成。本轮完整 Maven 为 317 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。
- 原总计划第 7 项：当前中文提交均在本地分支；本轮未 push、未合并。

该阶段历史结论：当时判断代码与默认门禁可以合并，PGVector 基础设施和无模型物理协议可用；该合并判断早于本轮五项终审修复，当前结论以本节顶部的新终审为准。正式 23 条摄取、30 条真实检索指标和十条首次生成构建均无成绩，因此当前不可发布；必须按“正式摄取并物理核验 → 真实检索达标 → 十条生成 10/10”顺序补齐外部门禁，不能用默认测试或协议探针缩小成功定义。

## 五骨架真实构建门禁补强（2026-08-11）

- 完成度审计发现：历史任务 2 曾手工验证 5/5 骨架，但永久测试 `VueSkeletonRealBuildTest` 只构建 `vue-skeleton-basic-001`，无法在后续回归中证明计划要求的 5/5。该问题是门禁覆盖不足，不是骨架实现失败。
- TDD RED：先增加“五个来源”断言并保留原单一来源，定向测试按预期失败，结果为 1 项、1 failure，错误为 `expected: <5> but was: <1>`。
- GREEN：测试现在固定校验五个计划骨架文件，使用动态测试从唯一知识源 JSON 提取工程，逐个调用真实 `VueProjectBuilder.buildProjectDetailed`，分别检查构建成功与 `dist` 目录存在；骨架 ID 和内嵌文件路径均不得逃逸各自构建目录。
- 离线默认模式：`VueSkeletonRealBuildTest` 共发现 2 项，来源完整性测试实际执行并通过，真实 npm 动态测试工厂按 `RAG_SKELETON_BUILD` 跳过 1 项。来源数量与固定 ID 不再因未设置外部开关而完全跳过。
- 显式真实模式：`RAG_SKELETON_BUILD=true` 执行 1 个来源断言和 5 个动态真实构建，共 6 项，0 failure、0 error、0 skipped，`BUILD SUCCESS`；五个独立结果均为 `success=true`、`stage=SUCCESS`、`exitCode=0`、`timedOut=false`，且 `dist` 全部存在。
- 最终完整 Maven：显式清空模型、图片、COS、外部集成和三类 RAG 门控变量，以项目 JDK 25 执行；279 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。日志明确包含 `Started AiAppGenerationApplicationTests`，Spring 上下文门禁未跳过。
- 本轮只修改测试门禁和审计文档，不修改 `src/main/**`、模板数据、依赖或生产配置。

## 真实外部门禁基础设施补验（2026-08-11）

- 已在项目忽略目录 `.codex/runtime/pgvector-data` 准备临时 PGVector，容器 `ai-codegen-rag-eval-pg` 状态为 `running/healthy`，仅映射 `127.0.0.1:5432`，数据库 `ai_codegen_rag` 的 `vector` 扩展版本为 `0.8.6`。
- 已使用项目 `.codex/runtime/jdk25` 和当前依赖 `langchain4j-pgvector 1.1.0-beta7` 执行 Java 协议探针：实际批量写入一个 1024 维向量，通过相同向量检索得到唯一命中，向量 ID、文本、相似度 `1.000000` 和 `documentId` metadata 均正确；SQL 侧确认维度与 metadata 后已删除独立探针表。
- 当前 JVM 全局配置了 SOCKS 代理，且默认 `socksNonProxyHosts` 不包含回环地址。直接连接 PGVector 会把本地 PostgreSQL 连接错误送入代理并报 `UnknownHostException: 127.0.0.1`；评测 JVM 显式增加 `-DsocksNonProxyHosts=localhost|127.*|[::1]` 后协议探针通过。该问题属于本机运行参数，不需要修改生产代码。
- 正式表 `templates_vue` 当前不存在。真实摄取必须由 `VueKnowledgeIngestor` 调用指定的 `text-embedding-v4`，为 5 个骨架和 13 个功能片段生成 23 个真实检索块向量；不能用探针向量、假向量或手工空表替代。
- 已提供本地临时数据库凭据，并用项目要求的 JDK 25 重新运行 `VueRetrievalQualityGateTest`：门禁入口 1/1，0 failure、0 error、0 skipped，`BUILD SUCCESS`；报告仍为“未执行”，当前只缺少 `DASHSCOPE_API_KEY`，未取得 Skeleton Hit@1、Feature Recall@4 或相对 Dense 退化指标。
- 已用相同数据库配置重新运行 `VueGenerationBuildQualityGateTest`：门禁入口 1/1，0 failure、0 error、0 skipped，`BUILD SUCCESS`；报告仍为“未执行”，当前只缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，未取得 10/10 真实生成构建结果。
- 结论：PGVector 基础设施与 Java 协议已经就绪；`DASHSCOPE_API_KEY` 是正式摄取、Dense、Rerank 和真实检索指标的不可替代阻断，`DEEPSEEK_API_KEY` 是十条真实生成的额外不可替代阻断。门禁入口通过不等于真实质量门槛通过，当前仍不可发布。

## 全分支最终审查修复

- 已修正任务 7 的旧链解释：`rag.hybrid.enabled=false` 只关闭 BM25、RRF、Rerank，不再回到旧 Vue `id/title/category/code` metadata 与通用 `assemble`；生产改用新版 Dense-only、当前目录父文档回查和 `assembleVueProject`。
- `rag.enabled` 现为 Vue 普通生成与真实生成评测的优先总开关；Service 层同步防御，不允许 Facade 之外的生产调用绕过。
- 跨层验收已串联同一 InMemory 存储、真实当前摄取、真实 Dense、父文档与 Hybrid 关闭 Facade，证明旧 schema/旧目录版本不可见、当前完整源码可用。
- 验证：指定覆盖 52/52，纯单元 265/265，均为 0 failure、0 error、0 skipped；`git diff --check` 通过。

## 修复后全范围验证

- 任务 1～8 目标回归：176 项，0 failure、0 error、1 skipped；跳过项为默认门控的真实骨架联网构建。
- 纯单元回归：266 项，0 failure、0 error、0 skipped。
- 完整 `mvn test`：补强五骨架门禁后为 279 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。运行进程显式清空模型、Pexels、COS、数据库及外部门控变量；`AiAppGenerationApplicationTests.contextLoads` 实际启动 Spring 上下文并通过，不在跳过项中。
- 七个跳过项均为显式外部测试：真实 npm 骨架构建 1 项、旧 RAG 联网评测 1 项、真实模型/网页外部集成 5 项。`CodeParserTest` 已移除无用 Spring 上下文，`JsonMessageStreamHandlerTest` 已补齐 `ToolMessageCollapser` 并验证非空折叠快照原样恢复。
- Maven 门禁独立最终复审：Spec Compliance 与 Task quality 均通过，Critical/Important/Minor 均为 0；所有历史 finding 已关闭。
- 真实检索门禁报告：状态“未执行”；数据库前置条件已补齐，当前只缺少 `DASHSCOPE_API_KEY`，未取得 Hit@1、Recall@4 或 Dense 相对退化指标。
- 十条真实生成构建报告：状态“未执行”；数据库前置条件已补齐，当前只缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，未取得 10/10 构建结果。
- `git diff --check`：通过；未推送远程。

- 任务 1：完成（提交 `f32d576..ac2bca9`，独立审查通过）
- 任务 2：完成（提交 `ac2bca9..8aead1b`，独立审查通过）
- 任务 3：完成（提交 `8aead1b..e4ce5fe`，独立审查通过）
- 任务 4：完成（提交 `e4ce5fe..b2a2db6`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 5：完成（提交 `b2a2db6..ede5a53`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 6：完成（提交 `ede5a53..12c5d61`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 7：完成（提交 `12c5d61..579e686`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 8：代码与默认 Maven 门禁完成（提交 `579e686..a5c09b8`，定向复审和 Maven 门禁复审均通过；真实外部门禁仍未执行）
- 该阶段历史全分支独立审查：Spec Compliance 与 Task quality 均通过，Critical/Important/Minor 均为 0；当时判断代码可合并。该判断早于本轮五项终审修复，当前合并结论以本节顶部的新终审为准；两个真实外部门禁没有成绩，发布状态始终为不可发布。
