# 记忆投影与工具协议恢复改造执行计划

> **执行要求：** 使用 `subagent-driven-development` 按任务串行实施；每个任务必须遵循 TDD（先红后绿）、独立代码审查、显式文件列表 Git 提交。每完成一个步骤，将对应复选框改为 `- [x] ✅`；未完成步骤保持 `- [ ]`。

**目标：** 彻底分离前端展示文本与模型记忆投影，阻止工具展示轨迹污染 L0/L1/冷启动；当 Vue 在线模型连续两次以普通正文模拟同一工具调用时，自动进行一次协议纠正，若再次退化则熔断为 `PROTOCOL_ERROR`，并通过可信 SSE 向前端展示友好的恢复状态。

**架构：** `chat_history.message` 继续保存用户可见内容；新增 `memoryMessage` 与 `memoryOutcome` 保存后端根据真实结构化事件生成的可信 AI 投影。所有模型记忆入口统一通过一个角色感知解析器读取投影。工具协议恢复在同一 `AiServiceTokenStream`、同一回合控制器和同一 Token 门禁内完成，采用 generation 级语义撤销隔离迟到回调；前端只消费服务端可信控制事件，不解析模型正文来决定恢复状态。

**技术栈：** Java 25、Spring Boot 3.5.4、LangChain4j 1.1.0/1.1.0-beta7 定制流式层、MyBatis-Flex、MySQL 8、Redis 7、Vue 3、TypeScript、Vitest、Chrome。

## 全局约束

