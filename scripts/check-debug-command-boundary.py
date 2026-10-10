#!/usr/bin/env python3
"""Source-set regression check for the shell-only debug command receiver."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
ACTIONS = {
    "com.wallhub.android.debug.OPEN_ONLINE_VIDEO",
    "com.wallhub.android.debug.SEEK_ONLINE_VIDEO",
}
RECEIVER = "com.wallhub.android.debug.DebugVideoCommandReceiver"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def source_text(source_set: str) -> str:
    root = ROOT / "app" / "src" / source_set
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.rglob("*"))
        if path.is_file() and path.suffix in {".kt", ".java", ".xml"}
    )


debug_manifest = ROOT / "app/src/debug/AndroidManifest.xml"
if not debug_manifest.is_file():
    fail("debug manifest is missing")

root = ET.parse(debug_manifest).getroot()
receivers = root.findall("./application/receiver")
receiver = next((item for item in receivers if item.get(ANDROID + "name") == RECEIVER), None)
if receiver is None:
    fail("debug receiver is not declared in the debug source set")
if receiver.get(ANDROID + "exported") != "true":
    fail("debug receiver must be exported for explicit adb shell broadcasts")
if receiver.get(ANDROID + "permission") != "android.permission.DUMP":
    fail("debug receiver must require android.permission.DUMP")
manifest_actions = {
    action.get(ANDROID + "name")
    for action in receiver.findall("./intent-filter/action")
}
if manifest_actions != ACTIONS:
    fail(f"debug receiver actions differ from the expected set: {manifest_actions}")

main_text = source_text("main")
for forbidden in ACTIONS | {RECEIVER, "android.permission.DUMP"}:
    if forbidden in main_text:
        fail(f"release-bearing main source set contains debug exposure marker: {forbidden}")

release_root = ROOT / "app/src/release"
if release_root.exists():
    release_text = source_text("release")
    for forbidden in ACTIONS | {RECEIVER}:
        if forbidden in release_text:
            fail(f"release source set contains debug exposure marker: {forbidden}")

receiver_source = ROOT / "app/src/debug/kotlin/com/wallhub/android/debug/DebugVideoCommandReceiver.kt"
receiver_text = receiver_source.read_text(encoding="utf-8")
if "DebugVideoCommandBus.open" not in receiver_text or "DebugVideoCommandBus.seek" not in receiver_text:
    fail("debug receiver does not dispatch through the internal command bus")
if "MainActivity" in receiver_text and "DebugVideoCommandBus.accept" in receiver_text:
    fail("debug receiver routes untrusted intents through MainActivity")

print("DEBUG_COMMAND_BOUNDARY=OK")
