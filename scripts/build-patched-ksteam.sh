#!/usr/bin/env bash
set -euo pipefail

readonly KSTEAM_PINNED_SHA="c6ca6ef389d65c5223b2af9bf422ac830a0a2f32"
readonly PATCH_STAMP="${KSTEAM_PINNED_SHA}:cm-failover-v1"
readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
readonly OUTPUT_DIR="$ROOT_DIR/build/ksteam-patched"
readonly KSTEAM_DIR="$OUTPUT_DIR/source"
readonly STAMP_FILE="$OUTPUT_DIR/stamp"

python_command=""
for candidate in python3 python 'py -3'; do
    if [[ "$candidate" == "py -3" ]]; then
        if command -v py >/dev/null 2>&1; then
            python_command="py -3"
            break
        fi
    elif command -v "$candidate" >/dev/null 2>&1; then
        python_command="$candidate"
        break
    fi
done
[[ -n "$python_command" ]] || {
    printf 'Python 3 is required to prepare patched kSteam.\n' >&2
    exit 1
}

if [[ -f "$STAMP_FILE" ]] && [[ "$(<"$STAMP_FILE")" == "$PATCH_STAMP" ]]; then
    exit 0
fi

mkdir -p "$OUTPUT_DIR"
if [[ -d "$KSTEAM_DIR/.git" ]]; then
    rm -rf "$KSTEAM_DIR"
fi
if [[ ! -d "$KSTEAM_DIR/.git" ]]; then
    git clone --no-checkout https://github.com/iTaysonLab/kSteam.git "$KSTEAM_DIR"
fi

git -C "$KSTEAM_DIR" fetch --depth 1 origin "$KSTEAM_PINNED_SHA"
git -C "$KSTEAM_DIR" checkout --quiet FETCH_HEAD

cd "$KSTEAM_DIR"
$python_command "$ROOT_DIR/scripts/patch-ksteam-auth-flow.py"
git submodule update --init --depth 1

if [[ -n "${WALLHUB_ANDROID_SDK:-}" ]]; then
    sdk_path="$WALLHUB_ANDROID_SDK"
elif [[ -d "/f/AI-Studio/.android-sdk" ]]; then
    sdk_path="/f/AI-Studio/.android-sdk"
else
    sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
fi
if [[ -n "$sdk_path" ]]; then
    printf 'sdk.dir=%s\n' "$sdk_path" > local.properties
fi

chmod +x gradlew

# kSteam's pinned proto tree needs the same deterministic repair used by CI.
./gradlew --no-daemon :proto-common:upgradeProtoFiles
$python_command - <<'PYFIX'
import os
import re
import subprocess

root = "proto-common/src/commonMain/proto"
stub_path = os.path.join(root, "webui", "zz_ci_stubs.proto")


def proto_files():
    return [
        os.path.join(base, name)
        for base, _, names in os.walk(root)
        for name in names
        if name.endswith(".proto")
    ]


def existing_stubs():
    if not os.path.exists(stub_path):
        return set()
    with open(stub_path, encoding="utf-8", errors="ignore") as handle:
        return set(re.findall(r"^message ([A-Za-z][A-Za-z0-9_]*) \{", handle.read(), re.M))


def fix_once():
    proc = subprocess.run(
        ["./gradlew", "--no-daemon", ":proto-common:generateCommonMainProtos", "--stacktrace"],
        capture_output=True,
        text=True,
    )
    output = proc.stdout + proc.stderr
    changed = 0
    for target, source in re.findall(r"([\w/]+\.proto) needs to import ([\w/]+\.proto)", output):
        path = os.path.join(root, target)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        if ('import "%s";' % source) in text:
            continue
        lines = text.splitlines(keepends=True)
        at = next((index + 1 for index, line in enumerate(lines) if line.startswith("syntax")), 0)
        lines[at:at] = ['import "%s";\n' % source]
        with open(path, "w", encoding="utf-8") as handle:
            handle.writelines(lines)
        changed += 1
    stubbed = existing_stubs()
    fresh = sorted(set(re.findall(r"unable to resolve \.([A-Za-z][A-Za-z0-9_]*)", output)) - stubbed)
    if fresh:
        with open(stub_path, "a", encoding="utf-8") as handle:
            if not stubbed:
                handle.write("// CI-generated stubs for SteamDatabase types outside kSteam's webui pipeline.\n")
                handle.write('syntax = "proto2";\n\n')
            for name in fresh:
                handle.write("message %s {}\n" % name)
        changed += 1
    return proc.returncode == 0, changed, output


for attempt in range(8):
    ok, changed, output = fix_once()
    if ok:
        break
    if changed == 0:
        print(output)
        raise SystemExit("kSteam proto repair did not converge")
else:
    raise SystemExit("kSteam proto repair exceeded retry limit")
PYFIX

./gradlew --no-daemon publishToMavenLocal
printf '%s\n' "$PATCH_STAMP" > "$STAMP_FILE"
