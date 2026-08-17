# 在线生成仅首轮执行 RAG 实施计划

> **执行要求：** 在隔离分支 `codex/token-layered-memory-v3` 中按 TDD 实施；先观察行为测试按预期失败，再修改生产代码。所有 Git 暂存都必须显式列出文件，不使用 `git add .`，不推送远程。

**目标：** 将 HTML、MULTI_FILE、VUE_PROJECT 三种在线生成模式的 RAG 从“每轮召回”改为“仅首轮召回”，降低后续轮的 Embedding、PGVector、Rerank 延迟与费用，并避免重复 RAG 内容继续占用热会话 Token。

**核心规则：** 在线入口仅在 `isFirstMessage && ragProperties.isEnabled()` 时执行 RAG；后续轮直接把当前用户原始消息交给生成模型。一次性同步入口与 Vue 离线评测保持原行为。

**技术栈：** Java 25、Spring Boot 3.5、LangChain4j、JUnit 5、Mockito、Maven Wrapper。

## 全局约束

- 覆盖 HTML、MULTI_FILE、VUE_PROJECT 三种在线模式。
- MULTI_FILE 与 Vue 的图片增强仍仅首轮执行，且首轮维持“图片增强后再拼装 RAG”的既有顺序。
- 后续轮即使提出新增功能也不重新召回，这是本版本已确认的产品取舍。
- 不修改 `RagRetrievalService`、向量库、Redis、数据库、前端协议或 RAG 配置结构。
- `generateAndSaveCode()` 继续每次执行普通 RAG。
- `generateVueProjectForEvaluation()` 继续每次执行真实 Vue Hybrid RAG。
- 保留工作区内其他未跟踪诊断文件，不清理、不暂存。

---

### 任务 1：建立实施基线并隔离已有前端修复

**文件：**

- 核对：`src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java`
- 核对：`src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java`
- 独立提交：`ai-app-generation-frontend/src/pages/app/AppChatPage.vue`
- 独立提交：`ai-app-generation-frontend/src/pages/app/AppChatPageGeneratingStatus.test.ts`

- [x] ✅ **步骤 1：确认分支和 worktree**

  ```bash
  git branch --show-current
  git status --short --branch
  ```

  验收：当前分支为 `codex/token-layered-memory-v3`，不在 `master` 上实施。

- [x] ✅ **步骤 2：核对设计与调用链**

  已确认 `AppServiceImpl` 依据当前用户消息落库前的 MySQL 历史记录计算 `isFirstMessage`，三种在线模式均把该值传入 Facade。

- [x] ✅ **步骤 3：重新验证前端独立修复**

  ```bash
  cd ai-app-generation-frontend
  npm test -- --run src/pages/app/AppChatPageGeneratingStatus.test.ts
  npm test -- --run
  npm run type-check
  npm run build
  ```

  验收：聚焦测试、134 个全量测试、类型检查和生产构建均成功；仅允许记录既有的大 chunk 警告。

- [x] ✅ **步骤 4：精确提交前端修复**

  仅暂存两处前端文件，提交信息使用：

  ```text
  修复：AI 输出后隐藏思考提示
  ```

---

### 任务 2：用失败测试锁定普通在线模式的首轮边界

**文件：**

- 修改：`src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java`

- [x] ✅ **步骤 1：保留 HTML 首轮正例**

  断言首轮调用 `retrieve(RAW_QUERY, HTML)` 和 `assemble(...)`，最终把增强后的请求交给 HTML 生成模型，且不调用图片增强。

- [x] ✅ **步骤 2：新增 HTML 后续轮反例**

  使用 `isFirstMessage = false`，断言：

  - 模型收到 `RAW_QUERY`。
  - `retrievalService`、`promptAssembler`、`imageCollectionService` 均无交互。

- [x] ✅ **步骤 3：保留 MULTI_FILE 首轮顺序正例**

  断言首轮严格按以下顺序发生：

  ```text
  图片增强 → 普通 RAG 召回 → Prompt 拼装 → MULTI_FILE 模型调用
  ```

- [x] ✅ **步骤 4：新增 MULTI_FILE 后续轮反例**

  使用 `isFirstMessage = false`，断言图片增强与 RAG 全部跳过，模型直接收到 `RAW_QUERY`。

- [x] ✅ **步骤 5：清理与新契约冲突的 stub**

  删除“普通在线生成安装统一门禁和真实回合原子门”中后续轮不再使用的 RAG stub，避免 Mockito strict-stubbing 把正确行为误判为测试错误。

---

### 任务 3：用失败测试锁定 Vue 在线模式的首轮边界

**文件：**

- 修改：`src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java`

- [x] ✅ **步骤 1：保留 Vue Hybrid 首轮正例**

  断言首轮：

  - 图片增强只作用于生成请求。
  - 检索词保持原始 `RAW_QUERY`。
  - 调用 `retrieveVueProject()`。
  - 拼装后请求进入 Vue 生成模型。

- [x] ✅ **步骤 2：保留 Vue Dense-only 首轮正例**

  关闭 Hybrid、保留 RAG 总开关，断言首轮调用 `retrieveVueProjectDenseOnly(RAW_QUERY)`，不调用 Hybrid 入口。

- [x] ✅ **步骤 3：把 Vue 后续轮旧测试改为不召回契约**

  将原“后续轮仍召回”的测试改为断言：

  - 不调用图片增强。
  - 不调用 Hybrid 或 Dense-only 检索。
  - 不调用 Vue Prompt 拼装。
  - 模型直接收到 `RAW_QUERY`。

