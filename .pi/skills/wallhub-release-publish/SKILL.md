---
name: wallhub-release-publish
description: Publishes a signed WallHub Android GitHub Release with bilingual notes, universal and ABI-specific APKs, SHA-256 checksums, source binding, and post-publication verification. Use when the user requests a versioned Release, public APK downloads, or Release notes.
---

# WallHub GitHub Release Publishing

Use the repository publisher as the release interface:

```bash
scripts/publish-github-release.sh \
  --tag v<version> \
  --notes docs/releases/v<version>.md
```

Read [the publishing contract](../../../docs/github-release.md) before changing the workflow or recovering a failed draft.

## 1. Establish The Release Commit

1. Inspect `git status`, `git diff`, `git log`, existing tags, and existing GitHub Releases.
2. Confirm `app/build.gradle.kts` has the intended `versionCode` and `versionName`.
3. Update `docs/development-log.md` with the product changes included in the version.
4. Commit only intended files on `main`; preserve unrelated user changes.
5. Confirm the release tag does not already exist locally or remotely.

Completion criterion: one reviewed source commit on `main` is ready to become `v<version>`, and no published Release or tag uses that version.

## 2. Write Bilingual Release Notes

Create `docs/releases/v<version>.md`. It must contain substantive content under every heading:

```text
## 中文
### 此次更新
### 修改
### 修复
### 下载说明
## English
### Updates
### Changes
### Fixes
### Download guide
```

The two languages must describe the same release. The download guide must map:

- `arm64-v8a` to most modern phones and tablets.
- `armeabi-v7a` to older 32-bit ARM devices.
- `x86_64` to 64-bit emulators and desktop Android environments.
- `x86` to legacy 32-bit emulators.
- `universal` to users who do not know their ABI, with a larger-size warning.

Do not invent checksums in the source notes. `release.yml` appends SHA-256 values calculated from the signed APKs.

Completion criterion: the versioned notes pass every bilingual heading check and give an unambiguous APK choice for every supported ABI.

## 3. Validate Before Publishing

Android source or build changes must first pass the normal commit-bound deployment:

```bash
scripts/push-build-install.sh
```

Require the same source commit to pass JVM tests, `lintDebug`, signed universal Release assembly, artifact checks, ADB replacement, and cold-start verification. A documentation-only release preparation may reuse a successful validation of its parent source commit when no APK input changed.

Completion criterion: the exact APK-producing source has a successful GitHub Actions and device validation record.

## 4. Publish And Verify

Load the token without printing it:

```bash
set -a
. /root/.config/wallhub/github.env
set +a
```

Run the publisher from a clean `main`. Add `--prerelease` only when explicitly requested.

The publisher must succeed through every gate:

1. Push exact `main` and dispatch `release.yml`.
2. Require tag, notes filename, and `versionName` to match.
3. Require tests, lint, stable signing, and five APK outputs.
4. Require each split APK to contain only its named ABI and universal to contain all four ABIs.
5. Create a draft, upload the complete expected asset set, then publish it.
6. Download every public asset and verify `SHA256SUMS.txt`, `classes.dex`, ZIP integrity, source commit, ABI set, and signing certificate.
7. Require the tag to resolve to the exact dispatched commit.

Release assets are:

```text
WallHub-<version>-arm64-v8a.apk
WallHub-<version>-armeabi-v7a.apk
WallHub-<version>-x86.apk
WallHub-<version>-x86_64.apk
WallHub-<version>-universal.apk
SHA256SUMS.txt
SOURCE-COMMIT.txt
SIGNING-CERTIFICATE-SHA256.txt
```

Completion criterion: the Release is public, all eight assets verify after downloading from GitHub, and a stable release is marked Latest.

## 5. Record Publication

Append the Release URL, tag, source SHA, workflow run, checksum/ABI/certificate verification, install target, version, and cold-start result to `docs/development-log.md`. Commit and push this as a documentation-only follow-up, then require its CI run to pass.

Completion criterion: `main` and `origin/main` agree, the publication record is committed, and unrelated local changes remain untouched.

## Failed Drafts

A published tag is immutable release identity. When a run fails after creating a draft, inspect the draft, tag, assets, and workflow logs. Remove only a confirmed failed draft/tag before retrying a corrected commit. Never overwrite a published tag or replace assets under an existing public version.
