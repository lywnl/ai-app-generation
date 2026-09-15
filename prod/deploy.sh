#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

PROD="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ENV_FILE="$PROD/.env"
CHECK_ONLY=false
ALL_SERVICES=false
WAIT_TIMEOUT=300
MIN_FREE_MB=5120

usage() {
  echo "用法: ./deploy.sh [--check] [--all-services] [--env-file 文件] [--wait-timeout 秒] [--min-free-mb MiB]"
  echo "默认只更新 backend/nginx；全新环境使用 --all-services。--check 不构建或启动容器。"
}

die() { echo "部署已停止: $*" >&2; exit 1; }
while (($#)); do
  case "$1" in
    --check) CHECK_ONLY=true; shift ;;
    --all-services) ALL_SERVICES=true; shift ;;
    --env-file|--wait-timeout|--min-free-mb)
      (($# >= 2)) || die "$1 缺少参数"
      case "$1" in
        --env-file) ENV_FILE="$2" ;;
        --wait-timeout) WAIT_TIMEOUT="$2" ;;
        --min-free-mb) MIN_FREE_MB="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "未知参数: $1" ;;
  esac
done
[[ "$WAIT_TIMEOUT" =~ ^[1-9][0-9]*$ && "$MIN_FREE_MB" =~ ^[1-9][0-9]*$ ]] || die "等待时间和磁盘阈值必须是正整数"
[[ "$(uname -s)" == Linux ]] || die "请在 Linux 部署服务器上执行"
for dependency in docker python3 curl flock; do
  command -v "$dependency" >/dev/null || die "缺少命令: $dependency"
