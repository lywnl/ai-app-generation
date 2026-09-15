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
