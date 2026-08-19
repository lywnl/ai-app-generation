# 伪工具正文严格隔离与提示词约束改造执行计划

> **执行要求：** 按任务顺序实施；每一步完成后把对应 `- [ ]` 改为 `- [x]`。实现阶段必须使用测试驱动开发，并在每个任务完成后做定向验证和中文 Git 提交。

**目标：** 通过“系统提示词事前约束 + 后端严格隔离 + 一次自动纠正 + 二次失败熔断”，保证伪工具正文不会显示在前端、不会持久化、不会进入任何记忆层，同时保持真实结构化工具调用正常执行和展示。

**架构：** `ToolProtocolRecoveryDetector` 从首个伪工具候选开始隔离当前 generation 的未受信后缀；`AiServiceStreamingResponseHandler` 只下发清洗后的可信正文与真实工具事件；`ToolProtocolRecoveryCoordinator` 为整个用户回合提供一次自动纠正额度。系统提示词负责降低协议退化概率，前端只消费受信的恢复状态事件，不承担伪工具识别。

**技术栈：** Java 25、Spring Boot 3.5.4、LangChain4j 流式服务、Reactor/SSE、Vue 3、TypeScript、Vitest、Maven。

## 全局约束

- [x] ✅ 只改造 Vue 在线生成链路，不改变 HTML、多文件生成及未安装恢复策略的通用调用。
- [x] ✅ 真实结构化工具调用、工具卡片和真实执行结果继续正常显示。
- [x] ✅ 伪工具正文不得进入 SSE、`chat_history.message`、`memoryMessage`、L0、L1、L2。
- [x] ✅ 前端不得增加伪工具正则识别；协议安全边界必须在后端完成。
- [x] ✅ 自动纠正按整个用户回合最多执行一次，不能形成无限重试。
- [x] ✅ 保持 `tool-protocol-recovery/v1` 的 `STARTED / RECOVERED / FAILED` SSE 契约不变。
- [x] ✅ 不增加数据库字段，不执行历史数据迁移。
- [x] ✅ 所有新增文本、计划、注释均使用 UTF-8 简体中文。
- [x] ✅ 保留现有 `.codex/sdd/progress.md` 和所有未跟踪诊断材料；禁止使用 `git add .`、`git add -A`，禁止未经允许推送远程。

---

## Task 1：用失败测试锁定严格隔离契约

**文件：**

- 修改：`src/test/java/dev/langchain4j/service/ToolProtocolRecoveryDetectorTest.java`
- 修改：`src/test/java/dev/langchain4j/service/AiServiceTokenStreamTest.java`

**输入接口：** `ToolProtocolRecoveryDetector.accept(String)`、`observeStructuredToolCall()`、`finish()` 和 `TokenStream.toolProtocolRecoveryPolicy(...)`。

**输出契约：** 普通可信正文可流式输出；从首个伪工具标记开始的正文只能被隔离、丢弃或触发恢复，不能作为普通 `Text` 释放。

- [x] ✅ 增加逐字符分片测试，验证 `[工具调用]` 标记跨多个分片时不会部分泄漏。
- [x] ✅ 增加“可信前缀 + 单个伪工具块”测试，验证只输出可信前缀，流结束时确认协议退化。
- [x] ✅ 增加两个参数不同的伪工具块测试，验证两个块及中间未受信正文均不输出。
- [x] ✅ 增加未知工具、错误 JSON、重复 JSON 字段和未闭合 JSON 测试，验证明确工具标记不会被原样释放。
- [x] ✅ 增加正常 Markdown、普通 JSON 和仅提及 `tool_calls` 的技术说明测试，验证没有明确工具标记时不误杀。
- [x] ✅ 增加单个伪工具块流结束后启动第二次模型请求的测试。
- [x] ✅ 保留两个规范指纹相同的伪工具块提前启动恢复的测试。
- [x] ✅ 增加纠正 generation 再次退化测试，断言严格只有两次模型请求并以 `PROTOCOL_ERROR` 结束。
- [x] ✅ 运行红灯测试：

```bash
./mvnw -Dtest=ToolProtocolRecoveryDetectorTest,AiServiceTokenStreamTest test
```

预期：新增的单候选、坏 JSON、未知工具和流结束恢复用例在旧实现下失败；原有正常响应用例继续通过。

---

## Task 2：把检测器重构为严格隔离状态机