done
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else "需要 Python 3.9+")'
[[ "$ENV_FILE" == /* ]] || ENV_FILE="$PROD/$ENV_FILE"
[[ -f "$ENV_FILE" ]] || die "未找到环境文件: $ENV_FILE；已有环境请保留原文件"
cd "$PROD"

# 在固定生产目录的项目 .codex 下锁定整次部署，不删除锁文件，避免产生第二把锁。
STATE_DIR="$PROD/../.codex/deployments"
mkdir -p "$STATE_DIR"
exec 9> "$STATE_DIR/deploy.lock"
flock -n 9 || die "已有部署正在执行"
RUN_DIR="$(mktemp -d "$STATE_DIR/deploy-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
COMPOSE=(docker compose --project-directory "$PROD" --env-file "$ENV_FILE" -f "$PROD/docker-compose.yml")
CHECKER="$PROD/tools/deployment_checks.py"
PHASE="部署前检查"
SWITCH_STARTED=false

on_error() {
  local code="$1"
  trap - ERR INT TERM
  echo "部署失败，阶段: $PHASE；记录目录: $RUN_DIR" >&2
  if "$SWITCH_STARTED"; then
    "${COMPOSE[@]}" ps -a > "$RUN_DIR/failed-status.log" 2>&1 || true
    "${COMPOSE[@]}" logs --no-color --tail 80 backend nginx > "$RUN_DIR/failed-containers.log" 2>&1 || true
  fi
  echo "未执行自动回滚、SQL、模板导入或数据卷清理。" >&2
  exit "$code"
}
trap 'on_error "$?"' ERR
trap 'on_error 130' INT
trap 'on_error 143' TERM

RELEASE="$(python3 -B "$CHECKER" package "$PROD")"
if [[ -n "${DOCKER_CONTEXT:-}" ]]; then
  docker_endpoint="$(docker context inspect "$DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}')"
elif [[ -n "${DOCKER_HOST:-}" ]]; then
  docker_endpoint="$DOCKER_HOST"
else
  docker_endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
fi
[[ "$docker_endpoint" == unix://* ]] || die "部署脚本只支持服务器本机 Docker，不能使用远程 Docker endpoint"
docker info --format '{{.OSType}} {{.Architecture}}' > "$RUN_DIR/docker-platform.txt"
read -r docker_os docker_arch < "$RUN_DIR/docker-platform.txt"
[[ "$docker_os" == linux && "$docker_arch" =~ ^(x86_64|amd64)$ ]] || die "当前浏览器运行环境要求 Linux AMD64 Docker"
docker compose version > "$RUN_DIR/compose-version.txt"
"${COMPOSE[@]}" up --help > "$RUN_DIR/compose-up-help.txt"
grep -q -- '--wait-timeout' "$RUN_DIR/compose-up-help.txt" || die "Docker Compose 版本过旧，需要支持 --wait-timeout"
IMAGES="$("${COMPOSE[@]}" config --format json | python3 -B "$CHECKER" config "$RELEASE")"
docker_root="$(docker info --format '{{.DockerRootDir}}')"
python3 -B "$CHECKER" disk "$MIN_FREE_MB" "$PROD" "$docker_root"
while IFS= read -r image; do
  if docker image inspect "$image" >/dev/null 2>&1; then
    die "版本镜像已存在: $image；请使用新的发布版本，避免覆盖旧镜像"
  fi
done <<< "$IMAGES"
if ! "$ALL_SERVICES"; then
  docker inspect ai-mysql ai-redis ai-milvus ai-milvus-etcd ai-milvus-minio |
    python3 -B "$CHECKER" infrastructure
fi

if "$CHECK_ONLY"; then
  echo "部署前校验通过，版本: $RELEASE；未构建镜像或启动容器。"
  exit 0
fi

PHASE="保存切换前记录"
"${COMPOSE[@]}" ps -aq > "$RUN_DIR/container-ids.txt"
mapfile -t container_ids < "$RUN_DIR/container-ids.txt"
if ((${#container_ids[@]})); then
  docker inspect "${container_ids[@]}" | python3 -B "$CHECKER" snapshot > "$RUN_DIR/before-containers.json"
fi
cp docker-compose.yml artifacts/RELEASE artifacts/SHA256SUMS "$RUN_DIR/"
sha256sum "$ENV_FILE" > "$RUN_DIR/env.sha256"
PHASE="构建前后端镜像"
echo "开始构建版本 $RELEASE，构建日志: $RUN_DIR/build.log"
"${COMPOSE[@]}" build backend nginx > "$RUN_DIR/build.log" 2>&1
# 构建期间文件或 .env 被修改时阻止切换，避免实际启动另一套版本或配置。
python3 -B "$CHECKER" package "$PROD" > /dev/null
cmp --silent "$RUN_DIR/SHA256SUMS" artifacts/SHA256SUMS || die "构建期间发布清单发生变化"
cmp --silent "$RUN_DIR/RELEASE" artifacts/RELEASE || die "构建期间发布版本发生变化"
sha256sum --check "$RUN_DIR/env.sha256" > /dev/null
current_images="$("${COMPOSE[@]}" config --format json | python3 -B "$CHECKER" config "$RELEASE")"
[[ "$current_images" == "$IMAGES" ]] || die "构建期间发布版本发生变化"
PHASE="切换并等待容器就绪"
SWITCH_STARTED=true
if "$ALL_SERVICES"; then
  "${COMPOSE[@]}" up -d --no-build --wait --wait-timeout "$WAIT_TIMEOUT" > "$RUN_DIR/up.log" 2>&1
else
  "${COMPOSE[@]}" up -d --no-build --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" backend nginx > "$RUN_DIR/up.log" 2>&1
fi
PHASE="验证 Nginx 和后端接口"
CURL=(curl --noproxy '*' --fail --silent --show-error --max-time 10 --retry 5 --retry-delay 2 --retry-connrefused)
"${CURL[@]}" http://127.0.0.1/ -o "$RUN_DIR/homepage.html"
"${CURL[@]}" http://127.0.0.1/api/actuator/health -o "$RUN_DIR/health.json"
python3 -B "$CHECKER" http "$PROD" "$RUN_DIR"
"${COMPOSE[@]}" ps > "$RUN_DIR/after-status.log"
echo "部署成功，版本: $RELEASE；容器就绪、首页及后端健康检查通过。记录: $RUN_DIR"
