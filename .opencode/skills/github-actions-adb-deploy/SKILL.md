---
name: github-actions-adb-deploy
description: Deploys WallHub Android changes through GitHub Actions, downloads the commit-bound signed Release APK in the LXC, and installs and cold-start verifies it on the currently connected ADB device. Use automatically after modifying Android source code, when validating Android changes, or when the user requests an APK or device deployment.
---

# GitHub Actions Release And ADB Deploy

Use the repository script as the single deployment interface:

```bash
scripts/push-build-install.sh
```

This is the normal validation path. It commits nothing: source changes and documentation must already be committed, `main` must be checked out, and the worktree must be clean.

## 1. Prepare The Commit

Before deployment:

1. Inspect `git status`, `git diff`, and recent commits.
2. Update `docs/development-log.md` when the APK contains a product update or fix.
3. Commit only intended files with a focused message.
4. Confirm `git status --short` is empty and the current branch is `main`.

Completion criterion: the exact source to validate is one clean local `main` commit.

## 2. Confirm Credentials And ADB

Load the GitHub API token without printing it:

```bash
set -a
. /root/.config/wallhub/github.env
set +a
```

Confirm the token variable is non-empty and inspect current devices:

```bash
test -n "${GITHUB_TOKEN:-}"
adb devices -l
```

The installer resolves the target again immediately before installation. With exactly one `device` target it proceeds automatically. With zero or multiple `device` targets it stops safely; for multiple targets, rerun with the user's explicit selection:

```bash
scripts/push-build-install.sh --serial <current-adb-serial>
```

Never persist a wireless ADB host or port in the skill, scripts, or docs.

Completion criterion: GitHub API authentication is loaded and the intended ADB target selection is unambiguous.

## 3. Push, Build, Download, And Install

Run:

```bash
scripts/push-build-install.sh
```

The script must complete all gates for the same commit SHA:

1. Push local `main` to `origin`.
2. Wait for `.github/workflows/verify.yml` on that SHA.
3. Require `testDebugUnitTest`, `lintDebug`, signed `:app:assembleRelease`, and artifact upload to succeed.
4. Download only `wallhub-release-<commit-sha>`.
5. Verify the commit marker, SHA-256, ZIP integrity, `classes.dex`, and APK certificate.
6. Compare the artifact certificate with the installed APK before replacement.
7. Install with `adb install -r` on one current `device` target.
8. Verify package path and version.
9. Cold-start WallHub, require a live PID, and reject `FATAL EXCEPTION`, ANR, or OOM logs.

Completion criterion: the script exits successfully after reporting the commit SHA, selected device, installed package version, and live cold-start PID.

When the user explicitly requests installation without opening WallHub, use:

```bash
scripts/push-build-install.sh --install-only
```

`--install-only` ends after package/version verification and does not clear logs, force-stop, launch, or cold-start-check WallHub. Preserve the normal cold-start path unless the user explicitly requests install-only behavior.

## 4. Handle Failure At The Failed Gate

- Action failure: inspect the run URL and authenticated job logs; fix the first failing test, lint, signing, or build step, commit, and rerun the normal path.
- Missing signing secret: configure all four `WALLHUB_RELEASE_*` repository secrets described in `docs/github-actions-adb.md`.
- Artifact mismatch or expiry: push or rerun the exact current commit; never select an APK by modification time.
- Certificate mismatch: stop and report it. Never uninstall WallHub automatically because uninstalling erases app data.
- Zero/multiple ADB devices: report `adb devices -l`; reconnect or request an explicit serial.
- Cold-start failure: keep the failed build installed for diagnosis and report PID/log evidence.

## LAN Fallback

Use the `android-release-build` skill only when GitHub Actions or GitHub artifact access is unavailable, or when the user explicitly requests the LAN Windows worker. A successful LAN fallback does not replace the normal GitHub Actions verification.

The workflow contract and one-time credential setup live in `docs/github-actions-adb.md`.