1. 全程 UTF-8、简体中文注释与提交信息；未经允许不得 push。
2. 只在当前 worktree `/Users/terminus1/Documents/ai-gen-soft/ai-app-generation/.worktrees/token-layered-memory-v3` 工作；不得修改其他工作区或系统文件。
3. 保留所有用户已有改动与 `.codex/` 诊断材料；禁止 `git add .`、`git clean`、`git reset --hard`。
4. Java 验证固定使用工作区 JDK：

   ```bash
   export JAVA_HOME="$PWD/.codex/runtime/jdk-25.0.4+7/Contents/Home"
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

5. 固定 Token 预算保持不变：L0 `12288`、L1 摘要最大 `3072`、L2 `1024`、异步压缩阈值 `28672`、阻塞压缩阈值 `30720`、输入硬上限 `32768`、输出预算 `8192`、安全系数 `1.15`。
6. `message` 只承担展示语义；AI 的 `memoryMessage` 不得包含 `[工具调用]`、工具参数 JSON、读文件正文、代码 diff、逐步工具轨迹、模型思考或未经执行的成功声明。
7. 新 AI 回合的 `memoryMessage` 必须由后端真实结构化事件确定性生成，禁止再调用模型自由总结本轮结果。
8. 用户历史继续读取 `message`；AI 历史只读取 `memoryMessage`。AI 投影为空时禁止回退 `message`。
9. HTML/MULTI_FILE 成功回合兼容完整代码上下文：`memoryMessage = message`、`memoryOutcome = SUCCEEDED`；失败回合保存固定安全投影与 `SYSTEM_ERROR`。
10. L2 偏好证据仍只来自用户原话；AI 行只用于闭合回合，并通过 `memoryOutcome` 排除 `PROTOCOL_ERROR`、`LEGACY_UNVERIFIED` 及缺失投影的回合，同时可靠推进扫描游标。
11. 工具协议检测只启用于 Vue 在线生成；HTML、MULTI_FILE 与离线评测不启用。
12. 同一 generation 同时满足“尚未观察到结构化 tool call、注册工具名、完整严格 JSON、连续两个规范化指纹完全相同的伪工具块”才判定退化；不得使用宽松模糊匹配。
13. 第一次退化只撤销当前 generation，自动纠正一次；纠正 generation 再次退化直接 `PROTOCOL_ERROR`，不得创建第三个模型请求。
14. 临时纠正指令参与 28K/30K/32K 门禁和输入 Token 估算，但不得进入 ChatMemory、MySQL、L0、L1、L2；恢复后真实结构化工具调用与工具结果仍按正常工具循环进入操作性记忆。
15. 旧 generation 的正文、工具分片、完整响应、错误、完成回调和迟到 handle 必须全部按 generation 身份丢弃；底层不支持物理取消时以语义隔离保证正确性，不虚构精确供应商用量。
16. 新增受信 SSE：`event: tool-protocol-recovery`，协议固定 `tool-protocol-recovery/v1`，阶段固定 `STARTED | RECOVERED | FAILED`，文案必须使用后端固定安全文案。
17. 前端状态优先级固定为：`上下文压缩中 > 工具协议校正中 > AI 正在思考`；真实正文或结构化工具开始后隐藏校正提示。
18. 历史迁移不删除、不修改 `message`；旧 Vue AI 一律 `LEGACY_UNVERIFIED`，旧 HTML/MULTI_FILE 可 `LEGACY_IMPORTED`；已知故障消息 `447109043745288192` 定向标记 `PROTOCOL_ERROR`。
19. Redis L0 使用版本化 key 前缀失效旧缓存，不做宽泛通配删除；旧 key 依赖现有 3600 秒 TTL 自然过期。
20. 每个生产代码任务必须：先写失败测试并保存 RED 证据，再写最小实现，相关测试通过后审查，最后更新本计划并中文提交。

---

### 任务 0：恢复计划并建立可信基线

**文件：**

- 创建：`.codex/plans/2026-08-18-memory-projection-tool-protocol-recovery-implementation.md`
- 证据：`.codex/verification/task-0-backend-baseline-java25.log`
- 证据：`.codex/verification/task-0-frontend-baseline.log`
- 只读报告：`.codex/reviews/current-memory-projection-map.md`
- 只读报告：`.codex/reviews/current-tool-protocol-recovery-map.md`
- 只读报告：`.codex/reviews/current-frontend-protocol-map.md`

- [x] ✅ **步骤 0.1：确认隔离 worktree、分支与用户改动边界**

  已确认当前目录是 linked worktree，分支为 `codex/token-layered-memory-v3`；生产文件无未提交改动，`.codex/` 存在既有未跟踪诊断材料，后续只显式暂存本任务文件。

- [x] ✅ **步骤 0.2：读取项目规则、编码规范和执行技能**

  已读取 AI 结对开发、执行计划、子代理开发、TDD、代码审查、完成前验证、Java 与前端规范。

- [x] ✅ **步骤 0.3：完成三条链路的源码映射**

  已核实数据库/记忆链、LangChain4j generation/取消链和前端 SSE/UI 链；确认 L2 只使用用户文本，AI 行仅闭合回合。

- [x] ✅ **步骤 0.4：运行后端全量基线**

  ```bash
  export JAVA_HOME="$PWD/.codex/runtime/jdk-25.0.4+7/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  bash mvnw test
  ```

  结果：`Tests run: 1406, Failures: 0, Errors: 0, Skipped: 7`，`BUILD SUCCESS`。

- [x] ✅ **步骤 0.5：运行前端全量基线**

  ```bash
  cd ai-app-generation-frontend
  npm test -- --run
  ```

  结果：`7` 个测试文件、`134` 项测试全部通过。

- [x] ✅ **步骤 0.6：自审计划并提交**

  检查无 `TBD/TODO/稍后实现`，核对所有接口名、状态值、固定数字、任务依赖和验收命令；随后仅提交计划文件：

  ```bash
  git add -f .codex/plans/2026-08-18-memory-projection-tool-protocol-recovery-implementation.md
  git commit -m "计划：明确记忆投影与工具协议恢复改造步骤"
  ```

---

### 任务 1：建立 AI 记忆投影的数据库与领域契约

**文件：**

- 修改：`src/main/java/com/lyw/appgeneration/model/entity/ChatHistory.java`
- 创建：`src/main/java/com/lyw/appgeneration/model/enums/ChatMemoryOutcome.java`
- 创建：`src/main/java/com/lyw/appgeneration/ai/memory/ChatHistoryMemoryResolver.java`
- 修改：`src/main/java/com/lyw/appgeneration/service/ChatHistoryService.java`
- 修改：`src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java`
- 修改：`sql/schema.sql`
- 修改：`prod/sql/schema.sql`
- 创建：`sql/migrations/2026-08-18-chat-history-memory-projection.sql`
- 创建：`prod/sql/migrations/2026-08-18-chat-history-memory-projection.sql`
- 修改：`src/test/java/com/lyw/appgeneration/sql/MemorySchemaMigrationContractTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImplLoadTest.java`
- 创建：`src/test/java/com/lyw/appgeneration/ai/memory/ChatHistoryMemoryResolverTest.java`

**产出接口：**

```java
public enum ChatMemoryOutcome {
    SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, SYSTEM_ERROR,
    PROTOCOL_ERROR, LEGACY_IMPORTED, LEGACY_UNVERIFIED
}

ChatHistory addAiMessageAndReturn(
        Long appId,
        String displayMessage,
        String memoryMessage,
        ChatMemoryOutcome memoryOutcome,
        Long userId);

