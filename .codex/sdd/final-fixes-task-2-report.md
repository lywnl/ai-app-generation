# Task 2：同轮真实质量前置与可靠报告生命周期实施报告

## 状态

- 结果：完成。
- 独立复核：Critical=0，Important=0。
- 事实边界：本轮未运行真实模型、正式摄取或十条 npm 生成；缺少真实成绩时仍不可发布。

## 实现摘要

1. 新增 `AtomicEvaluationReportWriter`：UTF-8、同目录临时文件、`ATOMIC_MOVE + REPLACE_EXISTING` 优先、不支持原子移动时降级为 `REPLACE_EXISTING`，`finally` 清理临时文件。
2. 检索和生成报告增加本轮运行标识与明确失败状态；所有状态均带 runId，所有 Markdown 继续经过统一脱敏器。
3. 新增结构化生成 Runner，固定顺序为：23 条物理核验 → 30 条真实检索且报告通过 → 生成 Supplier。
4. 同一个 `TemplateCatalog` 对象贯穿物理核验、检索资源和生成 Spring，上下文不再重新加载不一致目录快照。
5. 检索报告 `passed()` 强制 Hybrid/Dense 的 `queryCount` 和逐条结果均恰好为 30。
6. 检索与生成入口每轮先原子写失败占位；未启用环境改写为未执行；异常和 `AssertionError` 改写本轮失败报告后原样重抛，报告写入失败加入 suppressed。
7. 默认环境在目录、数据库、模型、Spring、生成服务和 npm 构建前短路；报告不作为程序输入，也不解析历史 Markdown。

## TDD 证据

### 循环一：报告状态与 writer

- RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" bash mvnw -Dtest='AtomicEvaluationReportWriterTest,VueRetrievalEvaluationReportTest,VueGenerationBuildReportTest' test`
- RED 结果：testCompile 失败，3 个预期错误；writer 与两个 `failed(runId, reasons)` 工厂不存在。
- GREEN：同一命令通过，9 个测试，0 失败、0 错误、0 跳过。

### 循环二：结构化生成前置 Runner

- RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" bash mvnw -Dtest='VueRetrievalQualityGateRunnerTest,VueGenerationBuildQualityGateRunnerTest' test`
- RED 结果：testCompile 失败，1 个预期错误；生成 Runner 不存在。
- GREEN：同一命令通过，5 个测试，0 失败、0 错误、0 跳过。

### 循环三：入口旧报告异常覆盖

- RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" bash mvnw -Dtest='VueRetrievalQualityGateTest,VueGenerationBuildQualityGateTest' test`
- RED 结果：testCompile 失败，2 个预期错误；报告生命周期执行器不存在。
- GREEN：同一命令通过，6 个测试，0 失败、0 错误、0 跳过。

### 独立审阅追加缺口

- 同一目录对象复用 RED：`VueRetrievalResourceProviderTest` testCompile 失败，缺少 `TemplateCatalog` 注入构造器；GREEN 为 4/4。
- 30 条完整性 RED：报告测试和生成 Runner 测试共 10 个，其中 2 个按预期失败，证明单条完美样本会错误放行；GREEN 为 10/10。
- `AssertionError` 生命周期 RED：writer/lifecycle 共 3 个测试，其中 1 个按预期失败，报告停留在“运行中”；GREEN 为 3/3。
- 生成 Spring 快照复用 RED：生成入口 testCompile 失败，缺少快照注册方法；GREEN 为 4/4，容器内 provider 与本轮 catalog 为同一实例。

## 最终验证

- JDK：项目 `.codex/runtime/jdk25/Contents/Home`。
- 回环直连：`MAVEN_OPTS='-DsocksNonProxyHosts=localhost|127.*|[::1]'`。
- 默认环境回归命令：
  `env -u RAG_VUE_INGEST -u RAG_EVAL -u RAG_BUILD_EVAL -u DASHSCOPE_API_KEY -u DEEPSEEK_API_KEY -u RAG_PGVECTOR_PASSWORD bash mvnw -Dtest='VueIngestion*,VuePgVector*,VueRetrieval*,VueEvaluation*,VueGenerationBuild*,TemplateCatalogTest' test`