**文件：**

- 修改：`src/main/java/dev/langchain4j/service/ToolProtocolRecoveryDetector.java`
- 验证：`src/test/java/dev/langchain4j/service/ToolProtocolRecoveryDetectorTest.java`

**接口决策：**

- 保留 `Text(String text)`：只承载可立即下发的可信正文。
- 保留 `Buffering`：表示当前分片仍处于标记识别或隔离阶段。
- 将只描述重复候选的 `Duplicate` 泛化为 `Violation(String trustedText, ViolationReason reason)`。
- 新增内部枚举 `ViolationReason`：`DUPLICATE_BLOCK`、`STREAM_FINISHED`、`QUARANTINE_LIMIT`。

- [x] ✅ 继续保留可能构成 `[工具调用]` 的跨分片标记前缀，避免标记字符提前输出。
- [x] ✅ 一旦确认完整 `[工具调用]` 标记，进入该 generation 的隔离状态。
- [x] ✅ 隔离后只允许返回标记之前的可信前缀，不再释放标记、工具名、参数及其后续正文。
- [x] ✅ 对已注册工具和合法 JSON 继续生成规范化指纹，用于重复块提前熔断。
- [x] ✅ 未注册工具、坏 JSON、残缺 JSON 和不同参数候选保持隔离，在流结束时返回 `STREAM_FINISHED`。
- [x] ✅ 收到真实结构化工具通知时，将候选标记为需要丢弃，但不释放被隔离正文。
- [x] ✅ 已出现真实结构化工具后仍继续检测后续普通正文，防止混合响应中的伪工具文本绕过检测。
- [x] ✅ 设置 `65_536` 字符隔离上限；达到上限返回 `QUARANTINE_LIMIT`，避免异常输出无限占用内存。
- [x] ✅ 检测器只保存完成标记识别、严格 JSON 解析和规范指纹比较所需的有限状态，不长期保存已决定丢弃的大段正文。
- [x] ✅ 运行绿灯测试：

```bash
./mvnw -Dtest=ToolProtocolRecoveryDetectorTest test
```

预期：`ToolProtocolRecoveryDetectorTest` 全部通过。

- [x] ✅ 定向提交：

```bash
git add src/main/java/dev/langchain4j/service/ToolProtocolRecoveryDetector.java \
        src/test/java/dev/langchain4j/service/ToolProtocolRecoveryDetectorTest.java
git commit -m "重构伪工具正文严格隔离状态机"
```

---

## Task 3：让任意确认的伪工具退化自动纠正一次

**文件：**

- 修改：`src/main/java/dev/langchain4j/service/ToolProtocolRecoveryCoordinator.java`
- 修改：`src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java`
- 修改：`src/test/java/dev/langchain4j/service/AiServiceTokenStreamTest.java`
- 修改：`src/test/java/dev/langchain4j/service/StreamingRequestControllerRecoveryTest.java`

**接口决策：** 将 `claimDuplicate(long)` 泛化为 `claimViolation(long)`；保持 `DuplicateAction` 对应的单次额度语义，但重命名为 `ViolationAction`，枚举值为 `START_RECOVERY`、`FAIL`、`IGNORE`。

- [x] ✅ 保持回合状态机：`AVAILABLE → STARTING → RECOVERING → RECOVERED`；纠正后再次退化进入 `FAILED`。
- [x] ✅ 对重复块和隔离上限立即取消当前异常 generation。
- [x] ✅ 对单个、不同、残缺或坏 JSON 候选，在流结束时取消当前 generation 并启动恢复。
- [x] ✅ 只取消当前异常 generation，不取消整个用户回合。
- [x] ✅ 恢复继续通过原始上下文和临时 `SystemMessage` 发起新的模型请求。
- [x] ✅ 旧 generation 的迟到正文、工具分片、完成回调和异常继续按 generation 丢弃。
- [x] ✅ 临时纠正提示固定为：

