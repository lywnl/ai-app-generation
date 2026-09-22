#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROD="$ROOT/prod"
API_BASE_URL="${1:-/api}"
command -v python3 >/dev/null 2>&1 || { echo "发布打包需要 Python 3.9+" >&2; exit 1; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else "发布打包需要 Python 3.9+")'

run() { echo "+ $*"; "$@"; }
run env VITE_API_BASE_URL="$API_BASE_URL" npm --prefix "$ROOT/ai-app-generation-frontend" run build
if command -v mvn >/dev/null 2>&1; then
  run mvn -q -DskipTests package -f "$ROOT/pom.xml"
else
  [[ -f "$ROOT/mvnw" ]] || { echo "未找到 Maven 或项目 Maven Wrapper" >&2; exit 1; }
  run bash "$ROOT/mvnw" -q -DskipTests package -f "$ROOT/pom.xml"
fi

mkdir -p "$PROD/artifacts/frontend" "$PROD/artifacts/backend" "$PROD/grafana/dashboards"
rm -rf "$PROD/artifacts/frontend/dist" "$PROD/embed_text"
cp -R "$ROOT/ai-app-generation-frontend/dist" "$PROD/artifacts/frontend/dist"
jar="$(find "$ROOT/target" -maxdepth 1 -type f -name '*.jar' ! -name '*original*' -print -quit)"
[[ -n "$jar" ]] || { echo "未找到后端 JAR" >&2; exit 1; }
cp "$jar" "$PROD/artifacts/backend/app.jar"
cp "$ROOT/sql/schema.sql" "$PROD/sql/schema.sql"
cp -R "$ROOT/embed_text" "$PROD/embed_text"
cp "$ROOT/grafana/ai-model-observability-dashboard.json" "$PROD/grafana/dashboards/"

date -u +%Y%m%d-%H%M%S > "$PROD/artifacts/RELEASE"
run python3 -B "$PROD/package-release.py" --manifest-only
