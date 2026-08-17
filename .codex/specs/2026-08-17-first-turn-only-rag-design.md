# 在线生成仅首轮执行 RAG 设计

## 1. 目标

将在线代码生成的 RAG 策略从“每个用户回合都召回”改为“仅应用首轮对话召回”，覆盖：

- HTML
- MULTI_FILE
- VUE_PROJECT

主要收益：

- 后续回合不再调用 Embedding、PGVector 检索与 Rerank，降低请求延迟和外部服务费用。
- 后续回合不再重复拼接模板上下文，减少新增到热会话中的重复 Token。

## 2. 已确认产品规则

1. 在线首轮：当 `isFirstMessage == true` 且 RAG 总开关开启时，执行一次 RAG。
2. 在线后续轮：当 `isFirstMessage == false` 时，无条件跳过 RAG。
3. 后续轮即使提出新增功能，也不重新召回；这是当前版本明确接受的质量取舍。
4. HTML、MULTI_FILE、VUE_PROJECT 使用同一轮次边界。
5. 一次性同步生成 `generateAndSaveCode()` 没有多轮会话语义，保持每次执行一次 RAG。
6. Vue 离线质量评测入口必须保持每次召回，不受在线首轮策略影响。

## 3. 事实依据

### 3.1 首轮判定来源

`AppServiceImpl` 在当前用户消息落库前读取最后一条聊天记录：

- 无历史：`isFirstMessage = true`
- 有历史：`isFirstMessage = false`

HTML、MULTI_FILE、VUE_PROJECT 在线入口均把该值传给 `AiCodeGeneratorFacade`，不新增另一套首轮状态。

### 3.2 当前 RAG 调用位置

- HTML：`createSimpleCodeStream()` 每轮调用 `ragAugment()`。
- MULTI_FILE：`createSimpleCodeStream()` 每轮调用 `ragAugment()`。
- VUE_PROJECT：`generateVueProjectStream()` 每轮调用 `retrieveVueContext()` 并拼装 Vue RAG 上下文。
- 离线评测：`generateVueProjectForEvaluation()` 独立执行真实 RAG。

### 3.3 记忆影响

在线代理会把增强后的用户消息内容写入 L0。首轮召回结果因此可随热会话历史进入后续模型请求；MySQL 聊天历史仍只保存用户原文。发生 L0 冷重建或上下文压缩后，不保证完整模板正文仍然存在。

## 4. 行为矩阵

| 入口 | 首轮 | RAG 开启 | 预期行为 |
|---|---:|---:|---|
| HTML 在线 | 是 | 是 | 召回并拼装普通模板 |
| HTML 在线 | 否 | 是 | 不调用 RAG，直接传本轮用户消息 |
| MULTI_FILE 在线 | 是 | 是 | 保留图片增强，再执行 RAG |
| MULTI_FILE 在线 | 否 | 是 | 不调用图片增强和 RAG，直接传本轮用户消息 |
| VUE_PROJECT 在线 | 是 | 是 | 保留图片增强，召回并拼装 Vue 上下文 |
| VUE_PROJECT 在线 | 否 | 是 | 不调用图片增强和 RAG，直接传本轮用户消息 |
| 任一在线模式 | 是或否 | 否 | 不执行 RAG |
| Vue 离线评测 | 不适用 | 是 | 继续执行真实 RAG |
| 一次性同步生成 | 不适用 | 是 | 继续执行一次 RAG |

## 5. 实现设计

### 5.1 普通在线模式

在 `AiCodeGeneratorFacade.createSimpleCodeStream()` 内以现有 `isFirstMessage` 控制 RAG：

- HTML 首轮：`ragAugment(userMessage, HTML)`。
- HTML 后续轮：直接使用 `userMessage`。
- MULTI_FILE 首轮：先执行现有图片增强，再对增强结果执行 `ragAugment()`。
- MULTI_FILE 后续轮：直接使用 `userMessage`。

不修改 `RagRetrievalService`，避免把会话策略下沉到通用检索服务。

### 5.2 Vue 在线模式

在 `AiCodeGeneratorFacade.generateVueProjectStream()` 中，仅当以下条件同时成立时召回：

```text
isFirstMessage && ragProperties.isEnabled()
```

Hybrid 与 Dense-only 的现有选择逻辑保持不变；首轮仍使用原始 `userMessage` 作为检索词，并把结果拼接到已完成图片增强的 `generationRequest`。

### 5.3 保持不变的入口

- `generateAndSaveCode()`：同步一次性生成继续调用 `ragAugment()`。
- `generateVueProjectForEvaluation()`：继续强制调用真实 Vue RAG，保证现有质量门禁语义。
- `RagRetrievalService`、向量库、Rerank、模板摄取和配置结构均不修改。

## 6. 异常与降级

- 首轮 RAG 失败时，沿用现有无 RAG 降级逻辑，不阻断生成。
- 后续轮不触发 RAG，因此不会产生 RAG 超时或失败。
- `isFirstMessage` 继续由 MySQL 聊天历史事实决定，不使用进程缓存或 Redis 推断。

## 7. 测试设计

主要修改 `AiCodeGeneratorFacadeTest`，至少覆盖：

1. HTML 首轮调用普通 RAG，后续轮不调用。
2. MULTI_FILE 首轮保留图片增强并调用普通 RAG，后续轮两者均不调用。
3. Vue 首轮调用 Hybrid 或 Dense-only RAG，后续轮不调用任何 Vue RAG 入口。
4. 后续轮传给生成模型的是本轮原始用户消息。
5. RAG 关闭时，Vue 首轮也不召回。
6. 既有 Vue 离线评测测试继续通过，证明质量门禁未被首轮策略误伤。

验证命令：

```bash
env JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
  PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
  bash mvnw -Dtest=AiCodeGeneratorFacadeTest test
env JAVA_HOME="$PWD/.codex/runtime/jdk25/Contents/Home" \
  PATH="$PWD/.codex/runtime/jdk25/Contents/Home/bin:$PATH" \
  bash mvnw test
```

如仓库实际使用 Maven Wrapper 以外的既有门禁命令，实施计划应按项目事实调整。

## 8. 非目标

本次不实现：

- 后续新增功能的条件式召回。
- 模板 ID 去重或 RAG 状态持久化。
- RAG 上下文与 L0 解耦。
- RAG 结果缓存。
- 数据库或 Redis schema 变更。
- 前端交互变化。

## 9. 已知代价与回退

### 已知代价

- 用户后续新增首轮未包含的功能时，无法召回匹配模板。
- 上下文压缩或冷重建后，不保证首轮完整模板仍可被模型看到。
- 本次减少的是后续回合新增的重复 RAG Token；不会主动清理首轮已进入 L0 的 RAG 内容。

### 回退

改动只位于 Facade 的在线回合分支，可通过移除 `isFirstMessage` 条件恢复每轮召回；无需回滚数据或迁移。
