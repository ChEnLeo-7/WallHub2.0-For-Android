#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY="${WALLHUB_GITHUB_REPOSITORY:-ChEnLeo-7/WallHub2.0-For-Android}"
readonly WORKFLOW_FILE="${WALLHUB_RELEASE_WORKFLOW:-release.yml}"
readonly BRANCH="main"
readonly POLL_SECONDS=10
readonly TIMEOUT_SECONDS=5400

usage() {
    cat <<'EOF'
Usage: scripts/publish-github-release.sh --tag <vX.Y.Z> --notes <.github/releases/vX.Y.Z.md> [--prerelease]

Pushes the exact clean local main commit and version tag, waits for the tag-
triggered release.yml run, then downloads and verifies every Release asset.

Required environment:
  GITHUB_TOKEN  Token with repository Actions read and Contents read access.
                Git SSH credentials perform the main and tag pushes.

Optional environment:
  WALLHUB_GITHUB_REPOSITORY  Defaults to ChEnLeo-7/WallHub2.0-For-Android.
  WALLHUB_RELEASE_WORKFLOW   Defaults to release.yml.
EOF
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

api_request() {
    local method="$1"
    local url="$2"
    local data="${3:-}"
    local accept="${4:-application/vnd.github+json}"
    local -a args=(
        --fail
        --silent
        --show-error
        --location
        --retry 5
        --retry-all-errors
        --retry-delay 2
        --request "$method"
        --header "Accept: $accept"
        --header "Authorization: Bearer $GITHUB_TOKEN"
        --header "X-GitHub-Api-Version: 2022-11-28"
    )
    if [[ -n "$data" ]]; then
        args+=(--header "Content-Type: application/json" --data "$data")
    fi
    curl "${args[@]}" "$url"
}

api_get() {
    api_request GET "$1"
}

tag=""
notes_file=""
prerelease=false
while (($# > 0)); do
    case "$1" in
        --tag)
            (($# >= 2)) || fail "--tag requires a value"
            tag="$2"
            shift 2
            ;;
        --notes)
            (($# >= 2)) || fail "--notes requires a value"
            notes_file="$2"
            shift 2
            ;;
        --prerelease)
            prerelease=true
            shift
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

require_command curl
require_command git
require_command python3
require_command sha256sum
require_command unzip

[[ -n "${GITHUB_TOKEN:-}" ]] || fail "GITHUB_TOKEN is required"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || fail "Invalid tag: $tag"
if [[ "$prerelease" == true ]]; then
    [[ "$tag" == *-* ]] || fail "Prerelease tags must include a suffix, for example v1.0.0-beta.1"
else
    [[ "$tag" != *-* ]] || fail "A suffixed tag requires --prerelease"
fi
[[ "$notes_file" == ".github/releases/$tag.md" ]] || fail "Notes must be .github/releases/$tag.md"
[[ -f "$notes_file" ]] || fail "Release notes do not exist: $notes_file"
[[ "$(git branch --show-current)" == "$BRANCH" ]] || fail "Run from the $BRANCH branch"
[[ -z "$(git status --porcelain)" ]] || fail "Commit or stash all local changes before publishing"

version_name="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' app/build.gradle.kts)"
[[ "$tag" == "v$version_name" ]] || fail "Tag $tag does not match versionName $version_name"

for heading in \
    '## 中文' \
    '### 此次更新' \
    '### 修改' \
    '### 修复' \
    '### 下载说明' \
    '## English' \
    '### Updates' \
    '### Changes' \
    '### Fixes' \
    '### Download guide'; do
    grep -qxF "$heading" "$notes_file" || fail "Release notes are missing heading: $heading"
done

sha="$(git rev-parse HEAD)"
git push origin "$BRANCH"
git fetch origin "$BRANCH" --tags
[[ "$sha" == "$(git rev-parse origin/$BRANCH)" ]] || fail "Local HEAD is not the latest origin/$BRANCH"

repository_api="https://api.github.com/repos/$REPOSITORY"
if api_get "$repository_api/releases/tags/$tag" >/dev/null 2>&1; then
    fail "Release already exists: $tag"
fi
[[ -z "$(git ls-remote --tags origin "refs/tags/$tag")" ]] || fail "Tag already exists: $tag"

runs_url="$repository_api/actions/workflows/$WORKFLOW_FILE/runs?event=push&per_page=50"
before_json="$(api_get "$runs_url")"
before_ids="$(python3 -c '
import json
import sys
for run in json.load(sys.stdin).get("workflow_runs", []):
    print(run["id"])
' <<<"$before_json")"

git tag "$tag" "$sha"
if ! git push origin "refs/tags/$tag"; then
    git tag --delete "$tag" >/dev/null
    fail "Cannot push Release tag $tag"
fi

printf 'Pushed %s for %s; waiting for %s.\n' "$tag" "$sha" "$WORKFLOW_FILE"
deadline=$((SECONDS + TIMEOUT_SECONDS))
run_id=""
run_url=""
while ((SECONDS < deadline)); do
    runs_json="$(api_get "$runs_url")"
    run_json="$(python3 -c '
import json
import sys
before = set(filter(None, sys.argv[1].splitlines()))
sha = sys.argv[2]
runs = json.load(sys.stdin).get("workflow_runs", [])
match = next((run for run in runs if str(run["id"]) not in before and run.get("head_sha") == sha), {})
print(json.dumps(match))
' "$before_ids" "$sha" <<<"$runs_json")"
    run_id="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("id", ""))' <<<"$run_json")"
    if [[ -n "$run_id" ]]; then
        status="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("status", ""))' <<<"$run_json")"
        conclusion="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("conclusion") or "")' <<<"$run_json")"
        run_url="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("html_url", ""))' <<<"$run_json")"
        case "$status:$conclusion" in
            completed:success)
                printf 'Release workflow succeeded: %s\n' "$run_url"
                break
                ;;
            completed:*)
                fail "Release workflow failed with conclusion $conclusion: $run_url"
                ;;
            *)
                printf 'Release workflow %s is %s; waiting...\n' "$run_id" "$status"
                ;;
        esac
    else
        printf 'Waiting for the tag-triggered Release workflow run...\n'
    fi
    sleep "$POLL_SECONDS"
done
[[ -n "$run_id" ]] || fail "Timed out waiting for the Release workflow"

release_json="$(api_get "$repository_api/releases/tags/$tag")"
release_url="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("html_url", ""))' <<<"$release_json")"
is_draft="$(python3 -c 'import json, sys; print(str(json.load(sys.stdin).get("draft", True)).lower())' <<<"$release_json")"
is_prerelease="$(python3 -c 'import json, sys; print(str(json.load(sys.stdin).get("prerelease", False)).lower())' <<<"$release_json")"
[[ "$is_draft" == false ]] || fail "Release is still a draft: $release_url"
[[ "$is_prerelease" == "$prerelease" ]] || fail "Release prerelease state does not match the request"
if [[ "$prerelease" == false ]]; then
    latest_json="$(api_get "$repository_api/releases/latest")"
    latest_tag="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("tag_name", ""))' <<<"$latest_json")"
    [[ "$latest_tag" == "$tag" ]] || fail "Stable Release is not marked Latest"
fi

tag_json="$(api_get "$repository_api/git/ref/tags/$tag")"
tag_type="$(python3 -c 'import json, sys; print(json.load(sys.stdin)["object"]["type"])' <<<"$tag_json")"
tag_sha="$(python3 -c 'import json, sys; print(json.load(sys.stdin)["object"]["sha"])' <<<"$tag_json")"
[[ "$tag_type" == commit && "$tag_sha" == "$sha" ]] || fail "Release tag does not resolve to the published source commit"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
assets_file="$work_dir/assets.tsv"
python3 -c '
import json
import sys
for asset in json.load(sys.stdin).get("assets", []):
    print("{}\t{}\t{}\t{}".format(
        asset["id"],
        asset["name"],
        asset["browser_download_url"],
        asset.get("digest", ""),
    ))
' <<<"$release_json" > "$assets_file"

expected_names="$(printf '%s\n' \
    "WallHub-$version_name-arm64-v8a.apk" \
    "WallHub-$version_name-armeabi-v7a.apk" \
    "WallHub-$version_name-universal.apk" \
    "WallHub-$version_name-x86.apk" \
    "WallHub-$version_name-x86_64.apk" | sort)"
actual_names="$(cut -f2 "$assets_file" | sort)"
[[ "$actual_names" == "$expected_names" ]] || {
    diff -u <(printf '%s\n' "$expected_names") <(printf '%s\n' "$actual_names") || true
    fail "Published Release assets do not match the expected APK-only set"
}

release_body="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("body", ""))' <<<"$release_json")"
[[ "$release_body" == *"$sha"* ]] || fail "Release body is missing the source commit"
while IFS=$'\t' read -r asset_id asset_name download_url asset_digest; do
    [[ "$asset_digest" == sha256:* ]] || fail "GitHub did not report a SHA-256 digest for $asset_name"
    printf 'Downloading Release asset %s...\n' "$asset_name"
    if ! curl \
        --fail \
        --location \
        --retry 10 \
        --retry-all-errors \
        --retry-delay 2 \
        --continue-at - \
        --output "$work_dir/$asset_name" \
        "$download_url"; then
        rm -f "$work_dir/$asset_name"
        api_request GET "$repository_api/releases/assets/$asset_id" "" application/octet-stream > "$work_dir/$asset_name"
    fi
    [[ -s "$work_dir/$asset_name" ]] || fail "Downloaded asset is empty: $asset_name"
    expected_checksum="${asset_digest#sha256:}"
    actual_checksum="$(sha256sum "$work_dir/$asset_name" | awk '{print $1}')"
    [[ "$actual_checksum" == "$expected_checksum" ]] || fail "SHA-256 mismatch for $asset_name"
    [[ "$release_body" == *"$expected_checksum"* ]] || fail "Release body is missing SHA-256 for $asset_name"
done < "$assets_file"

apksigner="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}/build-tools/35.0.0/apksigner"
[[ -x "$apksigner" ]] || fail "apksigner is unavailable at $apksigner"
expected_certificate=""
expected_certificate_subject=""
expected_release_certificate="$(tr -d '[:space:]' < config/release-signing-certificate.sha256 | tr '[:lower:]' '[:upper:]')"
[[ "$expected_release_certificate" =~ ^[0-9A-F]{64}$ ]] || fail "Pinned Release certificate SHA-256 is invalid"
expected_all_abis=$'arm64-v8a\narmeabi-v7a\nx86\nx86_64'
for apk in "$work_dir"/WallHub-*.apk; do
    unzip -tq "$apk"
    entries="$(unzip -Z1 "$apk")"
    grep -qx 'classes.dex' <<<"$entries" || fail "$apk is missing classes.dex"
    certificate="$($apksigner verify --print-certs "$apk" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print toupper($2); exit}')"
    certificate_subject="$($apksigner verify --print-certs "$apk" | awk -F': ' '/Signer #1 certificate DN:/{print $2; exit}')"
    [[ -n "$certificate" && -n "$certificate_subject" ]] || fail "Cannot read the signing certificate for $apk"
    [[ "$certificate" == "$expected_release_certificate" ]] || fail "$apk does not match the pinned published signing identity"
    if grep -Eiq '(^|,[[:space:]]*)CN=Android Debug(,|$)' <<<"$certificate_subject"; then
        printf 'WARNING: Pinned legacy Release identity uses Android Debug DN; rotate only with an Android signing lineage\n' >&2
    fi
    if [[ -z "$expected_certificate" ]]; then
        expected_certificate="$certificate"
        expected_certificate_subject="$certificate_subject"
    else
        [[ "$certificate" == "$expected_certificate" ]] || fail "Signing certificate mismatch for $apk"
        [[ "$certificate_subject" == "$expected_certificate_subject" ]] || fail "Signing certificate subject mismatch for $apk"
    fi

    label="${apk##*-}"
    label="${label%.apk}"
    case "$(basename "$apk")" in
        *-arm64-v8a.apk) label=arm64-v8a ;;
        *-armeabi-v7a.apk) label=armeabi-v7a ;;
        *-x86_64.apk) label=x86_64 ;;
        *-x86.apk) label=x86 ;;
        *-universal.apk) label=universal ;;
    esac
    packaged_abis="$(sed -n 's#^lib/\([^/]*\)/.*#\1#p' <<<"$entries" | sort -u)"
    if [[ "$label" == universal ]]; then
        [[ "$packaged_abis" == "$expected_all_abis" ]] || fail "Universal APK ABI set is unexpected"
    else
        [[ "$packaged_abis" == "$label" ]] || fail "$label APK contains unexpected ABIs: $packaged_abis"
    fi
done
[[ "$release_body" == *"$expected_certificate"* ]] || fail "Release body is missing the signing certificate SHA-256"
[[ "$release_body" == *"$expected_certificate_subject"* ]] || fail "Release body is missing the signing certificate subject"

printf 'Published and verified GitHub Release: %s\n' "$release_url"
printf 'Release workflow: %s\n' "$run_url"
printf 'Source commit: %s\n' "$sha"
