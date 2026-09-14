#!/usr/bin/env bash
set -Eeuo pipefail

# 远程迁移阶段使用；本脚本不会自动删除旧 pgvector 或数据卷。
PROD_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-$PROD_DIR/.env}"
BACKUP_DIR="${RAG_MIGRATION_BACKUP_DIR:-/var/backups/ai-app-generation}"
PG_CONTAINER="${SOURCE_PG_CONTAINER:-ai-pg}"
MODE="${1:-preflight}"
mkdir -p "$BACKUP_DIR"

need() { [[ -n "${!1:-}" ]] || { echo "缺少环境变量: $1" >&2; exit 2; }; }
compose() { docker compose --env-file "$ENV_FILE" -f "$PROD_DIR/docker-compose.yml" "$@"; }

case "$MODE" in
  preflight)
    need INFRA_SHARED_PASSWORD
    docker inspect "$PG_CONTAINER" >/dev/null
    docker inspect "$PG_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}'
    docker compose --env-file "$ENV_FILE" -f "$PROD_DIR/docker-compose.yml" config >/dev/null
    free -m; df -h "$BACKUP_DIR"
    ;;
  backup)
    stamp="$(date -u +%Y%m%d-%H%M%S)"
    mkdir -p "$BACKUP_DIR/$stamp"
    docker inspect "$PG_CONTAINER" > "$BACKUP_DIR/$stamp/pg-container-inspect.json"
    docker exec "$PG_CONTAINER" sh -c 'exec pg_dumpall -U "$POSTGRES_USER"' | gzip > "$BACKUP_DIR/$stamp/pgvector.sql.gz"
    sha256sum "$BACKUP_DIR/$stamp/pgvector.sql.gz" > "$BACKUP_DIR/$stamp/SHA256SUMS"
    echo "备份完成: $BACKUP_DIR/$stamp"
    ;;
  snapshot)
    stamp="$(date -u +%Y%m%d-%H%M%S)"
    mkdir -p "$BACKUP_DIR/$stamp"
    docker exec "$PG_CONTAINER" sh -c 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -c "SELECT '''templates_html''',count(*),min(vector_dims(embedding)),max(vector_dims(embedding)) FROM templates_html UNION ALL SELECT '''templates_multi''',count(*),min(vector_dims(embedding)),max(vector_dims(embedding)) FROM templates_multi UNION ALL SELECT '''templates_vue''',count(*),min(vector_dims(embedding)),max(vector_dims(embedding)) FROM templates_vue;"' > "$BACKUP_DIR/$stamp/pgvector-summary.txt"
    find "$PROD_DIR/embed_text" -type f -name '*.json' | sort > "$BACKUP_DIR/$stamp/local-template-files.txt"
    printf '旧库摘要和本地模板清单已保存至 %s\n' "$BACKUP_DIR/$stamp"
    ;;
  start-milvus)
    compose up -d milvus-etcd milvus-minio milvus
    compose ps milvus-etcd milvus-minio milvus
    ;;
  ingest)
    compose run --rm -e RAG_INGEST_ENABLED=true -e RAG_INGEST_TYPES=HTML,MULTI_FILE,VUE_PROJECT backend
    ;;
  verify)
    compose ps
    curl -fsS http://127.0.0.1:9025/api/actuator/health >/dev/null
    ;;
  cleanup)
    echo "仅在人工确认备份、Milvus 数据和检索验收通过后执行。"
    [[ "${CONFIRM_DELETE_PGVECTOR:-}" == "yes" ]] || { echo "设置 CONFIRM_DELETE_PGVECTOR=yes 后重试" >&2; exit 2; }
    pg_volume="$(docker inspect "$PG_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}')"
    [[ -n "$pg_volume" ]] || { echo "未找到 pgvector 数据卷，停止清理" >&2; exit 2; }
    docker rm -f "$PG_CONTAINER"
    docker volume rm "$pg_volume"
    ;;
  *) echo "用法: $0 {preflight|backup|snapshot|start-milvus|ingest|verify|cleanup}" >&2; exit 2 ;;
esac
