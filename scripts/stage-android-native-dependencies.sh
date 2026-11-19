#!/usr/bin/env bash
set -euo pipefail

repo_dir="${WALLHUB_STEAM_CONTENT_SDK_REPO:?Set WALLHUB_STEAM_CONTENT_SDK_REPO to the pinned SDK checkout}"
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected_commit="$(tr -d '[:space:]' < "$root_dir/config/steam-content-sdk.commit")"
actual_commit="$(git -C "$repo_dir" rev-parse HEAD)"
[[ "$actual_commit" == "$expected_commit" ]] || {
  echo "ERROR: SDK checkout is $actual_commit, expected $expected_commit" >&2
  exit 1
}

(
  cd "$repo_dir"
  SC_ANDROID_AAR_VERSION=0.1.157 SC_AAR_GRADLE_OFFLINE="${SC_AAR_GRADLE_OFFLINE:-0}" \
    ./scripts/check-android-aar.sh
)

rm -rf "$root_dir/sc-sdk-maven/repository"
mkdir -p "$root_dir/sc-sdk-maven/repository"
cp -R "$repo_dir/target/build/android-aar/maven/." "$root_dir/sc-sdk-maven/repository/"
python "${root_dir}/scripts/sdk-artifact-lock.py" verify \
  --require-complete \
  --sdk-repo "$repo_dir"

"$root_dir/scripts/build-etc2.sh"
