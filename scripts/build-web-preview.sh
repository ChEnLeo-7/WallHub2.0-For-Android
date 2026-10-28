#!/usr/bin/env bash
set -euo pipefail

readonly BINARYEN_VERSION="125"
readonly BINARYEN_ARCHIVE_SHA256="7c3bc16599c8274a04d34a504fe4be2047884f900e0e2da2f6fb9cd667183be4"
readonly BINARYEN_URL="https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz"
readonly CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/wallhub"
readonly BINARYEN_ROOT="$CACHE_ROOT/binaryen-$BINARYEN_VERSION"
readonly BINARYEN_ARCHIVE="$CACHE_ROOT/binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz"

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

if [[ "$mode" == "production" && ! -x "$BINARYEN_ROOT/bin/wasm-opt" ]]; then
    mkdir -p "$CACHE_ROOT"
    if [[ ! -f "$BINARYEN_ARCHIVE" ]] ||
        [[ "$(sha256sum "$BINARYEN_ARCHIVE" | awk '{print $1}')" != "$BINARYEN_ARCHIVE_SHA256" ]]; then
        rm -f "$BINARYEN_ARCHIVE"
        curl --location --fail --show-error --silent --retry 5 --retry-all-errors \
            --output "$BINARYEN_ARCHIVE" "$BINARYEN_URL"
    fi
    echo "$BINARYEN_ARCHIVE_SHA256  $BINARYEN_ARCHIVE" | sha256sum --check --status || {
        printf 'ERROR: Binaryen archive hash does not match the pinned value.\n' >&2
        exit 1
    }
    rm -rf "$BINARYEN_ROOT"
    mkdir -p "$BINARYEN_ROOT"
    tar -xzf "$BINARYEN_ARCHIVE" --strip-components=1 -C "$BINARYEN_ROOT"
fi

before_oom="$(awk '/oom_kill / { print $2 }' /sys/fs/cgroup/memory.events 2>/dev/null || printf 'unknown')"
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

env "${env_args[@]}" ./gradlew "${args[@]}"
after_oom="$(awk '/oom_kill / { print $2 }' /sys/fs/cgroup/memory.events 2>/dev/null || printf 'unknown')"
if [[ "$before_oom" != "unknown" && "$after_oom" != "$before_oom" ]]; then
    printf 'ERROR: Web preview build changed oom_kill from %s to %s.\n' "$before_oom" "$after_oom" >&2
    exit 1
fi

printf 'WEB_PREVIEW_BUILD=OK mode=%s output=%s oom_kill=%s\n' "$mode" "$output" "$after_oom"
