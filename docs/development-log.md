# Development Log

## 2026-09-12

### Fixed

- Kept Steam CDN calls open until manifest response bodies are fully consumed, preventing the concurrent CDN probe cleanup from closing successful sockets prematurely.
- Preserved cancellation propagation so losing CDN probes and user-cancelled downloads still cancel their underlying OkHttp calls.
- Expanded the patched kSteam CI cache to include its locally published transitive modules, preventing cache-hit builds from missing `kotlinx-vdf`.
- Prewarmed Workshop targets and public Steam depot access while users view project details, reducing work left on the click-to-first-chunk path.
- Added bounded, session-scoped caches for Workshop targets, depot IDs, depot keys, and CDN directories without persisting credentials or authorization tokens.
- Reused the application Steam HTTP connection pool across downloads and refreshed cached CDN directories after recoverable transfer failures.
- Allowed direct-file downloads to proceed without waiting for Steam credential restoration.

### Verification

- Added regression coverage for normal response-body consumption and coroutine cancellation of an in-flight CDN call.
- Added cloud-executed regression coverage for target request coalescing, cache expiry and invalidation, Steam-client isolation, depot-key copying, and direct-file credential bypass.