- 结果：119 个测试，0 失败、0 错误、0 跳过，BUILD SUCCESS。
- 报告状态：检索和生成均为本轮“未执行”，分别说明 `RAG_EVAL`、`RAG_BUILD_EVAL` 未设置为 true；无历史指标或 `10/10`。
- `git diff --check`：通过。
- 独立终审：首次发现 3 个 Important，修复后发现生成 Spring 再读取目录 1 个 Important；最终复核 Critical=0、Important=0。

## 关注点

- 新增的 `VueRetrievalResourceProvider(TemplateCatalog)` 只供已加载快照注入；Spring 在线默认构造器及生产可用性降级语义未改变。
- 真实发布结论未改变：正式 23 条摄取、30 条真实检索成绩和十条 10/10 构建成绩仍未取得。

## 2026-08-11 正式独立评审 Important 修复追加

### Important 1：真实生成 Spring 复用已核验快照并释放资源

- 根因：initializer 使用 `registerSingleton("vueRetrievalResourceProvider", ...)` 注册的实例，会被后续组件扫描得到的同名 `@Component` BeanDefinition 替换；测试用简化容器无法证明真实生成 Spring 使用了该实例。
- RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" env -u RAG_EVAL -u RAG_BUILD_EVAL -u DASHSCOPE_API_KEY -u DEEPSEEK_API_KEY -u RAG_PGVECTOR_PASSWORD bash mvnw -Dtest='VueGenerationBuildQualityGateTest#真实生成Spring复用同一目录快照并在关闭时释放资源' test`
- RED 结果：真实完整组件扫描上下文成功启动，但最终 provider 的 `current()` 为空，测试因 `NoSuchElementException` 失败；1 个测试，0 失败、1 错误。
- GREEN：使用 `BeanFactoryPostProcessor` 在组件扫描完成后替换同名 BeanDefinition，定义由本轮已核验的 `TemplateCatalog` 创建严格 provider，并声明 `destroyMethodName("close")`。启动级属性改由 `SpringApplicationBuilder.properties(...)` 提前注入，避免惰性属性在自动配置判断之后才生效。
- GREEN 结果：同一命令 1 个测试通过；断言容器内 provider 持有同一 `TemplateCatalog` 实例，关闭上下文后再次调用已取得的 BM25 会抛异常，证明索引资源已释放。

### Important 2：严格 Hybrid/Dense 检索健康门禁

- 根因：`VueRagContext.degraded` 未进入结构化 observation/report；Dense 异常虽然写入 `error`，但 `passed()` 未检查；评测沿用生产 provider 的 BM25 降级初始化；报告也未校验逐行健康、queryId 唯一性和双链用例集合一致性。
- RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" bash mvnw -Dtest='VueRetrievalResourceProviderTest,VueRetrievalEvaluationReportTest,VueRetrievalEvaluatorTest' test`
- RED 结果：testCompile 按预期失败，缺少严格评测创建入口 `forEvaluation(...)` 和结构化字段 `VueRetrievalObservation.degraded`；测试未进入执行阶段。
- GREEN：新增仅供质量评测使用的 BM25 fail-closed 创建路径；生产 `RagProperties` 构造器继续保留在线降级。Hybrid 的 `degraded`、Dense 异常和 `error` 进入结构化观察及 Markdown；`passed()` 强制双链各 30 行、无错误、无退化、Hybrid queryId 30 个唯一且 Hybrid/Dense queryId 集合相同，再检查指标阈值。
- GREEN 结果：同一命令 13 个测试全部通过，0 失败、0 错误、0 跳过。

### 追加验证

- Task 2 九类覆盖回归：33 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`；其中真实完整 Spring Boot 上下文实际启动。
- 默认 unset 环境完整 Vue RAG 回归：122 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`；没有运行真实模型、数据库评测、正式摄取或十条 npm 生成。
- `git diff --check`：通过。
- 追加独立评审：Critical=0，Important=0；唯一 Minor 为 evaluator 测试只命中 `degraded` 表头，已收紧为直接断言结构化行渲染 `| true |`。
- 生产语义边界：在线 `VueRetrievalResourceProvider(RagProperties, ObjectMapper)` 的 BM25 失败降级保持不变；fail-closed 只用于离线质量门禁。

## 2026-08-11 第二轮正式复审 Important 修复追加

### Important 1：评测强制属性不再被宿主环境覆盖

