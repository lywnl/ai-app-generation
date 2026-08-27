#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="$PROJECT_ROOT/dev/.env"
COMPOSE_FILE="$PROJECT_ROOT/dev/docker-compose.local.yml"
BACKUP_ROOT="$PROJECT_ROOT/.codex/backups/local-compose-migration"

MYSQL_CONTAINER="ai-codegen-e2e-mysql"
REDIS_CONTAINER="ai-codegen-rag-eval-redis"
ETCD_CONTAINER="ai-codegen-milvus-etcd"
MINIO_CONTAINER="ai-codegen-milvus-minio"
MILVUS_CONTAINER="ai-codegen-milvus"
NGINX_CONTAINER="ai-app-generation-dev-nginx"

MYSQL_VOLUME="0f82a6a43eaafadbe6a0807976a0dd3f2a59b21e2d0b8b4e73494ec08dd4fd1d"
REDIS_VOLUME="b5fc6675bb3ba5bc0e5c60577a1245c34d1e891807e8e3d91a9f5ca75e9bf819"
ETCD_VOLUME="ai-codegen-rag_milvus_etcd_data"
MINIO_VOLUME="ai-codegen-rag_milvus_minio_data"
MINIO_LEGACY_VOLUME="5dc73cbb79051470a6060a90f4a608f85290405910f32722430cc715399d96b5"
MILVUS_VOLUME="ai-codegen-rag_milvus_data"

BACKEND_PORT=9025
FRONTEND_PORT=5173
MYSQL_PORT=3406
REDIS_PORT=6379
MILVUS_PORT=19530
MILVUS_HEALTH_PORT=9091
NGINX_PORT=80

MODE=""
RUN_ID="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$BACKUP_ROOT/$RUN_ID"
RENAMED_CONTAINERS=()
STOPPED_OLD_CONTAINERS=()
NEW_CONTAINERS_CREATED=false
MIGRATION_COMMITTED=false

log() {
  printf '[本地 Compose 迁移] %s\n' "$*"
}

fail() {
  printf '[本地 Compose 迁移][失败] %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令：$1"
}

usage() {
  cat <<'EOF'
用法：
  bash scripts/migrate-local-compose.sh --dry-run
  bash scripts/migrate-local-compose.sh --confirm

--dry-run 只检查配置、容器、卷和端口，不改变任何状态。
--confirm 备份数据后迁移容器；仅此模式会停止、重命名和删除容器。
EOF
}

parse_args() {
  [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
  case "$1" in
    --dry-run|--confirm) MODE="$1" ;;
    *) usage >&2; exit 2 ;;
  esac
}

load_environment() {
  [[ -f "$ENV_FILE" ]] || fail "缺少环境文件：$ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  [[ -n "${INFRA_SHARED_PASSWORD:-}" ]] || fail "dev/.env 未配置 INFRA_SHARED_PASSWORD"
  [[ -n "${MILVUS_MINIO_PASSWORD:-}" ]] || fail "dev/.env 未配置 MILVUS_MINIO_PASSWORD"
}

validate_compose() {
  [[ -f "$COMPOSE_FILE" ]] || fail "缺少统一 Compose 文件：$COMPOSE_FILE"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet \
    || fail "统一 Compose 配置校验失败：$COMPOSE_FILE"
}

container_exists() {
  docker container inspect "$1" >/dev/null 2>&1
}

container_running() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" == "true" ]]
}

volume_exists() {
  docker volume inspect "$1" >/dev/null 2>&1
}

validate_resources() {
  local container volume
  for container in \
    "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" \
    "$MINIO_CONTAINER" "$MILVUS_CONTAINER" "$NGINX_CONTAINER"; do
    container_exists "$container" || fail "缺少待迁移容器：$container"
  done
  for volume in \
    "$MYSQL_VOLUME" "$REDIS_VOLUME" "$ETCD_VOLUME" "$MINIO_VOLUME" \
    "$MINIO_LEGACY_VOLUME" "$MILVUS_VOLUME"; do
    volume_exists "$volume" || fail "缺少待复用数据卷：$volume"
  done
}

validate_target_names() {
  local target
  for target in \
    "$MYSQL_CONTAINER.migration-backup.$RUN_ID" \
    "$REDIS_CONTAINER.migration-backup.$RUN_ID" \
    "$ETCD_CONTAINER.migration-backup.$RUN_ID" \
    "$MINIO_CONTAINER.migration-backup.$RUN_ID" \
    "$MILVUS_CONTAINER.migration-backup.$RUN_ID" \
    "$NGINX_CONTAINER.migration-backup.$RUN_ID"; do
    container_exists "$target" && fail "迁移备份容器名称已存在：$target"
  done
  return 0
}

