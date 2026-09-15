# 第二版现有服务器升级

适用目录：`/root/ai_gen_app/prod`。本次发布标识为 `v2-20260914-01`。
运行时复用服务器已有 Java 25、Node 22 和浏览器环境，重新封装第二版 JAR、前端和 Nginx
配置。Milvus、etcd、MinIO 使用 Compose 中固定的版本。不会直接复用旧 pgvector 向量。
MinIO 从官方 `quay.io/minio/minio` 获取同版本镜像；旧 Docker Hub 地址在本次部署中
返回认证错误。镜像传输前后使用 SHA-256 校验，服务器仅加载 AMD64 架构镜像。

## 启动配置

配置已安全合并，所有 Compose 操作只使用主配置：

```bash
cd /root/ai_gen_app/prod
docker compose --env-file .env -f docker-compose.yml ps
docker compose --env-file .env -f docker-compose.yml logs --tail 100 backend
```

`.env` 中保存 `RELEASE_ID`、`BACKEND_RUNTIME_IMAGE`、`NGINX_RUNTIME_IMAGE`。
第一版 `latest` 标签仍为回滚保留；主 Compose 必须指定 `RELEASE_ID`，不会选择该标签。
统一后端 Dockerfile 将独立 RAG 工具复制到 `/app/deployment-tools`。
现有服务器继续保留 `BACKEND_RUNTIME_IMAGE` 和 `NGINX_RUNTIME_IMAGE` 的原值；
以后仅更新发布标识与产物，先构建和验收再启动。全新环境的运行时变量可以留空，
从头安装依赖，详见 [README.md](README.md)。

第二版升级已完成环境配置转换：保留原有 MySQL、Redis、Grafana 和 API 凭据，
为新 Milvus/MinIO 使用独立密码。后续部署继续复用服务器已有 `.env`，
不要重新生成或用示例覆盖。凭据文件只保存在服务器受限目录中，不提交、不打印。

## 数据库与备份

先停前后端，保存 MySQL、pgvector 逻辑备份及账号、应用、聊天正文校验基线；停止容器后
另备份 MySQL、Redis、pgvector、应用部署及临时文件卷。临时文件中的 `node_modules`
不纳入备份，可重新安装。

迁移脚本统一保存在源码仓库根目录 `sql/migrations/`，默认发布包不携带。
首次升级需要按顺序用 `package-release.py --migration 文件名` 选择下列脚本，
确认发布包包含所需 SQL 后再执行。已完成升级的服务器无需再次执行。
线上旧库不能执行整个 `sql/schema.sql`。首次升级顺序：

1. `2026-08-14-memory-baseline.sql`：补齐旧库没有的三张记忆表。
2. `2026-08-15-token-layered-memory-v3.sql`：分层记忆字段及索引。
3. `2026-08-18-chat-history-memory-projection.sql`：历史记忆投影。
4. `2026-09-10-user-display-identity.sql`：匿名昵称和统一头像。

迁移后比较用户账号、密码、角色、应用数据及聊天正文与基线；只通过 SCAN/UNLINK
删除 `good_app_page::*` 缓存。禁止 FLUSHDB 和 FLUSHALL。

## 模板导入与验收

先启动 MySQL、Redis、Milvus、etcd、MinIO，等待健康检查通过，再运行：

```bash
docker compose --env-file .env -f docker-compose.yml \
  run --rm --no-deps --entrypoint bash backend /app/deployment-tools/run-rag-tool.sh ingest
```

此命令不启动 Web 服务，使用挂载的 `embed_text` 全量重新 Embedding，会产生 Embedding
费用。35 份模板按生产解析规则形成 HTML 9 条、多文件 8 条、Vue 23 条，共 40 条记录。
稳定 ID 支持重复写入而不产生重复行，但重复导入仍产生 Embedding 费用。

`ingest` 完成后自动验证数量、ID、metadata、catalogVersion、索引文本、1024 维向量、
稠密自检和 Vue 骨架/功能 BM25 召回；成功输出 `RAG_DEPLOYMENT_VERIFIED`。
只重做验证、不调用 Embedding 时使用：

```bash
docker compose --env-file .env -f docker-compose.yml \
  run --rm --no-deps --entrypoint bash backend /app/deployment-tools/run-rag-tool.sh verify
```

正式后端保持 `RAG_INGEST_ENABLED=false`，避免每次重启再次导入。
验收后启动前后端和监控，检查首页、头像、精选列表、旧部署站点及应用日志。
健康接口成功不能代替模板数据和检索验证。无真实生成请求时，不宣称已通过生成端到端验收。

## 回滚与清理

本次旧配置和文件保存在：
`/root/ai_gen_app/.codex/deployments/v2-20260914-01/previous-prod`。
停机最终备份位于同级 `final-backup/`；早期完整镜像备份位于
`/root/ai_gen_app/.codex/backups/v1-20260914T110846Z-NAv21C`。
备份保留至少至 2026-10-14，不设置自动删除任务。

回滚时先停新版前后端并保存当前数据，再恢复旧 prod 配置与旧镜像。已删除 pgvector
卷时需按原卷名重建并还原备份，不能空库启动第一版。恢复 MySQL/Redis 等备份会丢失
备份后的写入，必须先核对停机期间是否已有新数据；不能直接覆盖当前数据卷。

pgvector 清理仅针对经 Compose 标签和挂载核对的 `ai-pg` 及
`ai-app-generation-prod_pg_data`，要求备份校验、Milvus 数据和检索及新版访问检查通过。
禁止 `docker compose down -v` 或全局 prune，不删除其他项目数据。