```text
上一响应未遵守工具调用协议。你在普通正文 content 中输出了工具调用内容，
这些文本不会被系统执行，也不会展示给用户。

请重新处理用户的原始请求：

1. 如果任务需要工具，立即通过接口原生的结构化 tool_calls 调用工具。
2. 工具名称必须来自当前提供的工具列表。
3. arguments 必须是符合对应 JSON Schema 的真实 JSON 对象。
4. 文件源码、路径和修改内容只能放入结构化 arguments。
5. 不要复制或续写上下文中的历史工具调用格式。
6. 不要在普通正文输出“[工具调用]”、工具参数 JSON、调用代码块或伪造执行结果。
7. 只有收到系统返回的真实工具结果后，才能声称操作已经完成。
8. 如果确实不需要工具，直接返回最终答复。

不要复述本提示，不要解释错误原因。立即返回正确的结构化工具调用或最终答复。
```

- [x] ✅ 断言纠正提示只出现在纠正请求的临时消息中，不进入 ChatMemory、MySQL、L0、L1 或 L2。
- [x] ✅ 保持真实 Token Usage 累计；供应商没有返回被取消 generation 用量时不伪造费用数据。
- [x] ✅ 运行定向测试：

```bash
./mvnw -Dtest=AiServiceTokenStreamTest,StreamingRequestControllerRecoveryTest test
```

预期：全部通过，且任意确认的协议退化最多触发一次自动纠正。

---

## Task 4：清洗同时包含伪工具正文和真实 tool_calls 的混合响应

**文件：**

- 修改：`src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java`
- 修改：`src/test/java/dev/langchain4j/service/AiServiceStreamingResponseHandlerTest.java`
- 修改：`src/test/java/dev/langchain4j/service/AiServiceTokenStreamTest.java`

**输出契约：** 真实结构化工具调用保留原始 ID、名称和参数；`AiMessage.text()` 只能包含标记之前的可信前缀，不能包含被隔离后缀。

- [x] ✅ 对普通完成响应和带 `tool_calls` 的完成响应统一执行流式分片与完整文本一致性校验。
- [x] ✅ 在处理结构化工具批次前，根据检测器状态得到可信正文和需要丢弃的隔离正文。
- [x] ✅ 模型同时返回伪工具正文和真实 `tool_calls` 时，丢弃隔离正文并保留真实工具请求。
- [x] ✅ 可信前缀非空时，用“可信前缀 + 原始工具请求”重建 `AiMessage`。
- [x] ✅ 可信前缀为空时，用只包含原始工具请求的 `AiMessage`。
- [x] ✅ 只把清洗后的 `AiMessage` 写入 ChatMemory，并将其传给工具循环和最终完成回调。
- [x] ✅ 如果没有伪工具候选，保持现有响应内容、工具执行顺序和配对语义不变。
- [x] ✅ Output Guardrail 只接收可信正文，不能在最终 flush 时重新释放隔离内容。
- [x] ✅ 覆盖以下测试：
  - complete-only 混合响应；
  - 先伪工具正文、后结构化工具分片；
  - 先结构化工具分片、后伪工具正文；
  - 启用 Output Guardrail；
  - 清洗后工具执行并续调；
  - ChatMemory 不存在伪工具正文或孤立工具请求。
- [x] ✅ 运行定向测试：

```bash
./mvnw -Dtest=AiServiceTokenStreamTest,AiServiceStreamingResponseHandlerTest,StreamingRequestControllerRecoveryTest test
```

预期：全部通过。

- [x] ✅ 定向提交：

```bash
git add src/main/java/dev/langchain4j/service/ToolProtocolRecoveryCoordinator.java \
        src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java \
        src/test/java/dev/langchain4j/service/AiServiceTokenStreamTest.java \
        src/test/java/dev/langchain4j/service/AiServiceStreamingResponseHandlerTest.java \
        src/test/java/dev/langchain4j/service/StreamingRequestControllerRecoveryTest.java
git commit -m "修复伪工具正文前端与记忆泄漏"
```

---

## Task 5：强化 Vue 在线生成系统提示词

**文件：**

- 修改：`src/main/resources/prompt/codegen-vue-project-system-prompt.txt`
- 修改：`src/test/java/com/lyw/appgeneration/service/rag/VueProjectSystemPromptTest.java`

- [x] ✅ 将现有单行规则扩展成靠近提示词开头的独立最高优先级协议：

