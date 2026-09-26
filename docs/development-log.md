# Development Log

## 2026-09-13

### Fixed

- Reduced formal chunk fallback latency, shared CDN route health across downloads, and published an initial speed from the first committed chunk.
- Added monotonic diagnostics for formal chunk request, response, decode, file-write, progress callback, and Room persistence boundaries.
- Added non-sensitive monotonic download timing telemetry for the homepage click-to-first-visible-speed path.
- Kept Steam CDN calls open until manifest response bodies are fully consumed, preventing the concurrent CDN probe cleanup from closing successful sockets prematurely.
- Preserved cancellation propagation so losing CDN probes and user-cancelled downloads still cancel their underlying OkHttp calls.
- Expanded the patched kSteam CI cache to include its locally published transitive modules, preventing cache-hit builds from missing `kotlinx-vdf`.
- Prewarmed Workshop targets and public Steam depot access while users view project details, reducing work left on the click-to-first-chunk path.
- Added bounded, session-scoped caches for Workshop targets, depot IDs, depot keys, and CDN directories without persisting credentials or authorization tokens.
- Reused the application Steam HTTP connection pool across downloads and refreshed cached CDN directories after recoverable transfer failures.
- Allowed direct-file downloads to proceed without waiting for Steam credential restoration.
- Added atomic per-task manifest caching and chunk checkpoints so resumed Steam downloads reuse
  committed chunks without re-requesting the manifest or rescanning completed staging data.
- Added conservative playback coordination so active online Workshop sessions block new background
  download, direct-file, and conversion work without changing the playback pipeline itself.
- Hardened depot manifest section-length parsing against truncated or oversized sections.

### Verification

- Added regression coverage for normal response-body consumption and coroutine cancellation of an in-flight CDN call.
- Added cloud-executed regression coverage for target request coalescing, cache expiry and invalidation, Steam-client isolation, depot-key copying, and direct-file credential bypass.
- Restored commit-bound GitHub Actions Release signing and deployment so cloud-built APKs preserve the installed application identity during ADB updates.
- Made pause propagate through concurrent CDN and chunk work without leaving parent coroutines waiting for missing results.
- Serialized task actions, preserved pending control requests across worker progress writes, and made cancellation override an in-flight pause.
- Replaced stale unique work when resuming or retrying, and wait for WorkManager scheduling operations to commit before reporting success.
- Made the download-to-conversion handoff conditional on no pending control request and cleaned interrupted conversion artifacts on every terminal path.
- Disabled conflicting controls while an action is pending, made large-file verification responsive to pause/cancel, and routed batch retry through storage permission checks.
- Added checkpoint and playback-coordination regression coverage for manifest-bound resume state and
  background work admission.

## 2026-09-25 — the Steam Content SDK owns the Android session and the Workshop download

The Steam Content SDK arm now performs a full Workshop download on the test device with the application
serving only its own UI and post-download steps. The reading that proves it: the download worker returns
`SUCCESS`, the item appears in 我的 → 下载管理 → **已完成** at 43.8 MB, and 我的 → 本地 lists it as
`HTML · 32.1 MB`, i.e. the app walked the tree the SDK published and typed it from its content. A cold start
restores the stored session without a password (`credential_load …` → `credential_roundtrip match=true` →
`phase_signed_in`).

### Changed in this repository

- `SteamContentSdkSessionBackend` records what the SDK says about a failure (`sdk_detail`), reads the stored
  credential back and compares it byte for byte (`credential_roundtrip`), and mirrors every session stage to
  `Log.i` under `WallHubSteamSession`; before this only the classification line was device-readable.
- The token the SDK retains is persisted only when it differs from the stored one, and a store read failure is
  never treated as an empty store.
