# 任务 9：本地迁移、缓存隔离与生产适配层探针证据

## 结论

- 本任务保存的开始/结束检查点中，四个既有目标容器的 `StartedAt` 完全一致；当前 Docker context `desktop-linux`、daemon ID 和最终容器集合已留证。当前 daemon 可返回窗口内，容器 `create/destroy/start/stop/die/restart/kill/oom/pause/unpause` 与卷 `create/destroy/prune` 事件均为 0，最终仍恰好只有目标四个容器。该证据证明目标四容器未重启或重建，并在当前 daemon 的可见窗口内未观察到第五个容器；不外推为其他 daemon 或不可见历史窗口的绝对证明。
- `exec_create` 文本危险模式命中 0 只作补强，因为它看不到 stdin、重定向文件或被调用脚本的内容。Redis 当前进程自启动以来 `FLUSHALL/FLUSHDB` 累计调用均为 0；MySQL 破坏性 SQL、行删除与逻辑删除由 Binlog 独立审计，不依赖 Docker exec 文本。
- MySQL 8.0.40 迁移前创建唯一不可覆盖备份表，96/96 行备份完成。
- 同一迁移连续执行两次，投影总指纹两次均为 `1594f2da9948c619f1e70c0ce6d939595e21d612b50a673b963749e8ef384106`，证明幂等。
- 96 行 `message` 与备份逐行比较 0 变化；迁移前后展示正文聚合 SHA-256 均为 `83691959b2585cb75af6183cccf5e793b782243b7f0ca223a015b3d074ea85d8`。
- 已知故障消息 `447109043745288192` 为 `PROTOCOL_ERROR`，投影 49 字符，伪工具标记为 0；原展示正文长度和 SHA-256 均未变化。
- 47 条其余旧 Vue AI 行全部为 `LEGACY_UNVERIFIED`；48 条用户行投影为空；AI 缺失/半状态/协议轨迹违规均为 0。
- 当前本地数据的 18 个应用全部是 `vue_project`，没有 HTML/MULTI_FILE 历史可做 `LEGACY_IMPORTED` 数据级抽样；该分支由字节级一致迁移脚本与 `MemorySchemaMigrationContractTest` 永久契约证明，不伪造业务夹具冒充真实历史。
- 生产 `SpringRedisChatMemoryStore` 实测忽略旧裸 key，只读写 `chat-memory:l0:v2:`；探针结束只精确删除两个完整 key，并确认清理成功。
- 全部未删除的真实 L1/L2 经脱敏统计：L1 7 行，`[工具调用]` 0、工具 JSON 0；L2 9 行，`[工具调用]`、工具 JSON、目标工具名均为 0。L1 有 2 行正常摘要提及工具名，不等同于伪工具轨迹；L2 游标仍有 19 行，证明没有为本任务全量清空 L2。
- 最终真实模型生产适配层探针：10/10 单次响应均为结构化 `readDir`，0/10 伪工具标记，0/10 重复普通正文行，0/10 非空普通正文；该探针未执行并回传工具结果，因此不把本结果表述为“多轮工具循环已验证”。真实多轮工具链由任务 10 的 Chrome E2E 单独证明。
- 额外执行 `.codex/run-tool-protocol-probe.mjs` 经脱敏 wire observer 的单次观测：HTTP 200、15 个结构化 `readFile` 分片、普通正文 0 字符、非法 JSON 0；observer 自测 2/2 通过。

## 四容器

| 容器 | 镜像 | 状态 | 绑定端口 |
|---|---|---|---|
| `ai-app-generation-dev-nginx` | `nginx:1.27-alpine` | healthy | `127.0.0.1:80` |
| `ai-codegen-e2e-mysql` | `mysql:8.0.40` | running | `127.0.0.1:3406` |
| `ai-codegen-rag-eval-redis` | `redis:7.4-alpine` | healthy | `127.0.0.1:6379` |
| `ai-codegen-rag-eval-pg` | `pgvector/pgvector:pg16` | healthy | `127.0.0.1:5432` |

原始证据：

- 任务开始/结束 `StartedAt`：`.codex/verification/task-9-containers-started-at-before-final.txt`、`.codex/verification/task-9-containers-started-at-after-final.txt`
- 前后一致断言：`.codex/verification/task-9-container-runtime-continuity-final.log`
- 可重跑审计脚本：`.codex/verification/run-task-9-container-volume-audit.sh`
- Docker context/daemon、证据边界、事件计数、Redis 进程级 FLUSH 计数、最终容器枚举与退出码：`.codex/verification/task-9-container-volume-event-audit.log`

## MySQL 证据