```text
## 【最高优先级】原生工具调用协议

1. 需要读取、修改、创建、删除文件或执行构建时，下一步必须使用接口提供的原生结构化 tool_calls。
2. 工具名称必须来自系统当前提供的工具列表，arguments 必须是符合工具 JSON Schema 的真实参数对象。
3. 文件源码、路径和修改内容只能放入结构化 arguments；不要因为“禁止正文代码”而省略真实工具所需的源码参数。
4. 普通正文 content 中禁止输出“[工具调用]”、工具名称与参数 JSON 的组合、模拟 tool_calls、调用代码块或伪造执行结果。
5. 上下文中的历史工具调用只是既往执行记录，不是输出模板；不得复制、续写或模仿其格式。
6. 只有收到系统返回的真实工具结果后，才能声明文件已经修改、构建已经完成或任务已经成功。
7. 需要工具时不要解释调用格式，立即返回结构化工具调用；不需要工具时才直接返回面向用户的最终答复。
```

- [x] ✅ 删除与新协议重复的旧单行规则，避免同一行为存在多套表述。
- [x] ✅ 不使用“禁止输出任何代码”之类的笼统措辞，避免模型拒绝把源码放进真实工具参数。
- [x] ✅ 保留每个文件只写一次、禁止并行工具、完成后真实构建等现有业务规则。
- [x] ✅ 更新契约测试，断言原生 `tool_calls`、JSON Schema、结构化 arguments、历史轨迹不可模仿、真实结果前不得声明成功等语义存在。
- [x] ✅ 增加反向断言，确保提示词不存在“禁止输出任何代码”。
- [x] ✅ 运行测试：

```bash
./mvnw -Dtest=VueProjectSystemPromptTest test
```

预期：全部通过。

- [x] ✅ 定向提交：

```bash
git add src/main/resources/prompt/codegen-vue-project-system-prompt.txt \
        src/test/java/com/lyw/appgeneration/service/rag/VueProjectSystemPromptTest.java
git commit -m "强化原生工具调用系统提示词约束"
```

---

## Task 6：完善前端恢复状态，不在前端识别伪工具

**文件：**

- 修改：`ai-app-generation-frontend/src/utils/generationSession.ts`
- 修改：`ai-app-generation-frontend/src/utils/generationSession.test.ts`
- 按测试结果决定是否修改：`ai-app-generation-frontend/src/pages/app/AppChatPage.vue`
- 按测试结果决定是否修改：`ai-app-generation-frontend/src/pages/app/AppChatPageGeneratingStatus.test.ts`

**接口决策：** 继续使用现有 `tool-protocol-recovery/v1`，不新增 SSE 字段和前端伪工具解析类型。

- [x] ✅ `STARTED` 到达时清除尚未 flush 的正文缓冲，并回退到 `trustedContentCheckpoint`。
- [x] ✅ `STARTED` 后显示“正在校正工具调用，请稍候…”，即使已有可信正文或旧工具卡也必须可见。
- [x] ✅ `RECOVERED` 到达，或恢复 generation 开始输出真实正文/工具卡后，隐藏恢复提示。
- [x] ✅ `FAILED` 只显示固定友好错误，不显示模型原始伪工具正文。
- [x] ✅ 不增加 `[工具调用]` 正则过滤，避免前后端形成两套不一致的判定逻辑。
- [x] ✅ 保持真实工具卡、参数脱敏和构建结果显示行为不变。
- [x] ✅ 运行前端定向验证：

```bash
cd ai-app-generation-frontend
npm test -- --run src/utils/generationSession.test.ts src/pages/app/AppChatPageGeneratingStatus.test.ts
npm run type-check
```

预期：测试和类型检查全部通过。

- [x] ✅ 只暂存本任务实际修改的文件并提交：

```bash
git add ai-app-generation-frontend/src/utils/generationSession.ts \
        ai-app-generation-frontend/src/utils/generationSession.test.ts
git diff --quiet -- ai-app-generation-frontend/src/pages/app/AppChatPage.vue || \
  git add ai-app-generation-frontend/src/pages/app/AppChatPage.vue
git diff --quiet -- ai-app-generation-frontend/src/pages/app/AppChatPageGeneratingStatus.test.ts || \
  git add ai-app-generation-frontend/src/pages/app/AppChatPageGeneratingStatus.test.ts
git commit -m "完善工具协议自动校正前端状态"
```

---

## Task 7：验证持久化和分层记忆无绕路污染

**文件：**

- 修改：`src/test/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandlerTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/core/handler/VueTurnFinalizerTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/ai/memory/ToolMessageCollapserTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngineTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/UserPreferenceBatchBuilderTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImplLoadTest.java`
- 修改：`src/test/java/com/lyw/appgeneration/controller/AppControllerSseTest.java`
- 组合验证：`src/test/java/dev/langchain4j/service/AiServiceTokenStreamTest.java`

