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
chmod 600 .env
```

`.env` 只保存在服务器本地，已被 Git 忽略，不要提交或上传到代码仓库。

编辑 `.env`，必须显式填写部署产物的外部访问基地址，例如：

```env
APP_CODE_DEPLOY_BASE_URL=http://your-domain.example
INFRA_SHARED_PASSWORD=请填写新的随机强密码
MYSQL_USER=admin
REDIS_USERNAME=admin
MILVUS_MINIO_PASSWORD=请填写至少8位的MinIO随机强密码
GRAFANA_ADMIN_USER=admin
```

该值只能包含协议、主机和可选端口，不能包含业务路径；部署访问路径由后端统一追加。

基础设施密码：

- 在 `.env` 中填写 `INFRA_SHARED_PASSWORD`。
- MySQL、Redis、Milvus root、Grafana 和后端连接统一使用该值。
- MinIO 使用独立的 `MILVUS_MINIO_PASSWORD`，不能复用当前长度不足 8 的共享密码；该密码至少为 8 个字符。
- MinIO 用户固定为 `minioadmin`，仅供 Milvus 内部对象存储使用，不对公网暴露。
- 建议使用新的随机强密码；仓库历史中的旧口令不应继续复用。
- 密码只能使用字母、数字、点、下划线和短横线，建议生成足够长的随机值。
- Redis ACL 由 `redis/start-redis.sh` 在容器启动时生成，仓库不保存明文 ACL。

注意：Redis 会在每次容器启动时应用新密码；MySQL、Milvus root 和 Grafana
只在首次初始化数据卷时读取初始化密码。已有数据卷切换密码前，必须先在各服务内
修改现有账号密码，再更新 `.env` 并重启；不能通过删除数据卷来“同步密码”。

Milvus root 初始密码仅在全新的 etcd 元数据中生效。已有 `milvus_etcd_data` 卷改密时，
必须先在 Milvus 内完成 root 密码修改，再更新 `.env` 中的 `INFRA_SHARED_PASSWORD`。
旧 PG 数据卷在迁移验收完成前只读保留，验收后先备份，再由人工删除；应用不会再连接旧卷。

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

# 单独启动并检查 RAG 向量基础设施
docker compose --env-file .env -f docker-compose.yml up -d milvus-etcd milvus-minio milvus
docker compose --env-file .env -f docker-compose.yml ps milvus-etcd milvus-minio milvus
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

任一步失败都保持 `RAG_HYBRID_ENABLED=false`。默认 Maven、离线协议探针、五骨架策展构建都不能替代以上三项真实成绩，也不得据此开启 Hybrid。

Milvus 说明：

- 服务端固定使用 `milvusdb/milvus:v2.5.9`，依赖 etcd `v3.5.18` 与固定版本 MinIO。
- 生产环境不发布 Milvus、etcd 或 MinIO 宿主端口；后端通过 `ai_net` 内部 DNS 连接 `milvus`。
- Compose 固定设置 `QUOTAANDLIMITS_FLUSHRATE_COLLECTION_MAX=-1`，仅取消单 Collection Flush QPS 上限，以支持稳定 ID 每次 upsert 后立即 flush；其他配额与保护保持 Milvus 默认值。
- 如需仅启动 RAG 向量基础设施：

```bash
docker compose --env-file .env -f docker-compose.yml up -d milvus-etcd milvus-minio milvus
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
- 后端镜像保留 Node.js、npm 及 npm registry 配置，支持 Vue 工程构建；同时保留 Chromium + chromedriver，满足截图服务。
- 服务器部署时不再依赖项目根目录源码，只依赖 `prod` 本目录文件。

## 6. Token 分层记忆 V3 上线前人工门禁

本节只定义上线检查、停止条件和非破坏性回滚流程。`prod/sql/migrations/2026-08-15-token-layered-memory-v3.sql` 不会由应用启动自动执行；真实生产备份、migration、部署、回滚和删列都必须由具备权限的人员另行审批后操作。

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
  async-compression-threshold: 49152
  blocking-compression-threshold: 57344
  hard-input-limit: 65536
  max-output-tokens: 8192
  minimum-model-context-window: 73728
  blocking-timeout: 60s
  l2-debounce: 30s
  estimation-safety-factor: 1.15
