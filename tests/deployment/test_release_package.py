"""发布目录、校验清单和失败保护测试，不调用应用构建或远程服务。"""

import hashlib
import importlib.util
import os
from pathlib import Path
import tarfile
import tempfile
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_package", ROOT / "prod/package-release.py")
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)
import deployment_checks as CHECKS


class ReleasePackageTest(unittest.TestCase):
    def setUp(self):
        temporary = ROOT / ".codex" / "release-package-tests"
        temporary.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=temporary)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.prod = self.root / "prod"
        for name in PACKAGE.REQUIRED_FILES:
            self.write(name, f"fixture {name}\n")
        for name in PACKAGE.REQUIRED_DIRS:
            self.write(f"{name}/sample.txt", "内容\n")
        self.write("artifacts/RELEASE", "test-01\n")

    def write(self, name, content):
        file = self.prod / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")
        return file

    def package(self, **kwargs):
        return PACKAGE.build_release(self.prod, "test-01", self.root / "releases", **kwargs)

    def test_allowlist_and_full_checksum_manifest(self):
        for name in (".env", "tests/test.py", "sql/migrations/old.sql", "logs/server.log",
                     "artifacts/frontend/dist/.env", "artifacts/frontend/dist/.DS_Store"):
            self.write(name, "private fixture")
        release = self.package()
        prod = release / "prod"
        actual = {path.relative_to(prod).as_posix() for path in prod.rglob("*") if path.is_file()}
        manifest = prod / "artifacts/SHA256SUMS"
        recorded = {}
        for line in manifest.read_text().splitlines():
            digest, path = line.split("  ", 1)
            recorded[path] = digest
            self.assertEqual(hashlib.sha256((prod / path).read_bytes()).hexdigest(), digest)
        self.assertEqual(set(recorded), actual - {"artifacts/SHA256SUMS"})
        self.assertFalse(any("migrations/" in name or "tests/" in name or ".env" == Path(name).name for name in actual))
        self.assertIn(".env.example", actual)
        self.assertIn("tools/RagDeploymentTool.java", actual)
        with tarfile.open(release / "prod.tar.gz") as archive:
            self.assertEqual(set(archive.getnames()), {f"prod/{name}" for name in actual})
        self.assertEqual(CHECKS.verify_package(prod), "test-01")

    def test_selected_migration_is_packaged_unchanged(self):
        sql = self.root / "sql/migrations/selected.sql"
        sql.parent.mkdir(parents=True)
        sql.write_bytes(b"SELECT 1;\n")
        release = self.package(migrations=[sql])
        self.assertEqual((release / "prod/sql/migrations/selected.sql").read_bytes(), sql.read_bytes())
        self.assertIn("sql/migrations/selected.sql", (release / "prod/artifacts/SHA256SUMS").read_text())
        self.assertEqual(CHECKS.verify_package(release / "prod"), "test-01")

    def test_deployment_verification_rejects_tampered_files(self):
        release = self.package()
        (release / "prod/docker-compose.yml").write_text("changed")
        with self.assertRaisesRegex(ValueError, "文件校验失败"):
            CHECKS.verify_package(release / "prod")

    def test_deployment_verification_rejects_extra_unlisted_assets(self):
        release = self.package()
        (release / "prod/artifacts/frontend/dist/old.js").write_text("stale")
        with self.assertRaisesRegex(ValueError, "未覆盖"):
            CHECKS.verify_package(release / "prod")

    def test_deployment_verification_rejects_duplicate_and_traversal_entries(self):
        release = self.package()
        manifest = release / "prod/artifacts/SHA256SUMS"
        original = manifest.read_text()
        for extra in (original.splitlines()[0], "0" * 64 + "  ../.env"):
            with self.subTest(entry=extra):
                manifest.write_text(original + extra + "\n")
                with self.assertRaisesRegex(ValueError, "非法、重复"):
                    CHECKS.verify_package(release / "prod")

    def test_missing_input_does_not_publish_partial_release(self):
        (self.prod / "artifacts/backend/app.jar").unlink()
        with self.assertRaises(FileNotFoundError):
            self.package()
        self.assertFalse((self.root / "releases/test-01").exists())

    def test_existing_release_is_not_overwritten(self):
        release = self.package()
        before = (release / "prod.tar.gz").read_bytes()
        with self.assertRaises(FileExistsError):
            self.package()
        self.assertEqual((release / "prod.tar.gz").read_bytes(), before)

    def test_symlink_cannot_include_files_outside_release_inputs(self):
        target = self.root / "private.txt"
        target.write_text("private fixture")
        (self.prod / "embed_text/html/private.txt").symlink_to(target)
        with self.assertRaises(ValueError):
            self.package()

    def test_invalid_release_name_is_rejected(self):
        for name in ("../escape", ".", "", "name with spaces"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                PACKAGE.build_release(self.prod, name, self.root / "releases")

    @unittest.skipUnless(os.name == "posix", "Shell 构建入口在 macOS/Linux 验证")
    def test_shell_build_stops_before_packaging_on_maven_failure(self):
        script = self.prod / "build-artifacts.sh"
        shutil.copy2(ROOT / "prod/build-artifacts.sh", script)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        for name, code in (("npm", 0), ("mvn", 23)):
            executable = bin_dir / name
            executable.write_text(f"#!/bin/sh\nexit {code}\n")
            executable.chmod(0o755)
        env = {**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"]}
        result = subprocess.run(["bash", str(script)], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertFalse((self.root / ".codex/releases").exists())
        self.assertEqual((self.prod / "artifacts/RELEASE").read_text(), "test-01\n")


if __name__ == "__main__":
    unittest.main()
