# 数据库迁移记录

这里是迁移 SQL 的唯一来源，文件内容纳入 Git 管理。构建脚本不再复制全部历史迁移到 `prod/`。
`sql/schema.sql` 与 `prod/sql/schema.sql` 用于全新数据库初始化，已有数据库必须按版本执行增量迁移。

第一版升级第二版的执行顺序：

1. `2026-08-14-memory-baseline.sql`：旧库补齐三张记忆表。
2. `2026-08-15-token-layered-memory-v3.sql`：分层记忆字段、索引和兼容数据。
3. `2026-08-18-chat-history-memory-projection.sql`：对话记忆投影。
4. `2026-09-10-user-display-identity.sql`：匿名昵称和统一头像。

以上升级已在当前生产环境完成，日常发布无需重复执行。
需要升级其他旧环境时，先备份并检查现有结构，再按上述顺序选择、审核和执行。
目录调整不会撤销已执行的数据库修改，也不会触发新的迁移。

在项目根目录执行以下命令，可将指定迁移随已有应用产物一起打包：

```bash
python3 prod/package-release.py --release-id your-release \
  --migration 2026-08-14-memory-baseline.sql \
  --migration 2026-08-15-token-layered-memory-v3.sql \
  --migration 2026-08-18-chat-history-memory-projection.sql \
  --migration 2026-09-10-user-display-identity.sql
```

该命令只复制文件并生成校验和，不连接数据库或执行 SQL。部署测试位于 `tests/deployment/`，
记忆结构和迁移内容约束测试位于 `src/test/java/com/lyw/appgeneration/sql/`。

## 2026-10-30：L2 正式记忆与候选隔离

`2026-10-30-l2-user-memory-isolation.sql` 尚需按目标环境单独执行，不属于上面已完成的旧版本升级。新代码依赖这次表结构，不能让旧写入程序与新程序同时运行。

1. 停止 L2 写入，独立导出旧 `app_memory` 和抽取游标，并保存应用版本及数据库结构。
2. 使用 MySQL 8.0.40 的 `mysql` 客户端在选定数据库执行脚本，不使用 `--force`。脚本先输出旧状态统计，检查未知内容、同属性互斥组合与 ID 溢出。出现问题会输出条目清单并停止，原业务表保持不变。
3. 根据原意确认无法映射的数据，再重试迁移；不能为通过预检而把候选改为 `ACTIVE`，或直接删除异常条目。
4. 脚本建立影子正式表、独立候选表、证据表以及 ID 映射表。旧组合中能明确区分的属性会拆分；“中英双语”整体保留。首个拆分项保留旧 ID，其余项在检查有符号 BIGINT 余量后安全分配，不对 Snowflake 做乘法。
5. 逐字段及行数验收成功后原子改名切换。原表完整保留为 `app_memory_legacy_20261030`，映射保留在 `app_memory_migration_20261030`。不伪造旧证据明细，不自动清理任何历史。
6. 核验状态分布、映射、旧计数和已知证据回合，再启动新后端。重复执行会识别已迁移结构并保留新数据。

执行示意（连接凭据使用部署环境的既有安全配置）：

```bash
mysql --default-character-set=utf8mb4 --database=ai_app_generation \
  < sql/migrations/2026-10-30-l2-user-memory-isolation.sql
```

MySQL DDL 会隐式提交，因此脚本不能被误认为一个可整体回滚的普通 DML 事务。切换前失败保留原表；切换后若需回退，必须先停写并导出新正式、候选、证据和游标，再按备份及映射人工恢复兼容版本。直接降级旧二进制会误解新状态和同类别多条记录，禁止用删列、清候选或覆盖整库的方式自动回退。

各环境的执行状态须分别核实，不以其他环境的迁移记录代替本环境检查。运行时规则和聊天例子见 [L2 用户长期记忆说明](../../docs/l2-user-memory.md)。

## 2026-10-30：长期记忆管理页

`2026-10-30-l2-memory-management.sql` 依赖上面的正式与候选分表迁移，供已有数据库启用管理页时执行。全新数据库的两份 schema 已包含最终结构，无需再执行历史迁移。

1. 停止后端写入，备份 `app_memory`、`app_memory_candidate`、`app_memory_evidence`、`app_memory_extract_cursor`；若已有 `app_memory_event`，也一并备份。
2. 记录上述表的行数和旧列内容，在选定的 MySQL 数据库执行脚本，不使用 `--force`。
3. 脚本为候选表新增 `requiresConfirmation`（旧候选默认 0），为证据表新增 `sourceType`（旧证据默认 CHAT），并创建 `app_memory_event`、请求唯一索引及字段约束。所有步骤可重入，不删除旧偏好或补造历史证据。
4. 对比旧列与行数，确认内容、状态、证据计数和抽取游标均未变化；检查新增默认值、约束和 `(userId, requestId)` 唯一索引。
5. 启动包含新实体和管理接口的后端，再验证登录后的 `/api/user/memory/list`、`preview`、`change`、`history` 与前端 `/user/memory`。

执行示意：

```bash
mysql --default-character-set=utf8mb4 --database=ai_app_generation \
  < sql/migrations/2026-10-30-l2-memory-management.sql
```

管理页与新后端同时发布。旧后端不能正确处理手动证据来源及须确认候选；启用手动管理后不要只回退后端程序。DDL 仍会隐式提交，失败时保留备份与错误记录，定位后重入；若需回退，先停写并导出迁移后的新增数据，再按兼容方案恢复，不能清空操作表或候选表。

## 2026-10-31：统一摘要与决策证据

`2026-10-31-unify-context-summarization.sql` 在已有摘要表和聊天记忆投影列之上执行，只增加四列，不回填旧正文、可信结果或 L2。两份初始化 schema 已包含这些列，增量 SQL 以本目录为唯一来源。

| 表／字段 | 类型、默认值及含义 |
| --- | --- |
| `app_memory_summary.summaryRevision` | `BIGINT NOT NULL DEFAULT 0`，摘要与覆盖快照的单调修订 |
| `app_memory_summary.coverageVersion` | `INT NOT NULL DEFAULT 1`，1 为旧完整历史游标，2 为组合覆盖 |
| `app_memory_summary.coverageState` | `MEDIUMTEXT NULL`，应用层校验的部分回合覆盖 JSON |
| `chat_history.decisionEvidence` | `MEDIUMTEXT NULL`，用户记录上内部使用的版本化证据，不进入历史 API 或 L2 |

执行前停止新生成进入，按现有生命周期等待或结束在途回合，并备份 `app_memory_summary`、`chat_history` 的结构、行数及旧列内容。使用已授权的 MySQL 8.0.40 目标执行脚本，不使用 `--force`；完成后核验四列元数据、旧内容和行数。旧行得到修订 0、覆盖版本 1、空覆盖和空证据，不被改写为新摘要。

脚本可重复执行；已有列只验收类型、默认值及可空性，已经写入的新数据保持不变。DDL 会隐式提交，不是能够整体回滚的 DML 事务；中途失败应保留已完成的列并诊断后重入。应用后续的摘要／证据 DML 由各自短事务和修订条件保护，不能用其回滚语义解释本脚本。

仅加列、尚未写入新覆盖或 L0 封装时，可保留新增列并回退程序。新格式写入后，旧完整游标逻辑不能接管，必须使用支持新摘要、覆盖和 L0 格式的修复版本；新旧写入器不得混用。不得清空新摘要或决策证据来实现降级。Redis 旧数组与冷重建的读取下界适配及恢复步骤由本变更任务 8.4 随实现补齐，不能把换 epoch 当成摘要依赖归零。
