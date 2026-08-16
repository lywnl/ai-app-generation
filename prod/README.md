# 生产部署（仅上传 prod 目录）

## 1. 本地先生成产物（前后端打包进 prod）

在项目根目录执行：

```powershell
.\prod\build-artifacts.ps1
```

该步骤会生成这些内容：

- `prod/artifacts/backend/app.jar`
- `prod/artifacts/frontend/dist/*`
- `prod/sql/schema.sql`
- `prod/embed_text/*`
- `prod/grafana/dashboards/ai-model-observability-dashboard.json`

## 2. 上传到服务器

只上传 `prod` 目录到服务器，例如：

`/opt/ai-app-generation/prod`

## 3. 服务器部署

进入服务器上的 `prod` 目录：

```bash
cd /opt/ai-app-generation/prod
cp .env.example .env
```

编辑 `.env`，必须显式填写部署产物的外部访问基地址，例如：

```env
APP_CODE_DEPLOY_BASE_URL=http://your-domain.example
```

该值只能包含协议、主机和可选端口，不能包含业务路径；部署访问路径由后端统一追加。

默认账号密码（已预置）：

- MySQL：`admin / lyw666`（root 密码 `lyw666`）
- Redis（ACL）：`admin / lyw666`
- PostgreSQL：`admin / lyw666`
- Grafana：`admin / lyw666`

如果暂时不填 API Key，请保留这些键且值为空：

```env
DEEPSEEK_API_KEY=
DASHSCOPE_API_KEY=
COS_HOST=
TEN_SERCET_ID=
TEN_SECRET_KEY=
PEXELS_API_KEY=
```

启动：

```bash
docker compose --env-file .env -f docker-compose.yml build
docker compose --env-file .env -f docker-compose.yml up -d
```

### 启用 Vue Hybrid 检索（默认关闭）

`RAG_HYBRID_ENABLED` 默认值为 `false`。仅当以下真实门禁严格依次完成后，才允许开启：

1. 正式 23 条摄取并物理核验通过。
2. 30 条真实检索达标。
3. 十条首次生成 10/10。
4. 在 `.env` 设置 `RAG_HYBRID_ENABLED=true`。
5. 重启 backend：

   ```bash
   docker compose --env-file .env -f docker-compose.yml up -d --force-recreate backend
   ```

任一步失败都保持 `RAG_HYBRID_ENABLED=false`。默认 Maven、PGVector 协议探针、五骨架策展构建都不能替代以上三项真实成绩，也不得据此开启 Hybrid。

PostgreSQL 说明：

- 当前已改为直接使用 `pgvector/pgvector:pg16` 镜像
- 不再在本地编译 pgvector（避免慢速 `apt + git + make`）
- 若你已提前拉取镜像，可直接启动：

```bash
docker compose --env-file .env -f docker-compose.yml up -d pg
```

## 4. 常用检查

```bash
docker compose --env-file .env -f docker-compose.yml ps
docker compose --env-file .env -f docker-compose.yml logs -f backend
```

访问：

- 业务入口：`http://服务器地址`
- 后端健康：`http://43.138.69.10:9025/api/actuator/health`
- Prometheus：`http://43.138.69.10:9090`
- Grafana：`http://43.138.69.10:3000`

## 5. 说明

- 后端镜像内包含 `chromium + chromedriver`，满足截图服务。
- 后端镜像内全局安装 `@mermaid-js/mermaid-cli`，命令为 `mmdc`。
- 服务器部署时不再依赖项目根目录源码，只依赖 `prod` 本目录文件。

## 6. Token 分层记忆 V3 上线前人工门禁

本节只定义上线检查、停止条件和非破坏性回滚流程。`prod/sql/migrations/2026-08-15-token-layered-memory-v3.sql` 不会被 `V1__hnsw_index.sql` 或应用启动自动执行；真实生产备份、migration、部署、回滚和删列都必须由具备权限的人员另行审批后操作。

