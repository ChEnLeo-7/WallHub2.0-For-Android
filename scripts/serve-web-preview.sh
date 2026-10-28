#!/usr/bin/env bash
set -euo pipefail

mode="${1:-production}"
port="${WALLHUB_WEB_PORT:-4180}"
case "$mode" in
    development)
        output="preview/web/build/dist/wasmJs/developmentExecutable"
        ;;
    production)
        output="preview/web/build/dist/wasmJs/productionExecutable"
        ;;
    *)
        printf 'Usage: %s [development|production]\n' "$0" >&2
        exit 2
        ;;
esac

[[ -f "$output/index.html" ]] || {
    printf 'ERROR: %s is not built. Run scripts/build-web-preview.sh %s first.\n' "$output" "$mode" >&2
    exit 1
}

printf 'Serving WallHub %s Web preview from %s on 0.0.0.0:%s\n' "$mode" "$output" "$port"
exec python3 scripts/web-preview-server.py \
    --port "$port" \
    --bind 0.0.0.0 \
    --directory "$output"