- 根因：`SpringApplicationBuilder.properties(...)` 只设置 default properties，优先级低于环境变量和宿主属性源；因此生产 Hybrid 开关为 false 或摄取开关为 true 时，十条生成评测可能绑定到错误语义，PGVector 目标也可能与同轮前置核验不一致。
- RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" env -u RAG_EVAL -u RAG_BUILD_EVAL -u DASHSCOPE_API_KEY -u DEEPSEEK_API_KEY -u RAG_PGVECTOR_PASSWORD bash mvnw -Dtest='VueGenerationBuildQualityGateTest#评测强制属性覆盖宿主冲突配置且保持快照生命周期,VueRetrievalEvaluationReportTest#外部完美汇总不得掩盖三十条全错明细+双链QueryId相同但完整用例不同不得通过' test`
- RED 结果：3 个测试全部按预期失败。完整 Spring 上下文最终绑定 `rag.enabled=false`，属性覆盖断言失败；30 条全错明细配外部完美汇总错误通过；双链 queryId 相同但完整用例不同也错误通过。
- GREEN：使用 initializer 将本轮评测 `MapPropertySource` 加到 Environment 最高优先级，并在同一 initializer 保留现有 `BeanFactoryPostProcessor`；启动惰性改用 `SpringApplicationBuilder.lazyInitialization(true)`，不再依赖低优先级 default property。
- GREEN 结果：扩充正常报告用例后同类定向共 4 个测试全部通过。完整 Spring 最终绑定 `enabled=true`、`hybrid=true`、`ingest=false`，PG host/port/database/user/password 全部来自本轮 evaluation Map；provider 仍 `assertSame` 同一 catalog，关闭上下文后 BM25 访问失败。

### Important 2：报告只以逐行证据计算唯一指标真相

- 根因：公共 `executed(comparison, hybridRows, denseRows)` 同时接收汇总与明细，允许两份事实互相矛盾；`passed()` 又只比较 queryId 集合，无法识别相同 ID 下 query、style、期望骨架或期望功能不同。
- GREEN：删除所有允许外部传入 comparison 的 `executed(...)` 工厂；报告内部唯一调用 `VueRetrievalMetrics.calculate` 和 `VueRetrievalComparison.compare`。双链分别要求 30 行、30 个唯一 queryId、无 error/degraded，并比较 queryId 到完整 `VueEvalCase` 的映射后再检查内部指标。
- 新增证明：30 条全错但健康的 rows 不通过；双链 queryId 相同但完整用例不同不通过；30 条完整、健康、对应且真实指标达标的 rows 通过并渲染内部指标。

### 第二轮验证与自审

- 新增定向：4 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
- Task 2 扩大覆盖：43 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
- 默认 unset Vue RAG 回归：125 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`；未运行真实模型、数据库评测、正式摄取或十条 npm 生成。
- `git diff --check`：通过。
- 自审：生成评测入口无 `.properties(...)` 低优先级绕过；报告无 comparison 构造绕过；完整用例对应、快照复用、严格 BM25 与 Spring 关闭销毁均有自动化覆盖；生产在线降级代码未修改。

### 第二轮独立复核追加修正

- 首次独立复核发现 1 个 Important：最高优先级 Map 同时包含生命周期测试假 API Key，真实十条生成可能覆盖宿主密钥。
- 追加 RED：在完整 Spring 宿主模拟源提供真实 DeepSeek、DashScope、Pexels Key，并断言最终 Environment 保留宿主值。结果 1 个测试失败：期望 `host-deepseek-key`，实际为 `unused-by-context-lifecycle-test`。
- 修复：最高优先级评测 Map 收窄为 `rag.enabled`、`rag.hybrid.enabled`、`rag.ingest.enabled`、`rag.templates-dir` 与 `rag.pgvector.*`；不再包含任何模型或图像 API Key。生命周期占位 Key 只由该测试的模拟宿主属性源提供，不进入正式评测启动路径。
- 追加 GREEN：原快照生命周期与宿主冲突完整 Spring 测试 2 个全部通过；宿主 DeepSeek、DashScope、Pexels Key 均保持原值，RAG/PG 强制值、同一 catalog 与关闭销毁断言仍通过。
- 修正后重新验证：Task 2 扩大覆盖 43/43、默认 unset Vue RAG 回归 125/125，均 0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
- 修正后独立复核：Critical=0，Important=0，Minor=0，可以提交。

## 2026-08-11 第三轮正式复审 Important 修复追加

### Important：生成阶段复用同轮规范化 PGVector 目标

- 根因：生成门禁入口先从同轮 variables 构造规范化 `VuePgVectorTarget`，物理核验和真实检索复用该 target；但生成 Supplier 只传 catalog，生成 Spring 再次 `System.getenv()` 并从原始字符串重复构造 PG 属性。原始端口非法、0 或 70000 时，前置 target 均规范化为 5432，而生成上下文仍使用原值。
- RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" bash mvnw -Dtest='VueGenerationBuildQualityGateTest#生成属性复用同轮规范化PgVector目标' test`
- RED 结果：参数化 3 个测试全部失败；三次均期望规范化端口 5432，实际依次为 `非法端口`、`0`、`70000`。
- GREEN：入口一次读取 variables、一次构造 target、一次取得 `RAG_PGVECTOR_PASSWORD`；同一 target 显式传入物理 verifier、真实检索与生成 Spring。`evaluateGeneration`、`startEvaluationApplication`、`evaluationProperties` 不再读取环境或解析 PG 目标；host/port/database/user 全部只从 target 写入，password 只接收入口同轮值。
- GREEN 定向：三组非法端口属性测试加完整 Spring 绑定测试共 4 个，0 失败、0 错误、0 跳过；完整 `VueGenerationBuildQualityGateTest` 8/8。

