#!/usr/bin/env bash
set -euo pipefail

readonly NODE_VERSION="v24.18.0"
readonly NPM_VERSION="11.16.0"
readonly BASE="preview/web-dom"
readonly RELEASES="$BASE/releases"
readonly OUTPUT="$BASE/dist"
readonly TRANSFER_BUDGET_BYTES=$((8 * 1024 * 1024))

[[ "$(node --version)" == "$NODE_VERSION" ]] || {
    printf 'ERROR: Node.js %s is required; found %s.\n' "$NODE_VERSION" "$(node --version)" >&2
    exit 1
}
[[ "$(npm --version)" == "$NPM_VERSION" ]] || {
    printf 'ERROR: npm %s is required; found %s.\n' "$NPM_VERSION" "$(npm --version)" >&2
    exit 1
}

node scripts/sync-web-ui-contract.mjs
source_hash="$(python3 scripts/web-preview-source-hash.py)"
mkdir -p "$RELEASES"
staging="$RELEASES/.${source_hash}.tmp.$$"
release="$RELEASES/$source_hash"
next_link="$BASE/.dist.next.$$"
legacy="$BASE/.dist.legacy.$$"
cleanup() {
    [[ "$staging" == "$RELEASES"/.????????????????????????????????????????????????????????????????.tmp.* ]] && rm -rf -- "$staging"
    [[ "$next_link" == "$BASE"/.dist.next.* ]] && rm -f -- "$next_link"
}
trap cleanup EXIT

npm ci --ignore-scripts --no-audit --no-fund --prefix "$BASE"
mkdir "$staging"
WALLHUB_OUTPUT_DIR="$(realpath "$staging")" \
WALLHUB_SOURCE_HASH="$source_hash" \
    npm run build --prefix "$BASE"

transfer_bytes="$(python3 - "$staging" <<'PY'
from pathlib import Path
import gzip
import sys

root = Path(sys.argv[1])
total = 0
for path in sorted(root.rglob("*")):
    if not path.is_file() or path.suffix in {".gz", ".map"} or path.name.startswith("."):
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

if [[ -e "$release" ]]; then
    rm -rf -- "$staging"
else
    mv -- "$staging" "$release"
fi
ln -s "releases/$source_hash" "$next_link"
if [[ -d "$OUTPUT" && ! -L "$OUTPUT" ]]; then
    mv -- "$OUTPUT" "$legacy"
    mv -Tf -- "$next_link" "$OUTPUT"
    [[ "$legacy" == "$BASE"/.dist.legacy.* ]] && rm -rf -- "$legacy"
else
    mv -Tf -- "$next_link" "$OUTPUT"
fi
python3 - "$RELEASES" "$source_hash" <<'PY'
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1]).resolve(strict=True)
active = sys.argv[2]
releases = sorted(
    (path for path in root.iterdir() if path.is_dir() and re.fullmatch(r"[0-9a-f]{64}", path.name)),
    key=lambda path: path.stat().st_mtime_ns,
    reverse=True,
)
keep = {active, *(path.name for path in releases[:3])}
for path in releases:
    if path.name not in keep and path.parent == root:
        shutil.rmtree(path)
PY
printf 'WEB_PREVIEW_BUILD=OK renderer=material-web-dom output=%s source=%s transfer_bytes=%s budget=%s\n' \
    "$OUTPUT" "$source_hash" "$transfer_bytes" "$TRANSFER_BUDGET_BYTES"
