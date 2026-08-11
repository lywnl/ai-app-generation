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