- `SteamContentSdkPlatformSession` gained `lastError()` (the SDK's own redacted diagnostic) and the
  classification line carries it, which is how a dropped CM connection was told apart from a refused token.
- AAR pins moved `0.1.29` → `0.1.34` (`35a6127`, `143c82f`, `b80e71d`, `de659dc`, `52ed862`, `bd8a2cb`); all six
  carry SDK-side fixes, none of them Kotlin behaviour.

### Still on the old backend

`SteamContentSdkWorkshopRepository` already implements the three Workshop/playtime contracts but is **not**
bound into the graph: `RepositoryModule.kt:131/139/143` still resolve them to `KSteamSessionRepository`, and
the temporary `wallhub.useSdk*` flags are still enabled. Binding that repository, reading the Range-stream arm
on the device, and then deleting the kSteam files plus `wallhub-rust` is the remaining path to "kSteam fully
replaced".

## 2026-09-25 (later) — kSteam and `wallhub-rust` are deleted, and ETC2 is the app's own module

The rows the previous entry left open are closed. Both old backends are gone from the tree, the graph serves
every Steam contract from the SDK, and the one product-format primitive the depot core owned now has an
app-owned native module. Commits: `5c5641c` (ETC2 module), `6347a30` (deletion), against SDK `e65800d` /
AAR `0.1.35`.

### ETC2 moved out of the depot core

`TexMobileConverter` no longer reaches into `WallHubRust.compressEtc2Rgba`. The encoder is now
`app/src/main/cpp` (the vendored etcpak sources with their license, one JNI-free core, one bridge) built by
`scripts/build-etc2.sh` into `libwallhub_etc2.so` for all four ABIs through the NDK clang toolchain - not a
Gradle CMake step, because the build worker's SDK has no CMake package. The move was proven before the old
bridge was deleted: 10 sizes x 5 patterns from 4x4 to 2048x256, old versus new, 50/50 byte-identical under
ASan/UBSan, and the first run caught a real bug in the new core (the output buffer was sized in blocks rather
than bytes). Each library exports exactly one symbol - `Java_com_wallhub_prototype_mpkg_Etc2Compressor_nativeCompressRgba`,
the name `javac -h` derives for the shape-equivalent class - and needs only libc/libm/libdl.

### What the deletion took with it

Five kSteam Kotlin files (`KSteamSessionRepository`, `SteamCredentialStore`'s kSteam driver,
`KSteamContentAccess`, `SteamContentTransfer`, `SteamContentDownloader`), `wallhub-rust`, `RustDepotDownloader`,
`HybridDepotDownloader`, `SteamProtocolClient`, the session-backend router, `CdnServer`/`parseDepotManifest`,
`SteamStreamAdaptivePolicy`, `SteamDownloadCheckpoint`, the kSteam build scripts and Maven dependency, and the
three `wallhub.useSdk*` flags: 17262 deletions against 390 insertions. `SteamCredentialStore.kt` stayed, because
`EncryptedSteamCredentialStore` is what the SDK session backend writes and the download/range arms read; its
one kSteam-typed helper (a legacy JWT `SteamId` reader whose only caller was a test) is gone, so no Kotlin file
imports kSteam any more.

Two things made that safe rather than hopeful. The declarations the surviving code still needed were re-homed
first, into `SteamDownloadModels.kt`, `DownloadDiskReservations.kt`, `WorkshopStaging.kt`, and
`WorkshopCommentInput.kt` - the keep set was computed as the transitive closure of what surviving main code
references, not read off by hand. And the arm-A parity readings were taken from the *installed* APK (still the
kSteam arm) before anything was deleted: subscriptions 1 / favorites 239 / votes 0 for `adgjl1182`, the
subscriptions list, the first six favorites with counts and sizes, item `3799142774`'s detail with 47 comments
and its two newest comments - plus the finding that the old path's **Voted list fails outright**
("无法读取个人资料库"), so the SDK's new `myvotes` ordinal can only improve that collection.

### Build and evidence status

- The SDK's `WorkshopPersonalList` gained `VOTED = 4` (SDK ABI minor 20, `e65800d`), inside the
  owner-approved schema route, so the Voted collection is served rather than answered with an empty page.
- The build worker's `Invoke-AndroidBuildWorker.ps1` called the deleted `scripts/build-rust.sh`; it now calls
  `scripts/build-etc2.sh` (backup kept beside it as `.bak-etc2`).
- `.github/workflows/verify.yml` was rewritten around what a hosted runner can actually do: the SDK preview AAR
  is a local, gitignored artifact, so CI cannot build the APK. The workflow now cross-compiles the ETC2 module
  for every ABI and guards that the removed backends stay removed; the APK is built on the LAN worker.
- **Open**: the application's Kotlin compile has not been run anywhere yet (Gradle is not allowed on the source
  host), so the next build is the first compiler this change meets; and the device readings for the SDK-served
  Workshop reads, depot-backed playback, and the Voted list are still to be taken.