validate_ports() {
  local port
  for port in "$MYSQL_PORT" "$REDIS_PORT" "$MILVUS_PORT" "$MILVUS_HEALTH_PORT" "$NGINX_PORT"; do
    if lsof -nP -i4TCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      log "端口 $port 当前由 IPv4 监听"
    else
      log "端口 $port 当前未被 IPv4 监听，迁移将继续并在重建后验证"
    fi
  done
}

save_metadata() {
  mkdir -m 700 -p "$BACKUP_DIR/containers" "$BACKUP_DIR/compose" "$BACKUP_DIR/redis" "$BACKUP_DIR/mysql" "$BACKUP_DIR/volumes"
  chmod 700 "$BACKUP_DIR"
  cp "$PROJECT_ROOT/dev/docker-compose.middleware.yml" "$BACKUP_DIR/compose/" 2>/dev/null || true
  cp "$PROJECT_ROOT/dev/docker-compose.yml" "$BACKUP_DIR/compose/" 2>/dev/null || true
  cp "$PROJECT_ROOT/docker/milvus.yml" "$BACKUP_DIR/compose/" 2>/dev/null || true
  local container volume
  for container in \
    "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" \
    "$MINIO_CONTAINER" "$MILVUS_CONTAINER" "$NGINX_CONTAINER"; do
    {
      printf '容器名称=%s\n' "$container"
      docker inspect -f '镜像={{.Config.Image}}\n启动命令={{json .Config.Cmd}}\n挂载={{json .Mounts}}\n端口={{json .HostConfig.PortBindings}}\n重启策略={{json .HostConfig.RestartPolicy}}' "$container"
    } >"$BACKUP_DIR/containers/$container.txt"
  done
  for volume in \
    "$MYSQL_VOLUME" "$REDIS_VOLUME" "$ETCD_VOLUME" "$MINIO_VOLUME" \
    "$MINIO_LEGACY_VOLUME" "$MILVUS_VOLUME"; do
    docker volume inspect "$volume" >"$BACKUP_DIR/volumes/$volume.inspect.json"
  done
}

restore_legacy_compose_files() {
  local source target
  for source in \
    "$BACKUP_DIR/compose/docker-compose.middleware.yml" \
    "$BACKUP_DIR/compose/docker-compose.yml" \
    "$BACKUP_DIR/compose/milvus.yml"; do
    [[ -f "$source" ]] || continue
    case "$(basename "$source")" in
      docker-compose.middleware.yml) target="$PROJECT_ROOT/dev/docker-compose.middleware.yml" ;;
      docker-compose.yml) target="$PROJECT_ROOT/dev/docker-compose.yml" ;;
      milvus.yml) target="$PROJECT_ROOT/docker/milvus.yml" ;;
      *) continue ;;
    esac
    mkdir -p "$(dirname "$target")"
    cp "$source" "$target"
  done
}

backup_mysql() {
  log "导出 MySQL 数据库"
  container_running "$MYSQL_CONTAINER" || fail "MySQL 容器未运行，无法执行一致性 SQL 备份"
  docker exec -e MYSQL_PWD="$INFRA_SHARED_PASSWORD" "$MYSQL_CONTAINER" \
    mysqldump -uroot --all-databases --routines --events --triggers --single-transaction \
    >"$BACKUP_DIR/mysql/all-databases.sql"
}

backup_redis() {
  log "导出 Redis RDB"
  container_running "$REDIS_CONTAINER" || fail "Redis 容器未运行，无法执行 RDB 备份"
  local dump_path="/tmp/local-compose-migration-$RUN_ID.rdb"
  docker exec -e REDISCLI_AUTH="$INFRA_SHARED_PASSWORD" "$REDIS_CONTAINER" \
    redis-cli --no-auth-warning --rdb "$dump_path" >/dev/null
  docker cp "$REDIS_CONTAINER:$dump_path" "$BACKUP_DIR/redis/dump.rdb"
  docker exec "$REDIS_CONTAINER" rm -f "$dump_path" >/dev/null
}

validate_live_credentials() {
  log "校验现有 MySQL 和 Redis 凭据"
  docker exec -e MYSQL_PWD="$INFRA_SHARED_PASSWORD" "$MYSQL_CONTAINER" \
    mysqladmin ping -uroot --silent >/dev/null \
    || fail "现有 MySQL 无法使用 dev/.env 中的 INFRA_SHARED_PASSWORD 认证"
  docker exec -e REDISCLI_AUTH="$INFRA_SHARED_PASSWORD" "$REDIS_CONTAINER" \
    redis-cli --no-auth-warning ping | grep -Fxq PONG \
    || fail "现有 Redis 无法使用 dev/.env 中的 INFRA_SHARED_PASSWORD 认证"
}