**验证链路：** 模型 `content` → SDK 流 → SSE → `JsonMessageStreamHandler` → `VueTurnFinalizer` → `chat_history.message / memoryMessage` → L0 → L1 → L2。

- [x] ✅ 增加“可信前缀 + 伪工具正文 + 自动纠正成功”集成测试。
- [x] ✅ 断言 SSE 普通内容事件不包含伪工具块。
- [x] ✅ 断言最终 `displayAiText` 和 MySQL `message` 不包含伪工具块。
- [x] ✅ 断言 `memoryAiText`、MySQL `memoryMessage` 和 L0 折叠消息只包含真实工具事实。
- [x] ✅ 增加纠正再次失败测试，断言最终类型为 `PROTOCOL_ERROR`，展示使用固定友好文案，记忆使用固定协议失败可信投影。
- [x] ✅ 断言 L1 摘要与 L2 偏好提取不读取协议失败正文。
- [x] ✅ 增加混合响应测试，断言真实工具事实可进入可信投影，但伪工具文本不能进入任何记忆入口。
- [x] ✅ 断言重新加载聊天历史后仍看不到伪工具正文。
- [x] ✅ 运行分层回归：

```bash
./mvnw -Dtest=AiServiceTokenStreamTest,JsonMessageStreamHandlerTest,VueTurnFinalizerTest,ToolMessageCollapserTest,MemorySummaryDraftEngineTest,UserPreferenceBatchBuilderTest,ChatHistoryServiceImplLoadTest,AppControllerSseTest test
```

结果：158 项通过，0 失败。

- [x] ✅ 定向提交：

```bash
git add src/test/java/com/lyw/appgeneration/core/handler/JsonMessageStreamHandlerTest.java \
        src/test/java/com/lyw/appgeneration/core/handler/VueTurnFinalizerTest.java \
        src/test/java/com/lyw/appgeneration/ai/memory/ToolMessageCollapserTest.java \
        src/test/java/com/lyw/appgeneration/service/impl/MemorySummaryDraftEngineTest.java \
        src/test/java/com/lyw/appgeneration/service/impl/UserPreferenceBatchBuilderTest.java \
        src/test/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImplLoadTest.java \
        src/test/java/com/lyw/appgeneration/controller/AppControllerSseTest.java
git commit -m "补全伪工具隔离分层记忆回归测试"
```

---

## Task 8：全量验证、人工验收和最终提交检查

- [x] ✅ 运行后端全量测试：

```bash
./mvnw test
```

实际使用 Temurin JDK 25 执行：

```bash
JAVA_HOME="$PWD/.codex/runtime/temurin25/Contents/Home" \
PATH="$PWD/.codex/runtime/temurin25/Contents/Home/bin:$PATH" \
bash mvnw test
```

结果：最终新鲜全量运行 `1623` 项，`0` 失败、`0` 错误、`7` 跳过，
`BUILD SUCCESS`。前一轮全量运行曾命中一个与本次修改无关的并发时序断言波动；
该用例随后单独连续运行 `20` 次均通过，最终全量重跑恢复全绿。

- [x] ✅ 运行后端构建：

```bash
./mvnw -DskipTests package
```

结果：`BUILD SUCCESS`。打包后的 Jar 再次核验仍包含正式阈值：异步
`49152`、阻塞 `57344`、硬上限 `65536`。

- [x] ✅ 运行前端全量测试、类型检查和生产构建：

```bash
cd ai-app-generation-frontend
npm test
npm run type-check
npm run build
```

结果：前端 `7` 个测试文件、`163/163` 项通过；`type-check` 通过，生产构建成功。
仅保留既有单包体积超过 500 kB 的警告，无构建错误。

- [x] ✅ 人工验证正常纯文本回答仍可流式显示。受控 SSE
  `.codex/e2e/strict-plain-text.sse` 已收到两段普通正文；该场景没有构建终态，
  因而最终业务终态是“未构建”的 `PROTOCOL_ERROR`，不是协议检测误判。浏览器正常
  工具链场景最终 `SUCCEEDED`，截图见
  `.codex/e2e/evidence/tool-protocol-normal.png`。
