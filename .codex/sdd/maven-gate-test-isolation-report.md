# Maven 测试门禁隔离报告

## 结论

默认 Maven 测试已在不提供模型密钥、数据库、Redis、PGVector 或远程服务的条件下通过。

- 命令：`JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" sh ./mvnw test`
- 结果：278 项、0 failure、0 error、8 skipped、退出码 0。
- 运行环境：项目要求 Java 25；系统默认 Java 17 会在 Surefire 加载已有 Java 25 类文件前失败（class file version 69），故验证显式使用工作树提供的 JDK 25。

## RED 复现与根因

### JsonMessageStreamHandlerTest

复现命令：

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
sh ./mvnw -Dtest=JsonMessageStreamHandlerTest test
```

修改前结果：2 项、0 failure、2 error。两个测试均在
`JsonMessageStreamHandler.java:100` 抛出：

```text
Cannot invoke "ToolMessageCollapser.collapseLastTurn(long, String)"
because "this.toolMessageCollapser" is null
```

根因：生产类新增了 `@Resource ToolMessageCollapser`，但 Mockito `@InjectMocks` 夹具没有对应 `@Mock`，因此没有注入。不是生产逻辑可以接受 null，也不能用吞异常规避。

### SpringBootTest 外部依赖图

最小复现命令（临时进程变量，未写入仓库）：

```bash
DEEPSEEK_API_KEY=temporary-test-value \
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
sh ./mvnw -Dtest=AiAppGenerationApplicationTests test
```

结果：补齐 DeepSeek 临时值后，上下文继续在 `ImageSearchTool` 的
`@Value("${pexels.api-key}")` 处因缺少 `PEXELS_API_KEY` 失败。

再用临时 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、`PEXELS_API_KEY`、COS 参数运行后，
上下文继续创建 `RedissonConfig.redissonClient()` 并连接 `localhost:6379`，以
`RedisConnectionException: Connection refused` 失败；日志也记录了 PGVector 到 `localhost` 的连接失败。

证据表明原先的多个 `@SpringBootTest` 并非隔离测试：它们启动完整生产依赖图，涵盖模型、对象存储、Redis、数据库与 PGVector。仅写假密钥无法解决该根因，反而仍会触发真实网络连接。

## 同仓库模式与方案选择

- `RagEvaluationTest` 和 `VueSkeletonRealBuildTest` 已用 `@EnabledIfEnvironmentVariable` 明确标记必须人工提供外部环境的评测/真实构建。
- `CodeParserTest` 的两项断言只调用静态纯解析逻辑，不读取 Spring Bean；移除无用的 `@SpringBootTest` 后仍覆盖原断言。
- 对真实模型调用、网页访问、并发模型路由及完整生产上下文的测试，新增同类显式开关 `EXTERNAL_INTEGRATION_TESTS=true`。默认测试不会跳过普通业务单测；只有本身需要真实外部环境的测试会跳过，且仍可被显式执行。

未选择测试 profile + 假密钥的原因：完整上下文会继续实例化 Redisson、MySQL、PGVector、COS 及模型客户端，需要大量生产 Bean mock/自动配置排除；这既扩大范围，也不能验证这些网络探针的真实语义。

## RED → GREEN 最小修改

1. `JsonMessageStreamHandlerTest` 新增 `ToolMessageCollapser` mock；首轮场景 stubbing `collapseLastTurn`，并验证它位于历史写入之后、自检前；同时验证首轮会 restore、非首轮不 restore。
2. `CodeParserTest` 删除多余的 Spring 上下文注解。
3. 给五个真实外部集成测试新增 `@EnabledIfEnvironmentVariable(named = "EXTERNAL_INTEGRATION_TESTS", matches = "true")`。

修改文件：

- `src/test/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandlerTest.java`
- `src/test/java/com/lyw/appgeneration/core/CodeParserTest.java`
- `src/test/java/com/lyw/appgeneration/AiAppGenerationApplicationTests.java`
- `src/test/java/com/lyw/appgeneration/AiConcurrentTest.java`
- `src/test/java/com/lyw/appgeneration/ai/AiCodeGeneratorServiceTest.java`
- `src/test/java/com/lyw/appgeneration/ai/AiCodeGenTypeRoutingServiceTest.java`
- `src/test/java/com/lyw/appgeneration/utils/WebScreenshotUtilsTest.java`

没有改动 `src/main/**`、生产配置或 `pom.xml`，没有写入长期假密钥。

## GREEN 与完整回归

定向 GREEN：

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
sh ./mvnw -Dtest=JsonMessageStreamHandlerTest test
```

结果：2 项、0 failure、0 error、0 skipped、退出码 0。

完整默认回归：

```bash
JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
sh ./mvnw test
```

结果：278 项、0 failure、0 error、8 skipped、退出码 0。

跳过项为：6 个 `EXTERNAL_INTEGRATION_TESTS` 真实外部测试（完整应用上下文、并发模型路由、两项生成模型调用、路由模型调用、远程网页截图）、`RagEvaluationTest`（`RAG_EVAL`）和 `VueSkeletonRealBuildTest`（`RAG_SKELETON_BUILD`）；它们均保留显式人工执行入口。完整构建的 `target/surefire-reports` 与 Maven 控制台摘要为准。

格式检查：`git diff --check` 通过（退出码 0）。

## 自审与遗留风险

- 自审确认 `git diff -- src/main` 无输出，所有代码改动都在允许的 `src/test/java/**`；报告为允许的 `.codex/sdd/**`。独立只读复核未发现 Critical 或 Required 问题，并确认其余默认测试均为 Mockito 或内存依赖。
- 外部集成测试仍需在有授权密钥和真实服务的环境中以 `EXTERNAL_INTEGRATION_TESTS=true` 执行；默认 Maven 门禁刻意不替代它们。
- Java 25 是独立环境前置条件。默认系统 Java 17 的 class-file 版本错误不属于测试隔离缺陷；本工作树提供 `.codex/runtime/jdk25` 可用于本地验证。
- Maven 仍打印 Mockito 动态附加 agent、Lombok Unsafe 与 Lucene Vector API 警告；这些不造成测试失败，且本任务未改构建插件。

## 提交

提交 SHA：见本报告所属的 Git 提交；最终交付中以 `git rev-parse HEAD` 的输出为准。报告与提交不能同时固定自身哈希（将 SHA 写入提交内容会改变提交 SHA）。