### 6.1 发布前置条件

1. 固定目标数据库为 **MySQL 8.0.40**，记录当前后端、前端、数据库 schema 版本和发布时间窗。
2. 停止扩大流量；若无法保证数据库变更期间没有新写入，必须使用平台提供的一致性备份或短暂停止新生成流。
3. 分别备份 `app_memory_summary`、`app_memory`、`app_memory_extract_cursor`，记录每张表的行数、主键范围和备份位置。
4. 在非生产实例完成一次恢复验证，确认备份不是“文件存在但无法恢复”。

出现以下任一情况立即停止：数据库版本不是 8.0.40、备份缺失或恢复失败、磁盘空间不足、三张表行数/主键范围无法解释、发布版本与本 migration 不匹配。

### 6.2 MySQL 8.0.40 非生产副本三项演练

只在由旧生产结构复制出的非生产数据库执行：

```bash
mysql --default-character-set=utf8mb4 < prod/sql/migrations/2026-08-15-token-layered-memory-v3.sql
```

必须分别留存三组证据：

1. **旧结构首次执行**：从完全未升级的旧结构运行完整脚本；确认脚本返回的列定义、NULL 数、兼容数据数量和唯一索引均符合预期。
2. **部分状态续跑**：在非生产副本模拟“部分列已增加、兼容列仍可空、部分旧数据待回填”的中断状态，再重新运行完整脚本；确认 `information_schema` 条件判断不会重复加列，NULL 被回填后才收紧约束。
3. **完整脚本连续双跑**：在首次成功后不清库，连续再运行一次完整脚本；第二次必须成功，三张表行数、旧数据迁移数量和索引不能发生非预期变化。

每次演练都要核对：

- `app_memory_summary.nextRetryTime` 与 `app_memory_extract_cursor.nextRetryTime` 为 `DATETIME NULL DEFAULT NULL`。
- `app_memory.status/evidenceType/evidenceCount` 分别为 `ACTIVE/EXPLICIT/1` 的非空兼容默认值；`lastEvidenceTurnId` 可空。
- `app_memory` 兼容列 NULL 行数为 0；旧偏好迁移为 `ACTIVE + EXPLICIT + 1` 的数量与执行前旧数据量一致。
- `uk_appId`、`uk_userId_type_name` 等既有唯一索引仍存在。
- 脚本中的 `DROP COLUMN` 只位于注释标记的手工参考区，没有进入正向执行路径。

任一 SQL 报错、行数不一致、存在 NULL、默认值错误或索引丢失时停止发布。不要在生产环境“继续试一下”；先在非生产副本定位根因并重新完成三项演练。

### 6.3 配置和兼容性门禁

部署前核对后端实际配置：

```yaml
ai.memory.token:
  l0-retained-tokens: 12288
  l1-max-summary-tokens: 3072
  l2-max-recall-tokens: 1024
  async-compression-threshold: 28672
  blocking-compression-threshold: 30720
  hard-input-limit: 32768
  max-output-tokens: 8192
  minimum-model-context-window: 40960
  blocking-timeout: 60s
  l2-debounce: 30s
  estimation-safety-factor: 1.15
```

- 主模型供应商声明和实际配置的上下文窗口都必须至少为 `40960 Token`，不能只看应用中的阈值配置。
- 有界 L1/L2 与同步压缩线程池必须使用 `AbortPolicy`；不得切回静默 `DiscardPolicy` 或在请求线程执行 60 秒压缩。
- 当前版本的 app/user 一致性锁、single-flight 和版本仲裁均以单个后端进程为边界，因此只支持 **单后端实例**。在横向扩容前，必须为 L0/L1/L2 的提交、缓存失效、游标推进和删除栅栏设计跨实例原子协议，例如 Redis CAS/Lua、带版本的条件写入，或等价的分布式一致性方案；未完成该改造时不得直接部署多个后端副本。
- 新前端兼容旧后端；旧前端不认识新后端的 `context-compression` 命名事件。因此数据库迁移完成后，发布顺序固定为：**先前端，后后端**。