- [x] ✅ 人工验证真实 `readFile`、`writeFile`、`modifyFile`、`buildProject` 工具卡和结果正常；
  受控 SSE、浏览器截图及 `AiServiceTokenStreamTest` 覆盖真实工具执行与续调。
- [x] ✅ 人工验证单个伪工具块从未显示，随后出现恢复提示和纠正输出；证据为
  `strict-pseudo-recover-success.sse`、`tool-protocol-recovery-started.png`、
  `tool-protocol-recovery-success.png`。
- [x] ✅ 人工验证两个相同伪工具块会提前取消异常 generation；
  `strict-pseudo-repeat.control.json` 记录只使用一次恢复额度，伪正文未进入前端，
  后端定向测试覆盖 generation 取消。
- [x] ✅ 人工验证两个不同伪工具块均不显示，流结束后自动纠正；证据为
  `strict-pseudo-different.sse` 和对应控制记录。
- [x] ✅ 人工验证伪工具正文后到达真实 `tool_calls` 时，伪内容不显示而真实工具正常执行；
  `strict-mixed.sse`、`AiServiceTokenStreamTest` 和 `AiServiceStreamingResponseHandlerTest`
  均断言可信前缀与真实工具请求保留。
- [x] ✅ 人工验证纠正后再次退化不会发起第三次请求；
  `strict-double-failure.control.json` 的请求数恰为 `2`，终态为 `PROTOCOL_ERROR`，
  前端显示固定友好提示。
- [x] ✅ 人工验证后端重启并加载历史后，伪工具内容不会从 MySQL 或 Redis 恢复；
  `strict-cold-rebuild-mixed.control.json` 中冷重建请求的
  `promptHasPseudoTool`、`promptHasPoisonedSuffix`、`promptHasRecoveryPrompt` 均为 `false`，
  分层回归测试同时覆盖 MySQL、L0、L1、L2。
- [x] ✅ 人工验证与 48K/56K/64K 上下文压缩及工具链续行兼容；E2E 使用临时缩小阈值
  验证了异步/阻塞/硬上限三条路径，`strict-hard-tool-chain-retry-2.sse` 证明硬上限
  检查点压缩后继续 `readDir → buildProject` 并成功结束，正式 Jar 阈值保持不变。
- [x] ✅ 检查工作树，只允许本计划涉及的代码和测试进入提交：

```bash
git status --short
git diff --check
git log -8 --oneline
```

检查结果：`git diff --check` 无输出；本次最终提交只精确暂存计划和本计划涉及的
实现/测试文件，`.codex/sdd/progress.md` 及其他诊断材料保留未暂存，未执行远程推送。

- [x] ✅ 最终验收标准：浏览器、SSE、MySQL、L0、L1、L2 六个位置均不存在本轮伪工具正文；
  真实结构化工具调用仍能执行、展示、持久化可信事实并继续工具循环。证据由浏览器
  受控验收、strict E2E 控制记录以及 Task 7 分层记忆回归共同构成。

### Task 8.1：最终审查修复与 fresh E2E 复验

- [x] ✅ 修复前端可信正文检查点：direct 正文、节流 buffer 和 flush 后同步推进
  `trustedContentCheckpoint`；`STARTED` 只清理尚未下发的候选，不回滚已确认正文。
  定向前端回归为 `117` 项通过。
- [x] ✅ 为 `AiServiceStreamingResponseHandler` 增加 generation 级 `65_536` 字符
  响应跟踪上限，覆盖 partial、complete-only 和工具响应；超过上限统一进入
  `RESOURCE_LIMIT_EXCEEDED`，避免响应缓存无限增长。
- [x] ✅ 在完整响应边界显式处理 `null completeResponse` 或 `null aiMessage`，统一走
  `StreamingResponseConsistencyException`；工具响应省略完整正文时使用累计可信 partial
  重建 `AiMessage`。
- [x] ✅ 补充后端契约测试：`AiServiceTokenStreamTest` 与
  `AiServiceStreamingResponseHandlerTest` 覆盖响应边界；连同
  `StreamingRequestControllerRecoveryTest`、`ToolLoopTerminationProtocolTest` 的高风险
  回归共 `147/147` 项通过。前端 `generationSession.test.ts` 共 `117/117` 项通过，
  前端全量共 `163/163` 项通过，类型检查通过。
