#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="$PROJECT_ROOT/dev/.env"
LOCAL_COMPOSE="$PROJECT_ROOT/dev/docker-compose.local.yml"
RUNTIME_DIR="$PROJECT_ROOT/logs/runtime"

BACKEND_PORT=9025
FRONTEND_PORT=5173

BACKEND_PID_FILE="$RUNTIME_DIR/backend.pid"
FRONTEND_PID_FILE="$RUNTIME_DIR/frontend.pid"
BACKEND_SCREEN_SESSION="ai-app-generation-backend"
FRONTEND_SCREEN_SESSION="ai-app-generation-frontend"

MYSQL_CONTAINER="ai-codegen-e2e-mysql"
REDIS_CONTAINER="ai-codegen-rag-eval-redis"
ETCD_CONTAINER="ai-codegen-milvus-etcd"
MINIO_CONTAINER="ai-codegen-milvus-minio"
MILVUS_CONTAINER="ai-codegen-milvus"
NGINX_CONTAINER="ai-app-generation-dev-nginx"

MODE=""

log() {
  printf '[本地停止] %s\n' "$*"
}

fail() {
  printf '[本地停止][失败] %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
用法：
  bash scripts/stop-local.sh --dry-run
  bash scripts/stop-local.sh --confirm

--dry-run 只检查配置、目标容器和应用进程，不改变任何状态。
--confirm 停止前端、后端以及统一 Compose 管理的全部中间件；不会删除容器、数据卷或镜像。
EOF
}

parse_args() {
  [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
  case "$1" in
    --dry-run|--confirm) MODE="$1" ;;
    *) usage >&2; exit 2 ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令：$1"
}

load_environment() {
  [[ -f "$ENV_FILE" ]] || fail "缺少环境文件：$ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
}

container_exists() {
  docker container inspect "$1" >/dev/null 2>&1
}

container_running() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" == "true" ]]
}

container_managed_by_local_compose() {
  [[ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$1" 2>/dev/null || true)" == "ai-app-generation-local" ]]
}

validate_compose() {
  [[ -f "$LOCAL_COMPOSE" ]] || fail "缺少统一 Compose 文件：$LOCAL_COMPOSE"
  docker compose --env-file "$ENV_FILE" -f "$LOCAL_COMPOSE" config --quiet \
    || fail "统一 Compose 配置校验失败：$LOCAL_COMPOSE"
}

validate_target_containers() {
  local container
  for container in \
    "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$ETCD_CONTAINER" \
    "$MINIO_CONTAINER" "$MILVUS_CONTAINER" "$NGINX_CONTAINER"; do
    if container_exists "$container" && ! container_managed_by_local_compose "$container"; then
      fail "容器 $container 不属于统一 Compose 项目，拒绝停止以避免误伤其他服务"
    fi
  done
}

screen_session_exists() {
  screen -ls 2>/dev/null | grep -Fq ".${1}"
}

stop_screen_session() {
  local session_name="$1"
  local label="$2"
  if screen_session_exists "$session_name"; then
    log "停止${label} screen 会话：$session_name"
    screen -S "$session_name" -X quit
  else
    log "${label} screen 会话不存在：$session_name"
  fi
}

process_command() {
  ps -p "$1" -o command= 2>/dev/null || true
}

process_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
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
    log "进程未在宽限期内退出，发送 SIGKILL：$pid"
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
      log "停止 PID 文件记录的${label}进程：$pid"
      kill_tree "$pid"
      wait_for_exit "$pid"
    else
      fail "${label} PID 文件指向非本项目进程，拒绝终止：$pid"
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
      fail "${label}端口 $port 被非本项目进程占用，拒绝终止：$pid"
    fi
    log "停止占用${label}端口 $port 的项目进程：$pid"
    kill_tree "$pid"
    wait_for_exit "$pid"
  done < <(
    {
      lsof -t -nP -i4TCP:"$port" -sTCP:LISTEN 2>/dev/null
      lsof -t -nP -i6TCP:"$port" -sTCP:LISTEN 2>/dev/null
    } | sort -u
  )
}

stop_application_processes() {
  stop_screen_session "$BACKEND_SCREEN_SESSION" "后端"
  stop_screen_session "$FRONTEND_SCREEN_SESSION" "前端"
  stop_pid_file_process "$BACKEND_PID_FILE" "后端"
  stop_pid_file_process "$FRONTEND_PID_FILE" "前端"
  stop_port_processes "$BACKEND_PORT" "后端"
  stop_port_processes "$FRONTEND_PORT" "前端"
}

wait_for_containers_stopped() {
  local container
  local attempts
  for container in \
    "$NGINX_CONTAINER" "$MILVUS_CONTAINER" "$MINIO_CONTAINER" \
    "$ETCD_CONTAINER" "$REDIS_CONTAINER" "$MYSQL_CONTAINER"; do
    container_exists "$container" || continue
    attempts=0
    while container_running "$container" && ((attempts < 30)); do
      sleep 1
      ((attempts += 1))
    done
    container_running "$container" && fail "容器停止超时：$container"
    log "容器已停止：$container"
  done
}

verify_application_stopped() {
  local port pid
  for port in "$BACKEND_PORT" "$FRONTEND_PORT"; do
    while read -r pid; do
      [[ -n "$pid" ]] || continue
      if is_project_process "$pid"; then
        fail "端口 $port 仍由本项目进程监听：$pid"
      fi
      log "端口 $port 由非本项目进程占用，已保留：$pid"
    done < <(
      {
        lsof -t -nP -i4TCP:"$port" -sTCP:LISTEN 2>/dev/null
        lsof -t -nP -i6TCP:"$port" -sTCP:LISTEN 2>/dev/null
      } | sort -u
    )
  done
}

dry_run() {
  validate_compose
  validate_target_containers
  log "将停止 screen 会话：${BACKEND_SCREEN_SESSION}、${FRONTEND_SCREEN_SESSION}"
  log "将清理应用 PID 文件：${BACKEND_PID_FILE}、${FRONTEND_PID_FILE}"
  log "将停止统一 Compose 的 6 个中间件容器"
  log "dry-run 检查通过，未改变任何状态"
}

confirm_stop() {
  validate_compose
  validate_target_containers
  stop_application_processes
  log "停止统一 Compose 管理的全部中间件容器"
  docker compose --env-file "$ENV_FILE" -f "$LOCAL_COMPOSE" stop --timeout 30
  wait_for_containers_stopped
  verify_application_stopped
  log "前端、后端和中间件均已停止；容器、数据卷和镜像均已保留"
}

main() {
  parse_args "$@"
  require_command docker
  require_command lsof
  require_command pgrep
  require_command ps
  require_command screen
  docker info >/dev/null 2>&1 || fail "Docker Desktop 未运行或当前用户无权访问 Docker"
  docker compose version >/dev/null 2>&1 || fail "当前 Docker 不支持 Docker Compose"
  load_environment

  if [[ "$MODE" == "--dry-run" ]]; then
    dry_run
  else
    confirm_stop
  fi
}

main "$@"