- 迁移前 SQL：`.codex/verification/task-9-mysql-before.sql`
- 迁移前日志：`.codex/verification/task-9-mysql-before.log`
- 双次迁移日志：`.codex/verification/task-9-mysql-migration.log`
- 迁移后 SQL：`.codex/verification/task-9-mysql-after.sql`
- 迁移后日志：`.codex/verification/task-9-mysql-after.log`
- 备份表：`chat_history_memory_projection_backup_20260818_223033`
- 备份创建前存在数：0；备份后 96 行，AI/用户各 48 行。
- 已知故障展示正文：21,517 字符；SHA-256 `d1d7bc5e48a2ec02153484a1981d94db78af58c0bed2832853459716e8221b58`，迁移前后相同。

## Redis 证据

- 探针源码：`.codex/MemoryProjectionRedisNamespaceProbe.java`
- 运行日志：`.codex/verification/task-9-redis-namespace.log`
- 核心结果：`oldKeyIgnored=true`、`versionedKeyRoundTrip=true`、`exactKeys=2`。

## 真实模型探针证据

- 探针源码：`.codex/ExactMemoryToolCallProbe.java`
- 首次失败观测：`.codex/verification/task-9-exact-model-probe.jsonl`。原目标用户指令只有 6 字符且不明确要求工具，10 次仅 3 次结构化调用；0 次伪工具循环。该结果用于证明验收输入不充分，不能算通过。
- 明确工具任务校准：`.codex/verification/task-9-exact-model-probe-calibration.jsonl`，3/3 结构化 `readDir`。
- 权威正式结果：`.codex/verification/task-9-exact-model-probe-final-v3.jsonl`。正式 `actual` 模式强制至少 10 次；本次 10/10 均恰好返回一个结构化 `readDir`，参数为合法 JSON 且只含符合 Schema 的文本字段 `relativeDirPath`，普通正文、伪工具标记和工具名均为 0，最终 `passed=true`、`probe-exit=0`。
- 最少次数负向门禁：`.codex/verification/task-9-exact-model-probe-min-runs-gate.log`。`actual` 少于 10 次时以非零退出并报告“至少必须运行 10 次”，证明正式探针不会把 `1/1` 当成通过。
- `actual` 上下文硬断言：L1 非空；L0 恰好 4 条且用户/AI 各 2 条；SQL 层 AI 只读取 `memoryMessage`，没有展示正文回退；参数只记录合法性、长度与 SHA-256，不记录参数值。
- 正式输入包含目标应用真实 L1 摘要和迁移后 `memoryMessage` L0；L2 部分明确使用合成偏好夹具，不冒充真实用户 L2 数据。用户任务改为明确、安全、必须读取工程的请求。模型仍为 `tool_choice=auto`，没有通过强制 `required` 美化成绩。
- 报告只记录消息数量/字符总数、工具名、调用计数、正文长度和正文 SHA-256，不记录系统提示、用户正文、摘要正文、投影正文、工具参数或模型正文。
- wire-level 观测：`.codex/verification/task-9-wire-observation.jsonl`；只记录角色计数、工具元数据、分片计数、Token 用量和受控枚举，不记录消息正文、工具描述或参数值。

## 真实 L1/L2 脱敏统计

- 权威日志：`.codex/verification/task-9-real-l1-l2-sanitized-stats.log`
- 采集范围：本地 MySQL 全部 `isDelete=0` 的 L1 摘要、L2 偏好与 L2 游标。
- 隐私边界：只输出计数、最大游标、采集时间和 MySQL 版本，不输出摘要、偏好、用户或模型正文。
- 结果：L1 `7/0/0/2`（总行/伪标记/工具 JSON/工具名）；L2 `9/0/0/0`；L2 游标 `19` 行，最大游标 `447346724575535104`。
- 未清空判断以任务 9 不可覆盖备份表的 Binlog `CREATE TABLE` 位置作为精确检查点。脚本先冻结审计终点 `file/position`，再严格读取到该排他终点，避免“先读事件、后取终点”的竞态：检查点后 `app_memory` 的 `Write/Update/Delete_rows` 均为 0；`app_memory_summary` 只有 1 次写入、0 次删除；游标只有 1 次写入、4 次更新、0 次删除；三张表破坏性 Query 为 0。对于当前 daemon 仍保留的 `binlog.000001` 至冻结终点，三张记忆表物理 `Delete_rows`、破坏性 Query 和 FULL 行镜像中的 `isDelete: 0→1` 逻辑删除均为 0。这个结论只覆盖当前保留日志，不外推到未保留日志或其他 daemon。可重跑脚本 `.codex/verification/run-task-9-memory-binlog-audit.sh`，结果 `.codex/verification/task-9-memory-binlog-audit.log`。

## 未执行的破坏性操作

- 未删除备份表；它是本次本地迁移的恢复点。
- 未全量清空 L2。
- 未使用 Redis `KEYS *`、SCAN 或通配删除。
- 未重建或停止任何容器。
- 未 push。
