#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="$PROJECT_ROOT/dev/.env"
RUNTIME_DIR="$PROJECT_ROOT/logs/runtime"
LOCAL_COMPOSE="$PROJECT_ROOT/dev/docker-compose.local.yml"
FRONTEND_DIR="$PROJECT_ROOT/ai-app-generation-frontend"

BACKEND_PORT=9025
FRONTEND_PORT=5173
NGINX_PORT=80
MYSQL_PORT=3406
REDIS_PORT=6379
MILVUS_PORT=19530
MILVUS_HEALTH_PORT=9091

BACKEND_PID_FILE="$RUNTIME_DIR/backend.pid"
FRONTEND_PID_FILE="$RUNTIME_DIR/frontend.pid"
BACKEND_LOG="$RUNTIME_DIR/backend.log"
FRONTEND_LOG="$RUNTIME_DIR/frontend.log"
START_LOG="$RUNTIME_DIR/start-local.log"
BACKEND_SCREEN_SESSION="ai-app-generation-backend"
FRONTEND_SCREEN_SESSION="ai-app-generation-frontend"

mkdir -p "$RUNTIME_DIR"
touch "$START_LOG"
exec > >(tee -a "$START_LOG") 2>&1

log() {
  printf '[本地启动] %s\n' "$*"
}

fail() {
  printf '[本地启动][失败] %s\n' "$*" >&2
  exit 1
}

cleanup_on_error() {
  local exit_code=$?
  if ((exit_code != 0)); then
    printf '[本地启动][失败] 启动中断，后端日志：%s，前端日志：%s\n' "$BACKEND_LOG" "$FRONTEND_LOG" >&2
  fi
  exit "$exit_code"
}
trap cleanup_on_error EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令：$1"
}

load_environment() {
  [[ -f "$ENV_FILE" ]] || fail "缺少环境文件：$ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  [[ -n "${INFRA_SHARED_PASSWORD:-}" ]] || fail "dev/.env 未配置 INFRA_SHARED_PASSWORD"
}