public Optional<String> resolveModelText(ChatHistory history);
public boolean isEligibleForLongTermPreference(ChatHistory aiHistory);
```

- [x] ✅ **步骤 1.1：先写数据库与解析器红测**

  测试必须断言：实体存在 `memoryMessage/memoryOutcome`；AI 专用写入同时保存三个字段；用户消息仍只需 `message`；`repairOrphanUserTurn` 使用固定安全投影与 `SYSTEM_ERROR`，不得经旧通用 API 产生空投影 AI 行；AI 投影为空时 `resolveModelText` 返回空且绝不读取展示文本；用户消息解析为原始 `message`；L2 排除 `PROTOCOL_ERROR/LEGACY_UNVERIFIED/null`。

- [x] ✅ **步骤 1.2：运行红测并保存失败证据**

  ```bash
  bash mvnw -Dtest='MemorySchemaMigrationContractTest,ChatHistoryServiceImplLoadTest,ChatHistoryMemoryResolverTest' test \
    2>&1 | tee .codex/verification/task-1-red.log
  ```

  预期：因字段、枚举、AI 专用写入接口和迁移文件不存在而失败。

- [x] ✅ **步骤 1.3：实现最小领域与持久化契约**

  `memoryMessage` 映射 `MEDIUMTEXT NULL`，`memoryOutcome` 映射 `VARCHAR(32) NULL`；旧 `addChatMessage/addChatMessageAndReturn` 行为保持兼容，但所有生产 AI 调用方必须迁移到专用接口。`addAiMessageAndReturn` 固定写入 `messageType=ai` 并校验投影和 outcome 非空，避免调用方写错角色；`repairOrphanUserTurn` 也必须使用该接口写入可信系统错误投影。

- [x] ✅ **步骤 1.4：编写幂等 MySQL 迁移**

  两份迁移必须字节级相同；条件加列后按 `app.codeGenType` 回填：

  - `vue_project` AI：固定保守投影，`LEGACY_UNVERIFIED`；
  - `html/multi_file` AI：`memoryMessage=message`，`LEGACY_IMPORTED`；
  - 已知消息 `447109043745288192`：固定协议异常投影，`PROTOCOL_ERROR`；
  - 用户行保持 `memoryMessage/memoryOutcome=NULL`；
  - 不修改 `message`，脚本可重复执行且带元数据验收。

- [x] ✅ **步骤 1.5：运行绿测与静态一致性检查**

  ```bash
  bash mvnw -Dtest='MemorySchemaMigrationContractTest,ChatHistoryServiceImplLoadTest,ChatHistoryMemoryResolverTest' test
  cmp sql/migrations/2026-08-18-chat-history-memory-projection.sql \
      prod/sql/migrations/2026-08-18-chat-history-memory-projection.sql
  git diff --check
  ```

- [x] ✅ **步骤 1.6：独立审查、修复重要问题、勾选并提交**

  ```bash
  git add src/main/java/com/lyw/appgeneration/model/entity/ChatHistory.java \
    src/main/java/com/lyw/appgeneration/model/enums/ChatMemoryOutcome.java \
    src/main/java/com/lyw/appgeneration/ai/memory/ChatHistoryMemoryResolver.java \
    src/main/java/com/lyw/appgeneration/service/ChatHistoryService.java \
    src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java \
    sql/schema.sql prod/sql/schema.sql \
    sql/migrations/2026-08-18-chat-history-memory-projection.sql \
    prod/sql/migrations/2026-08-18-chat-history-memory-projection.sql \
    src/test/java/com/lyw/appgeneration/sql/MemorySchemaMigrationContractTest.java \
    src/test/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImplLoadTest.java \
    src/test/java/com/lyw/appgeneration/ai/memory/ChatHistoryMemoryResolverTest.java \
    .codex/plans/2026-08-18-memory-projection-tool-protocol-recovery-implementation.md
  git commit -m "重构：建立聊天展示与模型记忆投影契约"
  ```

---

### 任务 2：根据真实 Vue 工具事件生成确定性记忆投影

**文件：**

- 创建：`src/main/java/com/lyw/appgeneration/core/handler/VueTurnMemoryProjection.java`
- 创建：`src/main/java/com/lyw/appgeneration/ai/tools/VueToolExecutionFact.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/handler/VueTurnOutcome.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandler.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/handler/VueTurnFinalizer.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/handler/SimpleTextStreamHandler.java`
- 修改：`src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java`
- 创建：`src/test/java/com/lyw/appgeneration/core/handler/VueTurnMemoryProjectionTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandlerTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/core/handler/VueTurnFinalizerTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/core/handler/SimpleTextStreamHandlerTest.java`

**产出接口：**

```java
public record VueTurnOutcome(
        VueBuildPhase phase,
        TurnOutcomeType outcome,
        String displayAiText,
        String memoryAiText,
        boolean shouldRefreshPreview,
        String clientMessage) {}
