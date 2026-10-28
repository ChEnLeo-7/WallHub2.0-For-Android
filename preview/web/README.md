# WallHub Web Preview

The primary Web renderer is `preview/web-dom`: native semantic DOM plus pinned Material Web components. Shared presentation state, design tokens, resources, and Android Compose UI remain under `:shared:ui`.

The original Compose/Wasm spike is retained in `preview/web` only as a compatibility probe. It proved rendering, CJK resources, browser state, history, and Android accessibility exposure, but failed the hard desktop-keyboard gate: Canvas semantics produced no DOM focus targets or dialog focus trap. It must not be presented as the production Web mirror.

## Current phase

The page currently shown is an adaptive Current UI shell and accessibility probe, not a completed WallHub screen mirror. It verifies:

- official Material Web 2.5.0 buttons, text field, and dialog;
- native DOM roles, Tab traversal, dialog focus trapping, Escape dismissal, and focus restoration;
- Compact `<600`, Medium `600–839`, and Expanded `>=840` navigation;
- browser history, persistent state, real dark/light appearance switching, and reduced motion;
- self-hosted Simplified Chinese font resources and an 8 MiB compressed-transfer gate.

Do not describe the preview as complete until the route/state/theme/language coverage matrix is closed.

## Build and serve

```bash
scripts/build-web-preview.sh
scripts/serve-web-preview.sh
```

Both commands require Node.js 24.18.0 with npm 11.16.0. `shared/ui-contract/wallhub-current.json` is the renderer-neutral Current UI contract; each build checks Android/shared Kotlin parity and verifies its generated Web CSS before bundling. The build uses `npm ci` with a committed lock, bundles only imported Material Web components, generates deterministic gzip assets, and enforces the transfer budget. The default LAN port is `4180`; override it with `WALLHUB_WEB_PORT`. The server falls back to `index.html` for extensionless deep links while preserving real 404 responses for missing assets. Build output uses content-hashed immutable assets and an atomic release symlink; the server refuses output whose source hash is stale.

The deterministic Fake Backend and State Lab live in `preview/state-lab`. Normal `/` renders the Current Home slice without developer controls; append `?lab=1` and optionally `&scenario=loading`, `empty`, `error`, or `content` to inspect reproducible states. Run the pure state regressions with `npm test --prefix preview/web-dom`.

The required browser gate builds a fresh release, runs the state regressions, opens it in Android Chrome, and verifies exact breakpoints, reduced motion, dialog focus trapping/restoration, theme persistence, history, and State Lab URL/reset behavior:

```bash
WALLHUB_ADB_SERIAL=192.168.2.234:33445 scripts/verify-web-preview.sh
```

To rebuild the non-shipping Compose/Wasm compatibility probe:

```bash
scripts/build-compose-wasm-probe.sh development
scripts/build-compose-wasm-probe.sh production
```

The probe script uses one Gradle worker, in-process Kotlin, a 1536 MiB heap, verified Binaryen 125, and cgroup OOM checking.

## Font provenance

```bash
scripts/verify-web-font-subset.sh
```

The script verifies immutable Google Fonts and FontTools inputs by SHA-256, regenerates the subset from the committed character ledger, and requires a byte-for-byte match with the committed TTF.

## Supported browsers

Modern Chrome/Edge and Android Chrome are primary. Safari requires practical WasmGC support only for the retained Compose probe; the primary DOM renderer does not require WasmGC. Firefox is best effort. The OnePlus 5T currently validates Chrome 155; its Via browser is not an acceptance browser.
