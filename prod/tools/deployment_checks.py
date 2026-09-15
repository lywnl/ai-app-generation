"""发布输入与部署校验，共享给打包入口和 Linux 部署入口。"""

import hashlib
import json
from pathlib import Path
import re
import shutil
import sys


REQUIRED_FILES = (
    "docker-compose.yml", ".dockerignore", ".env.example", "README.md", "UPGRADE-V2.md", "deploy.sh",
    "docker/Dockerfile.backend", "docker/Dockerfile.nginx", "nginx/nginx.conf",
    "redis/start-redis.sh", "prometheus/prometheus.yml", "sql/schema.sql",
    "grafana/provisioning/datasources/datasource.yml",
    "grafana/provisioning/dashboards/dashboards.yml",
    "grafana/dashboards/ai-model-observability-dashboard.json",
    "tools/RagDeploymentTool.java", "tools/run-rag-tool.sh", "tools/deployment_checks.py",
    "artifacts/backend/app.jar", "artifacts/frontend/dist/index.html", "artifacts/RELEASE",
)
REQUIRED_DIRS = (
    "artifacts/frontend/dist", "embed_text/html", "embed_text/multi-file", "embed_text/vue-project",
)


def checked_file(path: Path) -> Path:
    if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
        raise ValueError(f"发布输入不能经过符号链接: {path}")
    if not path.is_file():
        raise FileNotFoundError(f"缺少发布文件: {path}")
    if any(character in path.name for character in "\r\n\\"):
        raise ValueError(f"发布文件名不支持换行或反斜杠: {path}")
    return path


def release_inputs(prod: Path) -> dict[str, Path]:
    files = {name: checked_file(prod / name) for name in REQUIRED_FILES}
    for name in REQUIRED_DIRS:
        directory = prod / name
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError(f"发布目录缺失或是符号链接: {directory}")
        for path in sorted(directory.rglob("*")):
            relative = path.relative_to(prod)
            if path.is_symlink():
                raise ValueError(f"发布输入不能是符号链接: {path}")
            if any(part.startswith(".") or part == "node_modules" for part in relative.parts):
                continue
            if path.is_file():
                files[relative.as_posix()] = checked_file(path)
    return files


def sha256(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def verify_package(prod: Path) -> str:
    expected = release_inputs(prod)
    migrations = prod / "sql/migrations"
    if migrations.exists():
        for path in migrations.glob("*.sql"):
            expected[path.relative_to(prod).as_posix()] = checked_file(path)
    recorded = {}
    for line in checked_file(prod / "artifacts/SHA256SUMS").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([a-fA-F0-9]{64})  (.+)", line)
        if not match or match[2] not in expected or match[2] in recorded:
            raise ValueError("校验清单包含非法、重复或非发布文件路径")
        recorded[match[2]] = match[1].lower()
    if recorded.keys() != expected.keys():
        raise ValueError("校验清单未覆盖全部发布文件，可能漏传或残留旧文件")
    for name, path in expected.items():
        if sha256(path) != recorded[name]:
            raise ValueError(f"文件校验失败: {name}")
    release = (prod / "artifacts/RELEASE").read_text(encoding="utf-8-sig").strip()
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", release):
        raise ValueError("发布版本不是合法镜像标签")
    return release


def verify_config(config: dict, release: str) -> list[str]:
    if config.get("name") != "ai-app-generation-prod":
        raise ValueError("Compose 项目名称不匹配")
    images = []
    for name in ("backend", "nginx"):
        service = config["services"][name]
        expected = f"ai-app-generation-{name}:{release}"
        if service["image"] != expected:
            raise ValueError(f"{name} 实际镜像版本与 artifacts/RELEASE 不一致，请核对环境变量及 .env")
        if service.get("build", {}).get("args", {}).get("RUNTIME_IMAGE") == expected:
            raise ValueError(f"{name} 不能把输出镜像作为自身运行时基础")
        images.append(expected)
    backend_env = config["services"]["backend"].get("environment", {})
    if str(backend_env.get("RAG_INGEST_ENABLED", "false")).lower() != "false":
        raise ValueError("普通部署必须关闭 RAG_INGEST_ENABLED，模板导入请独立执行")
    nginx_ports = config["services"]["nginx"].get("ports", [])
    if not any(port.get("target") == 80 and str(port.get("published")) == "80"
               and port.get("host_ip", "0.0.0.0") in ("0.0.0.0", "127.0.0.1") for port in nginx_ports):
        raise ValueError("Nginx 必须在本机 IPv4 的 80 端口提供部署验收入口")
    return images


def verify_infrastructure(containers: list[dict]) -> None:
    required = {"ai-mysql", "ai-redis", "ai-milvus", "ai-milvus-etcd", "ai-milvus-minio"}
    indexed = {item["Name"].lstrip("/"): item for item in containers}
    for name in sorted(required):
        item = indexed.get(name, {})
        state = item.get("State", {})
        labels = item.get("Config", {}).get("Labels", {}) or {}
        if labels.get("com.docker.compose.project") != "ai-app-generation-prod":
            raise ValueError(f"基础服务 {name} 不属于当前 Compose 项目")
        if not state.get("Running") or state.get("Health", {}).get("Status") != "healthy":
            raise ValueError(f"基础服务 {name} 未就绪；先排查基础设施，全新部署可使用 --all-services")


def container_snapshot(containers: list[dict]) -> list[dict]:
    # 不保存 Config.Env，避免将运行凭据混入普通诊断文件。
    return [{"name": item["Name"], "id": item["Id"], "image_id": item["Image"],
             "image_tag": item["Config"]["Image"], "mounts": item["Mounts"],
             "started_at": item["State"]["StartedAt"]} for item in containers]


def main() -> None:
    mode, *args = sys.argv[1:]
    if mode == "package":
        print(verify_package(Path(args[0])))
    elif mode == "config":
        print("\n".join(verify_config(json.load(sys.stdin), args[0])))
    elif mode == "infrastructure":
        verify_infrastructure(json.load(sys.stdin))
    elif mode == "snapshot":
        print(json.dumps(container_snapshot(json.load(sys.stdin)), ensure_ascii=False, indent=2))
    elif mode == "disk":
        minimum = int(args[0]) * 1024 * 1024
        for path in args[1:]:
            free = shutil.disk_usage(path).free
            if free < minimum:
                raise ValueError(f"{path} 磁盘空间不足，至少需要 {args[0]} MiB 可用空间")
            print(f"磁盘检查通过: {path}，可用 {free // (1024 * 1024)} MiB")
    elif mode == "http":
        prod, run_dir = map(Path, args)
        if sha256(prod / "artifacts/frontend/dist/index.html") != sha256(run_dir / "homepage.html"):
            raise ValueError("Nginx 首页与本次前端产物不一致")
        if json.loads((run_dir / "health.json").read_text())["status"] != "UP":
            raise ValueError("经 Nginx 转发的后端健康状态不是 UP")
    else:
        raise ValueError("未知部署校验操作")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as error:
        sys.exit(f"部署校验失败: {error}")