```

- [ ] **步骤 2.1：先写确定性投影红测**

  覆盖真实 `file-tool/v1` 与 `vue-build-tool/v1` 事件：只记录实际执行工具名、真实变更文件、真实构建次数与终态；读文件正文、参数 JSON、代码 diff、模型声明均不出现。`PROTOCOL_ERROR` 使用固定失败投影。Simple 成功 `memoryMessage=message`，失败使用固定安全投影。

- [ ] **步骤 2.2：运行红测并保存证据**

  ```bash
  bash mvnw -Dtest='VueTurnMemoryProjectionTest,JsonMessageStreamHandlerTest,VueTurnFinalizerTest,SimpleTextStreamHandlerTest' test \
    2>&1 | tee .codex/verification/task-2-red.log
  ```

- [ ] **步骤 2.3：实现真实事件事实解析与投影构造**

  `VueToolExecutionFact` 严格解析现有受信工具结果协议；变更文件使用去重且保持首次出现顺序的相对路径。`VueTurnMemoryProjection` 只接收解析后的事实和终态，不接收任意模型自由文本。

- [ ] **步骤 2.4：分离展示累积与记忆事实累积**

  `JsonMessageStreamHandler` 继续把正文/工具卡写入 `displayAiText`，同时独立积累结构化事实；`VueTurnFinalizer` 使用 `addAiMessageAndReturn(displayAiText,memoryAiText,outcome)`，L0 折叠改传 `memoryAiText`。

- [ ] **步骤 2.5：运行相关绿测与回归测试**

  ```bash
  bash mvnw -Dtest='VueTurnMemoryProjectionTest,JsonMessageStreamHandlerTest,VueTurnFinalizerTest,SimpleTextStreamHandlerTest,AppServiceImplVueTurnTest,AppServiceSimpleTurnLifecycleTest' test
  git diff --check
  ```

- [ ] **步骤 2.6：独立审查、修复、勾选并中文提交**

  提交信息：`重构：按真实工具事件生成可信回合记忆`

---

### 任务 3：统一 L0、冷启动、L1、L2 与 Token 读取口径

**文件：**

- 修改：`src/main/java/com/lyw/appgeneration/ai/memory/ToolMessageCollapser.java`
- 修改：`src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java`
- 修改：`src/main/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngine.java`
- 修改：`src/main/java/com/lyw/appgeneration/service/impl/UserPreferenceBatchBuilder.java`
- 修改：`src/main/java/com/lyw/appgeneration/ai/memory/SpringRedisChatMemoryStore.java`
- 修改：`src/test/java/com/lyw/appgeneration/ai/memory/ToolMessageCollapserTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImplLoadTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngineTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/UserPreferenceBatchBuilderTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/ai/memory/SpringRedisChatMemoryStoreTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinatorTest.java`

- [ ] **步骤 3.1：先写所有记忆入口的污染回归红测**

  使用 `message` 含伪工具 Markdown、`memoryMessage` 为安全投影的同一 AI 行，断言 L0 折叠、冷启动、L1 prompt、稳定回合边界、Token 估算只看到投影。断言 L2 prompt 只含用户原话，协议异常/旧 Vue 不可信回合不作为证据但扫描游标推进。

- [ ] **步骤 3.2：运行红测并保存证据**

  ```bash
  bash mvnw -Dtest='ToolMessageCollapserTest,ChatHistoryServiceImplLoadTest,MemorySummaryDraftEngineTest,UserPreferenceBatchBuilderTest,SpringRedisChatMemoryStoreTest,ContextCompressionCoordinatorTest' test \
    2>&1 | tee .codex/verification/task-3-red.log
  ```

- [ ] **步骤 3.3：统一使用 `ChatHistoryMemoryResolver`**

  用户读取 `message`；AI 读取 `memoryMessage`。禁止任何调用点在 AI 投影为空时回退展示文本。现有摘要上限、12K 最近消息目标和 28K/30K/32K 门禁不变。

- [ ] **步骤 3.4：版本化 Redis L0 key**

  `SpringRedisChatMemoryStore.redisKey(memoryId)` 改为固定前缀 `chat-memory:l0:v2:` 加 memoryId；所有 get/update/delete/CAS 使用同一个方法。旧无前缀 key 不读取、不扫描、不批量删除，按现有 TTL 自然过期。

- [ ] **步骤 3.5：运行绿测与分层记忆集成测试**

  ```bash
  bash mvnw -Dtest='ToolMessageCollapserTest,ChatHistoryServiceImplLoadTest,MemorySummaryDraftEngineTest,UserPreferenceBatchBuilderTest,UserMemoryServiceImplTest,SpringRedisChatMemoryStoreTest,ContextCompressionCoordinatorTest,LayeredMemoryIntegrationTest,LayeredMemoryL2IntegrationTest' test
  git diff --check
  ```

- [ ] **步骤 3.6：独立审查、修复、勾选并中文提交**

  提交信息：`修复：统一分层记忆读取可信投影`

---

### 任务 4：实现纯增量伪工具块检测器

**文件：**

- 创建：`src/main/java/dev/langchain4j/service/ToolProtocolRecoveryDetector.java`
- 创建：`src/test/java/dev/langchain4j/service/ToolProtocolRecoveryDetectorTest.java`

**检测契约：**

```text
[工具调用] <registeredToolName> <completeJsonObject>
```

规范指纹为 `toolName + "\n" + canonicalJson`；JSON 对象键递归排序、数组顺序与值类型保持不变。

- [ ] **步骤 4.1：先写检测器红测**

  覆盖任意 chunk 切分、嵌套 JSON、字符串转义、字段顺序等价、未知工具、残缺 JSON、两个不同块、中间正常正文、真实结构化工具先到、首个候选暂存和被打断后原样释放。

- [ ] **步骤 4.2：运行红测并保存证据**

  ```bash
  bash mvnw -Dtest='ToolProtocolRecoveryDetectorTest' test \
    2>&1 | tee .codex/verification/task-4-red.log
  ```

- [ ] **步骤 4.3：实现最小字符状态机**

  检测器只负责解析、规范化与候选缓冲，不依赖模型、ChatMemory、SSE 或 Vue。第一个候选不得提前下发；打断时释放；第二个完全相同块触发 `Duplicate` 并丢弃两个伪块。

- [ ] **步骤 4.4：运行绿测、审查、勾选并中文提交**

  ```bash
  bash mvnw -Dtest='ToolProtocolRecoveryDetectorTest' test
  git diff --check
  ```

  提交信息：`新增：识别连续重复的伪工具调用`

---

### 任务 5：实现 generation 级撤销与临时消息 Token 门禁

**文件：**

- 修改：`src/main/java/dev/langchain4j/service/StreamingRequestController.java`
- 修改：`src/main/java/dev/langchain4j/service/ModelRequestGate.java`
- 修改：`src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionModelRequestGate.java`
- 修改：`src/main/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinator.java`
- 创建：`src/test/java/dev/langchain4j/service/StreamingRequestControllerRecoveryTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/ai/memory/ContextCompressionModelRequestGateTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/ai/memory/ContextCompressionCoordinatorTest.java`

**产出接口：**

```java
CallbackTicket enterCallback(long requestGeneration);
boolean runIfCurrentGeneration(long requestGeneration, Runnable action);
GenerationCancellation cancelGenerationForRecovery(long expectedGeneration);