```

- 主模型供应商声明和实际配置的上下文窗口都必须至少为 `73728 Token`，不能只看应用中的阈值配置。
- 有界 L1/L2 与同步压缩线程池必须使用 `AbortPolicy`；不得切回静默 `DiscardPolicy` 或在请求线程执行 60 秒压缩。
- L0 最终裁剪通过 Redis Lua 跨实例 CAS 提交，并使用 Redis `TIME` 在快照比较前和实际写入前检查绝对截止。后端 JVM 主机与 Redis 主机必须启用并通过 NTP/chrony 等时钟同步健康检查；任一主机时间未同步时不得发布，否则 60 秒截止可能提前或延后生效。
- Redis ACL 至少允许 L0 使用脚本入口 `EVAL`，并允许脚本内调用 `TIME`、`GET`、`SETEX`、`SET`、`DEL`。当前 `prod/redis/start-redis.sh` 生成的 `admin +@all` 已覆盖这些命令；未来收紧权限时必须在非生产 Redis 以部署账号实际执行一次完整 L0 CAS 探针，不能只验证 `PING`。
- L0 的跨实例 CAS 只解决最终裁剪同一快照的竞争。当前版本的 L1/L2 single-flight、app/user 一致性锁、缓存失效、游标推进和删除栅栏仍以单个后端进程为边界，因此整体仍只支持 **单后端实例**。横向扩容前必须为这些剩余边界补齐分布式原子协议或事务 outbox/租约等等价方案；未完成时不得直接部署多个后端副本。
- 新前端兼容旧后端；旧前端不认识新后端的 `context-compression` 命名事件。因此数据库迁移完成后，发布顺序固定为：**先前端，后后端**。

任一阈值错序、模型窗口不足、启动校验失败、时钟同步不健康、Redis 脚本权限探针失败、部署副本数大于 1 或前端尚未完成协议升级时，不得部署新后端。

### 6.4 发布顺序与逐步验收

1. 完成三张表备份和非生产三项 migration 演练。
2. 在生产 MySQL 8.0.40 执行 migration，并立即核对字段、默认值、NULL 数、旧数据迁移数量和索引；不通过则停止，不部署应用。
3. 部署前端，验证旧后端下普通生成仍可用，未知事件门禁和既有唯一终态没有回归。
4. 部署后端；检查 `/api/actuator/health`、启动日志和 Prometheus 抓取。启动校验、数据库映射或缓存初始化出现异常时立即回滚后端包。
5. 只开放小流量，实际核对：48K 当前请求不被阻塞、56K 显示“正在压缩上下文，请稍候…”、COMPLETED 后恢复原文案、64K 触发未完成工具链检查点或类型化拒绝、控制事件不进入聊天正文。
6. 至少观察一个完整小流量窗口，再决定是否扩大流量。

### 6.5 观测与扩大/停止条件

必须同时观察：

- `memory_context_gate_total{mode,outcome}`：48K/56K/64K 门禁分布与失败原因。
- `memory_compression_total{mode,outcome}`、`memory_compression_duration_seconds{mode,outcome}`：真实压缩成功率、超时/模型失败和 P95。
- `memory_token_estimation_ratio{model_family}`：实际输入 Token / 估算 Token，判断是否低估。
- `memory_summary_tokens`、`memory_summary_reduce_rounds`：摘要是否稳定收敛到 3K。
- `memory_l2_debounce_total`、`memory_l2_candidate_total`、`memory_l2_recall_tokens`：30 秒防抖、证据状态和 1K 召回。
- 日志中不得出现用户正文、摘要正文、工具参数原文、原始模型输出或高基数业务 ID 的指标标签。

默认保守扩大条件：连续 30 分钟没有数据丢失、游标误推进、删除后复活或 SSE 协议错误；阻塞压缩 P95 小于 55 秒；超时占阻塞压缩尝试低于 1%；`memory_token_estimation_ratio` 的 P99 不大于 1。项目已有更严格 SLO 时，以更严格值为准。

出现以下任一情况立即停止扩大并回滚应用版本：任何数据/游标/缓存正确性异常；压缩 P95 达到或超过 60 秒；估算比值持续大于 1；64K 拒绝率突增且无法由真实超长输入解释；前端把控制事件写入正文或产生 `protocol_error`；监控或日志泄露敏感正文。数据库结构默认保留，不能把应用回滚升级为自动删列。

## 7. 非破坏性回滚

### 7.1 旧后端直接回滚兼容性门禁

不能只凭新增列有默认值就认定旧包可直接回滚。V3 之前的后端召回 L2 时不识别 `status`，会把 `CANDIDATE` 当成正式偏好；旧 L1/L2 还会把 `failCount >= 3` 当作永久熔断，并忽略数据库中的 `nextRetryTime`。回滚前必须先执行以下只读审计：

```sql
SELECT COUNT(*) AS candidateRows
FROM app_memory
WHERE isDelete = 0
  AND status <> 'ACTIVE';

