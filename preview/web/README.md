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

The scripts require Node.js 24.18.0 with npm 11.16.0, run one Gradle worker with a 1536 MiB heap, and fail if the cgroup `oom_kill` counter increases. Production builds download pinned Binaryen 125 into the user cache, verify its archive and executable SHA-256 plus version on every run, precompress browser assets, and enforce an 8 MiB gzip transfer budget.

## Serve

```bash
scripts/serve-web-preview.sh production
```

The default LAN port is `4180`; override it with `WALLHUB_WEB_PORT`. The preview server sends precompressed assets and falls back to `index.html` for extensionless deep links while preserving real 404 responses for missing build assets.

## Font provenance

The Simplified Chinese subset is reproducible from immutable Google Fonts and FontTools inputs. Run:

```bash
scripts/verify-web-font-subset.sh
```

The script verifies both downloaded inputs by SHA-256, regenerates the subset using the committed character ledger, and requires a byte-for-byte match with the committed TTF.

## Supported browsers

The Wasm build requires a modern WasmGC browser. Chrome 155 on the OnePlus 5T is the current phone validation browser. The device's Via browser is not a supported acceptance browser for this preview.
