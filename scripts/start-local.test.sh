#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
COMPOSE_FILE="$PROJECT_ROOT/dev/docker-compose.local.yml"
START_SCRIPT="$PROJECT_ROOT/scripts/start-local.sh"
MIGRATION_SCRIPT="$PROJECT_ROOT/scripts/migrate-local-compose.sh"
STOP_SCRIPT="$PROJECT_ROOT/scripts/stop-local.sh"

fail() {
  printf '[启动脚本契约测试][失败] %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  rg -Fq -- "$expected" "$file" || fail "$file 缺少：$expected"
}

assert_not_contains() {
  local file="$1"
  local forbidden="$2"
  if rg -Fq -- "$forbidden" "$file"; then
    fail "$file 不应包含：$forbidden"
  fi
}

[[ -f "$COMPOSE_FILE" ]] || fail "缺少统一 Compose 文件：$COMPOSE_FILE"
[[ -f "$START_SCRIPT" ]] || fail "缺少启动脚本：$START_SCRIPT"
[[ -f "$MIGRATION_SCRIPT" ]] || fail "缺少迁移脚本：$MIGRATION_SCRIPT"
[[ -f "$STOP_SCRIPT" ]] || fail "缺少停止脚本：$STOP_SCRIPT"
bash -n "$START_SCRIPT" || fail "启动脚本语法错误"
bash -n "$MIGRATION_SCRIPT" || fail "迁移脚本语法错误"
bash -n "$STOP_SCRIPT" || fail "停止脚本语法错误"

assert_contains "$START_SCRIPT" 'LOCAL_COMPOSE="$PROJECT_ROOT/dev/docker-compose.local.yml"'
assert_contains "$START_SCRIPT" 'docker compose --env-file "$ENV_FILE" -f "$LOCAL_COMPOSE"'
assert_contains "$START_SCRIPT" 'curl -4 -fsS'
assert_not_contains "$START_SCRIPT" 'MIDDLEWARE_COMPOSE='
assert_not_contains "$START_SCRIPT" 'MILVUS_COMPOSE='
assert_not_contains "$START_SCRIPT" 'NGINX_COMPOSE='
assert_not_contains "$START_SCRIPT" 'docker compose -f "$NGINX_COMPOSE"'
assert_not_contains "$START_SCRIPT" 'down -v'
assert_not_contains "$START_SCRIPT" 'docker rm -v'
assert_not_contains "$START_SCRIPT" 'docker volume rm'
assert_not_contains "$START_SCRIPT" 'docker volume prune'

assert_contains "$MIGRATION_SCRIPT" '--dry-run'
assert_contains "$MIGRATION_SCRIPT" '--confirm'
assert_contains "$MIGRATION_SCRIPT" 'remove_backup_containers'
assert_contains "$MIGRATION_SCRIPT" 'docker rm "$backup" >/dev/null'
assert_contains "$MIGRATION_SCRIPT" 'restart_unrenamed_old_containers'
assert_contains "$MIGRATION_SCRIPT" 'MIGRATION_COMMITTED=false'
assert_not_contains "$MIGRATION_SCRIPT" 'docker compose down -v'
assert_not_contains "$MIGRATION_SCRIPT" 'docker rm -v'
assert_not_contains "$MIGRATION_SCRIPT" 'docker volume rm'
assert_not_contains "$MIGRATION_SCRIPT" 'docker volume prune'

assert_contains "$STOP_SCRIPT" 'docker compose --env-file "$ENV_FILE" -f "$LOCAL_COMPOSE" stop'
assert_contains "$STOP_SCRIPT" 'ai-app-generation-backend'
assert_contains "$STOP_SCRIPT" 'ai-app-generation-frontend'
assert_contains "$STOP_SCRIPT" '--dry-run'
assert_contains "$STOP_SCRIPT" '--confirm'
assert_not_contains "$STOP_SCRIPT" 'down -v'
assert_not_contains "$STOP_SCRIPT" 'docker rm -v'
assert_not_contains "$STOP_SCRIPT" 'docker volume rm'
assert_not_contains "$STOP_SCRIPT" 'docker volume prune'

for service in mysql redis etcd minio milvus nginx; do
  assert_contains "$COMPOSE_FILE" "  $service:"
done

for volume in \
  '0f82a6a43eaafadbe6a0807976a0dd3f2a59b21e2d0b8b4e73494ec08dd4fd1d' \
  'b5fc6675bb3ba5bc0e5c60577a1245c34d1e891807e8e3d91a9f5ca75e9bf819' \
  'ai-codegen-rag_milvus_etcd_data' \
  'ai-codegen-rag_milvus_minio_data' \
  '5dc73cbb79051470a6060a90f4a608f85290405910f32722430cc715399d96b5' \
  'ai-codegen-rag_milvus_data'; do
  assert_contains "$COMPOSE_FILE" "name: $volume"
done

for port in '127.0.0.1:3406:3306' '127.0.0.1:6379:6379' '127.0.0.1:19530:19530' '127.0.0.1:9091:9091' '127.0.0.1:80:80'; do
  assert_contains "$COMPOSE_FILE" "$port"
done

assert_contains "$COMPOSE_FILE" 'test: ["CMD", "nginx", "-t"]'
assert_contains "$MIGRATION_SCRIPT" 'docker exec "$NGINX_CONTAINER" nginx -t'

printf '[启动脚本契约测试] 通过\n'