- [x] ✅ **步骤 4：保留 RAG 关闭首轮反例**

  断言 RAG 总开关关闭时，即使是首轮且 Hybrid 开启，也不调用任何 RAG 阶段；首轮图片增强仍按既有逻辑执行。

- [x] ✅ **步骤 5：保留首轮异常降级测试**

  断言首轮 Vue RAG 异常仍转换为 `VueRagContext.unavailable()` 并继续生成，避免首轮门禁破坏既有降级语义。

---

### 任务 4：执行 TDD RED 并确认失败原因

**文件：**

- 测试：`src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java`

- [x] ✅ **步骤 1：运行聚焦测试**

  ```bash
  env JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
    PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
    bash mvnw -Dtest=AiCodeGeneratorFacadeTest test
  ```

- [x] ✅ **步骤 2：核对 RED 证据**

  预期失败只能来自当前生产代码在后续轮仍调用普通 RAG 或 Vue RAG；若失败来自编译错误、错误 mock 或测试环境，先修正测试再重新运行，直到得到真实行为差异。

---

### 任务 5：最小修改在线 Facade 的 RAG 门禁

**文件：**

- 修改：`src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java`

- [x] ✅ **步骤 1：修改普通在线模式**

  在 `createSimpleCodeStream()` 中计算在线召回条件：

  ```java
  boolean shouldRetrieve = shouldRetrieveOnlineRag(isFirstMessage);
  ```

  HTML 后续轮直接使用 `userMessage`；MULTI_FILE 首轮保留图片增强，后续轮直接使用 `userMessage`。仅在 `shouldRetrieve` 为真时调用 `ragAugment()`。

- [x] ✅ **步骤 2：修改 Vue 在线模式**

  将现有 RAG 条件收紧为：

  ```java
  if (shouldRetrieveOnlineRag(isFirstMessage)) {
  ```

  保持首轮 Hybrid/Dense-only 分支、原始检索词和异常降级不变。

- [x] ✅ **步骤 3：确认不误伤非在线入口**

  静态核对以下方法没有被改成首轮条件：

  - `generateAndSaveCode()`
  - `generateVueProjectForEvaluation()`

---

### 任务 6：执行 GREEN、完整回归与代码质量审查

**文件：**

- 验证：生产代码、测试、设计文档和本计划

- [x] ✅ **步骤 1：运行聚焦测试确认 GREEN**

  ```bash
  env JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
    PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
    bash mvnw -Dtest=AiCodeGeneratorFacadeTest test
  ```

- [x] ✅ **步骤 2：运行完整后端测试**

  ```bash
  env JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
    PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
    bash mvnw test
  ```

- [x] ✅ **步骤 3：执行五轴审查**

  按正确性、可读性、架构、安全、性能检查：

  - 首轮条件是否覆盖三种在线模式。
  - 后续轮是否完全避开 Embedding、检索、Rerank、Prompt 拼装。
  - 同步入口和离线评测是否保持不变。
  - 是否只在 Facade 层表达在线会话策略。
  - 是否存在未使用 stub、死代码或无关重构。

- [x] ✅ **步骤 4：运行提交前差异门禁**

  ```bash
  git diff --check
  git status --short
  git diff -- src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java \
    src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java \
    .codex/specs/2026-08-17-first-turn-only-rag-design.md \
    .codex/plans/2026-08-17-first-turn-only-rag-implementation.md
  ```

---

### 任务 7：精确提交 RAG 优化并复核提交内容

**文件：**

- 提交：`src/main/java/com/lyw/appgeneration/core/AiCodeGeneratorFacade.java`
- 提交：`src/test/java/com/lyw/appgeneration/core/AiCodeGeneratorFacadeTest.java`
- 提交：`.codex/specs/2026-08-17-first-turn-only-rag-design.md`
- 提交：`.codex/plans/2026-08-17-first-turn-only-rag-implementation.md`

- [x] ✅ **步骤 1：精确暂存四个 RAG 文件**

  禁止使用 `git add .`；不得把 `.codex/` 下其他诊断文件加入暂存区。

- [x] ✅ **步骤 2：核对暂存差异**

  ```bash
  git diff --cached --check
  git diff --cached --stat
  git diff --cached
  ```

- [x] ✅ **步骤 3：生成中文提交**

  ```text
  优化：限制在线 RAG 仅首轮召回
  ```

- [x] ✅ **步骤 4：提交后复核**

  ```bash
  git log -2 --oneline
  git status --short --branch
  ```

  验收：两个独立中文提交存在；未推送远程；工作区只保留此前已有且不属于本次提交的诊断材料。

## 最终回归重点

1. HTML 首轮仍能获得模板 RAG，第二轮不再产生 RAG 请求。
2. MULTI_FILE 首轮图片增强和 RAG 顺序不变，第二轮两者都不执行。
3. Vue 首轮 Hybrid 与 Dense-only 分支均正常，第二轮不执行任何 RAG。
4. 首轮 RAG 异常仍能降级生成，不改变错误处理语义。
5. Vue 离线质量评测仍每次真实召回。
6. 一次性同步生成仍每次执行普通 RAG。

## 已知产品代价

- 后续轮新增首轮未包含的功能时不会获得新的模板召回。
- L0 冷重建或上下文压缩后，不保证首轮完整 RAG 模板仍留在热上下文。
- 这是当前版本为降低延迟、费用和重复 Token 已明确接受的取舍；未来若质量反馈明显，应优先设计“意图变化触发 + 已召回模板去重”，而不是直接恢复每轮无条件召回。