SELECT COUNT(*) AS l1LegacyRetryBlockedRows
FROM app_memory_summary
WHERE isDelete = 0
  AND (failCount >= 3 OR nextRetryTime > NOW());

SELECT COUNT(*) AS l2LegacyRetryBlockedRows
FROM app_memory_extract_cursor
WHERE isDelete = 0
  AND (failCount >= 3 OR nextRetryTime > NOW());
```

只有三个结果都为 `0` 时，才允许直接启动未经兼容修订的 V3 前旧后端。任一结果非零时立即停止直接回滚，且不得通过提升候选为 `ACTIVE`、软删除候选、清空证据或擅自重置退避元数据来“做绿”检查。

此时必须使用经过验证的**兼容回滚包**。该包以旧业务版本为基线，但至少需要满足：L2 召回只读取 `status='ACTIVE'`；回滚期间禁用旧 L2 抽取器，避免它覆盖 V3 证据状态；L1 不把 `failCount >= 3` 解释为永久停更，并尊重或保守延后数据库 `nextRetryTime`。没有兼容回滚包时，以上任一阻断结果都表示本次回滚不可继续。

### 7.2 回滚执行顺序

1. 停止新生成流，等待正在执行的门禁、压缩和 L2 抽取任务静默；确认相关 counter 不再增长且日志没有进行中的 owner。
2. 执行 7.1 的只读兼容性审计，记录查询结果和将要使用的回滚包 SHA；不满足直接回滚条件且没有兼容包时停止。
3. 在旧后端启动前做**定向缓存失效**：按受影响 app 通过应用相同 Redis 配置调用 `AtomicChatMemoryStore.deleteMessages(appId)`，使旧版从 MySQL 冷重建；删除对应 `mem:summary:{appId}`，并按 user 同时删除 `mem:pref:{userId}` 与 `mem:pref:v2:{userId}`。仓库当前没有可直接运行的批量运维命令，因此发布前必须准备一份受审计的一次性维护任务，并在非生产环境验证 ID 清单、执行结果和重复执行安全性；没有准备并验证该任务时，本次回滚必须停止。禁止现场猜测 L0 底层 Redis 键、直接操作不明确的序列化值、使用 `FLUSHDB` 或清理无关业务缓存。
4. 先回滚后端应用包，再按兼容性需要回滚前端；如果旧后端仍在线，新前端可以继续兼容。
5. 回滚后执行一条旧版本兼容写入和读取检查：旧实体不提供新字段时，`status/evidenceType/evidenceCount` 必须落为 `ACTIVE/EXPLICIT/1`，两个 `nextRetryTime` 保持可空；同时确认 L2 实际召回不包含 `CANDIDATE`。
6. 核对三张记忆表和 `chat_history` 行数没有减少，L1 摘要、L2 证据和游标仍在；旧版 L0 从 MySQL 重建成功后再恢复小流量。
7. 默认不恢复整库备份，因为这会覆盖 migration 后产生的新业务数据。只有确认 migration 造成数据损坏、完成影响审计并获得单独授权后，才按数据库恢复流程处理。

### 7.3 数据保留与破坏性操作边界

应用回滚默认保留新增数据库列、L1 摘要、L2 候选与活跃证据、游标和原始 `chat_history`。缓存失效只是让不同版本按各自协议重新回源，不删除 MySQL 事实数据。

破坏性 `DROP COLUMN` 不是常规回滚步骤。确需删列时，必须先停止所有新版本流量、导出六个新增列的内容、验证导出可恢复，再由人工逐条执行 migration 末尾注释中的明确列名 SQL；禁止把删列命令接入正向 migration、自动部署或普通应用回滚脚本。
