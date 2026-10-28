# WallHub Compose Web Preview

This module is the browser entry point for the WallHub shared Compose UI. It does not contain a second HTML/CSS implementation of application screens. Product UI belongs in `:shared:ui`; this module owns only Web/Wasm startup and browser-specific adapters.

## Current phase

The page currently shown is a compatibility probe, not a completed WallHub screen mirror. It verifies:

- Compose Multiplatform Material 3 rendering on Wasm;
- Android and Wasm consumption of one shared UI module;
- self-hosted Simplified Chinese font resources;
- browser text input, state changes, and accessibility semantics;
- bounded-memory development and production builds.

Do not describe the preview as complete until the route/state/theme/language coverage matrix is closed.

## Build

```bash
scripts/build-web-preview.sh development
scripts/build-web-preview.sh production
```

The scripts run one Gradle worker with a 1536 MiB heap and fail if the cgroup `oom_kill` counter increases. Production builds download the pinned Binaryen 125 archive into the user cache and verify its SHA-256 before use.

## Serve

```bash
scripts/serve-web-preview.sh production
```

The default LAN port is `4180`; override it with `WALLHUB_WEB_PORT`.

## Supported browsers

The Wasm build requires a modern WasmGC browser. Chrome 155 on the OnePlus 5T is the current phone validation browser. The device's Via browser is not a supported acceptance browser for this preview.
