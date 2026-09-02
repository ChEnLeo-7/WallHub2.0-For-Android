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