### Where the current state lives

The SDK repository's handover document (`../../Steam Content SDK/docs/AGENT_HANDOVER.md`, i.e.
`/root/pi/Wallhub-Release-2.0/Steam Content SDK/docs/AGENT_HANDOVER.md`) is the entry point for a new session: snapshot, the device-driven fix chain, the exact build/install/read
commands, the traps (never `pm clear`; the app is not debuggable, so logcat is the only reading channel), and
the prioritized remaining work. The SDK's `progress.md` §`CD4c` holds the evidence log for this campaign.

## 2026-09-25 (device run) — the SDK-served Workshop reads, and what the first compile found

The deletion build reached the device (`214dace` + `56ad037`, worker APK `20260925T065921Z-190919415`, SDK AAR
`0.1.35`) and the same surfaces the old path had been read on were read again. The Android source now compiles
against the 0.1.35 AAR with no kSteam, no `wallhub-rust` and no `libwallhub_rust.so` in the APK, and the ETC2
encoder ships for all four ABIs.

**Three defects the run found, all fixed**

1. `videoFileExtension` and `readBytesBounded` were dropped by the deletion; both are extension functions the
   re-homing scan could not see (`fc7dd9f`, `327db7b`).
2. `WALLPAPER_ENGINE_APP_ID` had been defined once per package by the deleted files, so two callers lost it;
   the constant now lives in `core/model` (`214dace`). The same commit deleted `data/steam/wire/DepotProtos.kt`,
   dead Wire protos that had compiled only because kSteam brought `com.squareup.wire` transitively.
3. `SteamContentSdkWorkshopRepository` read through the application-wide engine, which holds no session, so the
   profile answered 0/0/0. It now takes `@SteamSessionSdkEngine` - the engine the session domain authenticated -
   and the profile answers the same numbers as the old path (`56ad037`).

**Parity, read on the device**

| Surface | Old path (arm A) | SDK (arm B) |
| --- | --- | --- |
| Cold-start session restore | `credential_roundtrip match=true` -> `phase_signed_in` | same |
| Subscriptions / favourites / votes counts | 1 / 239 / 0 | **1 / 239 / 0** |
| Playtime | 1486 h | **1486 h** |
| Voted list | error "无法读取个人资料库" | empty list, no error |
| Subscriptions list | 1 item, 20.4 MB | same item, size "— MB" |
| Favourites list | 6 items with sizes and type badges | first 5 identical, sizes/badges missing |
| Detail + comments (community source) | 评论 (47) on one item | 评论 (25) and 评论 (58) on two others |
| Detail (CM source) | served by kSteam | resolved, but the comments tab has no count |
| Depot-backed playback | not read | not read |

**Open, and mostly SDK-side**: a personal-list row cannot report file size or content type; the SDK's search
request omits the `return_*` flags the old path set, so a CM-source browse can return rows with only ids when
nothing is cached; the CM detail read carries no comment count; and the Range arm was not exercised because
streaming is triggered from the Library's play affordance, which this run did not reach.

**On the device right now**: the Library's rating filter has one value selected (from this run's taps), so the
favourites list shows "没有符合条件的收藏" until it is cleared in the filter sheet (`恢复默认` -> `应用筛选`).
The Workshop data source is back on Steam Community HTML.

## 2026-09-25 - the SDK owns the Range arm, and three SDK defects it exposed

`6347a30` deleted kSteam and `wallhub-rust`; this span is what the SDK-arm readings after that deletion
turned up. Two device rounds: `0.1.37` + `facccbb` (the App scope), then `0.1.38` + `32d3fa9` (the tag
codec).

**What the App scope did and did not fix.** The device log now shows the request the adapter sends
(`request.app_id = 431960` in the staged JNI, `first-id`/`first-size` intact), but the personal rows still
arrived with `first-tags=[]`. The App scope was necessary and not sufficient: the SDK read
`PublishedFileDetails.tags` - field 52, which the locked schema defines as a repeated record with its text
at that record's field 1 - as a repeated string, so every tag element was a record's bytes
(`\n\u{5}Scene`), and the Workshop tag validator refuses a control character. One refused entry makes the
whole tag field unknown by decision, so every tag list arrived empty while the service had sent it. That
one defect was behind both remaining failures: the untyped Library rows *and* the "Steam CM did not return
this Workshop item" detail error (a details response for an item that has tags died in the same place,
which is why it looked intermittent). Fixed in SDK `5f2dc5e`, AAR `0.1.38`.

