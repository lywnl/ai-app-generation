#!/usr/bin/env python3
"""生成 prod 校验清单或手动打包；仅使用 Python 3.9+ 标准库，不读取真实 .env。"""

import argparse
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import sys


sys.path.insert(0, str(Path(__file__).resolve().parent / "tools"))
from deployment_checks import REQUIRED_DIRS, REQUIRED_FILES, checked_file, release_inputs, sha256


def write_manifest(root: Path, files: dict[str, Path]) -> None:
    text = "".join(f"{sha256(path)}  {name}\n" for name, path in sorted(files.items()))
    target = root / "artifacts/SHA256SUMS"
    temporary = target.with_suffix(".tmp")
    temporary.write_bytes(text.encode("utf-8"))
    temporary.replace(target)


def prepare_manifest(prod: Path, release_id: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", release_id):
        raise ValueError("发布版本必须是合法镜像标签，不能包含路径或空格")
    files = release_inputs(prod)
    # 直接上传目录时，已明确放入的迁移也必须与服务器校验范围一致。
    for path in sorted((prod / "sql/migrations").glob("*.sql")):
        files[path.relative_to(prod).as_posix()] = checked_file(path)
    (prod / "artifacts/RELEASE").write_bytes((release_id + "\n").encode("utf-8"))
    write_manifest(prod, files)


def build_release(prod: Path, release_id: str, output_root: Path,
                  migrations: tuple[Path, ...] = ()) -> Path:
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", release_id):
        raise ValueError("发布版本必须是合法镜像标签，不能包含路径或空格")
    destination = output_root / release_id
    if destination.exists():
        raise FileExistsError(f"发布版本已存在，不能覆盖: {destination}")
    files = release_inputs(prod)
    selected = {}
    for path in migrations:
        if path.suffix != ".sql" or path.name in selected:
            raise ValueError(f"迁移文件必须是不同名称的 SQL: {path}")
        selected[path.name] = checked_file(path)
    output_root.mkdir(parents=True, exist_ok=True)
    # 在同一文件系统暂存，全部完成后才发布目录，失败不会留下可误用的半成品。
    with tempfile.TemporaryDirectory(prefix=".pack-", dir=output_root) as temporary:
        staging = Path(temporary)
        package = staging / "prod"
        for name, source in {**files, **{f"sql/migrations/{k}": v for k, v in selected.items()}}.items():
            target = package / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        (package / "artifacts/RELEASE").write_bytes((release_id + "\n").encode("utf-8"))
        packaged_files = {p.relative_to(package).as_posix(): p for p in package.rglob("*") if p.is_file()}
        write_manifest(package, packaged_files)
        with tarfile.open(staging / "prod.tar.gz", "w:gz") as archive:
            for path in sorted(package.rglob("*")):
                if path.is_file():
                    archive.add(path, arcname="prod/" + path.relative_to(package).as_posix(), recursive=False)
        write_manifest(prod, files)
        # mkdir 独占版本名，防止两个并行打包进程覆盖同一个版本。
        destination.mkdir()
        try:
            (staging / "prod").replace(destination / "prod")
            (staging / "prod.tar.gz").replace(destination / "prod.tar.gz")
        except OSError:
            shutil.rmtree(destination)
            raise
    return destination


def main() -> None:
    prod = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest-only", action="store_true",
                        help="只更新 prod 的版本和校验清单，不复制发布目录或生成压缩包")
    parser.add_argument("--release-id", help="默认为 artifacts/RELEASE 中的版本")
    parser.add_argument("--migration", action="append", default=[], metavar="SQL文件名",
                        help="从根目录 sql/migrations 选择文件；只打包，不执行，可重复指定")
    args = parser.parse_args()
    if args.manifest_only and args.migration:
        parser.error("--manifest-only 不接受 --migration；请先将需要携带的 SQL 放入 prod/sql/migrations 后生成清单")
    release_id = args.release_id or (prod / "artifacts/RELEASE").read_text(encoding="utf-8-sig").strip()
    migrations = []
    for name in args.migration:
        if Path(name).name != name or "/" in name or "\\" in name:
            parser.error("--migration 只能传入文件名，不能传入路径")
        migrations.append(prod.parent / "sql/migrations" / name)
    try:
        if args.manifest_only:
            prepare_manifest(prod, release_id)
            print(f"产物目录: {prod}")
            print(f"校验清单: {prod / 'artifacts/SHA256SUMS'}")
            print("未生成发布副本或压缩包，可上传上述 prod 目录；保留服务器已有 .env。")
            return
        release = build_release(prod, release_id, prod.parent / ".codex/releases", migrations)
    except (OSError, ValueError) as error:
        parser.exit(1, f"打包失败: {error}\n")
    print(f"发布目录: {release / 'prod'}")
    print(f"上传压缩包: {release / 'prod.tar.gz'}")


if __name__ == "__main__":
    main()
