# Repository Guidelines

## Project Structure & Module Organization

This is a Kotlin, Jetpack Compose, multi-module Android application. `app/` owns the shell, navigation, and dependency injection. Shared models, persistence, and UI primitives live under `core/`; integrations and repositories live under `data/`; screens are grouped under `feature/` (`home`, `detail`, `downloads`, `library`, `local`, and `settings`). Most Kotlin source uses `src/main/kotlin`; the app keeps Kotlin in `app/src/main/java`. Put JVM tests in each module's `src/test/kotlin` tree and resources in `src/main/res`. Dependency and plugin versions are centralized in `gradle/libs.versions.toml`. `archive/`, `local-artifacts/`, and `local-snapshots/` are ignored local material.

## Build, Test, and Development Commands

Use JDK 17 or newer, Android SDK Platform 36, and Build Tools 35.0.0. The checked-in CI workflow will publish a signed Release APK artifact after pushes to `main` once the repository receives its first push and the required signing secrets are configured; see `docs/github-actions-adb.md` for setup and local download/install commands. From PowerShell:

- `.\gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug` runs the full CI verification suite and builds the debug APK.
- `.\gradlew.bat :feature:home:testDebugUnitTest` runs one module's unit tests; substitute another Gradle module path as needed.
- `.\gradlew.bat :app:installDebug` installs the debug build on a connected emulator or device.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Use `./gradlew` for Linux or macOS.

Do not run Gradle in the LXC. After Android source changes, automatically use the `github-actions-adb-deploy` repository skill: commit a clean `main`, run `scripts/push-build-install.sh`, and require the commit-bound GitHub Actions artifact, current ADB-device install, and cold-start verification to succeed. Use `android-release-build` only as an explicit or outage fallback. For a public versioned GitHub Release, use the `wallhub-release-publish` repository skill and `scripts/publish-github-release.sh`; require bilingual notes, ABI-specific plus universal APKs, generated SHA-256 values, exact source/tag binding, and post-download verification.

## Coding Style & Naming Conventions

Follow Kotlin's official style (`kotlin.code.style=official`): four-space indentation, trailing commas in multiline declarations and calls, and Android Studio's import ordering. Use `PascalCase` for classes, Compose functions, and files; `camelCase` for functions and properties; and `UPPER_SNAKE_CASE` for constants. Resource names use lowercase snake case, usually prefixed `wallhub_`. Keep shared UI in `core:designsystem`, contracts in `core:model`, and service or storage implementations in `data`. No standalone formatter is configured; `lintDebug` is the enforced static check.

## Testing Guidelines

Local tests use JUnit 4 and `kotlin-test`; coroutine-heavy modules also use `kotlinx-coroutines-test`. Name files `<Subject>Test.kt` and prefer descriptive backtick test names, for example ``fun `pause request remains persisted`()``. Add regression coverage in the module that owns the behavior. There is currently no instrumentation suite or coverage threshold.

## Commit & Pull Request Guidelines

Use short, imperative, scoped subjects such as `fix(downloads): resume paused tasks`. Keep each commit focused. Pull requests should explain the behavior and affected modules, link issues when applicable, report commands run, and include screenshots or recordings for UI or motion changes. CI must pass. When publishing an APK, add update, fix, and verification notes to `docs/development-log.md`.

## Security & Configuration

Never commit `local.properties`, signing files, credentials, APK/AAB outputs, caches, recordings, or local snapshots. Production signing material belongs in local configuration or GitHub Actions secrets.