ipv4_port_in_use() {
  lsof -nP -i4TCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

any_port_in_use() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

ipv4_http_ready() {
  local url="$1"
  curl -4 -fsS --max-time 3 "$url" >/dev/null 2>&1
}

wait_for_ipv4_http() {
  local url="$1"
  local label="$2"
  local attempts=0
  while ((attempts < 60)); do
    if ipv4_http_ready "$url"; then
      log "$label 已通过 IPv4 健康检查：$url"
      return 0
    fi
    sleep 1
    ((attempts += 1))
  done
  fail "$label 未能通过 IPv4 健康检查：$url"
}

wait_for_port() {
  local port="$1"
  local label="$2"
  local attempts=0
  while ((attempts < 60)); do
    if ipv4_port_in_use "$port"; then
      log "$label 已监听端口：$port"
      return 0
    fi
    sleep 1
    ((attempts += 1))
  done
  fail "$label 未能监听端口：$port"
}

wait_for_container_health() {
  local container="$1"
  local label="$2"
  local attempts=0
  while ((attempts < 60)); do
    if [[ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || true)" == "healthy" ]]; then
      log "$label 已通过 Docker 健康检查"
      return 0
    fi
    sleep 1
    ((attempts += 1))
  done
  fail "$label 未通过 Docker 健康检查：$container"
}

compose_up() {
  local service="$1"
  shift
  docker compose --env-file "$ENV_FILE" -f "$LOCAL_COMPOSE" up -d "$service" "$@"
}

compose_up_without_dependencies() {
  local service="$1"
  docker compose --env-file "$ENV_FILE" -f "$LOCAL_COMPOSE" up -d --no-deps "$service"
}

validate_compose() {
  [[ -f "$LOCAL_COMPOSE" ]] || fail "缺少统一 Compose 文件：$LOCAL_COMPOSE"
  docker compose --env-file "$ENV_FILE" -f "$LOCAL_COMPOSE" config --quiet \
    || fail "统一 Compose 配置校验失败：$LOCAL_COMPOSE"
  local volume
  for volume in \
    "0f82a6a43eaafadbe6a0807976a0dd3f2a59b21e2d0b8b4e73494ec08dd4fd1d" \
    "b5fc6675bb3ba5bc0e5c60577a1245c34d1e891807e8e3d91a9f5ca75e9bf819" \
    "ai-codegen-rag_milvus_etcd_data" \
    "ai-codegen-rag_milvus_minio_data" \
    "5dc73cbb79051470a6060a90f4a608f85290405910f32722430cc715399d96b5" \
    "ai-codegen-rag_milvus_data"; do
    docker volume inspect "$volume" >/dev/null 2>&1 \
      || fail "统一 Compose 所需外部数据卷不存在：$volume"
  done
}

container_exists() {
  docker container inspect "$1" >/dev/null 2>&1
}

container_managed_by_local_compose() {
  [[ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$1" 2>/dev/null || true)" == "ai-app-generation-local" ]]
}

start_existing_containers() {
  local label="$1"
  shift
  log "$label 端口未监听，启动已经存在的 Docker 容器"
  docker start "$@" >/dev/null
}

ensure_mysql() {
  if any_port_in_use "$MYSQL_PORT"; then
    ipv4_port_in_use "$MYSQL_PORT" || fail "MySQL 端口 $MYSQL_PORT 仅被 IPv6 监听，拒绝跳过启动；请先修复 IPv4 监听"
    log "MySQL 的 IPv4 端口 $MYSQL_PORT 已存在，跳过重启"
    return 0
  fi
  if container_exists "ai-codegen-e2e-mysql"; then
    start_existing_containers "MySQL" "ai-codegen-e2e-mysql"
    wait_for_port "$MYSQL_PORT" "MySQL"
    return 0
  fi
  log "MySQL 未监听 $MYSQL_PORT，启动本地 Docker 容器"
  compose_up mysql
  wait_for_port "$MYSQL_PORT" "MySQL"
}

ensure_redis() {
  if any_port_in_use "$REDIS_PORT"; then
    ipv4_port_in_use "$REDIS_PORT" || fail "Redis 端口 $REDIS_PORT 仅被 IPv6 监听，拒绝跳过启动；请先修复 IPv4 监听"
    log "Redis 的 IPv4 端口 $REDIS_PORT 已存在，跳过重启"
    return 0
  fi
  if container_exists "ai-codegen-rag-eval-redis"; then
    start_existing_containers "Redis" "ai-codegen-rag-eval-redis"
    wait_for_port "$REDIS_PORT" "Redis"
    return 0
  fi
  log "Redis 未监听 $REDIS_PORT，启动本地 Docker 容器"
  compose_up redis
  wait_for_port "$REDIS_PORT" "Redis"
}

ensure_milvus() {
  local grpc_ready=false
  local health_ready=false
  any_port_in_use "$MILVUS_PORT" && grpc_ready=true
  any_port_in_use "$MILVUS_HEALTH_PORT" && health_ready=true

  if [[ "$grpc_ready" == true && "$health_ready" == true ]]; then
    ipv4_port_in_use "$MILVUS_PORT" || fail "Milvus gRPC 端口 $MILVUS_PORT 仅被 IPv6 监听，拒绝跳过启动；请先修复 IPv4 监听"
    ipv4_port_in_use "$MILVUS_HEALTH_PORT" || fail "Milvus 健康端口 $MILVUS_HEALTH_PORT 仅被 IPv6 监听，拒绝跳过启动；请先修复 IPv4 监听"
    log "Milvus 的 IPv4 端口 $MILVUS_PORT 和健康端口 $MILVUS_HEALTH_PORT 均已存在，跳过重启"
    return 0
  fi
  if [[ "$grpc_ready" == true || "$health_ready" == true ]]; then
    fail "Milvus 端口状态不完整：$MILVUS_PORT=$grpc_ready，$MILVUS_HEALTH_PORT=$health_ready；为避免误覆盖，未启动或重启容器"
  fi

  [[ -n "${MILVUS_MINIO_PASSWORD:-}" ]] || fail "Milvus 未运行，且 dev/.env 未配置 MILVUS_MINIO_PASSWORD；未启动或重启任何 Milvus 容器"

  local container
  for container in ai-codegen-milvus-etcd ai-codegen-milvus-minio ai-codegen-milvus; do
    if container_exists "$container" && ! container_managed_by_local_compose "$container"; then
      fail "Milvus 容器 $container 仍属于旧 Compose 项目；请先完成统一本地 Compose 迁移"
    fi
  done

  log "Milvus 未运行，按依赖顺序启动 etcd、MinIO 和 Milvus"
  if container_exists "ai-codegen-milvus-etcd"; then
    start_existing_containers "etcd" "ai-codegen-milvus-etcd"
  else
    compose_up etcd
  fi
  wait_for_container_health "ai-codegen-milvus-etcd" "etcd"

  if container_exists "ai-codegen-milvus-minio"; then
    start_existing_containers "MinIO" "ai-codegen-milvus-minio"
  else
    compose_up minio
  fi
  wait_for_container_health "ai-codegen-milvus-minio" "MinIO"

  if container_exists "ai-codegen-milvus"; then
    start_existing_containers "Milvus" "ai-codegen-milvus"
  else
    compose_up_without_dependencies milvus
  fi
  wait_for_port "$MILVUS_PORT" "Milvus gRPC"
  wait_for_ipv4_http "http://127.0.0.1:$MILVUS_HEALTH_PORT/healthz" "Milvus"
}

ensure_nginx() {
  if any_port_in_use "$NGINX_PORT"; then
    ipv4_port_in_use "$NGINX_PORT" || fail "Nginx 端口 $NGINX_PORT 仅被 IPv6 监听，拒绝跳过启动；请先修复 IPv4 监听"
    log "Nginx 的 IPv4 端口 $NGINX_PORT 已存在，跳过重启"
    wait_for_ipv4_http "http://127.0.0.1:$NGINX_PORT/" "Nginx"
    return 0
  fi
  if container_exists "ai-app-generation-dev-nginx"; then
    start_existing_containers "Nginx" "ai-app-generation-dev-nginx"
    wait_for_ipv4_http "http://127.0.0.1:$NGINX_PORT/" "Nginx"
    return 0
  fi
  log "Nginx 未监听 $NGINX_PORT，启动开发 Docker 容器"
  compose_up nginx
  wait_for_ipv4_http "http://127.0.0.1:$NGINX_PORT/" "Nginx"
}

process_command() {
  ps -p "$1" -o command= 2>/dev/null || true
}

process_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

screen_session_exists() {
  screen -ls 2>/dev/null | grep -Fq ".${1}"
}

stop_screen_session() {
  local session_name="$1"
  local pid_file="$2"
  local service_name="$3"
  if screen_session_exists "$session_name"; then
    log "停止由 screen 管理的旧${service_name}会话：$session_name"
    screen -S "$session_name" -X quit
  fi
  rm -f "$pid_file"
}

is_project_process() {
  local pid="$1"
  local command
  local cwd
  command="$(process_command "$pid")"
  cwd="$(process_cwd "$pid")"
  [[ "$command" == *"$PROJECT_ROOT"* || "$cwd" == "$PROJECT_ROOT" || "$cwd" == "$PROJECT_ROOT"/* ]]
}

child_pids() {
  pgrep -P "$1" 2>/dev/null || true
}

kill_tree() {
  local pid="$1"
  local child
  while read -r child; do
    [[ -n "$child" ]] && kill_tree "$child"
  done < <(child_pids "$pid")
  kill -TERM "$pid" 2>/dev/null || true
}

wait_for_exit() {
  local pid="$1"
  local attempts=0
  while kill -0 "$pid" 2>/dev/null && ((attempts < 20)); do
    sleep 0.5
    ((attempts += 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "$pid" 2>/dev/null || true
  fi
}

stop_pid_file_process() {
  local pid_file="$1"
  local label="$2"
  [[ -s "$pid_file" ]] || return 0
  local pid
  pid="$(head -n 1 "$pid_file")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    if is_project_process "$pid"; then
      log "停止旧${label}进程：$pid"
      kill_tree "$pid"
      wait_for_exit "$pid"
    else
      fail "$label PID 文件指向非本项目进程，拒绝终止：$pid"
    fi
  fi
  rm -f "$pid_file"
}

stop_port_processes() {
  local port="$1"
  local label="$2"
  local pid
  while read -r pid; do
    [[ -n "$pid" ]] || continue
    if ! is_project_process "$pid"; then
      fail "$label 端口 $port 被非本项目进程占用，拒绝终止：$pid"
    fi
    log "停止占用${label}端口 $port 的旧进程：$pid"
    kill_tree "$pid"
    wait_for_exit "$pid"
  done < <(
    {
      lsof -t -nP -i4TCP:"$port" -sTCP:LISTEN 2>/dev/null
      lsof -t -nP -i6TCP:"$port" -sTCP:LISTEN 2>/dev/null
    } | sort -u
  )
}

start_backend() {
  stop_screen_session "$BACKEND_SCREEN_SESSION" "$BACKEND_PID_FILE" "后端"
  stop_port_processes "$BACKEND_PORT" "后端"
  log "启动后端，显式绑定 IPv4 通配地址 0.0.0.0，端口 $BACKEND_PORT"
  screen -dmS "$BACKEND_SCREEN_SESSION" bash -c \
    'cd "$1" && export SERVER_ADDRESS=0.0.0.0 SERVER_PORT="$2" JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.net.preferIPv4Stack=true" && exec /bin/bash "$1/mvnw" -Dmaven.test.skip=true spring-boot:run >>"$3" 2>&1' \
    _ "$PROJECT_ROOT" "$BACKEND_PORT" "$BACKEND_LOG"
  wait_for_ipv4_http "http://127.0.0.1:$BACKEND_PORT/api/actuator/health" "后端"
  local pid
  pid="$(lsof -t -nP -i4TCP:"$BACKEND_PORT" -sTCP:LISTEN | head -n 1)"
  [[ "$pid" =~ ^[0-9]+$ ]] || fail "无法获取后端监听进程 PID"
  printf '%s\n' "$pid" >"$BACKEND_PID_FILE"
}

start_frontend() {
  stop_screen_session "$FRONTEND_SCREEN_SESSION" "$FRONTEND_PID_FILE" "前端"
  stop_port_processes "$FRONTEND_PORT" "前端"
  log "启动前端，显式绑定 IPv4 通配地址 0.0.0.0，端口 $FRONTEND_PORT"
  screen -dmS "$FRONTEND_SCREEN_SESSION" bash -c \
    'cd "$1" && exec "$3" run dev -- --host 0.0.0.0 --port "$2" >>"$4" 2>&1' \
    _ "$FRONTEND_DIR" "$FRONTEND_PORT" "$(command -v npm)" "$FRONTEND_LOG"
  wait_for_ipv4_http "http://127.0.0.1:$FRONTEND_PORT/" "前端"
  local pid
  pid="$(lsof -t -nP -i4TCP:"$FRONTEND_PORT" -sTCP:LISTEN | head -n 1)"
  [[ "$pid" =~ ^[0-9]+$ ]] || fail "无法获取前端监听进程 PID"
  printf '%s\n' "$pid" >"$FRONTEND_PID_FILE"
}

print_summary() {
  printf '\n'
  log "全部服务已就绪"
  printf '  Nginx：  http://127.0.0.1:%s/\n' "$NGINX_PORT"
  printf '  前端：   http://127.0.0.1:%s/\n' "$FRONTEND_PORT"
  printf '  后端：   http://127.0.0.1:%s/api/\n' "$BACKEND_PORT"
  printf '  健康检查：http://127.0.0.1:%s/api/actuator/health\n' "$BACKEND_PORT"
  printf '  日志目录：%s\n' "$RUNTIME_DIR"
  printf '  后端 PID：%s\n' "$(cat "$BACKEND_PID_FILE")"
  printf '  前端 PID：%s\n' "$(cat "$FRONTEND_PID_FILE")"
}

main() {
  require_command docker
  require_command curl
  require_command lsof
  require_command pgrep
  require_command ps
  require_command screen
  require_command npm
  load_environment
  docker info >/dev/null 2>&1 || fail "Docker Desktop 未运行或当前用户无权访问 Docker"
  docker compose version >/dev/null 2>&1 || fail "当前 Docker 不支持 Docker Compose，无法启动中间件"
  validate_compose

  ensure_mysql
  ensure_redis
  ensure_milvus
  start_backend
  start_frontend
  ensure_nginx
  print_summary
}

main "$@"
trap - EXIT
