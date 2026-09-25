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

### Where the current state lives

The SDK repository's [`../../Steam Content SDK/docs/AGENT_HANDOVER.md`](../../Steam%20Content%20SDK/docs/AGENT_HANDOVER.md)
is the handover document for a new session: snapshot, the device-driven fix chain, the exact build/install/read
commands, the traps (never `pm clear`; the app is not debuggable, so logcat is the only reading channel), and
the prioritized remaining work. The SDK's `progress.md` §`CD4c` holds the evidence log for this campaign.