record Request(
        Object memoryId,
        Supplier<ChatMemory> latestMemory,
        List<ToolSpecification> toolSpecifications,
        ContinuationGate continuationGate,
        List<ChatMessage> transientMessages) {}
```

- [ ] **步骤 5.1：先写 generation 竞争红测**

  断言撤销当前代后整轮仍 `ACTIVE`；旧代所有回调和迟到 handle 失效；新代能启动；全局取消/超时与恢复竞争只有一个赢家；模型请求计数包含恢复请求。

- [ ] **步骤 5.2：先写临时消息门禁红测**

  断言临时 `SystemMessage` 在 28672/30720/32768 边界参与完整请求估算；压缩后仍位于 `Decision.messages` 尾部；真实 ChatMemory 不包含该消息；硬拒绝不调用模型。

- [ ] **步骤 5.3：运行红测并保存证据**

  ```bash
  bash mvnw -Dtest='StreamingRequestControllerRecoveryTest,ContextCompressionModelRequestGateTest,ContextCompressionCoordinatorTest,ConservativeChatTokenEstimatorTest' test \
    2>&1 | tee .codex/verification/task-5-red.log
  ```

- [ ] **步骤 5.4：实现锁内线性化和门禁临时尾部**

  generation 撤销只记录 revoked generation、摘除当前 handle 并在锁外尽力取消；不得设置整轮 `CANCELLED`。压缩规划只裁剪真实 memory，捕获快照、压缩后复检和最终 Decision 均统一追加 `transientMessages` 再估算。

- [ ] **步骤 5.5：运行绿测与并发重复测试**

  ```bash
  bash mvnw -Dtest='StreamingRequestControllerRecoveryTest,ContextCompressionModelRequestGateTest,ContextCompressionCoordinatorTest,ConservativeChatTokenEstimatorTest,ToolLoopTerminationProtocolTest' test
  for i in {1..10}; do bash mvnw -q -Dtest='StreamingRequestControllerRecoveryTest' test || exit 1; done
  git diff --check
  ```

- [ ] **步骤 5.6：独立审查、修复、勾选并中文提交**

  提交信息：`重构：支持模型请求代次隔离与临时门禁消息`

---

### 任务 6：在同一 TokenStream 内完成一次协议自校正与二次熔断

**文件：**

- 创建：`src/main/java/dev/langchain4j/service/ToolProtocolRecoveryPolicy.java`
- 创建：`src/main/java/dev/langchain4j/service/ToolProtocolRecoveryCoordinator.java`
- 修改：`src/main/java/dev/langchain4j/service/TokenStream.java`
- 修改：`src/main/java/dev/langchain4j/service/AiServiceTokenStream.java`
- 修改：`src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java`
- 修改：`src/test/java/dev/langchain4j/service/AiServiceTokenStreamTest.java`
- 修改：`src/test/java/dev/langchain4j/service/AiServiceStreamingResponseHandlerTest.java`

**固定纠正指令：**

```text
上一响应未遵守工具调用协议。你把工具名称和参数写进了普通文本 content，系统不会执行这种文本形式的工具调用。

请重新处理用户的原始请求：
1. 如果任务需要工具，必须通过接口原生的结构化 tool_calls 字段调用工具。
2. 工具名称必须来自当前提供的工具列表。
3. arguments 必须是符合对应 JSON Schema 的有效 JSON 对象。
4. 不要在普通文本中输出“[工具调用]”、参数 JSON、工具代码块或伪造的执行结果。
5. 不要复述本提示，不要解释错误原因。
6. 如果确实不需要工具，直接返回最终答复。