任一阈值错序、模型窗口不足、启动校验失败、部署副本数大于 1 或前端尚未完成协议升级时，不得部署新后端。

### 6.4 发布顺序与逐步验收

1. 完成三张表备份和非生产三项 migration 演练。
2. 在生产 MySQL 8.0.40 执行 migration，并立即核对字段、默认值、NULL 数、旧数据迁移数量和索引；不通过则停止，不部署应用。
3. 部署前端，验证旧后端下普通生成仍可用，未知事件门禁和既有唯一终态没有回归。
4. 部署后端；检查 `/api/actuator/health`、启动日志和 Prometheus 抓取。启动校验、数据库映射或缓存初始化出现异常时立即回滚后端包。
5. 只开放小流量，实际核对：28K 当前请求不被阻塞、30K 显示“正在压缩上下文，请稍候…”、COMPLETED 后恢复原文案、32K 复检拒绝不调用模型、控制事件不进入聊天正文。
6. 至少观察一个完整小流量窗口，再决定是否扩大流量。

### 6.5 观测与扩大/停止条件

必须同时观察：

- `memory_context_gate_total{mode,outcome}`：28K/30K/32K 门禁分布与失败原因。
- `memory_compression_total{mode,outcome}`、`memory_compression_duration_seconds{mode,outcome}`：真实压缩成功率、超时/模型失败和 P95。
- `memory_token_estimation_ratio{model_family}`：实际输入 Token / 估算 Token，判断是否低估。
- `memory_summary_tokens`、`memory_summary_reduce_rounds`：摘要是否稳定收敛到 3K。
- `memory_l2_debounce_total`、`memory_l2_candidate_total`、`memory_l2_recall_tokens`：30 秒防抖、证据状态和 1K 召回。
- 日志中不得出现用户正文、摘要正文、工具参数原文、原始模型输出或高基数业务 ID 的指标标签。

默认保守扩大条件：连续 30 分钟没有数据丢失、游标误推进、删除后复活或 SSE 协议错误；阻塞压缩 P95 小于 55 秒；超时占阻塞压缩尝试低于 1%；`memory_token_estimation_ratio` 的 P99 不大于 1。项目已有更严格 SLO 时，以更严格值为准。

出现以下任一情况立即停止扩大并回滚应用版本：任何数据/游标/缓存正确性异常；压缩 P95 达到或超过 60 秒；估算比值持续大于 1；32K 拒绝率突增且无法由真实超长输入解释；前端把控制事件写入正文或产生 `protocol_error`；监控或日志泄露敏感正文。数据库结构默认保留，不能把应用回滚升级为自动删列。

## 7. 非破坏性回滚

1. 停止新生成流，等待正在执行的门禁、压缩和 L2 抽取任务静默；确认相关 counter 不再增长且日志没有进行中的 owner。
2. 先回滚后端应用包，再按兼容性需要回滚前端；如果旧后端仍在线，新前端可以继续兼容。
3. 保留新增数据库列、L1 摘要、L2 证据、游标和原始 `chat_history`。新增非空列带有 `ACTIVE / EXPLICIT / 1` 默认值，旧应用不写这些字段仍可插入。
4. 回滚后执行一条旧版本兼容写入和读取检查，确认旧包不会因新增列失败；同时核对三张记忆表行数没有减少。
5. 默认不恢复整库备份，因为这会覆盖 migration 后产生的新业务数据。只有确认 migration 造成数据损坏、完成影响审计并获得单独授权后，才按数据库恢复流程处理。

破坏性 `DROP COLUMN` 不是常规回滚步骤。确需删列时，必须先停止所有新版本流量、导出六个新增列的内容、验证导出可恢复，再由人工逐条执行 migration 末尾注释中的明确列名 SQL；禁止把删列命令接入正向 migration、自动部署或普通应用回滚脚本。
