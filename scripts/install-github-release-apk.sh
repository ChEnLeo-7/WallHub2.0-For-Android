#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY="${WALLHUB_GITHUB_REPOSITORY:-ChEnLeo-7/WallHub2.0-For-Android}"
readonly WORKFLOW_FILE="${WALLHUB_GITHUB_WORKFLOW:-verify.yml}"
readonly APPLICATION_ID="com.wallhub.android"
readonly POLL_SECONDS=10
readonly TIMEOUT_SECONDS=3600

usage() {
    cat <<'EOF'
Usage: scripts/install-github-release-apk.sh [--serial <adb-serial>] [--sha <commit-sha>]

Waits for the successful GitHub Actions workflow run for a commit, downloads
only that run's signed Release APK artifact, verifies its checksum and signing
certificate, then installs it in place on one connected ADB device.

Defaults:
  --serial  Automatically select the only currently connected device.
            Required when zero or multiple devices are connected.
  --sha     the current HEAD commit

Environment:
  GITHUB_TOKEN                 Required for private repositories. A fine-grained
                               token needs Actions artifacts read access.
  WALLHUB_GITHUB_REPOSITORY    Defaults to ChEnLeo-7/WallHub2.0-For-Android.
  WALLHUB_GITHUB_WORKFLOW      Defaults to verify.yml.

The target is resolved immediately before installation, never from a hard-coded
address. The script refuses to uninstall the app or bypass signing mismatch
checks.
EOF
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

api_get() {
    local url="$1"
    local -a curl_args=(
        --fail
        --silent
        --show-error
        --location
        --header "Accept: application/vnd.github+json"
        --header "X-GitHub-Api-Version: 2022-11-28"
    )

    if [[ -n "${GITHUB_TOKEN:-}" ]]; then
        curl_args+=(--header "Authorization: Bearer $GITHUB_TOKEN")
    fi

    curl "${curl_args[@]}" "$url"
}

resolve_adb_target() {
    local requested_serial="$1"
    local candidate_serial
    local candidate_state
    local ignored
    local -a connected_serials=()

    while read -r candidate_serial candidate_state ignored; do
        [[ "$candidate_serial" == "List" || -z "$candidate_serial" ]] && continue
        [[ "$candidate_state" == "device" ]] && connected_serials+=("$candidate_serial")
    done < <(adb devices -l)

    if [[ -n "$requested_serial" ]]; then
        local adb_state
        adb_state="$(adb -s "$requested_serial" get-state 2>/dev/null || true)"
        if [[ "$adb_state" != "device" ]]; then
            adb devices -l >&2
            fail "Requested ADB target $requested_serial is not connected with state device"
        fi
        printf '%s\n' "$requested_serial"
        return
    fi

    case "${#connected_serials[@]}" in
        1)
            printf '%s\n' "${connected_serials[0]}"
            ;;
        0)
            adb devices -l >&2
            fail "No ADB target is connected with state device"
            ;;
        *)
            adb devices -l >&2
            fail "Multiple ADB targets are connected; rerun with --serial <adb-serial>"
            ;;
    esac
}

serial=""
sha="$(git rev-parse HEAD)"