**Read back on the device**

| Surface | Reading |
| --- | --- |
| Library rows | every card typed again - 网站 / 场景 / 视频 - with `first-tag-count=8` and the item's own tags on the diagnostic line; `first-content-type=0`, so for these rows the type comes from tags alone |
| CM item detail (`3723118336`) | resolved: title, preview, 简介, author, id - the intermittent refusal is gone |
| Video play action | appears on a video row now that the row is typed (it is gated on the type) |
| Range arm | `SteamVideoStream: playback served by=steam-content-sdk app=431960 depot=431960` - the SDK resolved the item and its manifest and owns the range path |
| Range arm, stream open | fails inside the SDK: `depot_access_denied: content resolution failed at manifest_request_code`, and on retry `content resolution failed at workshop_details` |
| Voted collection | fails: `invalid_input: the request was refused before it was sent`; the count stays 0 |

**The two stages, and where they come from.** `resolve_workshop_content` still calls the *unscoped*
`workshop().details(item)` (`crates/sc-content/src/lib.rs:2216`), so the chain that must learn an item's App
asks the question that can come back without the item - the retry's stage. And the range open re-resolves
the App/depot identity through a resolver with no reusable material
(`crates/sc-content-ffi/src/lib.rs:1776`), so it asks for a fresh manifest request code - the first
attempt's stage.

**Corrections to earlier readings.** The Voted list was recorded as "served but empty"; the adapter now
logs the typed failure it used to swallow, and it was a failed read. And the personal rows' file *size* was
never the missing fact - the size came from the item's own field; the type came from the tags.

**Environment.** To reach the Range arm the "SteamKit 在线分块播放" toggle had to be turned on (it defaults
off); its label still names SteamKit although the implementation is the SDK's. The first resolution took
about two minutes end to end.

## 2026-09-25 (2) - the Range arm plays, after four SDK defects

The Library's play affordance reaches the SDK, and the SDK now serves the ranges end to end. The readings that
got there, one build each, each one naming the next stage because the SDK's flattened messages were given
their inner text along the way:

| Build | Reading | Fix |
| --- | --- | --- |
| 0.1.38 | `first-tags=[]` on every personal row; `Steam CM did not return this Workshop item` for items that had tags | the tag field is a repeated record; the codec read it as a string, so the tag validator dropped the whole list |
| 0.1.39 | `content resolution failed at workshop_details` | the resolution chain's own details read was unscoped; the resolve request now names the App |
| 0.1.39 | `content resolution failed at manifest_request_code` | one playback resolved its identity three times, asking for three Manifest Request Codes; the SDK now reuses one answer per identity |
| 0.1.40 | `the range stream could not serve that read` | the stream required a file's chunks in listing order, where a live manifest sends a rotation |
| 0.1.41 | the same line, after the stream opened | the range plane had the encoded source the download engine wants, while a stream validates against the manifest's checksum and needs decoded bytes |

The final reading: `playback served by=steam-content-sdk app=431960 depot=431960`, ExoPlayer's video decoder
initialising, `00:15 / 00:30` on the player's own controls, a seek that advanced the picture, and a clean
release when the screen was left. Start, seek, and cancel are read; a rebuffer was not forced.

The Voted collection still fails (`invalid_input: the request was refused before it was sent`) - a locally
refused call the SDK's taxonomy does not name precisely enough to diagnose from the device, so it needs a live
probe of the `myvotes` selector rather than another guess.

## 2026-09-26 - the two streaming-cache controls, read on the source before another build

The SDK arm is the only playback backend left, so the two settings-screen controls were re-read against the
implementation rather than assumed.

**"Clear online playback cache" used to claim work it had not done.** `SteamVideoStreamCache.clearRoot`
returns `0` when the cache root holds no files
(`app/src/main/kotlin/com/wallhub/android/data/downloads/SteamVideoStreamCache.kt:594`), and nothing in the app
constructs `SteamVideoStreamCache` any more - the class is only ever reached through that static clear
(`.../SteamWorkshopVideoStreamRepository.kt:96`), because SDK-served playback keeps its decrypted chunks in one
bounded in-memory cache per stream (`.../SteamContentSdkVideoStreamSession.kt:11`). So on a build that no longer
writes the on-disk cache the button reported "Cleared 0 B of online playback cache" - a no-op dressed as work.
It now reports `settings_streaming_cache_empty` ("No stored online playback cache to clear") for a zero reading
and keeps the byte count for a real one
(`app/src/main/kotlin/com/wallhub/settings/SettingsViewModel.kt:246`); the legacy leftovers it can still free are
still freed. The Chinese copy was added too (`res/values-zh-rCN/strings.xml`).

