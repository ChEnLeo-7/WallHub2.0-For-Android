#!/usr/bin/env bash
set -euo pipefail

readonly BRANCH="main"

usage() {
    cat <<'EOF'
Usage: scripts/push-build-install.sh [--serial <adb-serial>]

Pushes the current clean main branch to origin, then delegates to
install-github-release-apk.sh to wait for, download, verify, and install the
GitHub Actions Release artifact built from that exact commit.

Pass GITHUB_TOKEN when the repository or its Actions artifacts are private.
EOF
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

serial=""
while (($# > 0)); do
    case "$1" in
        --serial)
            (($# >= 2)) || fail "--serial requires a value"
            serial="$2"
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

[[ "$(git branch --show-current)" == "$BRANCH" ]] || fail "Run this workflow from the $BRANCH branch"
[[ -z "$(git status --porcelain)" ]] || fail "Commit or stash all local changes before pushing"

sha="$(git rev-parse HEAD)"
git push origin "$BRANCH"

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
args=(--sha "$sha")
if [[ -n "$serial" ]]; then
    args+=(--serial "$serial")
fi

exec "$script_dir/install-github-release-apk.sh" "${args[@]}"
