# Development Log

## 2026-09-02

### Steam profile avatar

- Update: Request the signed-in account's Persona name and presence data after login or session restore.
- Fix: Merge Persona callbacks according to their status flags so partial updates cannot discard a cached avatar.
- Verification: Added regression coverage for the request mask and partial callback merging. The commit-bound signed Debug APK must build, install in place, cold-start, and display the restored Steam avatar on the target device.

### Steam session lifecycle

- Update: Added process-level foreground/background coordination and an idempotent foreground restore path for stale Steam CM sessions.
- Fix: Restore jobs are started under the lifecycle lock and shared by concurrent callers; Keystore read failures no longer delete the saved encrypted session.
- Policy: A live session is reused for short background intervals; after two minutes in the background, WallHub rebuilds the CM session silently with the saved refresh token. Expired Steam tokens are not retried by background content work.
- Verification: Added lifecycle and credential-storage regression tests. Device verification covers returning from background and restarting the process without clearing WallHub data.

### kSteam + Rust hybrid migration Phase 1

- Update: Introduced the engine-neutral `SteamProtocolClient` protocol seam (aggregate of the five session contracts) and the `DepotDownloader` seam with capability declarations, bound in Hilt to the existing JavaSteam-backed singletons. No behavior change.
- Update: Added the verified Rust depot core `wallhub-rust/` (Adler-32, LZ4/ZSTD + Steam `VSZa` container decoding, AES-256-ECB/CBC chunk crypto) with 16 passing host tests, `scripts/build-rust.sh`, and a feature-branch-only `build-hybrid.yml` workflow.
- Fix: Corrected `docs/MIGRATION_STATUS.md` claims that kSteam dependencies and interface files already existed; they were landed in this change instead.
- Verification: Fresh restorable snapshot `archive/wallhub-source-20260902T190409Z-pre-ksteam-rust-phase1.tar.gz` (SHA-256 recorded). Kotlin verification via GitHub Actions on the deployment commit; Rust verification locally (cargo test/clippy/fmt).

### Rust depot engine wiring (hybrid Phase 3)

- Update: Added network chunk download (tokio + reqwest HTTP/2, rustls) and Android JNI exports to `wallhub-rust`; verified release cross-compiles for all four ABIs with NDK 27 (arm64 3.4 MB).
- Update: New Kotlin JNI bridge `WallHubRust` (runtime availability detection), `RustDepotDownloader`, and `HybridDepotDownloader` routing engine (Rust preferred, Kotlin fallback after 3 consecutive failures with 5-minute re-probe) bound as the default `DepotDownloader`.
- Update: CI (debug-apk.yml, verify.yml) now installs Rust + NDK 27 and builds the native core before Gradle; R8 keep rules protect the JNI surface.
- Verification: cargo test 19/19, clippy clean, fmt clean, four-ABI cross-compile locally; Android verification via GitHub Actions on the deployment commit.
