# WallHub for Android

WallHub for Android is the native Jetpack Compose client for browsing, playing, downloading, and managing Wallpaper Engine Workshop items. It runs independently from the WallHub Web service and does not embed WebView, Node.js, Python, or DepotDownloader.

## Requirements

- Android Studio with JDK 17 or newer
- Android SDK Platform 36 and Build Tools 35.0.0
- Android 8.0 (API 26) or newer for installation

Do not commit `local.properties`, signing keys, APK/AAB output, Gradle caches, recordings, or local source snapshots.

## Build And Verify

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug
```

On Linux or macOS:

```bash
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Public releases must use a dedicated production signing key configured through local or GitHub Actions secrets.

For the development-device flow, the checked-in GitHub Actions workflow will run tests and lint, build a signed Release APK, and upload a commit-bound artifact after the repository receives its first push and signing secrets are configured. `scripts/push-build-install.sh` then pushes the current commit and safely downloads and installs that exact artifact. See `docs/github-actions-adb.md` for one-time setup and usage.

## Project Structure

- `app/`: application shell, navigation, and dependency wiring.
- `core/`: models, database, and the shared design system.
- `data/`: Steam access, Workshop data, downloads, settings, and diagnostics.
- `feature/`: home, detail, downloads, library, local, and settings screens.
- `docs/development-log.md`: version history and manual verification record.

Legacy prototype source, local recordings, and pre-refactor snapshots are retained locally in ignored directories and are not part of the published repository.

## License

See `LICENSE` for repository licensing terms. Steam, Wallpaper Engine, and related names belong to their respective owners. Use the application in accordance with platform and creator rules.