while (($# > 0)); do
    case "$1" in
        --serial)
            (($# >= 2)) || fail "--serial requires a value"
            serial="$2"
            shift 2
            ;;
        --sha)
            (($# >= 2)) || fail "--sha requires a value"
            sha="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done

require_command adb
require_command curl
require_command unzip
require_command sha256sum
require_command python3

apksigner="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}/build-tools/35.0.0/apksigner"
[[ -x "$apksigner" ]] || fail "apksigner is unavailable at $apksigner"

repository_api="https://api.github.com/repos/$REPOSITORY"
workflow_runs_url="$repository_api/actions/workflows/$WORKFLOW_FILE/runs?event=push&head_sha=$sha&per_page=20"
deadline=$((SECONDS + TIMEOUT_SECONDS))
run_id=""

printf 'Waiting for GitHub Actions workflow %s for %s...\n' "$WORKFLOW_FILE" "$sha"
while ((SECONDS < deadline)); do
    runs_json="$(api_get "$workflow_runs_url")" || fail "Cannot read GitHub Actions runs for $REPOSITORY. Set GITHUB_TOKEN if required."
    run_json="$(python3 -c '
import json
import sys
runs = json.load(sys.stdin).get("workflow_runs", [])
print(json.dumps(runs[0] if runs else {}))
' <<<"$runs_json")"
    run_id="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("id", ""))' <<<"$run_json")"

    if [[ -n "$run_id" ]]; then
        status="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("status", ""))' <<<"$run_json")"
        conclusion="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("conclusion") or "")' <<<"$run_json")"
        run_url="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("html_url", ""))' <<<"$run_json")"

        case "$status:$conclusion" in
            completed:success)
                printf 'GitHub Actions succeeded: %s\n' "$run_url"
                break
                ;;
            completed:*)
                fail "GitHub Actions failed with conclusion $conclusion: $run_url"
                ;;
            *)
                printf 'Workflow run %s is %s; waiting...\n' "$run_id" "$status"
                ;;
        esac
    else
        printf 'No push workflow run for this SHA yet; waiting...\n'
    fi

    sleep "$POLL_SECONDS"
done

[[ -n "$run_id" ]] || fail "Timed out waiting for a successful GitHub Actions workflow run"

artifact_name="wallhub-release-$sha"
artifacts_json="$(api_get "$repository_api/actions/runs/$run_id/artifacts?name=$artifact_name&per_page=20")"
artifact_id="$(python3 -c '
import json
import sys
artifacts = [artifact for artifact in json.load(sys.stdin).get("artifacts", []) if not artifact.get("expired")]
print(artifacts[0]["id"] if len(artifacts) == 1 else "")
' <<<"$artifacts_json")"
[[ -n "$artifact_id" ]] || fail "Expected one non-expired artifact named $artifact_name for run $run_id"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
archive_path="$work_dir/artifact.zip"
artifact_dir="$work_dir/artifact"
apk_path="$artifact_dir/wallhub-release.apk"

printf 'Downloading artifact %s...\n' "$artifact_name"
api_get "$repository_api/actions/artifacts/$artifact_id/zip" > "$archive_path"
unzip -q "$archive_path" -d "$artifact_dir"

[[ -s "$apk_path" ]] || fail "Downloaded artifact does not contain wallhub-release.apk"
[[ -s "$artifact_dir/wallhub-release.apk.sha256" ]] || fail "Downloaded artifact does not contain its SHA-256 file"
[[ "$(tr -d '\r\n' < "$artifact_dir/commit-sha.txt")" == "$sha" ]] || fail "Artifact commit SHA does not match requested commit"

(
    cd "$artifact_dir"
    sha256sum --check wallhub-release.apk.sha256
)

artifact_certificate="$("$apksigner" verify --print-certs "$apk_path" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print $2; exit}')"
[[ -n "$artifact_certificate" ]] || fail "Cannot read the artifact signing certificate"

serial="$(resolve_adb_target "$serial")"
printf 'Selected current ADB target: %s\n' "$serial"

installed_apk="$(adb -s "$serial" shell pm path "$APPLICATION_ID" 2>/dev/null | tr -d '\r' | cut -d: -f2 | head -n 1)"
if [[ -n "$installed_apk" ]]; then
    installed_apk_copy="$work_dir/installed.apk"
    adb -s "$serial" pull "$installed_apk" "$installed_apk_copy" >/dev/null
    installed_certificate="$("$apksigner" verify --print-certs "$installed_apk_copy" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print $2; exit}')"
    if [[ -z "$installed_certificate" ]]; then
        fail "Cannot verify the installed APK certificate; refusing an unsafe replacement"
    fi
    if [[ "${artifact_certificate,,}" != "${installed_certificate,,}" ]]; then
        fail "Signing certificate mismatch. Refusing to uninstall $APPLICATION_ID and erase app data. Configure the GitHub Actions signing secrets with the certificate already installed on $serial."
    fi
fi

printf 'Installing signed APK on %s...\n' "$serial"
adb -s "$serial" install -r "$apk_path" | tee "$work_dir/install.log"
grep -qx 'Success' "$work_dir/install.log" || fail "ADB installation did not report Success"

adb -s "$serial" shell pm path "$APPLICATION_ID"
adb -s "$serial" shell dumpsys package "$APPLICATION_ID" | grep -m 2 -E 'versionCode=|versionName='

printf 'Installed GitHub Actions artifact for %s on %s.\n' "$sha" "$serial"
