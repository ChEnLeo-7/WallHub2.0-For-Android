#!/usr/bin/env bash
set -euo pipefail

serial="${WALLHUB_ADB_SERIAL:?Set WALLHUB_ADB_SERIAL to the Android Chrome device}"
host="${WALLHUB_WEB_HOST:-192.168.2.155}"
port="${WALLHUB_WEB_VERIFY_PORT:-4184}"
cdp_port="${WALLHUB_CDP_PORT:-9223}"
server_pid=""
cleanup() {
    [[ -n "$server_pid" ]] && kill "$server_pid" 2>/dev/null || true
    adb -s "$serial" forward --remove "tcp:$cdp_port" >/dev/null 2>&1 || true
}
trap cleanup EXIT

scripts/build-web-preview.sh
if python3 - "$port" <<'PY'
import socket
import sys
with socket.socket() as connection:
    raise SystemExit(0 if connection.connect_ex(("127.0.0.1", int(sys.argv[1]))) == 0 else 1)
PY
then
    printf 'ERROR: Verification port %s is already in use.\n' "$port" >&2
    exit 1
fi
WALLHUB_WEB_PORT="$port" scripts/serve-web-preview.sh >/tmp/wallhub-web-verify-server.log 2>&1 &
server_pid=$!
for _ in $(seq 1 50); do
    kill -0 "$server_pid" 2>/dev/null || {
        cat /tmp/wallhub-web-verify-server.log >&2
        exit 1
    }
    curl -fsS "http://127.0.0.1:$port/" >/dev/null 2>&1 && break
    sleep 0.1
done
html="$(curl -fsS "http://127.0.0.1:$port/")"
expected_hash="$(python3 scripts/web-preview-source-hash.py)"
grep -Fq "name=\"wallhub-source\" content=\"$expected_hash\"" <<<"$html" || {
    printf 'ERROR: Verification server did not serve source %s.\n' "$expected_hash" >&2
    exit 1
}
adb -s "$serial" forward "tcp:$cdp_port" localabstract:chrome_devtools_remote >/dev/null
adb -s "$serial" shell am start \
    -n com.android.chrome/com.google.android.apps.chrome.Main \
    -a android.intent.action.VIEW \
    -d "http://$host:$port/?acceptance=dom" >/dev/null
for _ in $(seq 1 50); do
    curl -fsS "http://127.0.0.1:$cdp_port/json/list" 2>/dev/null | grep -q "${host}:${port}" && break
    sleep 0.2
done
WALLHUB_CDP_PORT="$cdp_port" \
WALLHUB_WEB_ORIGIN="http://$host:$port" \
    node scripts/check-web-preview-dom.mjs
printf 'WEB_PREVIEW_VERIFY=OK serial=%s origin=http://%s:%s\n' "$serial" "$host" "$port"
