# GitHub Release Publishing

WallHub publishes signed GitHub Releases from the latest clean `main` commit. A Release is separate from the short-lived Actions artifact used by the ADB deployment path:

- `verify.yml` uploads one universal APK artifact for 7 days.
- `release.yml` publishes versioned Release assets that remain available until the GitHub Release is deleted.

## Release Assets

Each stable Release contains:

| Asset | Use |
|---|---|
| `WallHub-<version>-arm64-v8a.apk` | Most modern Android phones and tablets |
| `WallHub-<version>-armeabi-v7a.apk` | Older 32-bit ARM devices |
| `WallHub-<version>-x86_64.apk` | 64-bit x86 emulators and desktop Android environments |
| `WallHub-<version>-x86.apk` | Legacy 32-bit x86 emulators |
| `WallHub-<version>-universal.apk` | Fallback when the device ABI is unknown; larger because it contains all ABIs |
| `SHA256SUMS.txt` | SHA-256 checksums for every APK |
| `SOURCE-COMMIT.txt` | Exact source commit used by the workflow |
| `SIGNING-CERTIFICATE-SHA256.txt` | Signing certificate identity shared by all APKs |

The architecture APKs are real ABI splits. WallHub currently packages native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Normal CI keeps ABI splitting disabled and continues to produce `app-release.apk`; only `release.yml` enables splits with `-Pwallhub.publishAbiApks=true`.

## Required GitHub Configuration

The four stable signing secrets used by `verify.yml` are also used by `release.yml`:

- `WALLHUB_RELEASE_KEYSTORE_BASE64`
- `WALLHUB_RELEASE_STORE_PASSWORD`
- `WALLHUB_RELEASE_KEY_ALIAS`
- `WALLHUB_RELEASE_KEY_PASSWORD`

The workflow has repository `contents: write` permission so the provided `GITHUB_TOKEN` can create a tag, upload assets, and publish the Release. Repository Actions settings must allow workflow write permissions; no personal token is stored in the workflow.

The local publisher requires `GITHUB_TOKEN` with Actions read/write and Contents read/write access. Load the existing token without printing it:

```bash
set -a
. /root/.config/wallhub/github.env
set +a
```

## Prepare A Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts` when the version changes.
2. Update `docs/development-log.md` with the release's update and fix notes.
3. Create `docs/releases/v<version>.md` with all required bilingual headings:
   - `此次更新` / `Updates`
   - `修改` / `Changes`
   - `修复` / `Fixes`
   - `下载说明` / `Download guide`
4. Commit only the intended source, workflow, documentation, and skill changes on `main`.
5. Require a clean worktree and inspect the exact commit that will be tagged.

The Markdown file does not hardcode APK checksums. `release.yml` appends the checksums calculated from the signed assets, source commit, app version, and signing certificate to the published body.

## Publish

Run from a clean local `main` branch:

```bash
scripts/publish-github-release.sh \
  --tag v0.8.25 \
  --notes docs/releases/v0.8.25.md
```

Add `--prerelease` only for a prerelease tag and notes file. The script:

1. Validates the tag against `versionName` and checks every required bilingual heading.
2. Pushes the exact local `main` commit.
3. Dispatches `.github/workflows/release.yml` for that commit.
4. Waits for tests, lint, signing, split builds, checksums, and Release publication.
5. Downloads all published assets and independently verifies SHA-256, ZIP/DEX structure, ABI isolation, source commit, and signing certificate.

The workflow first creates a draft Release, compares the uploaded asset names with the complete expected set, and only then makes it public. A stable Release is marked Latest. A prerelease is not marked Latest.

## Failure Recovery

- Test, lint, signing, split, checksum, or ABI failure stops before a public Release is created.
- A failure after draft creation can leave a draft and tag. Inspect them before retrying; never overwrite an existing published tag.
- Correct the source or notes, remove only a confirmed failed draft/tag when necessary, create a new clean commit, and rerun the publisher.
- A signing certificate change is a release blocker because existing installations cannot update in place without data loss.
- The Release assets are permanent until the Release is deleted. The duplicate Actions release bundle is retained for 7 days only and is not the public download source.

After publication, append the Release URL, workflow run, tag, source SHA, asset verification, and device smoke-test result to `docs/development-log.md` in a separate documentation commit.
