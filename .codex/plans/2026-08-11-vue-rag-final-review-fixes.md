# Vue RAG 全分支终审修复实施计划

> **代理执行要求：** 必须使用 `subagent-driven-development` 或 `executing-plans` 逐任务执行；所有行为以 `.codex/sdd/vue-rag-final-review-fixes-spec.md` 为唯一需求来源，严格执行 TDD RED→GREEN。

**目标：** 关闭全分支终审的 5 项 Important，使知识目录、PGVector 凭据、真实质量门禁、报告生命周期和生产开关形成一致、可审计、fail-closed 的发布链。

**架构：** 在生产目录加载边界补齐 schema 契约；测试门禁侧统一抽取 RAG 专用环境变量、同轮结构化前置编排和原子报告写入器。十条生成评测只消费本轮物理摄取与真实检索结果，不解析历史报告，也不改变在线生产链的降级语义。

**技术栈：** Java 25、Spring Boot、JUnit 5、Jackson、PGVector、Maven、Docker Compose。

## 全局约束

- 只支持 `schemaVersion: 1`，不升级为版本 2。
- RAG PGVector 密码变量唯一为 `RAG_PGVECTOR_PASSWORD`，不得回退 MySQL 的 `SPRING_DATASOURCE_PASSWORD`。
- 生成门禁顺序固定为：23 条物理核验 → 30 条真实检索并要求 `passed()` → 启动 Spring → 十条生成构建。
- 报告不能作为程序前置状态输入；必须使用同轮 Java 结构化结果。
- 报告先失效、异常时写本轮未通过、同目录临时文件原子替换，并带运行标识。
- `RAG_HYBRID_ENABLED` 生产默认值保持 `false`。
- 所有中文与代码文件使用 UTF-8；提交信息使用全量中文；不 push、不合并。
- 不伪造真实摄取、检索指标或 10/10 构建成绩。

---

### Task 1：收紧目录契约并统一 PGVector 密码

**文件：**

- 修改：`src/main/java/com/lyw/appgeneration/service/rag/catalog/TemplateCatalog.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/rag/catalog/TemplateCatalogTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironment.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironmentTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/ingest/VueKnowledgeIngestionQualityGateTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionReport.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueEvaluationEnvironment.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueEvaluationEnvironmentTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildEnvironment.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildEnvironmentTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildQualityGateTest.java`
- 修改：`.codex/plans/2026-08-11-vue-knowledge-ingestion-quality-gate-design.md`
- 修改：`.codex/plans/2026-08-11-vue-knowledge-ingestion-quality-gate-implementation.md`

**接口：**

- 目录继续由 `new TemplateCatalog(Path, ObjectMapper)` 构造，不新增生产公共入口。
- 三类环境模型继续只返回 `ready/reasons/target` 等非秘密状态；密码只在门禁入口从当前环境 Map 临时读取。
- 依赖 Map 缺失或为 `null` 等价于空 Map；非空 Map 的每个键和值必须是非空白文本。

- [ ] **步骤 1：为目录契约编写失败测试**

  在 `TemplateCatalogTest` 增加参数化用例，分别删除/置空 `schemaVersion`、`version`、`framework`、`language`、`buildTool`，增加 `schemaVersion=2`、空白依赖名、空白依赖版本和 `null` 依赖版本；断言异常包含来源路径与字段名。

- [ ] **步骤 2：运行目录 RED**

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
    bash mvnw -Dtest=TemplateCatalogTest test
  ```

  预期：新增用例因当前加载器接受无效工程元数据而失败。

- [ ] **步骤 3：实现最小目录校验**

  在 `validateDocument` 中校验 `schemaVersion == 1` 和四个非空工程字段；新增一个职责明确的私有方法检查依赖键值，不改变现有 package.json 一致性规则和知识块数量。

- [ ] **步骤 4：运行目录 GREEN**

  重跑步骤 2；预期全部通过。

- [ ] **步骤 5：为 RAG 专用密码编写失败测试**

  更新三类环境测试：环境同时提供不同的 `SPRING_DATASOURCE_PASSWORD=mysql-secret` 与 `RAG_PGVECTOR_PASSWORD=pg-secret`；断言缺少 PG 密码时短路，存在 PG 密码时就绪，任何状态对象均不渲染秘密。为门禁入口补参数捕获测试，证明 verifier、store/Spring 属性接收 `pg-secret`。

- [ ] **步骤 6：运行密码 RED**

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
    bash mvnw -Dtest='VueIngestionEnvironmentTest,VueEvaluationEnvironmentTest,VueGenerationBuildEnvironmentTest,VueKnowledgeIngestionQualityGateTest,VueRetrievalQualityGateTest,VueGenerationBuildQualityGateTest' test
  ```

  预期：当前实现仍要求或传递 `SPRING_DATASOURCE_PASSWORD`，新增断言失败。

- [ ] **步骤 7：切换三类门禁密码并更新受控原因**

  所有 PGVector JDBC、EmbeddingStore 与评测 Spring 属性只读取 `RAG_PGVECTOR_PASSWORD`；同步更新环境缺失原因、报告原因白名单和两份 2026-08-11 文档命令示例，不保留兼容回退。

