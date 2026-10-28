#!/usr/bin/env bash
set -euo pipefail

readonly NODE_VERSION="v24.18.0"
readonly NPM_VERSION="11.16.0"
readonly BINARYEN_VERSION="125"
readonly BINARYEN_ARCHIVE_SHA256="7c3bc16599c8274a04d34a504fe4be2047884f900e0e2da2f6fb9cd667183be4"
readonly BINARYEN_EXECUTABLE_SHA256="23c1f4106e34eeaf44e888f947671f04d0fec12c232cca19cc5c4b3cf7c96f08"
readonly BINARYEN_URL="https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz"
readonly CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/wallhub"
readonly BINARYEN_ROOT="$CACHE_ROOT/binaryen-$BINARYEN_VERSION"
readonly BINARYEN_ARCHIVE="$CACHE_ROOT/binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz"
readonly TRANSFER_BUDGET_BYTES=$((8 * 1024 * 1024))

read_oom_kills() {
    awk '/oom_kill / { print $2 }' /sys/fs/cgroup/memory.events 2>/dev/null || printf 'unknown'
}

mode="${1:-production}"
case "$mode" in
    development)
        task=":preview:web:wasmJsBrowserDevelopmentExecutableDistribution"
        output="preview/web/build/dist/wasmJs/developmentExecutable"
        ;;
    production)
        task=":preview:web:wasmJsBrowserDistribution"
        output="preview/web/build/dist/wasmJs/productionExecutable"
        ;;
    *)
        printf 'Usage: %s [development|production]\n' "$0" >&2
        exit 2
        ;;
esac

command -v node >/dev/null || {
    printf 'ERROR: Node.js is required for the Web preview build.\n' >&2
    exit 1
}
[[ "$(node --version)" == "$NODE_VERSION" ]] || {
    printf 'ERROR: Node.js %s is required; found %s.\n' "$NODE_VERSION" "$(node --version)" >&2
    exit 1
}
[[ "$(npm --version)" == "$NPM_VERSION" ]] || {
    printf 'ERROR: npm %s is required; found %s.\n' "$NPM_VERSION" "$(npm --version)" >&2
    exit 1
}

if [[ "$mode" == "production" && ! -x "$BINARYEN_ROOT/bin/wasm-opt" ]]; then
    mkdir -p "$CACHE_ROOT" "$BINARYEN_ROOT"
    if [[ ! -f "$BINARYEN_ARCHIVE" ]] ||
        [[ "$(sha256sum "$BINARYEN_ARCHIVE" | awk '{print $1}')" != "$BINARYEN_ARCHIVE_SHA256" ]]; then
        partial="$BINARYEN_ARCHIVE.partial"
        curl --location --fail --show-error --silent --retry 5 --retry-all-errors \
            --continue-at - --output "$partial" "$BINARYEN_URL"
        mv "$partial" "$BINARYEN_ARCHIVE"
    fi
    echo "$BINARYEN_ARCHIVE_SHA256  $BINARYEN_ARCHIVE" | sha256sum --check --status || {
        printf 'ERROR: Binaryen archive hash does not match the pinned value.\n' >&2
        exit 1
    }
    tar -xzf "$BINARYEN_ARCHIVE" --strip-components=1 --overwrite -C "$BINARYEN_ROOT"
fi
if [[ "$mode" == "production" ]]; then
    [[ "$($BINARYEN_ROOT/bin/wasm-opt --version)" == "wasm-opt version $BINARYEN_VERSION (version_$BINARYEN_VERSION)" ]] || {
        printf 'ERROR: Cached Binaryen version is not %s.\n' "$BINARYEN_VERSION" >&2
        exit 1
    }
    echo "$BINARYEN_EXECUTABLE_SHA256  $BINARYEN_ROOT/bin/wasm-opt" | sha256sum --check --status || {
        printf 'ERROR: Cached Binaryen executable hash mismatch.\n' >&2
        exit 1
    }
fi

before_oom="$(read_oom_kills)"
args=(
    "$task"
    --no-daemon
    --no-parallel
    --max-workers=1
    "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
    -Pkotlin.compiler.execution.strategy=in-process
)

env_args=("WALLHUB_NODE=$(command -v node)")
if [[ "$mode" == "production" ]]; then
    env_args+=("WALLHUB_WASM_OPT=$BINARYEN_ROOT/bin/wasm-opt")
fi

build_status=0
env "${env_args[@]}" ./gradlew "${args[@]}" || build_status=$?
after_oom="$(read_oom_kills)"
if [[ "$before_oom" != "unknown" && "$after_oom" != "$before_oom" ]]; then
    printf 'ERROR: Web preview build changed oom_kill from %s to %s.\n' "$before_oom" "$after_oom" >&2
    exit 1
fi
if ((build_status != 0)); then
    exit "$build_status"
fi

if [[ "$mode" == "production" ]]; then
    transfer_bytes="$(python3 - "$output" <<'PY'
from pathlib import Path
import gzip
import sys

root = Path(sys.argv[1])
total = 0
for path in sorted(root.rglob("*")):
    if not path.is_file() or path.suffix in {".gz", ".map", ".txt"}:
        continue
    compressed = gzip.compress(path.read_bytes(), compresslevel=9, mtime=0)
    path.with_name(path.name + ".gz").write_bytes(compressed)
    total += len(compressed)
print(total)
PY
)"
    if ((transfer_bytes > TRANSFER_BUDGET_BYTES)); then
        printf 'ERROR: Production transfer size %s exceeds budget %s.\n' \
            "$transfer_bytes" "$TRANSFER_BUDGET_BYTES" >&2
        exit 1
    fi
    printf 'WEB_PREVIEW_TRANSFER_BYTES=%s budget=%s\n' "$transfer_bytes" "$TRANSFER_BUDGET_BYTES"
fi

printf 'WEB_PREVIEW_BUILD=OK mode=%s output=%s oom_kill=%s\n' "$mode" "$output" "$after_oom"