**"Cache the complete file while watching" has no control to fix.** The banner that would carry it is gated on
`fullCacheState` being `CACHING`/`COMPLETE`/`ERROR` (`.../feature/detail/OnlineVideoPlayerScreen.kt:571`), and
the state is the contract default that never leaves `IDLE`
(`core/model/RepositoryContracts.kt:186`); `startFullCache` has no caller anywhere in `app/src`. The affordance
never appears, so it promises nothing on screen - it is unreachable code, not a visible no-op, and was left
alone rather than edited without a device reading. What the slider does promise - "Limits cached decrypted Steam
chunks for streaming" - is true: it is passed to the SDK range stream as that stream's own ceiling
(`.../SteamWorkshopVideoStreamRepository.kt:110` and `:155`, floor 128 MB). Direct-file items are read over
plain HTTP ranges by `SteamRemoteVideoStream` and hold no decrypted-chunk cache, so the ceiling correctly does
not apply to them.

Both controls still need one device reading to confirm the copy change - it is in the next app build.

## 2026-09-26 (2) - AAR 0.1.47 and the cache-copy fix are on the device

The worker published AAR **0.1.47**
(`sha256=1d43401ad58846fe76720038346ba75a36f3bb7b333b69c4128caa23544219c8`), the app pinned it at `4054275`,
and the release APK built from that revision was installed with `adb install -r`. The APK really carries this
ABI: `unzip -p <apk> lib/arm64-v8a/libsc_content_ffi.so | sha256sum` is
`d8f5c657ac2f527fdf0efcc4241987a07af518e8f28b95b00db2eca16f68875b`, byte-identical to the AAR's
`jni/arm64-v8a/libsc_content_ffi.so`. Artifact: `/root/builds/wallhub-release-20260926T144124Z-484913008.apk`.

Cold start on the installed build:

```text
I/WallHubSteamSession: credential_persisted persisted=true stage=restore length_class=long
I/WallHubSteamSession: phase_signed_in
W/WallHubSdkPlayer: openPlayerProfiles count=16 ids=[76561198148035115, ...]
W/WallHubSdkPlayer: openPlayerProfiles count=12 ids=[76561198812590217, ...]
I/WallHubSdkWorkshop: workshop page rows=30 of 30 collection=public first-id=3799142774 first-type=WEB
    first-size=50684227 first-tag-count=7 first-tags=[Web, Technology, Wallpaper, Audio responsive,
    Customizable, Dynamic resolution, Everyone]
```

Session restore, the chunked profile reads (the 16-account SDK ceiling), and the full public page all still
read correctly, so the new ABI did not regress startup. The `W/WallHubSdkPlayer` stack traces are the
repository's own deliberate caller diagnostics, not a failure.

This build also carries the online-playback-cache copy change from `7b07a4d`; the settings screen still needs
one reading of the button with an empty cache to confirm the new message. The Voted collection read still needs
one navigation to 我的 -> 我的投票 before its new named refusal can be read.

## 2026-09-26 (3) - the SDK browse request ignored the query's own language

Reading the four filter fields end to end for ① turned up one that was not actually wired: the SDK browse
request sent the **phone's** language rather than the query's.

```kotlin
language = defaultSteamWorkshopLanguage(),        // before
language = normalized.language.coerceAtLeast(0),  // after
```

`WorkshopBrowseQuery.language` (`core/model/WorkshopModels.kt:77`) is the field a caller states and its default
is already the locale's (`:80`), so nothing changes for a query that does not name one - but a query that does
was silently ignored, while the COMMUNITY_HTML builder has always honoured it
(`data/workshop/CommunityWorkshopRequestBuilder.kt:121` and `:182`). The SDK's contract makes zero mean "the
service's own default" (`crates/sc-content-ffi/src/workshop.rs:2042`, omitted on the wire at `:2535`), which is
what the clamp keeps. The unused import went with it.

