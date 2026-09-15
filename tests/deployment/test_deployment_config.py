"""使用虚拟凭据验证生产配置，不连接服务或读取真实 .env。"""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import unittest


PROD = Path(__file__).resolve().parents[2] / "prod"
FIXTURE = Path(__file__).with_name("deployment_contract.json")


def compose_config(files=None, overrides=None):
    env = {
        "PATH": os.environ["PATH"],
        "HOME": os.environ["HOME"],
        "RELEASE_ID": "contract-release",
        "BACKEND_RUNTIME_IMAGE": "contract-backend:runtime",
        "NGINX_RUNTIME_IMAGE": "contract-nginx:runtime",
        "BACKEND_HOST_PORT": "9025",
        "PROMETHEUS_HOST_PORT": "9090",
        "GRAFANA_HOST_PORT": "3000",
        "APP_CODE_DEPLOY_BASE_URL": "http://deployment.example",
        "MYSQL_USER": "contract-mysql",
        "REDIS_USERNAME": "contract-redis",
        "GRAFANA_ADMIN_USER": "contract-admin",
        "INFRA_SHARED_PASSWORD": "fake-shared-password",
        "MILVUS_MINIO_PASSWORD": "fake-minio-password",
        "MYSQL_ROOT_PASSWORD": "fake-mysql-root-password",
        "MYSQL_PASSWORD": "fake-mysql-app-password",
        "REDIS_PASSWORD": "fake-redis-password",
        "GRAFANA_ADMIN_PASSWORD": "fake-grafana-password",
        "RAG_MILVUS_PASSWORD": "fake-milvus-password",
        "RAG_HYBRID_ENABLED": "true",
        "RAG_INGEST_ENABLED": "false",
        "RAG_INGEST_TYPES": "",
        "DEEPSEEK_API_KEY": "",
        "DASHSCOPE_API_KEY": "",
        "COS_HOST": "",
        "TEN_SERCET_ID": "",
        "TEN_SECRET_KEY": "",
        "PEXELS_API_KEY": "",
    }
    env.update(overrides or {})
    command = ["docker", "compose", "--env-file", os.devnull,
               "--project-directory", str(PROD)]
    for file in files or [PROD / "docker-compose.yml"]:
        command.extend(["-f", str(file)])
    command.extend(["config", "--format", "json"])
    return subprocess.run(command, env=env, capture_output=True, text=True, check=False)


def runtime_contract(config):
    config = json.loads(json.dumps(config).replace(str(PROD), "<PROD>"))
    for service in config["services"].values():
        service.pop("build", None)
        for mount in service.get("volumes", []):
            if mount["type"] == "bind":
                # Compose 2 显式输出短语法的默认值，Compose 5 会省略它。
                mount.setdefault("bind", {}).setdefault("create_host_path", True)
    return {
        name: hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
        for name, value in {
            **{f"service:{name}": value for name, value in config["services"].items()},
            "volumes": config["volumes"], "networks": config["networks"],
            "project": config["name"],
        }.items()
    }


class DeploymentConfigTest(unittest.TestCase):
    def config(self, overrides=None):
        result = compose_config(overrides=overrides)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_runtime_matches_pre_merge_configuration(self):
        expected = json.loads(FIXTURE.read_text(encoding="utf-8"))
        actual = runtime_contract(self.config())
        self.assertEqual(actual.keys(), expected.keys())
        for item in expected:
            with self.subTest(item=item):
                self.assertEqual(actual[item], expected[item])

    def test_runtime_reuse_uses_unified_dockerfiles(self):
        services = self.config()["services"]
        for name in ("backend", "nginx"):
            build = services[name]["build"]
            self.assertEqual(build["dockerfile"].removeprefix("./"), f"docker/Dockerfile.{name}")
            self.assertEqual(build["args"]["RUNTIME_IMAGE"], f"contract-{name}:runtime")
            self.assertEqual(services[name]["image"], f"ai-app-generation-{name}:contract-release")

    def test_bind_default_normalization_preserves_explicit_false(self):
        config = self.config()
        original = runtime_contract(config)
        mount = next(item for item in config["services"]["backend"]["volumes"] if item["type"] == "bind")
        mount.setdefault("bind", {})["create_host_path"] = True
        self.assertEqual(runtime_contract(config), original)
        mount["bind"]["create_host_path"] = False
        self.assertNotEqual(runtime_contract(config), original)

    def test_new_environment_can_build_without_existing_runtime(self):
        services = self.config({"BACKEND_RUNTIME_IMAGE": "", "NGINX_RUNTIME_IMAGE": ""})["services"]
        self.assertEqual(services["backend"]["build"]["args"]["RUNTIME_IMAGE"], "runtime")
        self.assertEqual(services["nginx"]["build"]["args"]["RUNTIME_IMAGE"], "nginx:1.27-alpine")

    def test_release_is_required_instead_of_silently_using_latest(self):
        result = compose_config(overrides={"RELEASE_ID": ""})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RELEASE_ID", result.stderr)

    def test_independent_passwords_and_shared_fallback(self):
        keys = ("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "REDIS_PASSWORD",
                "GRAFANA_ADMIN_PASSWORD", "RAG_MILVUS_PASSWORD")
        services = self.config({key: "" for key in keys})["services"]
        for service, field in (("mysql", "MYSQL_ROOT_PASSWORD"), ("mysql", "MYSQL_PASSWORD"),
                               ("redis", "REDIS_PASSWORD"), ("backend", "SPRING_DATASOURCE_PASSWORD"),
                               ("backend", "SPRING_DATA_REDIS_PASSWORD"), ("backend", "RAG_MILVUS_PASSWORD"),
                               ("milvus", "COMMON_SECURITY_DEFAULTROOTPASSWORD"),
                               ("grafana", "GF_SECURITY_ADMIN_PASSWORD")):
            self.assertEqual(services[service]["environment"][field], "fake-shared-password")
        self.assertEqual(services["milvus"]["environment"]["MINIO_SECRET_ACCESS_KEY"], "fake-minio-password")


if __name__ == "__main__":
    unittest.main()
