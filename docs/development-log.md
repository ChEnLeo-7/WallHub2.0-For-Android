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
