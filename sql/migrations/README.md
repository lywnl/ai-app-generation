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

本次实现只在独立测试容器演练迁移，没有连接现有业务库执行该脚本。运行时规则和聊天例子见 [L2 用户长期记忆说明](../../docs/l2-user-memory.md)。
