"""Linux 部署入口的阶段与失败保护测试，Docker/curl 使用隔离替身。"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "prod/tools"))
import deployment_checks as CHECKS


def config():
    return {"name": "ai-app-generation-prod", "services": {
        "backend": {"image": "ai-app-generation-backend:test-02", "environment": {"RAG_INGEST_ENABLED": "false"}},
        "nginx": {"image": "ai-app-generation-nginx:test-02", "ports": [{"target": 80, "published": "80"}]},
    }}


class DeploymentChecksTest(unittest.TestCase):
    def test_effective_compose_images_must_match_release(self):
        self.assertEqual(len(CHECKS.verify_config(config(), "test-02")), 2)
        for name in ("backend", "nginx"):
            changed = config()
            changed["services"][name]["image"] = f"ai-app-generation-{name}:old"
            with self.subTest(service=name), self.assertRaises(ValueError):
                CHECKS.verify_config(changed, "test-02")

    def test_ingestion_and_wrong_project_are_rejected(self):
        changed = config()
        changed["services"]["backend"]["environment"]["RAG_INGEST_ENABLED"] = "true"
        with self.assertRaises(ValueError):
            CHECKS.verify_config(changed, "test-02")
        changed = config()
        changed["name"] = "other-project"
        with self.assertRaises(ValueError):
            CHECKS.verify_config(changed, "test-02")

    def test_snapshot_does_not_include_credentials(self):
        item = {"Name": "/ai-backend", "Id": "id", "Image": "sha256:old", "Mounts": [],
                "Config": {"Image": "backend:old", "Env": ["PASSWORD=private-fixture"]},
                "State": {"StartedAt": "before"}}
        self.assertNotIn("private-fixture", json.dumps(CHECKS.container_snapshot([item])))


MOCK_COMMAND = '''#!/usr/bin/env python3
import json, os, pathlib, sys
root = pathlib.Path(os.environ["FIXTURE_ROOT"])
args = sys.argv[1:]
mode = os.environ.get("FAIL_MODE", "")
name = pathlib.Path(sys.argv[0]).name
with (root / "calls.jsonl").open("a") as output:
    output.write(json.dumps([name, *args]) + "\\n")
if name == "curl":
    target = pathlib.Path(args[args.index("-o") + 1])
    if "health" in target.name:
        target.write_text(json.dumps({"status": "DOWN" if mode == "health" else "UP"}))
    else:
        target.write_text("wrong" if mode == "homepage" else "home")
elif args[0] == "context":
    print("tcp://remote:2375" if mode == "remote-docker" else "unix:///var/run/docker.sock")
elif args[0] == "info":
    print(str(root) if "DockerRootDir" in args[-1] else "linux amd64")
elif args[:2] == ["image", "inspect"]:
    sys.exit(0 if mode == "existing-image" else 1)
elif args[0] == "inspect":
    items = []
    for target in args[1:]:
        items.append({"Name": "/" + target, "Id": target, "Image": "sha256:old", "Mounts": [],
          "Config": {"Image": target + ":old", "Env": ["PASSWORD=private-fixture"],
          "Labels": {"com.docker.compose.project": "ai-app-generation-prod"}},
          "State": {"Running": True, "StartedAt": "before", "Health": {
          "Status": "unhealthy" if mode == "infrastructure" else "healthy"}}})
    print(json.dumps(items))
elif args[0] == "compose":
    if "version" in args:
        print("v2.40.0")
    elif "--help" in args:
        print("--wait-timeout")
    elif "config" in args:
        data = json.loads((root / "config.json").read_text())
        if mode == "version":
            data["services"]["backend"]["image"] = "backend:old"
        print(json.dumps(data))
    elif "build" in args:
        if mode == "build":
            sys.exit(17)
        if mode == "env-change":
            (root / "prod/.env").write_text("changed")
        if mode == "file-change":
            (root / "prod/README.md").write_text("changed")
    elif "up" in args:
        sys.exit(28 if mode == "up" else 0)
    elif "ps" in args and "-aq" in args:
        print("old-backend\\nold-nginx")
    elif "ps" in args or "logs" in args:
        print("fixture status")
    else:
        sys.exit(99)
else:
    sys.exit(99)
'''


@unittest.skipUnless(sys.platform.startswith("linux"), "在隔离 Linux 容器执行 Shell 集成测试")
class LinuxDeploymentScriptTest(unittest.TestCase):
    def setUp(self):
        directory = ROOT / ".codex/linux-deploy-tests"
        directory.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=directory)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.prod = self.root / "prod"
        for name in CHECKS.REQUIRED_FILES:
            file = self.prod / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("fixture")
        for name in CHECKS.REQUIRED_DIRS:
            file = self.prod / name / "sample.txt"
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("fixture")
        for name in ("deploy.sh", "tools/deployment_checks.py"):
            shutil.copy2(ROOT / "prod" / name, self.prod / name)
        (self.prod / "artifacts/RELEASE").write_text("test-02\n")
        (self.prod / "artifacts/frontend/dist/index.html").write_text("home")
        (self.prod / ".env").write_text("PASSWORD=private-fixture")
        self.manifest = self.prod / "artifacts/SHA256SUMS"
        self.manifest.write_text("".join(f"{CHECKS.sha256(path)}  {name}\n"
            for name, path in sorted(CHECKS.release_inputs(self.prod).items())))
        (self.root / "config.json").write_text(json.dumps(config()))
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        for name in ("docker", "curl"):
            file = bin_dir / name
            file.write_text(MOCK_COMMAND)
            file.chmod(0o755)
        self.env = {**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                    "FIXTURE_ROOT": str(self.root)}

    def run_deploy(self, *args, mode=""):
        result = subprocess.run(["bash", str(self.prod / "deploy.sh"), "--min-free-mb", "1", *args],
            env={**self.env, "FAIL_MODE": mode}, capture_output=True, text=True, timeout=30)
        self.assertNotIn("private-fixture", result.stdout + result.stderr)
        return result

    def calls(self):
        path = self.root / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def has_up(self):
        return any("up" in call and "--help" not in call for call in self.calls())

    def test_default_updates_only_application_services_and_verifies_http(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        up = next(call for call in self.calls() if "up" in call and "--help" not in call)
        self.assertIn("--no-deps", up)
        self.assertEqual(up[-2:], ["backend", "nginx"])
        self.assertIn("--wait", up)
        self.assertEqual(len([call for call in self.calls() if call[0] == "curl"]), 2)
        self.assertFalse(any("down" in call or "prune" in call or "ingest" in call for call in self.calls()))

    def test_all_services_is_explicit(self):
        result = self.run_deploy("--all-services")
        self.assertEqual(result.returncode, 0, result.stderr)
        up = next(call for call in self.calls() if "up" in call and "--help" not in call)
        self.assertNotIn("--no-deps", up)
        self.assertNotIn("backend", up)

    def test_check_only_never_builds_or_starts(self):
        result = self.run_deploy("--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.has_up())
        self.assertFalse(any("build" in call for call in self.calls()))

    def test_tampering_or_incomplete_manifest_stops_before_docker(self):
        self.manifest.write_text(self.manifest.read_text().splitlines()[0] + "\n")
        self.assertNotEqual(self.run_deploy().returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_preflight_failures_never_start_containers(self):
        for mode in ("version", "existing-image", "infrastructure", "remote-docker"):
            with self.subTest(mode=mode):
                self.assertNotEqual(self.run_deploy(mode=mode).returncode, 0)
                self.assertFalse(self.has_up())
                self.assertFalse(any("build" in call for call in self.calls()))

    def test_low_disk_space_stops_before_build(self):
        self.assertNotEqual(self.run_deploy("--min-free-mb", "1000000000").returncode, 0)
        self.assertFalse(self.has_up())
        self.assertFalse(any("build" in call for call in self.calls()))

    def test_build_failure_does_not_switch(self):
        self.assertEqual(self.run_deploy(mode="build").returncode, 17)
        self.assertFalse(self.has_up())

    def test_changed_env_during_build_does_not_switch(self):
        self.assertNotEqual(self.run_deploy(mode="env-change").returncode, 0)
        self.assertFalse(self.has_up())

    def test_changed_package_during_build_does_not_switch(self):
        self.assertNotEqual(self.run_deploy(mode="file-change").returncode, 0)
        self.assertFalse(self.has_up())

    def test_failed_up_preserves_exit_status_and_diagnostics(self):
        self.assertEqual(self.run_deploy(mode="up").returncode, 28)
        self.assertTrue(list((self.root / ".codex").rglob("failed-containers.log")))
        self.assertFalse(any(call[0] == "curl" for call in self.calls()))

    def test_http_failure_is_not_reported_as_success(self):
        for mode in ("homepage", "health"):
            with self.subTest(mode=mode):
                result = self.run_deploy(mode=mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("部署成功", result.stdout)

    def test_concurrent_deployment_is_blocked(self):
        directory = self.root / ".codex/deployments"
        directory.mkdir(parents=True)
        # 使用与脚本相同的 flock 工具，避免跨架构容器中 Python 锁实现的差异。
        with subprocess.Popen(["flock", "-n", str(directory / "deploy.lock"), "sh", "-c",
                               "echo locked; read token"], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, text=True) as holder:
            try:
                self.assertEqual(holder.stdout.readline().strip(), "locked")
                self.assertNotEqual(self.run_deploy().returncode, 0)
                self.assertEqual(self.calls(), [])
            finally:
                holder.communicate("release\n", timeout=5)


if __name__ == "__main__":
    unittest.main()
