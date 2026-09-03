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

### Final hybrid migration delivery (commit 91991a6)

- Update: Full Phase 2+3 delivery verified end to end. Rust depot core (verification, VSZa/zstd decode, AES chunk crypto, HTTP/2 chunk download) cross-compiled for four ABIs in CI and packaged into the debug APK.
- Update: HybridDepotDownloader bound as the default depot engine; per-capability routing with Kotlin fallback and diagnostics. Existing download pipeline intentionally left on its proven path until pipeline migration (next step).
- Verification: verify.yml green (131 unit tests, lint budget, detekt, ktlint, signed release, 40 MiB budget). Debug artifact commit-bound, SHA-256 and certificate verified. OnePlus 5T (arm64) install + cold start: live PID, zero FATAL/ANR/OOM, Home feed rendered ~2.49M Workshop items.

### kSteam migration Phase A: Kotlin 2.3.20 toolchain

- Update: Upgraded Kotlin 2.1.21 -> 2.3.20 (kSteam's toolchain) and aligned kotlinx-coroutines to 1.10.2. Snapshot `archive/wallhub-source-20260902T223744Z-pre-kotlin-2.3-upgrade.tar.gz` taken before the change.
- Verification: full CI (verify.yml) plus debug APK build on the upgrade commit.

### kSteam migration Phase B-1: engine source build and shadow client

- Update: CI now clones kSteam at a pinned SHA (e751f78, with the SteamDatabase protobufs submodule), publishes it to Maven Local, and the app consumes `bruhcollective.itaysonlab.ksteam:core:r50`. `settings.gradle.kts` resolves `mavenLocal()`.
- Update: Added `KSteamProtocolClient` implementing the engine-neutral protocol seam: session core (start/login/Steam Guard/refresh-token restore, pause-resume lifecycle) wired to kSteam; Workshop browse/collections/comments/interactions/playtime degrade gracefully with diagnostics (shadow mode). Added `KsteamEncryptedPersistenceDriver` (Keystore-encrypted kSteam session storage).
- Verification: full CI on the landing commit; runtime behavior unchanged (JavaSteam remains the active engine).

### kSteam migration Phase B-1: engine source build green

- Fix: kSteam's HEAD protobufs sync broke its own vendored proto tree; pinned the last known-good revision (c6ca6ef) and added a Wire-diagnostic-driven repair loop (missing imports + stub messages) that converges before publishToMavenLocal.
- Fix: Aligned KSteamProtocolClient/KsteamEncryptedPersistenceDriver with the r50 API (okio path conversion, Account-bound auth state flow, secure per-SteamId persistence members).
- Verification: kSteam publishToMavenLocal green in CI; app compilation landing next.
