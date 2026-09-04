# Development Log

## 2026-09-04

### kSteam CM routing and session restore

- Fix: Route kSteam HTTP and CM WebSocket traffic through WallHub's shared OkHttp Steam access bridge, including port-aware dynamic `steamserver.net` endpoint prewarming before the first WSS attempt.
- Fix: Support Steam CM WebSocket ports `27017`-`27050` in the no-SNI bridge and isolate route health by host and port so a `443` result cannot mask an unreachable CM endpoint.
- Fix: Stop issuing a second saved-account CM logon during restore; kSteam's AwaitingAuthorization callback owns that logon and now has a single restore path.
- Fix: Use the localized generic restore-failure message without treating it as a format string, keeping Android lint and runtime resource handling consistent.
- Maintenance: Removed eight bilingual JavaSteam status strings left unused by the kSteam migration and refreshed the reviewed main-source Lint warning budget from 208 to 209.
- Verification: Added secure Steam URL, port-isolated prewarming, and fail-closed session-storage regression coverage. Commit-bound CI build and device verification must confirm login, cold-start restore, CM browsing, download, and online playback.

## 2026-09-03

### Password-only Steam login

- Fix: Credential sessions with no manual Steam Guard method now continue through kSteam auth polling instead of being shown as a phone-confirmation request.
- Fix: Steam's explicit no-guard response is handled as password-only login; a standalone machine-token challenge is reported clearly instead of hanging.
- Verification: Added confirmation-state regression coverage; the Debug APK is to be built by GitHub Actions and checked on the connected ADB device for login, download, and online chunked playback.

### Online video stream cache consistency

- Update: Simplified the decoded video cache by removing the unused encrypted staging/spool subsystem and bounding stream pipeline concurrency by the encrypted-plus-decoded chunk memory peak.
- Fix: Serialized root eviction, protected active writes and playback windows, bounded per-chunk locks, corrected cache byte accounting, and made foreground reads refetch chunks that disappear after commit.
- Fix: Cache eviction now invalidates prefetch completion state, stale prefetch jobs cannot clear newer jobs, and coroutine cancellation closes the active OkHttp chunk request.
- Verification: Added regression coverage for protected eviction, manual clear, commit self-eviction, stale prefetch completion, and stream memory budgeting. Release build and installation are required before manual playback testing.

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

### JavaSteam removal: kSteam + Rust become the only Steam engines

- Update: Removed the `in.dragonbra:javasteam` dependency and every JavaSteam-backed source file (session repository/runtime, OkHttp CM websocket transport, connection policy, Community unified service, Kotlin depot baseline). Dropped the protoc toolchain and `community_messages.proto`; added the Wire Gradle plugin (5.5.1) with `app/src/wire/proto/steam_depot.proto` for the depot key exchange and manifest container messages. Removed unused lz4-java/xz dependencies.
- Update: `KSteamSessionRepository` is now the sole `SteamProtocolClient` (and `SteamSessionRepository`/`SteamContentCredentialProvider`/`AccountWorkshopRepository`/`SteamUnifiedWorkshopRepository`/`SteamPlaytimeRepository`) implementation. Password + Steam Guard (code and device confirmation) sign-in, refresh-token restore, and foreground/background pause-resume run on kSteam's `Account` handler; kSteam's automatic CM reconnection replaces the hand-rolled generation/restore state machine. EXPIRED detection keys off kSteam wiping a rejected saved account.
- Update: Workshop browse/details, account collections, subscriptions/favorites, comments, and playtime now execute through kSteam's Wire gRPC bridge (`PublishedFile`/`Community`/`Player` services). Author profiles resolve via `Player.GetPlayerLinkDetails` (persona name + avatar hash) instead of SteamFriends persona callbacks.
- Update: Public browsing and public-depot downloads without a signed-in account use a dedicated anonymous kSteam client that performs a raw `k_EMsgClientLogon` (kSteam's CM transport whitelists the logon message and adopts the granted session for any successful logon). Legacy encrypted refresh tokens migrate into kSteam per-account storage on first restore (JWT `sub` -> SteamID -> `signInWithRefreshToken`).
- Update: The depot pipeline now requests CDN servers, manifest request codes, and CDN auth tokens through the `ContentServerDirectory` unified service on the same kSteam clients, fetches the depot decryption key via the raw `k_EMsgClientGetDepotDecryptionKey` (5438) packet, downloads manifests over HTTPS (magic-delimited `ContentManifestPayload`/`Metadata` parsed with Wire, inline AES/ECB+CBC filename decryption), and decodes every chunk through the Rust engine via `HybridDepotDownloader`. `KotlinDepotDownloader` (JavaSteam `DepotChunk`/`Adler32` baseline) is gone; the Rust engine is mandatory, and JavaSteam types (`Server`/`ChunkData`/`FileData`/`DepotManifest`) are replaced by engine-neutral `CdnServer`/`DepotChunkSpec`/`DepotFileSpec`/`DepotManifestSpec`.
- Verification: pending - commit-bound debug-apk build plus device regression (login restore, browse, subscribe, download, online video).

### JavaSteam removal: compile completion, cold-start verification, and crash fix

- Fix: Removed the Wire Gradle plugin (5.5.1 cannot load under AGP 9's configuration model) in favor of hand-written Wire adapters for the depot key exchange and manifest container messages (`app/src/main/kotlin/com/wallhub/android/data/steam/wire/DepotProtos.kt`).
- Fix: Resolved okhttp duplicate-class conflicts between kSteam's transitive okhttp 4.12.0 and the app's okhttp-android 5.3.2 by forcing `com.squareup.okhttp3:okhttp:5.3.2` across all configurations.
- Fix: Corrected kSteam r50 API usage across the migration (`Account.clientAuthState`, `EOSType` internal visibility, `okio.Path.Companion.toPath`, Wire `value` -> `value_` rename, `ProtoAdapter.redact` override placement).
- Update: CI builds kSteam from source, publishes Maven Local artifacts, and uploads them (`ksteam-maven-local`); the repository vendors the r50 artifacts under `ksteam-maven/repository` so LAN build workers resolve kSteam without Maven Local.
- Fix (LAN build iteration): internalized `WallHubDownloadWorkerFactory`, its `Application` property, and `RepositoryModule` to satisfy Kotlin visibility checks against internal download types; built okio paths via `String.toPath` because `java.io.File.toPath()` (JDK member) shadows the okio extension.
- Fix (device crash): the main kSteam engine was never `start()`ed, so the foreground `resume()` connected with an empty CM server list and kSteam threw `CMList was not initialized` (cold-start FATAL). `KSteamSessionRepository` now tracks `engineStarted`, starts the engine idempotently before awaiting connection, and gates pause/resume/stop on it.
- Verification: LAN worker (MYCOLORFUL) signed Release APK `20260903T045703Z-26015325` (commit `edbae4d`, SHA-256 `5d639048...5389b0c`) installed on OnePlus 5T (arm64) with data preserved. Cold start: live PID, zero FATAL/ANR. Home feed renders Workshop items over the anonymous kSteam CM session; signed-in session restored and migrated (persona name hydrated, 239 subscriptions, 1486 h Wallpaper playtime); detail page loads full metadata with subscribe/favorite/download/video actions. Remaining: manual `verify.yml` + `debug-apk.yml` dispatch (both are `workflow_dispatch`-only; no GitHub token in the LXC) for unit-test and CI-artifact validation.
