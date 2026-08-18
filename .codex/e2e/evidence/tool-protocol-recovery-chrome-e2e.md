# 工具协议恢复与记忆压缩 Chrome E2E 汇总

## 验收范围

- Chrome 实际操作页面：受控前端 `http://127.0.0.1:5174`，真实前后端 `http://localhost:5173/app/chat/400000000000000001`。
- 真实专用应用：`appId=400000000000000001`；只在本地测试数据和工程目录内操作。
- 四个目标 Docker 容器的 `StartedAt` 前后一致，最终仍恰好只有这四个容器；当前 daemon 可返回事件窗口内未观察到容器创建/销毁或卷破坏事件。详细边界见任务 9 审计报告，不把不可见历史外推为绝对证明。
- 本报告只引用脱敏计数、终态、Token 和截图；不记录原始模型请求或完整 L0 正文。

## 四个确定性受控场景

| 场景 | Chrome 观察 | 终态与门禁 | 证据 |
|---|---|---|---|
| 正常生成 | 正文开始后不再显示“AI 正在思考”；`readDir` 工具卡完成，预览显示“受控可信预览” | `SUCCEEDED`，预览可刷新 | `tool-protocol-normal.png` |
| 校正成功 | `STARTED` 清除临时伪工具前缀；恢复后只显示可信正文和真实 `readDir` 工具卡 | `SUCCEEDED`；没有污染前缀回流 | `tool-protocol-recovery-started.png`、`tool-protocol-recovery-success.png` |
| 校正失败 | 二次退化后显示后端固定友好错误；没有第三次请求，没有新的工具卡 | `PROTOCOL_ERROR`，不刷新预览 | `tool-protocol-recovery-failed.png`；mock 状态的请求计数门禁由服务端脚本和前端测试共同覆盖 |
| 压缩/校正重叠 | 重叠时优先显示“正在压缩上下文”；压缩完成后显示“正在校正工具调用”；真实正文后两者都隐藏 | `SUCCEEDED`，状态优先级符合契约 | `tool-protocol-overlap-compression.png`、`tool-protocol-overlap-recovery.png`、`tool-protocol-overlap-final.png` |

受控服务端：`.codex/e2e/tool-protocol-recovery-mock-server.mjs`。它只发后端固定的受信 SSE 结构，不由模型正文伪造状态。

## 最新状态机定向回归

最终修复后用 Chrome 只补跑受影响的组合场景，不重复四个已完成场景：

1. 服务端先输出可信正文并完成真实 `readDir` 工具事件，前端据此建立可信正文 checkpoint；
2. 后续 generation 输出临时污染正文后进入 `STARTED`，页面仍保留“此前可信正文”和已完成的 `readDir` 工具卡，同时显示“正在校正工具调用”，临时正文不可见；
3. 服务端发送合法 `RECOVERED → FAILED → PROTOCOL_ERROR`，前端正常结束而不是误判控制协议；最终仍只保留此前可信正文/工具卡，临时污染正文与校正提示均不可见；
4. mock 状态：`requestCount=1`、`terminalCount=1`、`previewRefreshEligible=false`，Chrome 控制台 error/warn 为 0。

证据：`tool-protocol-trusted-checkpoint-started.jpg`、`tool-protocol-trusted-checkpoint-recovered-failed.jpg`。

## 真实后端多轮与压缩链路

### 1. 压缩前真实结构化工具回合

- 用户要求读取 `src/App.vue` 并执行真实构建。
- 页面历史显示 `readFile(src/App.vue) → buildProject`，终态成功，构建次数 1。
- 截图：`tool-protocol-real-round-1.png`。
- AI 展示 `message` 保留工具卡；对应 `memoryMessage` 只保留确定性结果，不含 `[工具调用]`。

### 2. 真实协议退化与隔离

- 一次校准回合出现协议退化，后端落成 `memoryOutcome=PROTOCOL_ERROR`。
- 安全投影固定为“本轮发生工具协议异常……不得复用本轮生成内容”，没有保存失败正文。
- Redis L0 脱敏检查：`[工具调用]` 0，内部纠正 Prompt 0。
- 证据：`.codex/e2e/evidence/real-compression-l0-after-protocol-error.json`、`.codex/e2e/evidence/real-post-compression-mysql-check.txt`。
- 这次随机真实模型未恢复，不用于替代受控的恢复成功/失败验收；它只证明真实失败会受控熔断和隔离。

### 3. 30K 阻塞压缩

- 压缩前估算：`31,893 Token`。
- 压缩后估算：`15,961 Token`。
- 指标：`memory_compression_total{mode="blocking",outcome="compressed"}=1`，门禁模式 `blocking_completed`。
- L1 摘要：`460 Token`，未触发二次 reducer；摘要中伪工具标记和参数 JSON 均为 0。
- 证据：`.codex/e2e/evidence/real-blocking-metrics-after.txt`、`.codex/e2e/evidence/real-blocking-l0-after.json`。
- Chrome 运行时已观察左右两处“正在压缩上下文，请稍候…”。该瞬态截图没有成功落盘，因此本报告不把不存在的截图列为证据；压缩执行本身由指标、L1 与压缩后回合共同闭环。

### 4. 压缩后读取、修改与真实构建

- 用户要求检查并添加 `<!-- memory-projection-e2e -->`，随后执行真实构建。
- 页面历史显示 `readFile(src/App.vue) → modifyFile(src/App.vue) → buildProject`；构建成功，标记恰好出现 1 次。
- 最终 AI 行：`memoryOutcome=SUCCEEDED`；展示正文 402 字符，投影 81 字符，投影中 `[工具调用]`、参数 JSON、内部纠正 Prompt 均为 0。
- L0 中 `[工具调用]` 和内部纠正 Prompt 均为 0；L1 中伪工具轨迹和参数 JSON 均为 0。
- 截图：`tool-protocol-real-post-compression-modify-build.png`。
- 跨层证据：`.codex/e2e/evidence/real-post-compression-mysql-check.txt`、`.codex/e2e/evidence/real-post-compression-l0.json`、`.codex/e2e/evidence/real-post-compression-metrics-after.txt`。

## 每轮脱敏验收表

| 阶段 | 终态 | 结构化工具 | 普通正文伪工具标记 | 压缩事件 | Token/投影结果 |
|---|---|---:|---:|---|---|
| 压缩前 | `SUCCEEDED` | `readFile`、`buildProject` | 0（投影/L0） | 无 | 构建 1 次 |
| 真实协议异常 | `PROTOCOL_ERROR` | 未完成目标工具链 | 0（安全投影/L0） | 无 | 失败正文不入稳定记忆 |
| 阻塞压缩 | 压缩完成 | 不适用 | 0（L1/L0） | blocking 1 次 | `31,893 → 15,961`，摘要 460 |
| 压缩后修改构建 | `SUCCEEDED` | `readFile`、`modifyFile`、`buildProject` | 0（投影/L0/L1） | 无新增 | 投影 81 字符，构建 1 次 |

## 结论与边界

- 多轮真实工程对话在压缩前后都能执行结构化工具；压缩后没有出现无限伪工具循环或旧工具轨迹回流。
- 展示正文可以继续呈现工具卡，模型记忆只读取可信投影；MySQL、Redis L0、L1 均未观察到被隔离的伪工具正文或内部纠正 Prompt。
- 真实模型行为有随机性，所以协议恢复的所有分支以受控 Chrome 场景作为确定性证据；真实后端场景负责证明生产接线、压缩和跨层记忆隔离。