立即返回正确的结构化工具调用或最终答复。
```

- [ ] **步骤 6.1：先写完整恢复状态机红测**

  覆盖：首次重复块→撤销旧 generation→门禁→第二次模型请求；纠正代首个真实结构化 tool call 标记恢复；纠正代再次重复→`PROTOCOL_ERROR`；模型请求总数严格为 2；旧代晚到 partial/tool/complete/error 不改内存、不回调；门禁拒绝只失败一次；临时纠正指令和伪正文不入 memory；恢复响应 usage 正常累计。

- [ ] **步骤 6.2：运行红测并保存证据**

  ```bash
  bash mvnw -Dtest='AiServiceTokenStreamTest,AiServiceStreamingResponseHandlerTest,StreamingRequestControllerRecoveryTest,ToolProtocolRecoveryDetectorTest' test \
    2>&1 | tee .codex/verification/task-6-red.log
  ```

- [ ] **步骤 6.3：实现共享协调器和每代检测器**

  一个 TokenStream 共享一个 `ToolProtocolRecoveryCoordinator`；每个 generation 创建独立 detector。正常流不改变；仅显式安装 policy 时启用。

- [ ] **步骤 6.4：实现一次纠正与二次受控终止**

  退出旧 SDK callback ticket 后再提交异步门禁，禁止在模型回调线程内递归发请求。首次退化发 `STARTED`；真实结构化工具开始发一次 `RECOVERED`；门禁/调度/模型启动失败或二次退化发一次 `FAILED`，二次退化调用受控 `PROTOCOL_ERROR`，绝不 prepare 第三次请求。

- [ ] **步骤 6.5：运行绿测与聚合协议测试**

  ```bash
  bash mvnw -Dtest='AiServiceTokenStreamTest,AiServiceStreamingResponseHandlerTest,StreamingRequestControllerRecoveryTest,ToolProtocolRecoveryDetectorTest,ToolLoopTerminationProtocolTest' test
  git diff --check
  ```

- [ ] **步骤 6.6：独立审查、修复、勾选并中文提交**

  提交信息：`修复：自动校正伪工具调用并在二次失败时熔断`

---

### 任务 7：接入 Vue 可信恢复事件、唯一终态与系统 Prompt 辅助约束

**文件：**

- 创建：`src/main/java/com/lyw/appgeneration/ai/model/message/ToolProtocolRecoveryMessage.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/handler/GenerationStreamEvent.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/handler/TurnProgressChannel.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/handler/VueTurnContext.java`
- 修改：`src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java`
- 修改：`src/main/java/com/lyw/appgeneration/controller/AppController.java`
- 修改：`src/main/resources/prompt/codegen-vue-project-system-prompt.txt`
- 修改：`src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/core/handler/VueTurnContextTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/core/handler/TurnProgressChannelTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandlerTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/controller/AppControllerSseTest.java`

**固定 SSE 文案：**

- `STARTED`：`正在校正工具调用，请稍候…`
- `RECOVERED`：`工具调用已校正，继续生成…`
- `FAILED`：`工具调用格式异常，系统自动校正后仍未恢复。本轮没有执行相关工具，请重新发送请求。`

- [ ] **步骤 7.1：先写可信事件与 Vue 接线红测**

  断言只有 Vue 在线流安装恢复策略；事件是 sealed 受信类型，不经过普通 content；SSE event 名、protocol、phase、固定文案精确；终态后迟到恢复事件丢弃；二次失败仍只由现有 Finalizer 产生一个 `PROTOCOL_ERROR`；done 唯一。

- [ ] **步骤 7.2：运行红测并保存证据**

  ```bash
  bash mvnw -Dtest='AiCodeGeneratorFacadeTest,VueTurnContextTest,TurnProgressChannelTest,JsonMessageStreamHandlerTest,AppControllerSseTest' test \
    2>&1 | tee .codex/verification/task-7-red.log
  ```

- [ ] **步骤 7.3：实现受信控制面与 Vue-only 安装**

  恢复事件通过回合 progress channel 与业务流合并，不进入 `JsonMessageStreamHandler` 的展示/记忆累积。Facade 从当前工具 executor 集合获得注册工具名，避免维护第二份白名单。

- [ ] **步骤 7.4：增加 Prompt 辅助约束**

  在 Vue system prompt 明确：普通正文中的工具名称、参数或执行结果不会被执行，需要操作工程文件时必须使用原生结构化工具调用。Prompt 只作辅助，不代替检测、隔离与熔断。

- [ ] **步骤 7.5：运行绿测与终态竞争回归**

  ```bash
  bash mvnw -Dtest='AiCodeGeneratorFacadeTest,VueTurnContextTest,TurnProgressChannelTest,JsonMessageStreamHandlerTest,VueTurnFinalizerTest,VueTurnCancellationCoordinatorTest,AppServiceImplVueTurnTest,AppControllerSseTest,VueProjectSystemPromptTest' test
  git diff --check
  ```

- [ ] **步骤 7.6：独立审查、修复、勾选并中文提交**

  提交信息：`新增：向前端发布可信工具协议恢复状态`

---

### 任务 8：实现前端恢复状态、污染前缀清理和提示优先级

**文件：**

- 修改：`ai-app-generation-frontend/src/utils/generationSession.ts`
- 修改：`ai-app-generation-frontend/src/utils/generationSession.test.ts`
- 修改：`ai-app-generation-frontend/src/pages/app/AppChatPage.vue`
- 修改：`ai-app-generation-frontend/src/pages/app/AppChatPageGeneratingStatus.test.ts`

**公开状态：**

```ts
export type ToolProtocolRecoveryState = 'idle' | 'recovering'
// GenerationSessionSnapshot 新增：
toolProtocolRecovery: ToolProtocolRecoveryState
```

- [ ] **步骤 8.1：先写前端状态机红测**

  覆盖正常无恢复、STARTED 清 direct 正文、STARTED 清 throttled buffer、RECOVERED、FAILED、真实正文/严格结构化工具开始后隐藏提示、压缩与恢复重叠优先级、重复/乱序/伪造/错误协议事件拒绝、控制文案不进入聊天正文。

- [ ] **步骤 8.2：运行红测并保存证据**

  ```bash
  cd ai-app-generation-frontend
  npm test -- src/utils/generationSession.test.ts src/pages/app/AppChatPageGeneratingStatus.test.ts \
    2>&1 | tee ../.codex/verification/task-8-red.log
  ```

- [ ] **步骤 8.3：实现严格 SSE 状态转移与缓冲清理**

  STARTED 必须同时取消 throttle timer、清空私有 buffer、清除当前 generation 已展示的临时正文；不得删除已有受信结构化工具卡。`finishSession` 不能重新 flush 被隔离文本。错误格式事件继续按 `protocol_error` 失败关闭。

- [ ] **步骤 8.4：实现单一提示派生逻辑**

  左右两处 UI 共用同一优先级：压缩文案优先，其次校正文案，最后普通思考/生成文案；保持“真实正文或工具卡出现后隐藏加载提示”的既有行为。

- [ ] **步骤 8.5：运行前端绿测、类型检查和构建**

  ```bash
  npm test -- --run
  npm run type-check
  npm run build
  git diff --check
  ```

- [ ] **步骤 8.6：独立审查、修复、勾选并中文提交**

  提交信息：`新增：展示工具调用自动校正状态`

---

### 任务 9：执行本地数据迁移、失效污染缓存并做精确生产探针

**文件：**

- 使用：`sql/migrations/2026-08-18-chat-history-memory-projection.sql`
- 创建证据：`.codex/verification/task-9-mysql-before.sql`
- 创建证据：`.codex/verification/task-9-mysql-migration.log`
- 创建证据：`.codex/verification/task-9-memory-projection-probe.md`
- 使用现有：`.codex/ExactMemoryToolCallProbe.java`
- 使用现有：`.codex/run-tool-protocol-probe.mjs`

- [ ] **步骤 9.1：只读核对四个现有容器与本地后端依赖**

  必须继续复用 `ai-app-generation-dev-nginx`、`ai-codegen-e2e-mysql`、`ai-codegen-rag-eval-redis`、`ai-codegen-rag-eval-pg`；不新建第五个容器，不执行 compose down/up，不清空卷。

- [ ] **步骤 9.2：备份并迁移本地 MySQL**

  先保存 `chat_history` 列定义、总行数、AI/用户行数、目标故障行和 outcome 分布；创建带时间戳的独立备份表且禁止覆盖已有表；执行幂等迁移两次，第二次不得改变数据；核对 `message` 的哈希与迁移前一致。

- [ ] **步骤 9.3：验证定向历史结果和 L2 排除规则**

  检查已知消息 `447109043745288192` 为 `PROTOCOL_ERROR` 且投影不含伪工具正文；旧 Vue 为 `LEGACY_UNVERIFIED`；旧简单模式为 `LEGACY_IMPORTED`；用户行投影为空。不得全量清空 L2。

- [ ] **步骤 9.4：验证 Redis 版本前缀隔离**

  写入旧无前缀污染样本后，新代码读取必须为空；写入新 `chat-memory:l0:v2:` key 后正常读取；只删除本探针显式 key，不做 `KEYS *` 或通配删除。

- [ ] **步骤 9.5：运行生产适配层精确探针**

  至少验证 10 次“L1 + 新投影 L0”的结构化工具调用成功率，不记录原始敏感会话，只记录结构化调用计数、伪工具计数、长度和 SHA-256；目标为 `10/10` 结构化工具调用、`0/10` 伪工具循环。

- [ ] **步骤 9.6：审查证据、勾选并提交必要脚本/契约修改**

  若本任务未修改生产或测试文件，不为日志单独制造空提交；只更新计划勾选，并在下一提交中显式包含计划文件。

---

### 任务 10：全量验证、Chrome E2E、最终审查与交付

**文件：**

- 创建：`.codex/e2e/tool-protocol-recovery-mock-server.mjs`
- 创建：`.codex/e2e/evidence/tool-protocol-recovery-chrome-e2e.md`
- 更新：`.codex/plans/2026-08-18-memory-projection-tool-protocol-recovery-implementation.md`

- [ ] **步骤 10.1：运行后端全量测试**

  ```bash
  export JAVA_HOME="$PWD/.codex/runtime/jdk-25.0.4+7/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  bash mvnw test 2>&1 | tee .codex/verification/task-10-backend-full.log
  ```

  验收：0 failures、0 errors；跳过项必须逐项确认与本改造无关。

- [ ] **步骤 10.2：运行前端全量测试、类型检查与生产构建**

  ```bash
  cd ai-app-generation-frontend
  npm test -- --run
  npm run type-check
  npm run build
  ```

- [ ] **步骤 10.3：运行跨层聚合回归**

  覆盖记忆投影、L0/L1/L2、Token 门禁、检测器、generation 竞态、唯一终态、受信 SSE 和前端状态机；并重复 generation 并发测试 10 次。

- [ ] **步骤 10.4：启动当前 worktree 前后端并保持四容器不变**

  后端使用工作区 Java 25；前端使用独立 Vite 端口。记录 PID、端口、健康检查和日志路径，结束时只停止本次宿主进程。

- [ ] **步骤 10.5：使用 Chrome 完成四个确定性 E2E 场景**

  必须通过 Chrome 实际操作页面并保存截图/DOM/console 证据：

  1. 正常生成：正文输出后“AI 正在思考”消失，成功终态与预览刷新正常；
  2. 恢复成功：临时伪工具前缀被清除，STARTED 显示校正提示，RECOVERED/真实工具后隐藏，最终只保留可信输出；
  3. 恢复失败：第二次退化后无第三次请求，显示固定友好错误，终态为 `protocol_error`，不刷新预览；
  4. 优先级：压缩与校正重叠时先显示压缩，压缩完成后显示校正，真实输出后两者都隐藏。

- [ ] **步骤 10.6：补充真实后端 Chrome 集成验证**

  使用专用测试 appId 验证浏览器收到后端真实 `tool-protocol-recovery` SSE 或正常结构化工具链；真实模型不可稳定触发的失败分支由受控 SSE E2E 证明，不以随机模型行为替代确定性验收。

- [ ] **步骤 10.7：执行最终全分支代码审查并修复 Critical/Important**

  审查范围从本分支起点 `f166046` 到最终 HEAD，重点检查：字段迁移兼容、AI 展示文本回退、L2 证据边界、generation 竞态、第三次请求、临时 Prompt 泄漏、控制事件伪造、前端 buffer 复活、终态重复与敏感日志。

- [ ] **步骤 10.8：逐条完成计划审计并全部勾选**

  对本计划每个显式要求建立“要求→代码/测试/运行证据”矩阵；证据不足不得勾选。确认 Git 工作树仅剩用户原有 `.codex/` 未跟踪材料，无遗漏生产修改。

- [ ] **步骤 10.9：完成最终中文 Git 提交**

  显式暂存最终修复、测试和本计划；提交信息参考历史全中文格式，例如：

  ```bash
  git commit -m "修复：收口记忆投影与工具协议恢复链路"
  ```

- [ ] **步骤 10.10：输出交付结果**

  汇报：所有提交哈希、关键文件具体改动、后端/前端测试数字、迁移与探针结果、Chrome 四场景证据路径、未解决风险。未经允许不得 push 或合并 master。

---

## 完成判定

只有同时满足以下条件才能宣布完成：

- [ ] `message` 与 `memoryMessage` 已在数据库、实体、写入 API 和所有读取入口中完成语义隔离。
- [ ] Vue 新回合投影完全来自真实结构化工具事实；协议异常正文未进入 MySQL/L0/L1/L2。
- [ ] L2 继续只使用用户原话，并可靠排除不可信 AI 回合。
- [ ] 旧 AI 投影为空时所有路径均无展示文本回退。
- [ ] Redis 旧污染 key 已由版本前缀隔离。
- [ ] 首次伪工具退化只自动纠正一次，二次退化严格无第三次模型请求。
- [ ] 所有旧 generation 迟到回调均无法污染新 generation。
- [ ] 临时纠正指令经过 28K/30K/32K 门禁且不进入任何持久记忆。
- [ ] 前端严格校验可信恢复 SSE，提示优先级和真实输出后的隐藏行为正确。
- [ ] 后端全量、前端全量/type-check/build、生产探针与 Chrome E2E 均有新鲜通过证据。
- [ ] 每个完成步骤已标 `✅`，每个阶段已完成全中文 Git 提交，且未 push。