- [ ] **步骤 8：运行任务 1 GREEN 与回归**

  重跑步骤 6，并追加：

  ```bash
  rg -n "SPRING_DATASOURCE_PASSWORD" \
    src/test/java/com/lyw/appgeneration/rag \
    .codex/plans/2026-08-11-vue-knowledge-ingestion-quality-gate-*.md
  ```

  预期：测试通过；搜索只允许出现明确证明“不串用 MySQL 密码”的测试夹具或迁移说明，不能出现 PGVector 取值代码和旧命令示例。

- [ ] **步骤 9：提交任务 1**

  ```bash
  git add src/main/java/com/lyw/appgeneration/service/rag/catalog/TemplateCatalog.java \
    src/test/java/com/lyw/appgeneration/rag \
    .codex/plans/2026-08-11-vue-knowledge-ingestion-quality-gate-design.md \
    .codex/plans/2026-08-11-vue-knowledge-ingestion-quality-gate-implementation.md
  git commit -m "修复: 收紧Vue知识目录与PGVector门禁"
  ```

---

### Task 2：实现同轮真实质量前置与可靠报告生命周期

**文件：**

- 新建或修改：`src/test/java/com/lyw/appgeneration/rag/eval/AtomicEvaluationReportWriter.java`
- 新建或修改：`src/test/java/com/lyw/appgeneration/rag/eval/AtomicEvaluationReportWriterTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalEvaluationReport.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalEvaluationReportTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateRunner.java`
- 新建或修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateRunnerTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildReport.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildReportTest.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildQualityGateRunner.java`
- 新建：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildQualityGateRunnerTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildQualityGateTest.java`

**接口：**

- `AtomicEvaluationReportWriter.write(Path target, String markdown)`：UTF-8、同目录临时文件、原子替换优先、替换降级。
- 检索/生成报告新增 `failed(String runId, List<String> reasons)` 或等价工厂；Markdown 明确渲染 `状态：未通过` 和非秘密运行标识。
- `VueGenerationBuildQualityGateRunner` 以 Supplier/函数参数依次取得 `VueIngestionVerification`、`VueRetrievalEvaluationReport` 和 `VueGenerationBuildReport`；只有前两者均通过才调用生成 Supplier。
- 门禁测试入口每轮开始先写失败占位；任一异常覆盖为本轮失败后原样重抛。

- [ ] **步骤 1：为原子报告写入和失败状态编写 RED**

  新增测试预置旧的 `状态：通过` 文件，调用 writer 后断言正式路径只含新内容、无临时文件遗留；为两个报告补“未通过 + runId + 脱敏”断言。

- [ ] **步骤 2：运行报告 RED**

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
    bash mvnw -Dtest='AtomicEvaluationReportWriterTest,VueRetrievalEvaluationReportTest,VueGenerationBuildReportTest' test
  ```

  预期：writer/failed 工厂尚不存在或状态断言失败。

- [ ] **步骤 3：实现最小原子 writer 与失败报告**

  writer 在目标父目录创建临时文件，写 UTF-8，优先 `ATOMIC_MOVE + REPLACE_EXISTING`，不支持时使用 `REPLACE_EXISTING`；finally 清理临时文件。两个报告所有状态带本轮运行标识，继续调用 `EvaluationReportSanitizer`。

- [ ] **步骤 4：运行报告 GREEN**

  重跑步骤 2；预期全部通过。

- [ ] **步骤 5：为生成前置编排编写 RED**

  `VueGenerationBuildQualityGateRunnerTest` 至少覆盖：物理核验失败不调用检索/生成；检索未执行不调用生成；检索执行但未达标不调用生成；两项前置通过才调用生成；各 Supplier 异常原样传播。用计数器证明调用顺序，不连接数据库或模型。

- [ ] **步骤 6：运行编排 RED**

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
    bash mvnw -Dtest='VueRetrievalQualityGateRunnerTest,VueGenerationBuildQualityGateRunnerTest' test
  ```

  预期：生成 Runner 不存在或不满足 fail-closed 断言。

- [ ] **步骤 7：实现结构化同轮编排**

  复用 `VueIngestionVerification` 与 `VueRetrievalEvaluationReport.passed()`；生成 Runner 不读取文件。重构检索入口，使同一轮结构化检索执行能力可被生成门禁复用，且不重复创建互相不一致的目录快照。

- [ ] **步骤 8：为旧报告异常覆盖编写 RED**

  对检索和生成门禁入口提取可测试执行器或生命周期方法。测试先写旧通过报告，再令目录/数据集/核验/模型服务或 Spring Supplier 抛出受控异常；断言异常类型/实例保持不变，报告被本轮 `状态：未通过` 与新 runId 覆盖，且不含旧指标或 `10/10`。

