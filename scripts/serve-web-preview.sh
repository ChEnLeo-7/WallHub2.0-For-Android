#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="preview/web-dom"
readonly OUTPUT="$ROOT/dist"
port="${WALLHUB_WEB_PORT:-4180}"
[[ -f "$OUTPUT/index.html" && -f "$OUTPUT/.wallhub-source-sha" ]] || {
    printf 'ERROR: %s is not built. Run scripts/build-web-preview.sh first.\n' "$OUTPUT" >&2
    exit 1
}
expected_hash="$(python3 scripts/web-preview-source-hash.py)"
actual_hash="$(tr -d '\r\n' < "$OUTPUT/.wallhub-source-sha")"
[[ "$actual_hash" == "$expected_hash" ]] || {
    printf 'ERROR: Web preview output is stale (%s != %s). Rebuild first.\n' "$actual_hash" "$expected_hash" >&2
    exit 1
}

printf 'Serving WallHub Material Web DOM preview from %s on 0.0.0.0:%s\n' "$OUTPUT" "$port"
exec python3 scripts/web-preview-server.py \
    --port "$port" \
    --bind 0.0.0.0 \
    --directory "$ROOT" \
    --spa-entry dist/index.html
