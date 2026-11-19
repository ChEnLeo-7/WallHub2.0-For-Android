#!/usr/bin/env python3
"""Generate or verify the local Steam Content SDK AAR provenance lock."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
import zipfile

sys.dont_write_bytecode = True
_NATIVE_SPEC = importlib.util.spec_from_file_location(
    "android_native_packaging",
    Path(__file__).with_name("android-native-packaging.py"),
)
if _NATIVE_SPEC is None or _NATIVE_SPEC.loader is None:
    raise RuntimeError("cannot load android-native-packaging.py")
_NATIVE_MODULE = importlib.util.module_from_spec(_NATIVE_SPEC)
_NATIVE_SPEC.loader.exec_module(_NATIVE_MODULE)
ValidationError = _NATIVE_MODULE.ValidationError
check_locked_aar = _NATIVE_MODULE.check_locked_aar

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "config/steam-content-sdk.lock.json"
BUILD_FILE = ROOT / "app/build.gradle.kts"
COORDINATE_PATTERN = re.compile(
    r'implementation\("(io\.steamcontent\.sdk:sc-content-android-preview:([^"()]+))"\)'
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
PLACEHOLDER = "PLACEHOLDER_"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validated_native_hashes(aar: Path) -> dict[str, str]:
    try:
        return check_locked_aar(aar)
    except (OSError, zipfile.BadZipFile, ValidationError) as error:
        fail(str(error))


def configured_coordinate() -> tuple[str, str]:
    match = COORDINATE_PATTERN.search(BUILD_FILE.read_text(encoding="utf-8"))
    if not match:
        fail("app/build.gradle.kts does not contain exactly formatted SDK preview dependency")
    return match.group(1), match.group(2)


def staged_artifact(version: str, extension: str) -> Path:
    name = f"sc-content-android-preview-{version}.{extension}"
    return ROOT / "sc-sdk-maven/repository/io/steamcontent/sdk/sc-content-android-preview" / version / name


def sdk_commit(sdk_repo: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(sdk_repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    commit = result.stdout.strip()
    if not COMMIT_PATTERN.fullmatch(commit):
        fail(f"unexpected SDK commit value: {commit}")
    return commit


def git_output(sdk_repo: Path, *args: str) -> bytes:
    return subprocess.run(
        ["git", "-C", str(sdk_repo), *args],
        check=True,
        capture_output=True,
    ).stdout


def sdk_source(sdk_repo: Path) -> dict[str, str]:
    commit = sdk_commit(sdk_repo)
    status = git_output(sdk_repo, "status", "--porcelain=v1", "--untracked-files=all")
    if not status:
        return {"commit": commit, "state": "clean"}
    digest = hashlib.sha256()
    digest.update(commit.encode("ascii"))
    digest.update(b"\0tracked\0")
    digest.update(git_output(sdk_repo, "diff", "--binary", "HEAD"))
    untracked = git_output(sdk_repo, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    for raw_path in sorted(path for path in untracked if path):
        relative = raw_path.decode("utf-8", errors="surrogateescape")
        digest.update(b"\0untracked\0")
        digest.update(raw_path)
        digest.update(b"\0")
        digest.update((sdk_repo / relative).read_bytes())
    return {"commit": commit, "state": "dirty-candidate", "treeSha256": digest.hexdigest()}


def verify_pom_coordinate(pom: Path, coordinate: str) -> None:
    try:
        root = ElementTree.parse(pom).getroot()
    except (OSError, ElementTree.ParseError) as error:
        fail(f"cannot parse staged POM {pom}: {error}")
    namespace = root.tag.removesuffix("project")
    values = []
    for name in ("groupId", "artifactId", "version"):
        element = root.find(f"{namespace}{name}")
        values.append(element.text.strip() if element is not None and element.text else "")
    actual = ":".join(values)
    if actual != coordinate:
        fail(f"POM coordinate {actual!r} does not match build {coordinate!r}")


def load_lock() -> dict[str, object]:
    try:
        value = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {LOCK_PATH.relative_to(ROOT)}: {error}")
    if not isinstance(value, dict):
        fail("lock root must be an object")
    return value


def verify_shape(lock: dict[str, object], coordinate: str) -> bool:
    if lock.get("schemaVersion") != 2:
        fail("unsupported lock schemaVersion")
    if lock.get("coordinate") != coordinate:
        fail(f"lock coordinate {lock.get('coordinate')!r} does not match build {coordinate!r}")
    source = lock.get("sdkSource")
    if not isinstance(source, dict):
        fail("sdkSource must be an object")
    commit = source.get("commit")
    state = source.get("state")
    if not isinstance(commit, str) or not COMMIT_PATTERN.fullmatch(commit):
        fail("sdkSource.commit must be a lowercase 40-character git object id")
    if state not in {"clean", "dirty-candidate"}:
        fail("sdkSource.state must be clean or dirty-candidate")
    tree_hash = source.get("treeSha256")
    if state == "dirty-candidate" and (not isinstance(tree_hash, str) or not SHA256_PATTERN.fullmatch(tree_hash)):
        fail("dirty-candidate sdkSource requires a lowercase treeSha256")
    if state == "clean" and tree_hash is not None:
        fail("clean sdkSource must not contain treeSha256")
    aar_hash = lock.get("aarSha256")
    if not isinstance(aar_hash, str):
        fail("aarSha256 must be a string")
    pom_hash = lock.get("pomSha256")
    if not isinstance(pom_hash, str):
        fail("pomSha256 must be a string")
    libraries = lock.get("nativeLibraries")
    if not isinstance(libraries, dict) or not libraries:
        fail("nativeLibraries must be a non-empty object")
    placeholder = (
        commit.startswith(PLACEHOLDER)
        or aar_hash.startswith(PLACEHOLDER)
        or pom_hash.startswith(PLACEHOLDER)
    )
    for name, digest in libraries.items():
        if not isinstance(name, str) or not isinstance(digest, str):
            fail("nativeLibraries keys and values must be strings")
        placeholder = placeholder or digest.startswith(PLACEHOLDER)
        if not digest.startswith(PLACEHOLDER) and not SHA256_PATTERN.fullmatch(digest):
            fail(f"invalid SHA-256 for native library {name}")
    if not aar_hash.startswith(PLACEHOLDER) and not SHA256_PATTERN.fullmatch(aar_hash):
        fail("aarSha256 must be lowercase SHA-256 or an explicit placeholder")
    if not pom_hash.startswith(PLACEHOLDER) and not SHA256_PATTERN.fullmatch(pom_hash):
        fail("pomSha256 must be lowercase SHA-256 or an explicit placeholder")
    return placeholder


def generate(args: argparse.Namespace) -> None:
    coordinate, version = configured_coordinate()
    aar = Path(args.aar) if args.aar else staged_artifact(version, "aar")
    pom = Path(args.pom) if args.pom else (aar.with_suffix(".pom") if args.aar else staged_artifact(version, "pom"))
    sdk_repo = Path(args.sdk_repo)
    if not aar.is_file():
        fail(f"AAR not found: {aar}; pass --aar to the LAN-produced artifact")
    if not pom.is_file():
        fail(f"POM not found: {pom}; pass --pom to the LAN-produced metadata")
    verify_pom_coordinate(pom, coordinate)
    lock = {
        "schemaVersion": 2,
        "coordinate": coordinate,
        "sdkSource": sdk_source(sdk_repo),
        "aarSha256": sha256_file(aar),
        "pomSha256": sha256_file(pom),
        "nativeLibraries": validated_native_hashes(aar),
    }
    LOCK_PATH.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"SDK_ARTIFACT_LOCK_GENERATED={LOCK_PATH.relative_to(ROOT)}")


def verify(args: argparse.Namespace) -> None:
    coordinate, version = configured_coordinate()
    lock = load_lock()
    placeholder = verify_shape(lock, coordinate)
    aar = Path(args.aar) if args.aar else staged_artifact(version, "aar")
    pom = Path(args.pom) if args.pom else (aar.with_suffix(".pom") if args.aar else staged_artifact(version, "pom"))
    if placeholder:
        if args.require_complete:
            fail("SDK artifact lock contains placeholders; generate it from the LAN-produced AAR and POM")
        print("SDK_ARTIFACT_LOCK=PLACEHOLDER (generate it from LAN artifacts before building)")
        return
    if not aar.is_file():
        fail(f"locked AAR not found: {aar}; pass --aar to its local path")
    if not pom.is_file():
        fail(f"locked POM not found: {pom}; pass --pom to its local path")
    verify_pom_coordinate(pom, coordinate)
    actual_aar = sha256_file(aar)
    if actual_aar != lock["aarSha256"]:
        fail(f"AAR SHA-256 mismatch: expected {lock['aarSha256']}, got {actual_aar}")
    actual_pom = sha256_file(pom)
    if actual_pom != lock["pomSha256"]:
        fail(f"POM SHA-256 mismatch: expected {lock['pomSha256']}, got {actual_pom}")
    actual_libraries = validated_native_hashes(aar)
    if actual_libraries != lock["nativeLibraries"]:
        fail("native library set or SHA-256 differs from the lock")
    expected_source = lock["sdkSource"]
    if args.sdk_repo:
        actual_source = sdk_source(Path(args.sdk_repo))
        if actual_source != expected_source:
            fail(f"SDK source mismatch: expected {expected_source}, got {actual_source}")
    elif args.sdk_commit_file:
        if expected_source["state"] != "clean":
            fail("dirty SDK candidate requires --sdk-repo; a static commit file cannot prove its source")
        commit_file = Path(args.sdk_commit_file)
        if not commit_file.is_file():
            fail(f"SDK commit provenance file not found: {commit_file}")
        actual_commit = commit_file.read_text(encoding="utf-8").strip()
        if len(actual_commit) != 40 or any(char not in "0123456789abcdef" for char in actual_commit):
            fail("SDK commit provenance must be one lowercase 40-hex commit")
        if actual_commit != expected_source["commit"]:
            fail(f"SDK commit mismatch: expected {expected_source['commit']}, got {actual_commit}")
    print("SDK_ARTIFACT_LOCK=OK")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    generate_parser = subparsers.add_parser("generate")
    generate_parser.add_argument("--aar")
    generate_parser.add_argument("--pom")
    generate_parser.add_argument("--sdk-repo", required=True)
    generate_parser.set_defaults(handler=generate)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--aar")
    verify_parser.add_argument("--pom")
    verify_parser.add_argument("--sdk-repo")
    verify_parser.add_argument("--sdk-commit-file")
    verify_parser.add_argument("--require-complete", action="store_true")
    verify_parser.set_defaults(handler=verify)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
