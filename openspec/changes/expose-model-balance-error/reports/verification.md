# 模型余额不足提示验收

- 日期：2026-11-02；Profile：standard；核验方式：machine。
- 规划基线：`reviews/planning-review.json`，持续约束有效，实施开始在首次源码修改前登记。
- 当前实现：`511edc1` 加本变更工作区修改；保留先前图片优化。后端及前端各自源码哈希与最终通过运行一致。
- 结论：**Ready for Archive**。3 项任务已完成，无阻塞；本轮未归档、提交或重启。

## 实现与覆盖

[verification-manifest.json](verification-manifest.json) 是唯一详细映射：4 项 Requirement、7 项 Scenario、3 项 Task，共 14 条，均对应当前实现、测试和原始证据，已人工核对完整覆盖。

`ModelServiceErrorMessages` 识别 DeepSeek HTTP 402，以及 SDK 中明确的余额错误字段或消息；沿 cause 有界遍历，固定输出“模型服务余额不足，请充值后重试。”。前置异常、JSON 响应、Vue 生成和提交后启动失败、普通生成均接入；新失败记录与可信原因保持一致。确认最新请求时取得实际 `insufficient_balance_error` 类型和带 request_id 后缀的消息，发现类型白名单漏项，已补齐并加入不依赖 HTTP 状态码的 SDK 响应回归。

代码自查确认未知故障沿用各入口回退提示，错误码和 SYSTEM 失败类别不变。前端继续优先使用后端 message，未新增业务展示分支。取消、协议、构建终态和唯一收尾顺序未修改，固定提示已纳入终态预算检查。评审为当前执行上下文普通自查，standard 不要求独立评审。

## 本次验证

- 后端最终回归：**211 项通过，失败 0、错误 0、跳过 0**，覆盖 SDK 默认异常映射、实际余额错误类型、路由前置失败、实际生成收尾与 SSE 编码、普通流及既有生命周期。
- 前端：**116 项通过，失败 0、跳过 0**，覆盖前置余额帧、Vue 余额终态、普通生成中断、加载结束、页面提示入口和预览规则。新增两项执行页面原有 `outcomeMessage` 与 `attachSessionListener` 函数、连接真实会话解析器的测试，确认两种错误帧均只调用一次 `message.error`，参数为完整余额提示。
- `git diff --check`、`openspec validate expose-model-balance-error --strict`、规划指纹检查均通过。

真实执行命令、时间、工作目录和退出码见结构化清单。入口脚本为 `.codex/expose-model-balance-error/run_tests.py` 和 `run_frontend_tests.py`；Java 临时目录、测试工作目录与 Vitest 缓存均位于本变更 `.codex/` 目录。

主要原始证据：

- [最终汇总与源码比对](../../../../.codex/expose-model-balance-error/summary.json)
- [后端运行记录](../../../../.codex/expose-model-balance-error/actual-format-regression/command.json)、同目录 `maven.log` 与 `junit/`
- [前端运行记录](../../../../.codex/expose-model-balance-error/page-confirmation/command.json) 和 [Vitest 结果](../../../../.codex/expose-model-balance-error/page-confirmation/vitest.json)
- [实际错误格式摘录](../../../../.codex/expose-model-balance-error/actual-format-sample.json)：来自最新运行日志，请求标识已替换为测试值，未推断原日志未记录的 HTTP 状态码。
- [静态与基线检查](../../../../.codex/expose-model-balance-error/final-checks.json)

首轮生成回归曾有 2 个测试失败，原证据保留在 `generation/`：一处错误地从客户端简短终态重建完整展示和记忆，现改为从实际持久化调用参数核对；另一处固定文案清单尚未加入新增提示，现已补齐并继续验证最大预留长度。未降低验收条件；此前 210 项通过记录保留，本次在补齐实际类型后重新运行 211 项，全部通过。

## 验证边界与交付

使用项目真实 LangChain4j 默认异常映射器、Vue 收尾组件与 SSE 编码；模型调用和存储依赖使用替身，验证的是返回提示、写入参数及资源释放，并非真实 DeepSeek 扣费或数据库写入。本轮新增证据来自用户已发生请求的日志，不发起收费请求。前端执行原页面回调，DOM 操作及提示组件以 spy 观察调用，未宣称已在运行中的浏览器看到新弹窗。

既有未知错误和不明确配额错误不会被猜测成余额不足。原始服务商内容仅用于后端识别，不进入 SSE、JSON、失败展示或可信原因。既有故障注入日志和 JDK 的 Lombok／Mockito 兼容警告不影响本次结果。

运行中的后端仍使用上一次重启的字节码，后续重启后新请求才加载此提示；不回写已有历史。无数据迁移，回退本轮错误提示相关源码即可恢复原行为。
