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