The other three fields were already real: `sdkCreatedAfter`/`sdkCreatedBefore` read
`normalizedCreatedRange()` (`WorkshopQuerySemantics.kt:133`), `days` maps onto the ABI's trend vocabulary in
`Int.toSdkTrendWindow()` (`data/steam/SteamContentSdkWorkshopRepository.kt:913`), and `tagGroups` is
`normalizedRequiredTagGroups()` - each populated from the official Discover descriptor, which carries
`timestamp_start`/`timestamp_end` and `requiredtaggroups`
(`feature/discover/DiscoverQueryAdapter.kt:141` and `:139`, `data/discover/OfficialDiscoverMetadataParser.kt:91`).

## 2026-09-26 (4) - the Voted read is refused by the service, not by us

AAR **0.1.48** (`sha256=3e1a4bf0970d8852ade94aaeb91232d9261563dff5e02b3a3b0c2e3dda6203da`), app revision
`b677ea9`, installed from `/root/builds/wallhub-release-20260926T150457Z-1764520491.apk` (the APK's own
`lib/arm64-v8a/libsc_content_ffi.so` is byte-identical to the AAR's, `4fc3f2050467a50a28085c8b1e57843bcfdc486a39cc18f4b6f887c8517fad0a`).
Opening 我的 on a cold start read:

```text
23:08:00.832 I/WallHubSdkWorkshop: workshop page rows=1 of 1 collection=SUBSCRIPTIONS first-id=3774904326 ...
23:08:01.433 I/WallHubSdkWorkshop: workshop page rows=1 of 1 collection=FAVORITES first-id=2905017768 ...
23:08:02.015 W/WallHubSdkWorkshop: workshop read unavailable: access_denied: the service refused the request
```

The 0.1.48 AAR carries the fix that keeps the session's typed published-file seam, so this sentence is the real
classification rather than the flattened one. It says the first two collections answered and the Voted read was
**refused by Steam** - `access_denied` is the service-answer arm of the mapping. Every local hypothesis is now
dead: the list ordinal, the `myvotes` selector, the one-row window, and the session state were each proven
correct (see the SDK's `progress.md` CD5j/CD5k). The app's own `runCatching{}.getOrDefault(0)` on that call
(`feature/profile/ProfileScreen.kt:74`) is why the screen showed 0 rather than an error.

The same change lands with 0.1.49: a service refusal now prints the protocol result code Steam sent
(`access_denied: the service refused the request (service result N)`), which the next reading needs to choose
between a rate limit, a privacy refusal, and an invalid selector.

## 2026-09-26 (5) - Voted is refused with EResult 8, and it never worked

AAR **0.1.49** (`sha256=ea649248131c6b6cca92aa93760567535ee0e8eddcd8ed6fea3f5ce6d8771b36`), app revision
`a3fae0c`, installed from `/root/builds/wallhub-release-20260926T151522Z-993322422.apk` (native library
`5eb22e529232248708cc7290127295c16113a6c4d82b6f2f95e4eedf9cde1636`, identical to the AAR's copy). The same
screen now reads the service's own code:

```text
23:18:12.366 I/WallHubSdkWorkshop: workshop page rows=1 of 1 collection=SUBSCRIPTIONS ...
23:18:13.253 I/WallHubSdkWorkshop: workshop page rows=1 of 1 collection=FAVORITES ...
23:18:13.518 W/WallHubSdkWorkshop: workshop read unavailable: access_denied: the service refused the request (service result 8)
```

`EResult 8` is `InvalidParam`. The three reads are identical except for the `type` string, and the two that
answer are accepted, so Steam is rejecting `myvotes` itself. That matches the pre-deletion arm-A baseline,
which already recorded the Voted list failing on the old backend and the profile's votes count at 0, and it
matches Steam's own community page, whose only personal Workshop tabs are `myfiles` and `myfavorites` - there
is no votes tab and no vote filter anywhere in it. The string came from WallHub's own earlier code
(`46e0eb6^:.../SecureSteamSessionRuntime.kt:285`), not from the service.

So the Voted collection is not a client defect: no selector in the approved published-file closure lists the
files an account voted on. The app's own `runCatching{}.getOrDefault(0)` on that read is what keeps it silent
(`feature/profile/ProfileScreen.kt:74`); the Library's Voted tab shows the empty state for the same reason.