### 第三轮验证与自审

- Task 2 扩大覆盖：46 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
- 默认 unset Vue RAG 回归：128 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`；未运行真实模型、数据库评测、正式摄取或十条 npm 生成。
- `git diff --check`：通过。
- `rg` 自审：生成门禁只有入口一处 `System.getenv()`；真实链只有入口一次 `VuePgVectorTarget.from(variables)`；下游 PG 属性构造无 `valueOrDefault` 或环境重解析。

### 第三轮独立复核追加修正

- 首次独立复核发现 2 个 Important：readiness 通过 `inspectSystemEnvironment()` 先读取一次系统环境，生成核心随后又读取一次；检索 runner 虽复用 target，仍从整张 variables 下游重新抽取 PG 密码。
- 追加 RED 命令：
  `JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" bash mvnw -Dtest='VueGenerationBuildQualityGateTest#生成入口使用调用方冻结的同一环境快照,VueRetrievalQualityGateTest#retrievalEvaluationUsesDedicatedPgVectorPassword,VueRetrievalIngestionPrerequisiteTest#规范化目标同时用于核验和评测配置' test`
- 追加 RED 结果：testCompile 按预期出现 3 个错误，缺少接受冻结 variables 的生成核心签名，且检索属性 API 仍要求整张 Map、不能接受显式 password。
- 修复：生成无参入口只执行一次 `System.getenv()` 并委托可测核心；核心使用 `VueGenerationBuildEnvironment.inspect(variables)`，从同一 Map 一次构造 target、一次抽取 PG 密码和 DashScope Key。检索 `evaluateDataset` 只接收显式 target、PG 密码、DashScope Key 和 catalog，删除下游对 `RAG_PGVECTOR_PASSWORD` 的按键访问。
- 追加 GREEN：冻结环境与显式密码目标测试 2/2；直接受影响的生成、检索、摄取前置测试 19/19。
- 最终验证：Task 2 扩大覆盖 47/47，默认 unset Vue RAG 回归 129/129，均 0 失败、0 错误、0 跳过，`BUILD SUCCESS`。

### 第三轮最终独立复核

- 最终复核：Critical=0，Important=0，可以提交。
- 复核确认：生成门禁只在入口适配层读取一次 `System.getenv()`；readiness、target、密码及后续流程复用同一冻结 variables。
- 复核确认：检索 runner 不再接收整张 variables，也不再查询 `RAG_PGVECTOR_PASSWORD`；同一 target 贯穿物理核验、真实检索和生成 Spring 属性。
- 独立验证：显式 unset 外部评测开关与密钥后，受影响测试 22/22，0 失败、0 错误、0 跳过；`git diff --check` 通过，未运行真实外部评测。
- Minor（不阻断）：`VueGenerationBuildEnvironment.inspectSystemEnvironment()` 已无调用，后续可单独删除或收窄，避免未来绕过冻结快照；本次不扩大修复范围。