archive_volume() {
  local volume="$1"
  local output="$BACKUP_DIR/volumes/$volume.tar.gz"
  log "归档数据卷：$volume"
  docker run --rm \
    --mount "type=volume,source=$volume,target=/volume,readonly" \
    --mount "type=bind,source=$BACKUP_DIR/volumes,target=/backup" \
    --entrypoint tar mysql:8.0.40 \
    -C /volume -czf "/backup/$(basename "$output")" .
}

stop_application_processes() {
  local session
  for session in ai-app-generation-backend ai-app-generation-frontend; do
    if screen -ls 2>/dev/null | grep -Fq ".${session}"; then
      log "停止应用 screen 会话：$session"
      screen -S "$session" -X quit
    fi
  done
  local port pid command cwd
  for port in "$BACKEND_PORT" "$FRONTEND_PORT"; do
    while read -r pid; do
      [[ -n "$pid" ]] || continue
      command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
      cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)"
      if [[ "$command" != *"$PROJECT_ROOT"* && "$cwd" != "$PROJECT_ROOT" && "$cwd" != "$PROJECT_ROOT"/* ]]; then
        fail "端口 $port 被非当前项目进程占用，拒绝终止：$pid"
      fi
      log "停止项目应用进程：$pid"
      kill -TERM "$pid" 2>/dev/null || true
    done < <(lsof -t -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u)
  done
}

stop_old_containers() {
  local container
  for container in "$NGINX_CONTAINER" "$MILVUS_CONTAINER" "$MINIO_CONTAINER" "$ETCD_CONTAINER" "$REDIS_CONTAINER" "$MYSQL_CONTAINER"; do
    if container_running "$container"; then
      log "停止旧容器：$container"
      docker stop "$container" >/dev/null
      STOPPED_OLD_CONTAINERS+=("$container")
    fi
  done
}

rename_old_containers() {
  local container target
  for container in \
    "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" \
    "$MINIO_CONTAINER" "$MILVUS_CONTAINER" "$NGINX_CONTAINER"; do
    target="$container.migration-backup.$RUN_ID"
    docker rename "$container" "$target"
    RENAMED_CONTAINERS+=("$target|$container")
  done
}

restore_old_containers() {
  local item backup original
  for item in "${RENAMED_CONTAINERS[@]}"; do
    backup="${item%%|*}"
    original="${item##*|}"
    if container_exists "$original"; then
      docker rm "$original" >/dev/null || true
    fi
    if container_exists "$backup"; then
      docker rename "$backup" "$original" >/dev/null || true
      docker start "$original" >/dev/null || true
    fi
  done
}

restart_unrenamed_old_containers() {
  local container
  for container in "${STOPPED_OLD_CONTAINERS[@]}"; do
    if container_exists "$container" && ! container_running "$container"; then
      log "重新启动未改名的旧容器：$container"
      docker start "$container" >/dev/null || true
    fi
  done
}

remove_new_containers() {
  local container
  for container in \
    "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" \
    "$MINIO_CONTAINER" "$MILVUS_CONTAINER" "$NGINX_CONTAINER"; do
    if container_exists "$container" \
      && [[ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$container" 2>/dev/null || true)" == "ai-app-generation-local" ]]; then
      docker rm -f "$container" >/dev/null || true
    fi
  done
}

rollback_on_error() {
  local exit_code=$?
  if ((exit_code != 0)) && [[ "$MODE" == "--confirm" ]] && [[ "$MIGRATION_COMMITTED" == false ]]; then
    log "迁移失败，开始回滚；数据卷不会删除"
    if [[ "$NEW_CONTAINERS_CREATED" == true ]]; then
      remove_new_containers
    fi
    restore_old_containers
    restart_unrenamed_old_containers
    restore_legacy_compose_files
    log "回滚完成；备份保留在：$BACKUP_DIR"
  fi
  exit "$exit_code"
}

wait_for_container_health() {
  local container="$1"
  local attempts=0
  while ((attempts < 60)); do
    if [[ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || true)" == "healthy" ]]; then
      return 0
    fi
    sleep 1
    ((attempts += 1))
  done
  fail "容器健康检查超时：$container"
}

wait_for_ipv4_port() {
  local port="$1"
  local attempts=0
  while ((attempts < 60)); do
    if lsof -nP -i4TCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    ((attempts += 1))
  done
  fail "端口未通过 IPv4 监听检查：$port"
}

verify_new_stack() {
  local container volume_name
  for container in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" "$MINIO_CONTAINER" "$MILVUS_CONTAINER" "$NGINX_CONTAINER"; do
    container_exists "$container" || fail "新 Compose 未创建容器：$container"
    container_running "$container" || fail "新 Compose 容器未运行：$container"
    [[ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$container" 2>/dev/null || true)" == "ai-app-generation-local" ]] \
      || fail "容器未由统一 Compose 项目管理：$container"
  done
  for container in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" "$MINIO_CONTAINER" "$MILVUS_CONTAINER" "$NGINX_CONTAINER"; do
    wait_for_container_health "$container"
  done
  wait_for_ipv4_port "$MYSQL_PORT"
  wait_for_ipv4_port "$REDIS_PORT"
  wait_for_ipv4_port "$MILVUS_PORT"
  wait_for_ipv4_port "$MILVUS_HEALTH_PORT"
  wait_for_ipv4_port "$NGINX_PORT"
  curl -4 -fsS --max-time 5 "http://127.0.0.1:$MILVUS_HEALTH_PORT/healthz" >/dev/null \
    || fail "Milvus 健康接口验证失败"
  docker exec "$NGINX_CONTAINER" nginx -t >/dev/null 2>&1 \
    || fail "Nginx 配置验证失败"
  for container in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" "$MINIO_CONTAINER" "$MILVUS_CONTAINER"; do
    case "$container" in
      "$MYSQL_CONTAINER") volume_name="$MYSQL_VOLUME" ;;
      "$REDIS_CONTAINER") volume_name="$REDIS_VOLUME" ;;
      "$ETCD_CONTAINER") volume_name="$ETCD_VOLUME" ;;
      "$MINIO_CONTAINER") volume_name="$MINIO_VOLUME" ;;
      "$MILVUS_CONTAINER") volume_name="$MILVUS_VOLUME" ;;
    esac
    docker inspect -f '{{range .Mounts}}{{println .Name}}{{end}}' "$container" \
      | grep -Fxq "$volume_name" \
      || fail "容器未挂载预期数据卷：$container -> $volume_name"
  done
}

remove_backup_containers() {
  local item backup
  for item in "${RENAMED_CONTAINERS[@]}"; do
    backup="${item%%|*}"
    docker rm "$backup" >/dev/null \
      || fail "旧迁移备份容器删除失败：$backup"
  done
}

remove_legacy_compose_files() {
  local file
  for file in \
    "$PROJECT_ROOT/dev/docker-compose.middleware.yml" \
    "$PROJECT_ROOT/dev/docker-compose.yml" \
    "$PROJECT_ROOT/docker/milvus.yml"; do
    [[ -e "$file" ]] || continue
    if ! rm -f "$file"; then
      log "警告：旧 Compose 文件删除失败，请手动删除：$file"
    fi
  done
}

dry_run() {
  validate_compose
  validate_resources
  validate_target_names
  validate_ports
  log "dry-run 检查通过，未执行任何迁移操作"
}

confirm_migration() {
  validate_compose
  validate_resources
  validate_target_names
  validate_live_credentials
  mkdir -p "$BACKUP_DIR"
  save_metadata
  backup_mysql
  backup_redis
  stop_application_processes
  stop_old_containers
  for volume in "$MYSQL_VOLUME" "$REDIS_VOLUME" "$ETCD_VOLUME" "$MINIO_VOLUME" "$MINIO_LEGACY_VOLUME" "$MILVUS_VOLUME"; do
    archive_volume "$volume"
  done
  rename_old_containers
  log "使用统一 Compose 创建新容器"
  NEW_CONTAINERS_CREATED=true
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d
  verify_new_stack
  remove_backup_containers
  remove_legacy_compose_files
  MIGRATION_COMMITTED=true
  NEW_CONTAINERS_CREATED=false
  log "迁移成功，旧容器已删除，数据卷已保留"
  log "备份保留在：$BACKUP_DIR"
  log "如需启动前后端，请执行：$PROJECT_ROOT/scripts/start-local.sh"
}

main() {
  parse_args "$@"
  require_command docker
  require_command curl
  require_command lsof
  require_command screen
  docker info >/dev/null 2>&1 || fail "Docker Desktop 未运行或当前用户无权访问 Docker"
  load_environment
  if [[ "$MODE" == "--dry-run" ]]; then
    dry_run
  else
    trap rollback_on_error EXIT
    confirm_migration
    trap - EXIT
  fi
}

main "$@"
