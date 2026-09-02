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

Only the five APKs are uploaded as GitHub Release assets. The Release body directly contains every APK SHA-256, the exact source commit, app version, and signing certificate SHA-256. GitHub automatically adds `Source code (zip)` and `Source code (tar.gz)` for every tag; workflows cannot remove those generated source links.

The architecture APKs are real ABI splits. WallHub currently packages native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Normal CI keeps ABI splitting disabled and continues to produce `app-release.apk`; only `release.yml` enables splits with `-Pwallhub.publishAbiApks=true`.

## Required GitHub Configuration

The four stable signing secrets used by `verify.yml` are also used by `release.yml`:

- `WALLHUB_RELEASE_KEYSTORE_BASE64`
- `WALLHUB_RELEASE_STORE_PASSWORD`
- `WALLHUB_RELEASE_KEY_ALIAS`
- `WALLHUB_RELEASE_KEY_PASSWORD`

The workflow has repository `contents: write` permission so the provided `GITHUB_TOKEN` can create a tag, upload assets, and publish the Release. Repository Actions settings must allow workflow write permissions; no personal token is stored in the workflow.

The local publisher requires `GITHUB_TOKEN` with Actions read and Contents read access. Existing Git SSH credentials push `main` and the version tag, which triggers the workflow without requiring Actions write permission on the local token. Load the token without printing it:

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

The Markdown file does not hardcode APK checksums and must not repeat the GitHub Release title as an H1. `release.yml` appends the checksums calculated from the signed assets, source commit, app version, and signing certificate to the published body. Metadata text files remain inside the 7-day Actions bundle for recovery but are not public Release assets.

## Publish

Run from a clean local `main` branch:

```bash
scripts/publish-github-release.sh \
  --tag v0.8.25 \
  --notes docs/releases/v0.8.25.md
```

Add `--prerelease` only for a prerelease tag and notes file. The script:

1. Validates the tag against `versionName` and checks every required bilingual heading.
2. Pushes the exact local `main` commit and an immutable `v<version>` tag.
3. Waits for the tag-triggered `.github/workflows/release.yml` run.
4. Waits for tests, lint, signing, split builds, checksums, and Release publication.
5. Downloads all five published APK assets and independently verifies GitHub's asset digest, the SHA-256 shown in the Release body, ZIP/DEX structure, ABI isolation, source commit, and signing certificate.

The workflow first creates a draft Release, compares the uploaded asset names with the complete expected set, and only then makes it public. A stable Release is marked Latest. A prerelease tag must include a suffix such as `-beta.1`, is published with `--prerelease`, and is not marked Latest. The workflow also retains a manual dispatch entry for repository operators with Actions write access.

## Correct Existing Release Metadata

Edit the versioned `docs/releases/v<version>.md` file and push it to `main`. `.github/workflows/release-metadata.yml` updates the existing Release body while preserving the generated SHA-256/source/certificate section, then removes any manually uploaded non-APK assets. It verifies that the body starts at `## 中文` and that exactly five APK assets remain. A repository operator can also dispatch the workflow manually with an existing tag.

GitHub's automatic source ZIP and tarball remain visible because they are generated from the tag rather than uploaded assets.

## Failure Recovery

- Test, lint, signing, split, checksum, or ABI failure stops before a public Release is created.
- A failure after draft creation can leave a draft and tag. Inspect them before retrying; never overwrite an existing published tag.
- Correct the source or notes, remove only a confirmed failed draft/tag when necessary, create a new clean commit, and rerun the publisher.
- A signing certificate change is a release blocker because existing installations cannot update in place without data loss.
- The Release assets are permanent until the Release is deleted. The duplicate Actions release bundle is retained for 7 days only and is not the public download source.

After publication, append the Release URL, workflow run, tag, source SHA, asset verification, and device smoke-test result to `docs/development-log.md` in a separate documentation commit.