- [ ] **步骤 9：运行生命周期 RED**

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
    bash mvnw -Dtest='VueRetrievalQualityGateTest,VueGenerationBuildQualityGateTest' test
  ```

  预期：当前入口只在成功返回报告后写文件，旧报告覆盖断言失败。

- [ ] **步骤 10：接入先失效、异常覆盖和同轮前置**

  两个入口在任何目录、数据集、模型、Spring 操作前写本轮失败占位；环境未启用时改写为未执行；异常时写受控失败报告并重抛。生成入口在启动 Spring 前完成目录快照、物理核验和 30 条真实检索达标判断。

- [ ] **步骤 11：运行任务 2 GREEN 与定向回归**

  重跑步骤 9，再执行：

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
  MAVEN_OPTS='-DsocksNonProxyHosts=localhost|127.*|[::1]' \
    env -u RAG_VUE_INGEST -u RAG_EVAL -u RAG_BUILD_EVAL \
        -u DASHSCOPE_API_KEY -u DEEPSEEK_API_KEY -u RAG_PGVECTOR_PASSWORD \
        bash mvnw -Dtest='VueIngestion*,VuePgVector*,VueRetrieval*,VueEvaluation*,VueGenerationBuild*,TemplateCatalogTest' test
  ```

  预期：全部通过；三个外部门禁均为真实未执行状态，没有伪指标。

- [ ] **步骤 12：提交任务 2**

  ```bash
  git add src/test/java/com/lyw/appgeneration/rag
  git commit -m "修复: 完善Vue真实质量门禁前置与报告"
  ```

---

### Task 3：接入生产 Hybrid 开关并完成全分支验收

**文件：**

- 修改：`prod/docker-compose.yml`
- 修改：`prod/.env.example`
- 修改：`prod/README.md`
- 新建或修改：`src/test/java/com/lyw/appgeneration/config/ProductionRagDeploymentConfigTest.java`
- 修改：`.codex/sdd/progress.md`
- 修改：`.codex/sdd/whole-branch-review.md`

**接口：**

- Compose backend 环境变量固定为 `RAG_HYBRID_ENABLED: ${RAG_HYBRID_ENABLED:-false}`。
- `.env.example` 固定提供 `RAG_HYBRID_ENABLED=false`。
- README 固定说明三项门禁依次通过后，人工设为 true 并重启 backend。

- [ ] **步骤 1：编写生产配置 RED**

  新增静态资源测试，读取三个生产文件并断言 Compose 默认 false、示例默认 false、README 中摄取→检索→十条生成→开启→重启顺序完整。

- [ ] **步骤 2：运行配置 RED**

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
    bash mvnw -Dtest=ProductionRagDeploymentConfigTest test
  ```

  预期：当前生产文件缺少 Hybrid 开关而失败。

- [ ] **步骤 3：补齐 Compose、环境示例和部署文档**

  按规格加入变量与可执行发布顺序；文档同时说明真实门禁失败时保持 false，不把默认 Maven 或 PGVector 协议探针当作开启依据。

- [ ] **步骤 4：运行配置 GREEN 与静态检查**

  重跑步骤 2，并执行：

  ```bash
  git diff --check
  rg -n "RAG_HYBRID_ENABLED" prod/docker-compose.yml prod/.env.example prod/README.md
  ```

- [ ] **步骤 5：提交生产开关**

  ```bash
  git add prod/docker-compose.yml prod/.env.example prod/README.md \
    src/test/java/com/lyw/appgeneration/config/ProductionRagDeploymentConfigTest.java
  git commit -m "文档: 接入Vue混合检索生产开关"
  ```

- [ ] **步骤 6：运行完整定向回归**

  使用任务 2 步骤 11 的命令，并保存完整日志到 `.codex/sdd/`。记录测试数、failure、error、skipped 和退出码。

- [ ] **步骤 7：运行完整 Maven**

  ```bash
  JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$JAVA_HOME/bin:$PATH" \
  MAVEN_OPTS='-DsocksNonProxyHosts=localhost|127.*|[::1]' \
    env -u RAG_VUE_INGEST -u RAG_EVAL -u RAG_BUILD_EVAL \
        -u DASHSCOPE_API_KEY -u DEEPSEEK_API_KEY -u RAG_PGVECTOR_PASSWORD \
        bash mvnw test
  ```

  必须 fresh 读取最终汇总和退出码；不能沿用历史 317 项结果。

- [ ] **步骤 8：更新审计文档**

  在 `.codex/sdd/progress.md` 记录每个修复提交、RED/GREEN 和完整 Maven 证据；重写 `.codex/sdd/whole-branch-review.md` 顶部当前结论，删除 5 项未修复前的“代码可合并”判断。没有真实凭据时明确三项未执行和“不可发布”。

- [ ] **步骤 9：生成全分支评审包并独立终审**

  以 `5850ef9` 为基线生成 `5850ef9..HEAD` 的 commit、stat 与完整 diff 评审包，交给只读独立评审代理。Critical 或 Important 非零时，使用一个修复代理处理完整 finding 列表，重跑覆盖测试后再次终审。

- [ ] **步骤 10：提交最终证据文档**

  ```bash
  git add .codex/sdd/progress.md .codex/sdd/whole-branch-review.md .codex/sdd/*.log
  git commit -m "文档: 更新Vue RAG终审验证证据"
  ```

  只提交有审计价值且未被忽略的 UTF-8 文本证据，不强行提交 `target/`、模型输出、生成应用或数据库运行数据。
