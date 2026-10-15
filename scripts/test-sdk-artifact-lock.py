#!/usr/bin/env python3
"""Pure-Python regression seam for the staged SDK AAR/POM artifact lock."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/sdk-artifact-lock.py"


def load_module():
    spec = importlib.util.spec_from_file_location("sdk_artifact_lock", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", str(SCRIPT), *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def expect_failure(result: subprocess.CompletedProcess[str], text: str) -> None:
    assert result.returncode != 0, result.stdout
    assert text in result.stderr, result.stderr


def main() -> None:
    lock_module = load_module()
    coordinate, version = lock_module.configured_coordinate()
    lock = lock_module.load_lock()
    assert coordinate.endswith(":" + version)
    assert version == lock["coordinate"].rsplit(":", 1)[1]
    lock_module.verify_shape(lock, coordinate)

    with tempfile.TemporaryDirectory(prefix="sdk-artifact-lock-") as temporary:
        root = Path(temporary)
        aar = root / f"sc-content-android-preview-{version}.aar"
        pom = aar.with_suffix(".pom")
        with zipfile.ZipFile(aar, "w") as archive:
            archive.writestr("jni/arm64-v8a/libsc_content_ffi.so", b"synthetic-native-library")
        pom.write_text(
            "<project><groupId>io.steamcontent.sdk</groupId>"
            f"<artifactId>sc-content-android-preview</artifactId><version>{version}</version></project>\n",
            encoding="utf-8",
        )
        complete = {
            "schemaVersion": 1,
            "coordinate": coordinate,
            "sdkCommit": "0" * 40,
            "aarSha256": lock_module.sha256_file(aar),
            "pomSha256": lock_module.sha256_file(pom),
            "nativeLibraries": lock_module.native_hashes(aar),
        }
        original_lock = lock_module.LOCK_PATH
        try:
            lock_module.LOCK_PATH = root / "lock.json"
            lock_module.LOCK_PATH.write_text(json.dumps(complete), encoding="utf-8")
            args = type(
                "Args",
                (),
                {
                    "aar": str(aar),
                    "pom": str(pom),
                    "sdk_repo": None,
                    "sdk_commit_file": None,
                    "require_complete": True,
                },
            )()
            lock_module.verify(args)
            pom.write_text(
                "<project><groupId>io.steamcontent.sdk</groupId>"
                f"<artifactId>sc-content-android-preview</artifactId><version>{version}</version>"
                "<description>tampered</description></project>\n",
                encoding="utf-8",
            )
            try:
                lock_module.verify(args)
            except SystemExit as error:
                assert error.code == 1
            else:
                raise AssertionError("tampered POM passed verification")
        finally:
            lock_module.LOCK_PATH = original_lock

    print("SDK_ARTIFACT_LOCK_TEST=OK")


if __name__ == "__main__":
    main()
