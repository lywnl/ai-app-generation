# Design

## Context

动机与来源见 proposal。当前前端已经优先显示 SSE message，根因位于后端固定错误转换。项目 LangChain4j 的 HTTP 异常保留 statusCode，默认映射器保留 cause；可在当前异常边界恢复分类，无需调整模型请求或前端协议。

## Goals / Non-Goals

目标是补充一种受控错误说明，并保持新失败展示与原因一致。不增加公共错误码或终态类型，不放宽未知错误脱敏，不修改压缩调度、图片辅助降级、记忆生命周期及历史数据。

## Decisions

### 1. 集中余额错误识别

新增无状态 `ModelServiceErrorMessages`，沿 cause 有界遍历并防止循环。只将模型 SDK 的 HTTP 402 或明确余额错误码／消息作为正向依据；对 SDK 响应只读取 error 对象或根级 code、type、message 字段。任意业务异常、反射回来的用户文本、单独的 429、模糊 `insufficient_quota` 均不认定为余额不足。

识别只返回固定中文提示，不把提供方响应、账号、URL、密钥或请求标识传给客户端。不同入口保留各自未知错误回退文案。常量与纯识别方法避免各入口重复散落字符串匹配。

### 2. 接入已有错误边界

`GenerationPreflightException.system` 在保留 kind/code 的前提下选择明确文案，覆盖 AppService 分类错误包装、Controller 同步失败和普通生成流的安全转换。`GlobalExceptionHandler` 对普通 RuntimeException 响应使用同一选择规则。

`JsonMessageStreamHandler` 只在现有异常分支选择余额文案及可信原因，保持受控终止的既有优先级。`AppServiceImpl.finalizeCommittedVueFailure` 对提交后启动错误使用同一分类。`SimpleTextStreamHandler` 以同一提示写新失败记录。`VueTurnFinalizer` 将固定提示纳入终态预留检查；`VueTurnMemoryProjection` 增加仅在内存投影中使用的余额原因，不改变数据库字段或终态枚举。

### 3. 复用前端展示

`generationSession` 与页面 `outcomeMessage` 原有逻辑已经满足展示目标；新增协议回归验证前置帧、Vue 终态、普通生成错误、加载结束和预览规则，不为了文案增加前端分支或宽松协议解析。

### 4. 验证与基线

Java 测试使用真实 SDK 异常及默认映射器、Mock 服务依赖与实际 SSE 编码，以用户确认的 DeepSeek HTTP 402 为必需样例，覆盖包装异常、余额正例、限流等反例、敏感原文、资源释放和持久化展示。前端使用现有 Vitest 会话解析测试。无需真实付费调用；证据放 `.codex/expose-model-balance-error/`。

standard 在当前执行上下文自查。规划持续约束与可修改源码分开登记；已完成的图片优化保持不变。

## Risks / Trade-offs

- 误将限流或额度提示识别为余额 → 只匹配明确余额证据，保留反例测试。
- 提供方改变错误格式或包装丢弃 cause → 缺少证据时保持通用提示，不猜测原因；用户补充原始错误时增补对应样例。
- 错误文案触及终态收尾 → 不变更终态枚举、先后顺序和释放逻辑，运行现有生命周期回归。
- 没有真实余额错误日志 → 如实限定为受控验证，不宣称线上复现。

## Migration Plan

无数据迁移。交付后按用户需要重启后端加载；回退本轮源码即可恢复旧提示。已存历史保持原样。
