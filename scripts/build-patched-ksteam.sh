#!/usr/bin/env bash
set -euo pipefail

readonly KSTEAM_PINNED_SHA="c6ca6ef389d65c5223b2af9bf422ac830a0a2f32"
readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
readonly OUTPUT_DIR="$ROOT_DIR/build/ksteam-patched"
readonly KSTEAM_DIR="$OUTPUT_DIR/source"

python_command=""
for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
        python_command="$candidate"
        break
    fi
done
if [[ -z "$python_command" ]] && command -v py >/dev/null 2>&1; then
    python_command="py -3"
fi
[[ -n "$python_command" ]] || {
    printf 'Python 3 is required to prepare patched kSteam.\n' >&2
    exit 1
}

mkdir -p "$OUTPUT_DIR"
if [[ ! -d "$KSTEAM_DIR/.git" ]]; then
    rm -rf "$KSTEAM_DIR"
    git clone --no-checkout https://github.com/iTaysonLab/kSteam.git "$KSTEAM_DIR"
fi

git -C "$KSTEAM_DIR" fetch --depth 1 origin "$KSTEAM_PINNED_SHA"
git -C "$KSTEAM_DIR" reset --hard --quiet FETCH_HEAD
git -C "$KSTEAM_DIR" submodule sync --quiet
git -C "$KSTEAM_DIR" submodule update --init --force --depth 1

cd "$KSTEAM_DIR"
$python_command "$ROOT_DIR/scripts/patch-ksteam-auth-flow.py"

# Follow the JDK already installed on the persistent LAN worker instead of
# downloading a second toolchain solely for this one-time dependency build.
$python_command - <<'PY'
from pathlib import Path

path = Path("build-extensions/src/main/kotlin/CoreMppSetup.kt")
text = path.read_text(encoding="utf-8")
path.write_text(text.replace("    jvmToolchain(17)\n", ""), encoding="utf-8")
PY

if [[ -n "${WALLHUB_ANDROID_SDK:-}" ]]; then
    sdk_path="$WALLHUB_ANDROID_SDK"
elif [[ -d "/f/AI-Studio/.android-sdk" ]]; then
    sdk_path='F:\\AI-Studio\\.android-sdk'
else
    sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
fi
if [[ -n "$sdk_path" ]]; then
    printf 'sdk.dir=%s\n' "$sdk_path" > local.properties
fi

chmod +x gradlew
./gradlew --no-daemon publishToMavenLocal
