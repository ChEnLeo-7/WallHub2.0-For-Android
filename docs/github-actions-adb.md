# GitHub Actions To ADB Workflow

This project can build and test an APK without the LAN Windows worker:

```text
local source change -> commit -> push main -> GitHub Actions -> signed Release APK artifact -> ADB install
```

`scripts/push-build-install.sh` implements the flow. It only accepts a clean
local `main` branch, pushes its exact HEAD to `origin`, then waits for the
successful `verify.yml` push run for that same commit SHA. It downloads only
the matching `wallhub-release-<sha>` artifact, verifies its embedded SHA-256
file and commit marker, checks the signing certificate against the APK already
installed on the device, and finally performs `adb install -r`.

It never uninstalls the app and never uses `-d` to bypass a version conflict.

## One-Time GitHub Setup

The Release artifact must use a stable signing certificate. GitHub-hosted
runners generate a different default debug keystore, so using the default
would make every `adb install -r` fail against the device's existing app.

In the repository's **Settings > Secrets and variables > Actions**, create
these repository secrets:

| Secret | Value |
|---|---|
| `WALLHUB_RELEASE_KEYSTORE_BASE64` | Base64 of the keystore that signed the APK currently installed on the test device |
| `WALLHUB_RELEASE_STORE_PASSWORD` | Keystore password |
| `WALLHUB_RELEASE_KEY_ALIAS` | Signing key alias |
| `WALLHUB_RELEASE_KEY_PASSWORD` | Signing key password |

The current Samsung test device has an app signed with certificate SHA-256:

```text
940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D
```

Verify the keystore certificate before storing it. It must match this value to
preserve app data during the first GitHub Actions deployment. The former LAN
worker likely used its Android debug keystore at
`%USERPROFILE%\.android\debug.keystore`; do not assume that path is correct
without checking its certificate.

On the Windows machine holding the matching keystore, create its Base64 value
without writing it to a file:

```powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")
)
```

Paste the output into `WALLHUB_RELEASE_KEYSTORE_BASE64`. If this is a standard
Android debug keystore, the alias and both passwords are normally `androiddebugkey`
and `android`, respectively. Store them as secrets nonetheless.

If no matching keystore exists, generate a new release keystore and set these
four secrets. The first deployment then requires a deliberate manual uninstall
of WallHub on the development device because Android will not replace an app
signed by a different certificate. The scripts intentionally refuse to erase
application data automatically.

## Daily Command

After committing a source change on `main`:

```bash
export GITHUB_TOKEN=<fine-grained-token-if-needed>
scripts/push-build-install.sh --serial 192.168.2.190:40283
```

`GITHUB_TOKEN` is not required while the repository remains public and GitHub
allows anonymous artifact reads, but supplying a fine-grained token with
repository Actions artifacts read access makes the download path reliable and
is required if the repository becomes private. Git push still needs write
credentials configured for `origin`.

The Action does the following for pushes to `main` and manual dispatches:

1. Uses Temurin JDK 17, Android API 36 and Build Tools 35.0.0.
2. Runs `testDebugUnitTest lintDebug`.
3. Restores the secret keystore and builds `:app:assembleRelease`.
4. Uploads a 14-day `wallhub-release-<commit-sha>` artifact containing the APK,
   SHA-256 file and commit marker.

Pull requests run tests and lint but do not consume the signing secrets or
publish an APK.

## Failure Handling

- A failed test, lint or Release build stops the Action before artifact upload.
- The installer rejects an artifact from another commit or with a wrong hash.
- A signing mismatch stops before installation and leaves device data intact.
- A non-`device` ADB target stops before any download or install.
- The old LAN builder remains available only as a fallback until the first
  GitHub Actions deployment is verified end to end.
