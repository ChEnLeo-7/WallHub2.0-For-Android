# Vendored uwu-sdk

Source: `F:\AI-Studio\uwu-sdk\uwuCompose`
Upstream commit: `9f64aea`
Working tree snapshot includes the uncommitted responsive-layout changes:
- `framework/layout/SettingsLayout.kt` (new)
- `test/.../SettingsLayoutTest.kt` (new)
- responsive metrics integration in scaffold/toolbar/AppList

Only `uwuCompose/src`, `uwuCompose/res`, `uwuCompose/test`, and `AndroidManifest.xml` are vendored.
The Gradle wrapper is WallHub-owned (`uwu-sdk/build.gradle.kts`) and must not contain component logic.

## Host compatibility seam

`SettingsAppBarScaffold` / `SettingsScaffold` retain optional `statusBarInsets` and
`containerColor` parameters. Their defaults are the upstream values
(`WindowInsets.statusBars` and the Material surface color). This is required because
WallHub already applies status-bar padding at its navigation root and passes
`WindowInsets(0, 0, 0, 0)` to avoid a second inset.

No visual component behavior is forked in this directory beyond that optional seam.

