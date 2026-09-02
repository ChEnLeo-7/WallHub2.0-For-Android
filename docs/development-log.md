# Development Log

## 2026-09-02

### Steam profile avatar

- Update: Request the signed-in account's Persona name and presence data after login or session restore.
- Fix: Merge Persona callbacks according to their status flags so partial updates cannot discard a cached avatar.
- Verification: Added regression coverage for the request mask and partial callback merging. The commit-bound signed Debug APK must build, install in place, cold-start, and display the restored Steam avatar on the target device.