- [x] ✅ 使用测试专用 Jar 临时将阈值缩小为异步 `28_672`、阻塞 `30_720`、硬上限
  `32_768`；测试完成后正式源码和 `application.yml` 已恢复为异步 `49_152`、阻塞
  `57_344`、硬上限 `65_536`，`MemoryTokenProperties` 的正式严格校验未放宽。
- [x] ✅ fresh 多轮压缩后伪工具复验：连续执行
  `.codex/e2e/fresh-multiround-r1.control.json` 至 `r5.control.json`，其中
  `R3 / R4 / R5` 均实际调用摘要模型完成压缩；随后
  `.codex/e2e/fresh-async-pseudo-after-multiround.control.json` 记录只使用一次恢复额度，
  对应 SSE 最终 `SUCCEEDED`。伪工具块 `[工具调用] readFile`、污染后缀和内部纠正提示
  均未进入 SSE。
- [x] ✅ fresh 异步压缩复验：
  `.codex/e2e/fresh-async-pseudo-final-exact.sse` 与对应
  `.control.json` 显示 `asyncGateDelta=1`、`asyncCompressionDelta=1`；压缩后
  触发伪工具时只自动纠正一次，随后真实工具链成功；伪工具块
  `[工具调用] readFile` 和污染后缀均未进入 SSE，真实中文工具卡正常保留。
- [x] ✅ fresh 阻塞压缩复验：
  `.codex/e2e/fresh-blocking-pseudo-final.sse` 与对应 `.control.json` 显示
  `blockingGateDelta=1`、`blockingCompressionDelta=1`；压缩后伪工具自动纠正一次并
  真实构建成功。
- [x] ✅ fresh 硬上限工具链复验：
  `.codex/e2e/fresh-hard-tool-chain-success.sse` 显示大文件写入后触发检查点压缩，
  随后继续 `readDir → buildProject` 并以 `SUCCEEDED` 结束；控制记录确认请求带有
  `checkpoint=true`，无伪工具提示词污染。
- [x] ✅ fresh 二次退化熔断复验：
  `.codex/e2e/fresh-double-failure.sse` 最多发送 `STARTED → FAILED`，控制记录流式
  generation 恰为 `2`（初次请求 + 1 次纠正），没有第三次模型请求，终态为
  `PROTOCOL_ERROR`。
- [x] ✅ fresh 跨层脱敏检查：异步、阻塞、硬上限和二次失败应用的 MySQL 展示消息、
  `memoryMessage`、Redis L0、L1 摘要均未发现伪工具块 `[工具调用] readFile`、
  污染后缀或内部纠正提示；对应计数均为 `0`。

### Task 8.2：最终代码审查

- [x] ✅ 复核当前工作树相对 `d89af05` 的实现和测试 diff，确认 checkpoint、响应上限、
  null complete、伪工具隔离和终态竞态均无 Critical/Important 问题。
- [x] ✅ 根据审查结果完成 generation-aware 受控终止修复：当前代超限会取消同代底层
  SDK handle；已被恢复撤销的旧 generation 迟到回调不能发布终态，也不能取消恢复代
  handle。相关后端高风险回归 `147/147` 项通过。
- [x] ✅ 更新最终验证：后端全量 `1623` 项、前端全量 `163` 项、正式 Jar 阈值
  `49152/57344/65536`；fresh E2E 覆盖异步压缩、阻塞压缩、硬上限工具链续行和二次
  退化熔断。最终代码提交为 `42cddcb 修复可信正文回滚与流式响应边界`。

## 兼容性和默认决策

- `TokenStream.toolProtocolRecoveryPolicy(...)` 公共签名保持不变。
- `tool-protocol-recovery/v1` SSE 协议保持不变。
- `ToolProtocolRecoveryDetector.Result` 是内部接口，允许从重复检测扩展为通用协议退化检测。
- 伪工具标记之前已经下发的可信普通前缀保留；标记开始后的当前 generation 后缀全部丢弃。
- 单个候选在流结束时也视为协议退化并自动纠正一次，不再原样释放。
- 明确工具标记下的未知工具、坏 JSON 和残缺 JSON同样隔离；没有明确工具标记的正常代码示例和 JSON 不拦截。
- 隔离缓冲默认上限为 `65_536` 个字符，达到上限立即自动纠正。
- 本次不清理已有历史数据，只保证新生成内容不再产生伪工具污染。
