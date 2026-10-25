# Development Log

## 2026-10-25 — Setup waits for anti-blocking route warm-up

When a user enables automatic anti-blocking during setup, Network now advances to a dedicated Material 3 preparation page instead of starting Steam service probes immediately. The page waits on the real enabled-provider benchmark, requires five measured domestic/regional and five measured international DoH routes, presents an indeterminate route-preparation state with system reduced-motion support, briefly confirms readiness, and only then starts the existing Directory, Public Details, Community HTML, Workshop image, and CM-transport checks. The disabled path still skips warm-up and preserves the prior skipped-check behavior.

An incomplete 5+5 selection no longer silently flows into service checks: the transition shows measured regional counts and offers an explicit retry, while Back returns to editable Network settings. The exact endpoint order/disabled set is serialized before preparation and `SteamAccessManager` must observe that full SMART_DOH snapshot, preventing an already-enabled stale selection from certifying newly edited settings. After readiness, the result remains visible for one second before automatic navigation; active accessibility services instead receive an explicit Continue action so the polite announcement is not removed prematurely. Back from either warm-up or connectivity cancels the active preparation/check. Process restoration maps both transient pages back to Network rather than reviving an IDLE screen without its ViewModel job. Regression coverage locks the Network → Warmup → Connectivity ordering, transient restoration rule, and exact 5+5 readiness boundary.

## 2026-10-25 — Automatic anti-blocking is opt-in on first initialization

Fresh WallHub preferences now initialize **自动防阻断** as disabled. The persisted setting still takes precedence, so existing users who explicitly enabled or disabled it keep their choice across upgrades. The setup wizard and initial home state read the same disabled default, preventing a transient enabled UI before DataStore finishes loading. A model regression test locks the new default.

## 2026-10-25 — Network-ranked 30-provider DoH catalog and Material 3 route manager

Automatic anti-blocking now ships a typed, deduplicated catalog of 20 international and 10 domestic/regional DoH providers. The resolver supports both DNS JSON and RFC 8484 wire GET, validates DNS transaction IDs/RCODE/record lengths, keeps the 64 KiB response bound, and supports endpoint-specific bootstrap addresses without weakening URL host, TLS SNI, platform trust, or hostname verification. Existing installations conservatively retain user ordering and custom URLs while the former three built-ins migrate to their replacement catalog identities.

On startup, settings changes, and active-network changes, a background benchmark measures enabled endpoints against the Steam Community compatibility authority. It selects the five fastest available domestic and five fastest available international providers as an in-memory network-specific set; successful custom routes remain eligible. Business requests never wait for the full benchmark: until it completes they use an interleaved, bounded 5+5 fallback. Empty transient benchmarks are not cached, retry after one minute, and never silently reduce enhanced routing to system DNS only. Query planning gives every interleaved provider one A-query slot before secondary authorities or AAAA work, and selected DoH answers enter the four-address TLS race before system DNS answers so polluted system results cannot crowd them out.

The DoH editor was rebuilt as a grouped Material 3 sheet with one automatic-selection status card and tonal grouped lists for domestic/regional, international, and custom routes. It displays real DNS response latency and selected state, preserves per-route enable/disable, custom HTTPS additions, group-local priority changes, reset/cancel/save, and provides explicit up/down controls and provider-labelled switch semantics instead of drag-only accessibility. The settings and setup flows continue to share the same editor contract.

Regression coverage locks the 20+10 catalog split and uniqueness, legacy URL migration, balanced fallback, five-per-region latency policy, regional interleaving, DNS wire query/response parsing, and protection against four polluted system DNS addresses crowding out a DoH answer. Source/native boundary CI passed for revisions `b09dd28a` and `ccadb92f`. The authorized Windows worker compiled and linted the application successfully; exact revision `ccadb92f` produced `/root/builds/wallhub-release-20261025T055117Z-2950424256.apk` (SHA-256 `f5760867e1bbbc7a77f1b7112a0ef390b4f03cdb56d4496b0badd2f8f9abb418`). Its pinned certificate `940402…074d` differed from PJZ110's prior device-debug certificate `933f46…1431`; after explicit user authorization, the old package and its app-private data were removed and the exact APK was installed successfully.

On PJZ110 over China Mobile 5G, the installed `0.8.9` process cold-started without a crash. Initial setup showed all 30 routes enabled and successfully loaded the public Workshop catalog. The background benchmark completed with **10 routes selected**; the live settings sheet displayed real per-provider response times, five selected domestic/regional routes and five selected international routes, while preserving switches and up/down priority controls. The custom section rendered separately and its plus action opened the HTTPS DoH add dialog. Steam Community HTML and Directory checks succeeded through the enhanced route; the independent Public Details and Workshop image setup probes timed out on that sample, but did not prevent setup or catalog browsing. No `FATAL EXCEPTION`/`AndroidRuntime` entry for WallHub appeared in the checked crash buffer.

## 2026-10-25 — Workshop image authority separation and local constrained build

Workshop covers from `images.steamusercontent.com` now retain the original HTTP Host while racing the original TLS identity against the exact `steamuserimages-a.akamaihd.net` compatibility authority. Strict live probing of a real 940,017-byte Workshop GIF returned HTTP 200 with platform-chain and alias-hostname verification; no trust bypass was added. Alias eligibility remains on the separate anonymous proxy credential and is limited to credential-free HTTP/1.1 GETs under the canonical `/ugc/<decimal>/<hex>/` path. Query parsing rejects malformed, unknown and duplicate parameters while allowing only bounded `imw`/`imh`, `ima=fit`, and boolean `letterbox`, matching both Public Details URLs and Community HTML enlargement URLs. Setup's image probe, the global Coil loader, and the local thumbnail cache use this anonymous client; unrelated URLs retain normal connection reuse, and CM/authenticated CDN planes remain unchanged.

Local TDD ran against the pinned SDK source worktree at `eaf979f` with one Gradle worker, no parallel/build cache, in-process Kotlin, a 1 GiB heap and 384 MiB metaspace. The focused route/request/setup suites passed. A clean Release attempt proved 1 GiB insufficient specifically at R8 and failed with `Java heap space`; the incremental retry raised only the heap to 1.5 GiB and completed `:app:assembleRelease` successfully without system OOM. Regenerable project-local Gradle/Kotlin/build intermediates were removed afterwards while dependency caches, staged SDK artifacts, source and the final APK were retained. Revision `675f569` produced `/root/builds/wallhub-image-authority-675f569-local-release.apk`, SHA-256 `d232a1baa024a4e9cf147da120f63c09a5f31f76e7587568382a5eb2c8d3070d`.

The local debug signing identity differs from the already installed Windows-built APK, so the production package was not replaced and its data was not touched. A temporary `.localtest` application ID was built and installed side-by-side for device verification, then removed. On `192.168.2.234:33445`, the setup check reported **连接状态良好** with Public Details in 2120 ms, Community HTML in 3123 ms and Workshop image in 3113 ms; the image request entered the authority-separated bridge and selected the original `images.steamusercontent.com` TLS target on that run. The independently verified alias remains available for races when the original route is blocked or slower. Crash buffer stayed empty.

## 2026-10-25 — App-scoped Steam Web authority separation

Automatic anti-blocking now reproduces the useful post-interception part of Steamcommunity 302 without changing system hosts/DNS, installing a system CA, or affecting other apps. Exact allowlisted Steam Web routes preserve four distinct values through the app-private loopback bridge: the original HTTP authority, the DNS authority, the upstream TLS SNI/certificate authority, and the resolved address. `steamcommunity-a.akamaihd.net` is a verified compatibility authority for `steamcommunity.com`, `www.steamcommunity.com`, `api.steampowered.com`, and `community.steam-api.com`; every route also keeps its original-authority target. DoH answers retain authority provenance, candidate races are globally bounded to four, and cache/persisted health keys isolate original authority, upstream TLS authority, port, network, and IP.

Alias-authority transport is exposed only through a separate random loopback proxy credential used by fixed public-read call sites. The bridge reads a bounded first HTTP/1.1 request head, requires a unique Host equal to CONNECT authority, requires `Connection: close`, rejects credentials and upgrade/streaming semantics, and applies strict method/path/query/content-type/body-length allowlists. Community HTML GET and keyless public-details form POST may use compatibility authorities; comments, profiles, API-key calls, CM, and authenticated CDN remain on their existing isolated planes. The upstream uses platform trust, explicit alias SNI, and strict alias hostname verification; the original request head and Host are forwarded byte-for-byte. No trust-all or caller-controlled TLS authority was introduced. Community Web probes have a 12-second outer budget so the bounded six-second route race can finish.

TDD covered exact route mappings, lookalike rejection, anonymous request classification, credential/query rejection, single-use tunnels, DNS authority provenance, identity-isolated health keys, explicit SNI, and fail-closed hostname verification. Windows focused verification ran 48 directly affected routing/setup/repository tests and Release assembly successfully at revision `538a064`. A full app suite run executed 695 tests: it exposed 17 failures, of which the one directly affected Community race test was stabilized and then passed in the focused run; the other 16 are pre-existing unrelated stale/failing tests in MPKG, progress projection, SDK session recovery, downloads filtering, and format limits. The clean final revision `6fa5ec3` built successfully as Windows job `20261024T202342Z-252927134`; APK SHA-256 is `fb7dc61cf3977aaa73e49d7b05a335c96d80cc766170f6ddb4d936c4994c940b`.

The final APK was installed in place on `192.168.2.234:33445` without clearing data. The first process-cold setup check completed Community HTML in 1502 ms and keyless Public Details in 1461 ms; two further no-prewarm checks completed Community in 1558 ms and 1809 ms. The bridge logs proved the new `authority_separated` plane and strict selected TLS authority/HTTP authority reporting; no crash was recorded. On this network the original-authority targets won the race, while independent strict TLS probes proved the allowlisted Akamai compatibility authority returned valid Steam Community/API responses with the original Host. Workshop image remained on its agreed original-only path and one later run timed out, so image authority separation remains deliberately out of scope rather than being misreported as fixed.

## 2026-10-25 — MPKG large-texture execution and extreme-quality fidelity fallback

Two retained physical-device failures exposed separate conversion-contract defects. A planned native static TEX larger than the executor's private 8 MiB input cap was accepted by the heap model and then rejected before conversion; the executor now reads only the planned base-mip slice from the scene package, applies the existing checked 64 MiB RGBA and 5/8-heap admission model, polls cancellation through row/block expansion, and has no second entry-size gate. A second Extreme-quality task rejected a structurally safe unknown texture before packaging; policy/plan v6 now defines Extreme as original-resolution, no-new-loss behavior, converting when it can and otherwise retaining source bytes with the existing length/SHA-256 verification and a bounded compatibility warning. Extreme execution/verification codec failures use the same exact-retention fallback, while I/O, cancellation, source mutation, format limits and disk failures remain fatal. Stale internal plans no longer classify as an invalid user project, and the tier copy now says **原分辨率 · 保真优先** rather than promising every retained resource is RGBA.

The authorized LAN Windows worker first ran the four focused MPKG suites through the Release task; the initial red run found five stale/incorrect expectations, including plan-v6 canonical JSON/goldens and production chunked fallback. After correction, the same focused suites and Release assembly passed at verification revision `7eed0f7`. The temporary Gradle test hook was removed, and final clean revision `fe57e51` built successfully as Windows job `20261024T173808Z-1521219005`; APK SHA-256 is `88db0adca8aed24bd838eac5dfcca859f8c28acad6e0be0e96558225b3e08934`.

The APK was installed in place on `192.168.2.234:33445` without clearing app or staging data. Retrying **星云变换t001** reused its 20.8 MB retained source and completed Extreme quality with `纹理转换 2 · 保留 0 · Shader 1`; the prior `materials/flowmask.tex exceeds the bounded in-memory conversion limit` did not recur. Retrying Workshop `3002995798` (**Lonely Cat: Audio visualizer, Clock, Chill, Multi language**) completed Extreme quality with `纹理转换 18 · 保留 1 · Shader 11`; its warning sheet records `materials/workshop/3002995798/number.am.tex`, `tex_passthrough_unknown_profile`, and `passthrough_with_warning`. Both tasks moved from Failed to Completed, the crash buffer stayed empty, and the revised four-tier sheet rendered without clipping. This proves conversion/package completion for these exact retained inputs; the warning deliberately does not claim verified Wallpaper Engine visual compatibility for the retained unknown texture.

## 2026-10-23 — Account-gated friend activity and hardened SDK routes

Friend Favorites and Friends Created now gate on the existing Steam account owner before paging: logged-out users get an immediate login action with no spinner or anonymous request, persisted accounts restore through the sole session backend, and connected requests use the account Engine with no Community/Web API fallback. Logout now fences queued recovery intent, manual Guard fallback is cancellable and bound to the exact session/generation, retained-token writes require exact readback, and an unusable orphaned Keystore key can self-heal without weakening existing-record handling.

CM discovery no longer contains regional bootstrap hosts: a first attempt uses live Directory then a bounded, unexpired cache; a live refresh is live-or-null. Directory/cache bytes are bounded before allocation, device identity is published only after atomic durable write and readback, and the loopback bridge has bounded connection admission with separate relay capacity. The SDK and Android route seam now preserve real `http`, `https`, `socks`, and `socks5` proxy schemes for both CM and CDN; HTTPS verifies the proxy host before CONNECT and still verifies the Steam origin, while `socks` aliases credentialless SOCKS5 with proxy-side DNS. Proxy authorization remains HTTP(S)-only and is never forwarded as origin authorization.

SDK `./scripts/check.sh` passed at `eaf979f`; the focused CM/CDN/FFI proxy tests and the isolated ARM64 AAR build also passed. Android pins AAR `0.1.149` with a complete artifact/source hash lock. LAN-Windows Release job `20261023T160815Z-2634420027` built commit `4de8126` successfully (`SDK_ARTIFACT_LOCK=OK`, `:app:assembleRelease`); APK SHA-256 is `0818a23a3cfacb26d4d423eba0c9dda5bfff0b77e5d5db818ef9cfe6fe4d2a55`.

The same APK was subsequently installed with app data retained on the authorized PJZ110 (`192.168.2.127:33445`, Android 16). A force-stop cold start resumed `MainActivityThemedIcon`, loaded the JNI library, rendered 30 Community rows and emitted no fatal/loader error. Opening **好友收藏** restored the persisted Steam account through the account owner (`credential_load present=true`, `phase_signed_in`) and completed one foreground SDK Workshop SEARCH in 328 ms with ten rows; the results rendered normally. Opening **好友创建** completed the same account SEARCH path in 324 ms and rendered the legitimate empty-result state. No anonymous fallback appeared in either trace. The saved account was deliberately retained, so the logged-out gate remains covered by deterministic tests rather than a destructive device logout. No live external proxy endpoint was available, so HTTP/HTTPS/SOCKS/SOCKS5 device transport remains unclaimed beyond the SDK loopback tests and Android route integration.

A subsequent latency pass found that the collection RPC itself was already fast (subscriptions 324 ms, favorites 189 ms), while repeated idle `session.pump()` calls retired otherwise authenticated generations after roughly 10–15 seconds and forced 4/8-second recovery debt. The host now checks maintenance every two seconds, writes a due heartbeat before reading, and drains only after `HEARTBEAT_SENT`; idle decisions no longer touch the quiet socket. Windows Release job `20261023T190012Z-1503631281` built commit `686b9d1` successfully; APK SHA-256 is `dcc9523bb94d961b328efb76da3cd2cc7608e496e708aedb31609a1a367ec335`. On PJZ110 the generation remained authenticated throughout a 70-second observation, sent/consumed heartbeats about every ten seconds, and recorded no retirement or recovery backoff. With that hot generation, **我的订阅** completed in 289 ms (`session-ready=1 ms`, network run 282 ms), and a repeat **我的收藏** opened from the exact account page cache in 1 ms. The Favorites route also emitted only `collection=FAVORITES`; the prior accidental default SUBSCRIPTIONS preload is gone. Profile counts are delayed/cancellable and explicit retry now bypasses only the UI auto-restore throttle, not the backend recovery policy.

The Community detail path now keeps a bounded five-minute public browse seed so a card opens with its already-rendered cover and metrics while HTML and keyless public-details JSON refresh concurrently. Real markup uses `previewImageMain`, not the old `previewImage`; the exact HTTPS `images.steamusercontent.com/ugc/` origin is now accepted by the parser and routed through the Steam image client without accepting lookalike hosts. JSON supplies exact subscriptions, favorites, views, size and timestamps while HTML retains the richer localized title, author and description. The PJZ110 seed path published immediately and completed fresh metadata in 620 ms; the resulting page displayed its cover, 73K subscriptions, 3.6K favorites, 9K views, 40.0 MB size and 2026-09-27 update date. The same item's first Community comment page returned 27 localized comments in 537 ms after the tab tap without flashing a false empty state. Simplified-Chinese resource fallbacks now cover `zh`, `zh-Hans`, `zh-CN` and `zh-SG`. Cold metadata without a seed measured 1,588 ms on this network, so the one-second claim applies to normal card navigation and comments, not an unseeded deep link. Final LAN-Windows Release job `20261023T195427Z-26814102` built commit `ea7d95c` successfully; APK SHA-256 is `492c65c2556a24e5f807eb53ad054b2b308f754d28dc4035c176c92cbf4400b9` and was installed with account/app data retained.

A follow-up device repro found that **重新检测线路** synchronously called `connectionPool.evictAll()` from the Compose click callback. Closing a live Conscrypt TLS socket emitted `close_notify` on the main thread and produced an exact `NetworkOnMainThreadException`, terminating the process. Explicit refresh now publishes `RESOLVING` through a single-flight gate, then invalidates/evicts/resolves entirely on the manager IO scope; non-cancellation failures become `FAILED` or `DEGRADED`, and best-effort diagnostic write failure cannot become a second crash. On PJZ110 at `mefrp_tunnel.atxa.top:19530`, the pre-fix tap killed the process with that crash stack; the final APK retained PID 18106 five seconds after the same tap, returned to **线路可用**, and left the crash buffer empty. Detail interaction controls also remain disabled/grey while their corresponding account state is `UNKNOWN`; the account read runs independently of public detail loading, UNKNOWN toggles are rejected, and single-field mutation responses merge without erasing the other known state. LAN-Windows Release job `20261024T061110Z-380026789` built commit `302ed77` successfully; APK SHA-256 is `410f8acf87debf23852a01556b631db78ceab6f0c7df9408bc703fbe583edc7a` and was installed with app/account data retained.

A later live trace reproduced a separate 41,960 ms Community detail stall: account interaction completed in 893 ms, but one enhanced Web branch hit the bridge's 20-second first-response boundary and the allowlisted anonymous interceptor retried inside the shared 45-second Call budget. Although HTML and keyless public JSON had started concurrently, the repository waited for both, so the slow optional branch blocked the successful one. Detail-only HTML/JSON clients now have independent three-second hard Call budgets and cancellable OkHttp enqueue bridges; the first successful branch wins immediately, a first failure waits only for the other bounded branch, and cancellation reaches `Call.cancel()`. The public seed remains bounded to 256/source/epoch but now lives 30 minutes, and sparse fast HTML is merged over the seed so its missing metrics never erase already-rendered subscriptions/favorites/views/size. Windows job `20261024T072842Z-2266117721` built final commit `ed772a4`; APK SHA-256 is `6d8c1ef2d1950ee71965a7502949f76b512edbce77943c589344d6279a0c89bc`. On PJZ110 the same homepage item then published its seed in 0 ms, completed HTML/fresh detail in 784/786 ms, cancelled the losing JSON Call at 785 ms, completed account interaction in 866 ms, and retained the seeded 138K subscription count instead of reverting it to unknown.

The setup wizard now separates editable network configuration from a dedicated **连接检测** page. Enabling automatic anti-blocking and leaving Network saves the effective settings, waits for `SteamAccessManager` to observe them, then runs bounded anonymous Directory, Public Details, Community HTML and Workshop image probes with at most three concurrent operations; account credentials are never sent, and CM transport is explicitly `未检测` rather than falsely passed because no safe anonymous transport handshake exists. The page renders Checking/Passed/Failed/Skipped, elapsed time and bounded reasons, exposes a visible **重新检测** action, disables Continue while checks run, and permits Continue after warnings as requested. Returning to Network cancels/clears the result. Windows Release job `20261024T101431Z-2873830117` built commit `a3c1272` successfully; final APK SHA-256 is `2717d034126ad3c5f23fcbfa0c406eeabd73457e31c2ccb2e07bcde0b59fc691`. On PJZ110 the screen was independently reachable after Network; one run showed Directory/Public Details/image timeouts, Community HTML success in 1,686 ms, and honest CM skip. Retry immediately reset every row to Checking, and after completion Continue advanced to Storage despite warnings. The crash buffer remained empty.

## 2026-10-22 — Demand-only Steam routing and independent Community HTML

Removed built-in Steam/Akamai IP seeds, startup/periodic TLS probes, prewarm completion trackers and Home prewarm gates. The existing manager now resolves actual requests through current DNS/configured DoH or user hosts, with a bounded 60-second, 64-key, four-address generation-fenced cache. History ranks only currently resolved addresses; failure-only records cannot become preferred routes. Hidden-SNI failure alone does not penalize an address that works with original SNI. Response-stage read failure/initial EOF is attributed to the actual upstream address before client closure; normal post-response EOF and client cancellation are not treated as route failures. Certificate-chain/original-hostname checks remain unchanged. There is no generic HTTP downgrade, account owner or background probe service.

A narrowly allowlisted anonymous read interceptor permits one pre-header I/O retry in the same Call budget, never response-body replay, HTTP-status retry, credential-bearing requests or mutations. Community HTML details and browse now parse bounded public HTML/observed SSR data directly without API enrichment gates; missing precise content identity stays unknown and is not fabricated. CM source retains its no-cross-source-fallback tests. Detail reload cancels the previous load. The global account banner is quiet for RESTORABLE/recovering states while real expiry and Steam-requested challenges remain actionable.

Windows builds passed at 6fd23b9, 1834e7d and final production-code revision 9e72d10 (job 20261022T085900Z-245827237). Final tests include the test-only c890c41 additions copied to the worker: 42 tests, zero failures/errors across routing, retry, HTML/repository, Home loading and account banner suites. Test reports/temp output were removed and daemon stopped. No local product build was run.

Device evidence (PJZ110, Wi-Fi off, NR_SA, enhancement enabled): first build loaded ten distinct Community details, each observed within 3.5–4.9 seconds including ADB/UI-dump overhead. Retry build loaded nine more, then Steam returned HTTP 429 on the tenth; requests stopped rather than retrying/routing around rate limiting. Community home rendered 30 rows with totals. Final production build loaded API-mode item 3798688689 with exact statistics/291.4 MB, then restored COMMUNITY_HTML and loaded the same item's title/description/author independently (unknown exact size). Three foreground UI samples on both the retry build and final production build showed no restore-failure banner; synthetic tests cover transient states. API browse was not proven (no API key configured); author legacy browse markup, live forced-bad-candidate switch, full download/CDN and Steam challenge flows remain unverified in this campaign. A single API success does not establish long-term availability or prove the earlier guessed winning-IP/network-blocking diagnosis. No real files or account mutations were used for validation.

## 2026-10-22 — SAF document deletion uses the document contract

Local deletion previously evaluated `ContentResolver.delete(documentUri) || DocumentFile.delete()`. ColorOS `DocumentsProvider.delete` throws `UnsupportedOperationException` for the ordinary ContentProvider CRUD route, so short-circuit fallback was never reached. Deletion now classifies each URI once: `file://` uses `File.delete`, SAF document URIs use `DocumentFile.delete`/the document contract directly, and ordinary MediaStore/content URIs use `ContentResolver.delete`. The ViewModel now surfaces the Repository's concrete failure message instead of discarding it for a generic error.

LAN-Windows build `20261022T073815Z-2333720883` from commit `95860f9` passed. The narrow `LocalWallpaperDeleteStrategyTest` ran on the Windows worker: 3 tests, 0 failures/errors/skips in 0.031 seconds, including proof that the document branch never calls an unsupported content deletion; reports/temp output were removed and the Gradle daemon stopped. On the remote device, a 16-byte valid `wallhub-delete-probe.mpkg` was indexed and scanned beside the two real packages. The pre-fix UI delete left it present and logged `UnsupportedOperationException: Delete not supported`; the final APK removed it through the same UI flow, refreshed it out of the list, emitted no provider exception, and left both real MPKG files byte-sized and present.

## 2026-10-22 — Setup-owned Workshop save-directory authorization

The initial setup wizard now contains a dedicated **保存位置 / Save location** step between network and Steam. Its default is `Download/WallHub`; on Android 10+ pressing Next without an existing directory grant launches `OpenDocumentTree` at `primary:Download/WallHub` and requires a persisted read/write SAF grant before continuing. The selected tree is saved through the same `SettingsRepository.setOutputDirectory` interface used by **Settings → Downloads**, so download export and local-wallpaper scanning share one stable directory and users can change or restore it later. Android 9 and earlier retain the manifest-scoped `WRITE_EXTERNAL_STORAGE` request; modern Android deliberately does not request ineffective `READ_EXTERNAL_STORAGE`, media-only permissions that exclude MPKG, or high-risk all-files access.

LAN-Windows build `20261022T070748Z-167795622` from commit `2970166` passed and was installed without clearing device data. Restarting the wizard showed `默认：Download/WallHub`; pressing Next opened the ColorOS system picker directly inside the existing `Download/WallHub` folder with both MPKG files visible and **使用此文件夹** available. Granting it advanced to Steam, persisted the tree, and finishing setup restored the app. The local page then immediately discovered both existing packages (`94.9 MB` and `22.9 MB`) through the authorized tree, closing the prior MediaStore-owner visibility failure.

## 2026-10-22 — Memory-bounded large scene JSON conversion

Workshop `3798688689` exposed a plan/execution contradiction rather than a corrupt download: its 2,508,971-byte `scene.json` exceeded the old 2 MiB material-graph admission cap, was planned as `PASSTHROUGH_WITH_WARNING/material_graph_document_invalid`, then was unconditionally rewritten for texture reduction and correctly rejected by the final passthrough length check. The conversion policy and plan are now v5. Root `scene.json` has an explicit `REWRITE_SCENE_SETTINGS` strategy independent from material-analysis completeness, while genuine passthrough resources retain exact length and digest verification.

A new `StreamingSceneJson` module reads PKG slices without allocating the whole document, validates bounded structure, captures only bounded key/reference strings, infers material references under a one-million-value work budget, and streams a targeted `general.texturereduction` patch to a temporary file with a 64 KiB growth bound. The fixed 2 MiB per-document and 8 MiB aggregate gates are gone. Budget exhaustion stops further semantic inference and leaves unknown textures on conservative policy paths; it does not declare the project invalid. The transformer uses a 64 KiB copy buffer, a 64-level structural-depth bound, 4 KiB key and 8 KiB reference capture bounds, cancellation polling, temporary output, and the existing atomic MPKG commit.

LAN-Windows build `20261022T054402Z-29928135` from commit `07d68e3` was installed without clearing app data. Retrying the retained real task reused the complete 305,525,706-byte staging input and converted Workshop `3798688689` successfully on final-build attempt 3: `COMPLETED`, export transaction `COMMITTED`, plan v5, attempt 3 successful, 99,474,433 output bytes, 179 textures converted, one conservatively retained, ten shaders rewritten, and five audio resources removed. The exported MPKG contains 703 planned resources; its `scene.json` is valid JSON, grew only from 2,508,971 to 2,508,992 bytes, and carries `general.texturereduction=1`. Live snapshots showed approximately 31–45 MiB Java heap and no OOM. The LAN Windows SSH host then ran the narrow `:app:testDebugUnitTest --tests com.wallhub.android.data.downloads.ConversionPolicyV2Test` task against the exact worker source: 9 tests, 0 failures/errors/skips in 33.491 seconds; the synthetic large-scene rewrite case itself passed in 33.09 seconds. Test XML, HTML report, and temporary execution output were removed afterward and the Gradle daemon stopped, while shared dependency and incremental compilation caches were retained to prevent repeated downloads.

## 2026-10-22 — WebView-informed enhanced Web TLS and CM comments

The WebView connection-enhancement source established the useful behavior missing from Android's transparent Web CONNECT path: eligible Steam Web requests terminate their upstream TLS inside the gateway, prefer hidden SNI, iterate bounded IP candidates, and fall back to original SNI. Android now applies that behavior inside its authenticated loopback bridge for allowlisted enhanced Steam HTTPS hosts. Unlike WebView's generic gateway, every Android upstream TLS attempt keeps the platform trust chain and explicitly verifies the original Steam hostname; no `rejectUnauthorized=false`, insecure curl fallback, trust-all manager, hostname bypass, or generic non-Steam interception was copied. The process-private CA remains trusted only by the dedicated Steam client. When enhancement is enabled, eligible Steam Web/API requests cannot bypass the bridge because a stale route snapshot says direct is healthy.

Cold-start failures exposed two additional gaps. Android already carried the same bounded Akamai seed pools as WebView, but system/history/DoH candidates filled the old four-address probe cap before seeds were reached; the route candidate pool is now eight while a real TLS race remains capped at four. More importantly, a real request no longer requires a successful prewarm snapshot: it prefers a current route, otherwise composes history + carrier DNS + DoH aliases + seeds and races authenticated TLS directly. This removed the `No accelerated route`/CONNECT 502 cold-start race without weakening verification.

The CM comments adapter was named authenticated but sent its request through `sdkPublicRead` and the anonymous gateway, which the live service refused before transmission. It now waits through `SteamContentSdkAuthenticatedSessionRegistry`, lets the sole Backend recover one account generation within the existing 75-second bound, and executes through the account gateway. No second account owner and no Community/Web API fallback were added.

LAN-Windows build `20261022T041352Z-193631591` from commit `0d569cb` was installed on the Wi-Fi-off China Mobile device. With Community HTML selected, two consecutive force-stop cold starts each loaded 30 rows (`约 2535686/2535687 项`) through enhanced Web bridge calls covering both `steamcommunity.com` and `api.steampowered.com`; the previous TLS reset and prewarm-gate 502 did not recur. After restoring `Steam CM WebSocket`, a detail page loaded `评论 (111)` with real bodies and logged `read kind=COMMENTS ... outcome=SUCCEEDED`. Enhancement was restored on and the original CM source retained after validation.

## 2026-10-22 — Account collections use the sole on-demand CM owner

Account status now separates persisted identity from real-time transport availability. `SteamSessionState.isConnected` is true only on the authenticated edge and becomes false when that exact generation retires; the settings card keeps the saved avatar/account but says that the account is saved and currently connects on demand, rather than claiming an active login or showing a generic restore failure. The settings summary likewise reports a saved disconnected account. Profile subscription/favorite counts are nullable, so failed reads render as `—` instead of falsely claiming zero.

Authenticated personal collections no longer poll the process engine for five seconds and then continue as if it were ready. `SteamWorkshopSdkGateway.awaitSignedInSession` now waits through `SteamContentSdkAuthenticatedSessionRegistry`, which requests one recovery from the sole `SteamContentSdkSessionBackend`, honors the shared 1–60 second recovery debt, waits within the 75-second bounded budget, and verifies that the returned engine and generation are still the expected authenticated publication before issuing `GetUserFiles`. No Community/Web API fallback or second account owner was added.

LAN-Windows build `20261022T031508Z-2072817462` from commit `1dc4d3e` was installed on the Wi-Fi-off China Mobile device. After generation 1 retired at 10.3 seconds, the profile initially showed `—` rather than false zeros. Opening the profile triggered one 1-second on-demand restore; counts became 38 subscriptions and 2 favorites. Opening **我的订阅** after another retirement honored a 2-second backoff, restored the account session, read 24 source rows and displayed 16 filtered items. Opening **我的收藏** honored a 4-second backoff and displayed both real items. Each short account generation later retired without invalidating the already-returned collection or starting a background reconnect storm. The Steam settings card then displayed `Steam 账号已保存；订阅、收藏或下载需要时将自动连接。`

## 2026-10-22 — SteamHelper-informed bounded account CM recovery debt

A primary-source review of SteamHelper-rs `steam-client-lib-v0.2.0` identified the remaining scheduling gap after on-demand recovery: failed short-lived sessions must retain reconnect debt rather than letting another foreground/lifecycle entry immediately start at full speed. Android's sole `SteamContentSdkSessionBackend` now owns a monotonic short-session counter. A session that dies before 60 seconds adds debt; every restore entry—including app startup, foreground, explicit restore, registry demand, and download demand—passes through the existing single-flight attempt gate and waits `1, 2, 4, 8, 16, 32, then 60` seconds. Only a generation surviving at least 60 seconds resets the debt. Logout clears it. No business request is replayed, persisted identity remains separate from transport availability, and already-authorized Manifest/chunk work is unaffected. The download Session wait budget is 75 seconds, matching the bounded 60-second maximum backoff plus one Directory/live-candidate pass.

The same upstream review confirmed that WallHub's independent depot decoder already covers SteamKit 3.5.0's new hardening: it validates actual decrypted plaintext after PKCS#7 removal, VZstd minimum envelope/footer, decompressed length, trailing data and Adler-32, with deterministic all-prefix truncation and byte-corruption corpus tests. No decoder source change was necessary. SteamKit's broader unknown-`https_support`-to-HTTP behavior was deliberately not copied; WallHub retains explicit `unavailable`-only HTTP admission.

LAN-Windows build `20261022T023155Z-3250310575` from commit `e38f726` passed and was installed on the Wi-Fi-off China Mobile device. A 10.3-second generation retired with `retryDebt=1` and did not automatically reconnect. A subsequent Workshop task completed `174,194,098 / 174,194,098` bytes and emitted SDK `COMPLETED`. Later foreground demand logged `content_session_recovery_backoff delayMs=4000`; the restored generation again lived about 10.3 seconds and retired with `retryDebt=4`, proving debt survives short successes and every restore entry honors it. APK SHA-256 is `9d028d9576222cbf26442d05fe2e198ee7b52e73136cff927c6fa0c8b34f3f09`.

## 2026-10-22 — WebView-parity on-demand account CM scheduling

A read-only comparison against the deployed WallHub for WebView and its source identified why Android showed a repeated “restore login” loop on China Mobile. WebView persists account identity separately from a live CM: remembered-session validation is single-flight and short-lived, the Workshop query bridge is created on demand and recreated by a later request after exit, and each DepotDownloader task owns a bounded process lifetime with one checkpoint/network/login retry. It has no five-second proactive account-CM rebuild loop. Android instead maintained one account CM every five seconds; the carrier closed it after roughly 5–15 seconds, and maintenance immediately opened another generation indefinitely.

Android keeps its stricter ownership contract—the existing `SteamContentSdkSessionBackend` remains the sole account Session owner—but now applies the equivalent scheduling policy. Maintenance detects and retires the exact dead generation while retaining persisted identity, then parks. `SteamContentSdkAuthenticatedSessionRegistry` asks that sole owner for one persisted-session restore only when an account/content operation actually awaits a Session; concurrent requests share the existing attempt gate. The download acquisition budget is 30 seconds, covering the observed Directory timeout plus one live-candidate retry. Cold start still reads secure storage once; it does not require the initial in-memory state to already know a credential exists.

LAN-Windows build job `20261022T020308Z-635321478` (Android commit `f799884`, SDK `0.1.145`) was installed over the mobile test device. Cold start authenticated once, maintenance later recorded `content_session_retired generation=1`, and no automatic generation storm followed. Retrying the existing failed Workshop `3804689861` caused one on-demand credential load at `10:07:28`; its account generation was retired at `10:07:39` while the already-authorized data plane continued, reached `174,194,098 / 174,194,098`, verified, and emitted SDK `COMPLETED` at `10:08:12`. No further credential-load/recovery loop appeared during the following observation window. APK SHA-256 is `90dd54b29f4e6baea0eef30d90ba3a152ba358494a326fd44c5da61340821193`.

## 2026-10-22 — China Mobile enhanced Steam login and HTTP-only Workshop completion

A PJZ110 on China Mobile NR_SA, with Wi-Fi disabled and App data cleared, completed enhanced-first onboarding, account login/verification, fresh public Workshop reads, and a first uncached Workshop download. The platform now routes Steam Web/API and CM Directory through the authenticated loopback broker with end-to-end TLS and bounded candidate racing; account and anonymous Steam CM use WebSocket routes without introducing a second account-session owner. Password login and refresh-token restore retain Steam's distinct verification outcomes, and a temporary CM route outage preserves persisted account identity while account operations continue to wait for a real authenticated Session.

Steam's cell-0 Directory returned regional nodes with `https_support=unavailable`. SDK 0.1.145 therefore followed HTTP/80 exactly as advertised, without generic downgrade: unknown/missing capability remains HTTPS-only, transport failure cannot switch scheme, redirects preserve scheme and bounds, and a regional HTTP node may hand off to a validated HTTP delivery host without forwarding its Authorization. Account CM replacement clears unresolved resolution material and idle sockets but no longer revokes an already-authorized Manifest/chunk data plane.

Final device task `d98e519f-4d20-4526-903b-89f7dc2d1022` downloaded Workshop `3804689861` from zero cache: public target completed, Depot Key / Manifest Request Code / CDN tokens completed, Manifest fetched and decoded, chunks reached `174,194,098 / 174,194,098`, verification ran, and the SDK emitted `COMPLETED`. Observed chunk throughput was roughly 5–18 MB/s.

A direct UI recheck then corrected an over-broad acceptance claim: a later Blue Archive task failed before SDK acquisition because `awaitCurrentUsable()` silently removed a dead published CM generation but never asked the sole Backend owner to recover it. Commit `d73afd7` now atomically retires that exact generation, signals the existing recovery owner, and waits for its replacement without creating another Session owner. On the same Wi-Fi-off China Mobile connection, retrying task `b2015dfd-1d71-46f5-b89c-553917c0740a` downloaded `56,520,921 / 56,520,921` bytes, emitted SDK `COMPLETED`, and appeared in the app's **已完成** tab as `【Blue Archive】普拉娜 - 与你的无尽夏`, `53.9 MB`, `已完成`. Account CM disconnected and restored repeatedly during and after the transfer without revoking the admitted data plane.

The corrected final LAN-Windows artifact is job `20261021T161516Z-220501405`, Android commit `d73afd7`, SDK commit `003e794`, SDK version `0.1.145`; APK SHA-256 is `4ee49f97ff0118a08912450380753455347804d40c3d12da24db9b8743542a9a`, and AAR SHA-256 is `bfdb4b0cf3263c0f27192035e5fc30f30112f0357c9330889fdb7c0892c39ae1`. All SDK/AAR/APK builds ran on the LAN Windows worker; the source host only edited and staged source.

## 2026-10-15 — Android Scene MPKG high-success conversion contract

Physical-device reproduction on a OnePlus 5T confirmed that Workshop `3299228616` downloads fully and then fails planning at `number.am.tex` with `unsupported_texture_profile`; Workshop `3757572576` completes after one transient SDK session retry and then fails planning at `全年龄.tex` with `preset_texture_input_budget_exceeded`. The owner froze the Android-only repair contract:

- compatibility converts eligible color textures to ETC2; standard uses RGBA up to 8 MiB decoded and ETC2 above; high quality uses RGBA up to 32 MiB and ETC2 above; source fidelity passes every TEX through unchanged;
- no preset downsamples; unknown, parse-failed, resource-constrained or execution-failed TEX and failed shader rewrites pass through with bounded warnings instead of rejecting the scene; source short reads, I/O, disk, cancellation, MPKG format limits and final container verification remain fatal;
- passthrough bytes are length/SHA-256 verified; declarative project/scene/material references determine texture semantics conservatively; unknown/data semantics use lossless RGBA or passthrough, never lossy ETC2;
- a Scene-only preserve-audio choice defaults off, is remembered globally and snapshotted per task; disabling it removes audio without rewriting references;
- warning completion remains `COMPLETED`, keeps at most 100 stable resource details plus summary counts, and does not promise Wallpaper Engine runtime compatibility;
- MPKG staging is retained for reconversion under an owner-visible LRU cache, and reconversion atomically replaces output only after verification while preserving the last successful output/report on failure;
- conversion uses per-resource durable checkpoints, removes arbitrary texture/package/entry/index budgets, preserves the 4 KiB path limit and the 32-bit MPKG approximately-4-GiB format limit, and performs real peak-disk preflight with a `max(512 MiB, total capacity * 5%)` reserve;
- this campaign changes Android only. Webview parity and the independently observed SDK `session_unavailable` retry are separate work.

The implementation is test-driven at the `WorkshopConverter` plan/execute interface, the versioned Room task repository, the conversion worker transaction, the source-cache manager, and the Compose screen contracts. Real Workshop bytes remain off-repository; synthetic fixtures reproduce only independently described structures.

### First physical-device repair evidence

A commit-bound build at `4f35d2c` compiled successfully on the authorized Windows worker and was re-signed with the already-installed development identity for in-place device testing. On the same unlocked OnePlus 5T, both previously failed tasks reused their intact staging without another download and completed as MPKG:

- `3299228616`: WorkManager `SUCCESS`, output `10,488,881` bytes, 14 converted textures, 5 retained resources, 11 rewritten shaders, 1 removed audio, and 5 compatibility warnings;
- `3757572576`: WorkManager `SUCCESS`, output `261,292,120` bytes, 1 converted texture, 2 retained resources, 1 rewritten shader, no removed audio, and 2 compatibility warnings.

MediaProvider logs show each candidate moving from a `.pending-*` path to the final MPKG name, and the UI reports `COMPLETED` with bounded warnings. This proves the two reported planning failures are repaired for the standard preset.

### Wallpaper Engine runtime import evidence

The two Standard-quality outputs were imported through Wallpaper Engine Android's own **Add → Import file** picker on the same OnePlus 5T. Both MPKG files were accepted, appeared under their project titles, entered `PreviewActivity`, and continued rendering without a process crash, but neither rendered normally:

- `3299228616` (`Lonely Cat: Audio visualizer, Clock, Chill, Multi language`) showed the clock and a few moving light particles over a nearly uniform gray/white field; two delayed screenshots changed particle position, proving the scene was running rather than frozen, while its principal background/layers were absent or badly washed out.
- `3757572576` (`万萝图`) rendered a uniform cyan field with no expected artwork or visible scene content.

Therefore MPKG file generation/import success is confirmed, but Standard-quality Wallpaper Engine visual compatibility is **not** confirmed and is currently red for both reported projects. This runtime result matches the product contract's distinction between a valid generated container and visual/runtime parity.

A subsequent commit-bound reconversion build completed the four-preset matrix from retained staging without redownloading. For `3299228616`, Compatibility and Standard were both `10,488,881` bytes, High Quality was `13,051,656` bytes, and corrected Source Fidelity was `10,516,875` bytes with `0` converted / `19` retained TEX resources. For `3757572576`, Compatibility, Standard, and High Quality were all `261,292,120` bytes, while corrected Source Fidelity was `261,292,205` bytes with `0` converted / `3` retained TEX resources. The required ordering `Compatibility <= Standard <= High Quality` therefore holds for both projects; equality is expected when the project has no eligible texture crossing the tier threshold. Source Fidelity is intentionally excluded from that ordering. Warning detail sheets showed only relative paths, stable reason codes, selected strategy, complete per-reason counts, and the bounded-warning completion state. A final `3299228616` Standard reconversion toggled preserve-audio on: the task snapshot reported `移除音频 0` and produced `12,476,451` bytes instead of the audio-off `10,488,881` bytes, proving the audio choice is independent of the quality tier. The final reviewed commit `a85a97c` compiled on the authorized Windows worker, passed Android GitHub boundary CI run `38779934820`, cold-started on the same device, and accepted `wallhub://downloads` by opening the downloads surface.

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

## 2026-09-26 (6) - the Voted probe: both remaining hypotheses refuted

Two probe builds answered the question the EResult 8 raised, and neither answer was the one we wanted:

- AAR **0.1.50** (app `d21b831`) tried the Voted read **without the App scope**, since every other parameter is
  identical to the two reads that answer. Device line:
  `access_denied: the service refused the request (service result 8)` - the same refusal, so the App scope is
  not what the service rejects.
- AAR **0.1.51** (app revision built at `20260926T153459Z`) walked the alternate selector spellings
  `myvotes` -> `voted` -> `myvoted` -> `myvotedfiles` and the service refused every one with `EResult 8`. The
  final line on the device was again `access_denied: the service refused the request (service result 8)`.

The probe is removed from the SDK again (its commit is `revert(workshop): remove the Voted selector probe`), so
the shipped read is the plain `GetUserFiles`. One environmental note from that session, recorded because it
looked like a defect and was not: the first restart after installing the probe build failed session restore with
`attempt failure class=2 stage=restore sdk=authentication failed during AccessTokenLogon cause=RemoteResult
(result=Some(48))` - `EResult 48` is `RateLimitExceeded`, from the run of installs and cold starts in a short
window. The app's own retry recovered it a few minutes later (`phase_signed_in`), and the next reading then
showed subscriptions and favourites answering normally.

Verdict for this collection: within the approved published-file closure there is no request that lists the
files an account voted on, and the pre-deletion backend failed on the same selector, so the Voted tab has never
been served. How the UI should present that is a product decision; today it silently shows 0 and an empty list.

## 2026-09-26 (7) - the Voted tab says what is true instead of nothing

Steam has no request that lists the files an account voted on, so the collection now reports that rather than
an invented zero:

- the profile's 我的投票 card shows `—` when the read was refused instead of answered
  (`feature/profile/ProfileScreen.kt`, `ProfileCounts.votedUnavailable`), because a zero would be a claim the
  read never made;
- the Library's Voted tab says "Steam does not provide a voted-items list" / "Steam 不提供已投票列表" instead
  of "No matching voted items" (`library_empty_voted`, both locales).

A read that is merely slow or empty still reads as empty, since only a refusal sets the flag.

**Correction to the entry above.** The first version of that change keyed the dash off the read failing, and the
device showed it did not work: `AccountWorkshopRepository.browseCollection` has no null answer, so a refused
read arrives as an *empty page* and the card still printed `0` (reading: 我的订阅 1 / 我的收藏 239 / 我的投票 0
on the `0.1.52` build). Since Steam's refusal is a property of the list rather than of the moment, the profile
now skips that read entirely and marks the count unavailable - the read could only ever produce an invented
zero, and not making it also saves a refused service call.

## 2026-09-26 (8) - the honest Voted card is on the device

Build `20260926T155031Z-1207729304` (app-only change; SDK pinned at 0.1.52, whose AAR sha256
`3ef8…`/`ea649248…` matches 0.1.49 because the probe was reverted before it). Cold start, then 我的:

```text
23:54:09.746 I/WallHubSdkWorkshop: workshop page rows=30 of 30 collection=public ...
23:54:35.134 I/WallHubSdkWorkshop: workshop page rows=1 of 1 collection=SUBSCRIPTIONS ...
23:54:35.954 I/WallHubSdkWorkshop: workshop page rows=1 of 1 collection=FAVORITES ...
```

and the screen reads **我的订阅 1 / 我的收藏 239 / 我的投票 —**, with the runtime line at 1486 hours. Two things
follow: the votes count is no longer an invented zero, and the refused Voted call is gone from the log entirely,
because the profile no longer asks a question Steam cannot answer. The favourites total (239) also matches the
arm-A baseline recorded before the old backend was deleted.

## 2026-09-27 - interaction readings: the subscription half is right, the favourite half is wrong

Build `20260926T155746Z-596418085` added one line per interaction read and per write, because the detail screen
draws the same hollow star for "not favourited" and "the read did not answer". Two item details then answered
with items whose membership is known independently - both taken from the account's own collections:

```text
I/WallHubSdkWorkshop: workshop interaction id=3774904326 subscribed=SUBSCRIBED     favorited=NOT_FAVORITED subscribed-field=1 favorited-field=1
I/WallHubSdkWorkshop: workshop interaction id=2905017768 subscribed=NOT_SUBSCRIBED favorited=NOT_FAVORITED subscribed-field=1 favorited-field=1
```

`subscribed-field=1` is the SDK's "present value" state, so both answers are real readings rather than unknowns.
Item `3774904326` came from 我的订阅 and reads `SUBSCRIBED`; item `2905017768` came from 我的收藏 (the same item
the service put first on that page, `rows=16 of 16 collection=FAVORITES`) and reads `NOT_FAVORITED`.

So:

- **The subscription half is verified on the device.** `AreFilesInSubscriptionList` with `listtype = 1`
  answers correctly in both directions.
- **The favourite half is a defect.** `GetAppRelationships` is matched against
  `FAVORITE_RELATIONSHIP = 1`, and no item the account favourited carries a relationship with that value. Like
  the Voted selector, that constant came from the deleted product code rather than from the service
  (`crates/sc-proto/src/published_file_relationship.rs:35`, doc-comment citing `KSteamSessionRepository`), and
  the locked schema names the `relationship` field without enumerating its values
  (`steammessages_publishedfile.steamclient.proto:565-575`). The old path therefore had the same unverified
  constant, and the detail screen has been showing "not favourited" for favourited items - the write path's
  own confirmation was the only thing that ever moved the star.

The next reading has to say which relationship value the service actually sends (or whether the answer is
empty because the read needs an App scope), which is a diagnostic the SDK has to make: the app cannot see the
raw pairs.

**Also read in the same session** (settings screen, 下载 section): 单项目分块并发 = 24 个, Steam 在线播放缓存 =
512 MB, Steam 在线分块播放 = on, and 网络代理 = off with an empty address. The 清理在线播放缓存 button now
answers 「没有可清理的在线播放缓存」 - the honest empty-cache copy from this campaign - confirming ④ on the
device.

## 2026-09-27 (2) - the favourite read comes back empty

AAR **0.1.53** temporarily appended the service's own relationship data to the item's description, since the
interaction field is a boolean. Opening the favourited item `2905017768` (from 我的收藏) and expanding its
简介 to the end read:

```text
[rel-probe n=0 mask=0 rels=[] first-app=0]
```

The service returns **no app relationships** for that item, so the hollow star is not a constant mismatch: an
empty answer is being read as "not favourited". The same items answer the subscription half correctly
(`subscribed=SUBSCRIBED` for the subscribed one, `NOT_SUBSCRIBED` for the other), so the two halves are
genuinely different reads.

The probe is removed from the SDK again (it wrote into a user-visible description). The interaction log on the
app side stays. Next: try `AreFilesInSubscriptionList` with a favourites `listtype`, the call that already
answers subscriptions correctly with `listtype = 1`.

**Detail page read along the way** (item `2905017768`, from 我的收藏): title resolved, 评论 (768), 作者 Cube,
项目 ID 2905017768, 订阅数 99.8万, 收藏数 12.7万, 浏览量 13.9万, 文件大小 621.3 MB, 最后更新 2026年6月28日,
类型 网站, and tags `[Web, Anime, Wallpaper, Audio responsive, Customizable, Dynamic resolution, Approved,
Everyone]` - the CM detail read carries all of it, and the tag list matches what the search page reported for
the same id.

## 2026-09-27 (3) - the favourites list is `listtype = 2`, verified both ways

One line in the SDK's subscription-list request (`list_type: 2`) and the same two items answered:

```text
00:18:32 I/WallHubSdkWorkshop: workshop interaction id=2905017768 subscribed=SUBSCRIBED     ... subscribed-value=1
00:19:01 I/WallHubSdkWorkshop: workshop interaction id=3774904326 subscribed=NOT_SUBSCRIBED ... subscribed-value=0
```

`2905017768` is in 我的收藏, `3774904326` is in 我的订阅, and under `listtype = 1` the two answers were exactly
the other way round. So the favourites list is named by `listtype = 2` on the very call that already answers
subscriptions correctly, and `GetAppRelationships` - which returns an empty list for a favourited item - is the
wrong question for this half.

The probe is reverted to `SUBSCRIPTION_LIST_TYPE`. The fix is to read the favourite half with `listtype = 2`
through the seam (so the list type stops being hard-coded in the session), which retires the unverified
`FAVORITE_RELATIONSHIP` constant from the read path. Until that build lands, the detail screen still shows a
hollow star for favourited items.

## 2026-09-27 (4) - the star is right now, and the write behind it is refused

AAR **0.1.55** (app `20260926T162921Z-1068210528`) reads the favourite from the favourites list, and the same
item that read `NOT_FAVORITED` before now reads:

```text
00:31:48 I/WallHubSdkWorkshop: workshop interaction id=2905017768 subscribed=NOT_SUBSCRIBED favorited=FAVORITED favorited-value=1
```

So the detail screen shows the account's real favourite state for the first time in this campaign, and a list
that answers about no item leaves the row unknown instead of reading as "not there".

Tapping the star then exercised the write, and the service refused it:

```text
00:32:02 W/WallHubSdkWorkshop: workshop read unavailable: access_denied: the service refused the request (service result 15)
00:32:02 I/WallHubSdkWorkshop: workshop favorite write id=2905017768 ask=false confirmed=null
```

`EResult 15` is `AccessDenied`, so the toggle does not reach the account today; the refused write changed
nothing, and the item is still in 我的收藏. `RemoveAppRelationship` is sent without the App field and with the
inherited `relationship = 1`, both of which are candidates for the refusal - the same two candidates the read
half just settled.

## 2026-09-27 (5) - the favourite write refuses relationship 2 as well

With AAR **0.1.56** the mutation named relationship 2 (the value the favourites *list* uses) instead of 1, and
the star tap was refused exactly as before:

```text
00:38:10 W/WallHubSdkWorkshop: workshop read unavailable: access_denied: the service refused the request (service result 15)
00:38:10 I/WallHubSdkWorkshop: workshop favorite write id=2905017768 ask=false confirmed=null
```

The same item read `favorited=FAVORITED` ten seconds earlier, so the read is fine and the write is not about the
relationship value. What is left is the `appid` field that `AddAppRelationship` / `RemoveAppRelationship` carry
and this SDK deliberately leaves unset, because the seam's interaction request has no App to give it. Naming one
is a seam + ABI change, so it is planned rather than guessed. The probe is reverted to the reviewed value.

## 2026-09-27 (6) - the subscription write works, the favourite write does not

The installed build's write diagnostics needed no new artifact: the bell on an item the account is subscribed
to answered both directions, and the account state was restored.

```text
00:39:38 I/WallHubSdkWorkshop: workshop subscribe write id=3774904326 ask=false confirmed=false
00:39:52 I/WallHubSdkWorkshop: workshop subscribe write id=3774904326 ask=true  confirmed=true
```

So ③'s subscription-write reading is done: cancel and re-subscribe both confirm. The favourite write on the same
session was refused with `EResult 15`, which isolates the defect to the favourite's own call -
`AddAppRelationship` / `RemoveAppRelationship` - rather than to the mutation path. Since `Subscribe` carries its
own `list_type` and the favourite *read* turned out to be the same list call with `listtype = 2`, the favourite
may simply be another subscription-list type, which would make the fix a constant rather than new ABI surface.

## 2026-09-27 (7) - the favourite write works: it is the subscription mutation with listtype 2

AAR **0.1.57** (app `20260926T164949Z-16467962`) asked for the favourite through `Subscribe`/`Unsubscribe` with
`list_type = 2`, and the device confirmed both directions:

```text
00:52:12 I/WallHubSdkWorkshop: workshop interaction id=2905017768 subscribed=NOT_SUBSCRIBED favorited=FAVORITED favorited-value=1
00:52:19 I/WallHubSdkWorkshop: workshop favorite write id=2905017768 ask=false confirmed=false
00:52:34 I/WallHubSdkWorkshop: workshop favorite write id=2905017768 ask=true  confirmed=true
```

No `access_denied` line, both directions confirmed, and the account's favourite was restored. So the star now
both shows and changes the account's real state - the first time in this campaign - and the app-relationship
mutation that used to sit behind it was the wrong call rather than a wrong value.

**③ interaction pair, complete on the device**: subscription state (`SUBSCRIBED` / `NOT_SUBSCRIBED`), favourite
state (`FAVORITED`), subscribe/unsubscribe writes confirmed, and favourite writes confirmed. The favourite
write's own probe has been promoted to the fix in the SDK, so the next build carries it as production code.

## 2026-09-27 (8) - the write tests left the account exactly as they found it

After the favourite and subscription writes were exercised on item `2905017768` and `3774904326`, 我的 reads
**我的订阅 1 / 我的收藏 239 / 我的投票 —**, the same counts the account had before the tests (and the same
favourites total the pre-deletion arm-A baseline recorded). A stale 我的收藏 page showed the empty state for a
moment because it had been reloaded while the item was briefly unfavourited; the authoritative counts on 我的
show nothing was lost, and no write was left applied.

That closes the interaction work of ③ on the device: subscription state, favourite state, subscribe/unsubscribe
writes, and favourite writes - each read and each write confirmed, with the account restored afterwards.

## 2026-09-27 (9) - the discover rails fail on a dropped connection, and a retry does not recover it

Opening 发现 showed every rail as 「此主题加载失败」 with a retry control, and the engine's own lines say why:

```text
00:51:30 I/WallHubSteamSession: phase_signed_in
00:52:12 I/WallHubSdkWorkshop: workshop interaction id=2905017768 ... favorited=FAVORITED      (reads answered)
00:57:18 W/WallHubSdkWorkshop: workshop read unavailable: temporarily_unavailable: the connection failed before the service answered
00:57:18 ... eight identical lines, one per rail
00:57:33 the same line after tapping a rail's retry
```

So this is not a filter, a selector, or a schema problem: the CM connection stopped answering about six minutes
after the session reported itself signed in, and every subsequent read reports the transport failure while the
session state never changes and the in-app retry does not re-establish it. Two things follow that a fresh
session should pick up: the discover rails were not readable at all in this state, and ③'s remaining rail-based
readings (合集子项, 好友动态排序, the two data sources) need a session that reconnects, so the reconnect path is
worth reading next - whether the SDK is meant to notice the drop (heartbeat policy) or the app to re-establish
the session when a read reports `temporarily_unavailable`.

No code change was made for this reading; it is recorded so the next session does not mistake the rails'
failure for the filter work of ①.

**The device was healthy while the reads failed.** One minute later: the app was alive and in the foreground
(`pid 14223`, `topResumedActivity=com.wallhub.android/.MainActivityThemedIcon`) and the default network was a
validated WiFi link (`WIFI CONNECTED`, `VALIDATED`, RSSI -12, 433 Mbps). So the failure is a stale CM
connection inside the session - the state still reports `phase_signed_in` and the engine never re-establishes
it - not a device or network outage. That is the concrete thing to read next: either the SDK should notice the
drop (heartbeat policy) or the app should rebuild the session when a read answers
`temporarily_unavailable`.

**The keepalive is not the missing piece either.** The protocol client always starts with a 30 s / 90 s
heartbeat policy (`crates/sc-protocol/src/async_client.rs:612`, applied at `:852`), and the engine's
`heartbeat_policy: None` selects that default rather than disabling it
(`crates/sc-content-ffi/src/lib.rs:557`, `crates/sc-content/src/session_bootstrap.rs:281`). So the connection was
being kept alive and still died unnoticed above the heartbeats: the session state never moved off
`phase_signed_in`, and a read that answers `temporarily_unavailable` (retry advice `Immediately`) does not
rebuild the session or the client. That session-failure path is the next thing to read.

**A restart is the only thing that repairs it.** Force-stopping and relaunching the app one minute later gave
`phase_signed_in` and every read answered again - the public page (`rows=30 of 30`), subscriptions (`rows=1 of
1`), favourites (`rows=1 of 1`). So the stale connection is a defect with a workaround rather than a lost
network: the app rebuilds its session on start and never rebuilds it while running, which is why the discover
rails stayed failed through a rail's own retry while the device and network were healthy.

## 2026-09-27 (10) - the discover rails load again once the session is healthy

With the restarted session the discover feed's rails answered, two of them logged within a second of opening
发现:

```text
00:59:55 I/WallHubSdkWorkshop: workshop page rows=20 of 20 collection=public first-id=3807473731
    first-tags=[Scene, Anime, Wallpaper, Audio responsive, Customizable, Dual 7680 x 2160, Media Integration, Everyone]
00:59:56 I/WallHubSdkWorkshop: workshop page rows=20 of 20 collection=public first-id=3616541797
    first-tags=[Wallpaper, Scene, Anime, Approved, 2560 x 1440, Everyone, Customizable]
```

So the rails are served by the SDK/CM path and carry the resolution, rating, and official tags the filter
vocabulary names - the 「此主题加载失败」 screens an hour earlier were the stale connection, not the rails
themselves. That also means the rail-based readings still owed to ③ (collection children, friend-activity
ordering, and the two data sources) can be taken on a session that has just started, and that a restart is the
workaround until the reconnect path is fixed.

## 2026-09-27 (11) - what the collection-children reading needs

Preparing the ③ item: `getPublicCollectionChildren` resolves the collection through the same
`openWorkshopItem` details read every item uses and returns `readCollectionChildren(result)` - a pass-through
over the details response's own ordered children (ADR 0033), with the contract's null for an unavailable read
and an empty list for a resolved collection that holds none
(`data/steam/SteamContentSdkWorkshopRepository.kt:213-228`). Nothing in that path logs, so the device reading
this item owes needs one of two things: opening a collection item whose page lists its children, or the same
kind of one-line diagnostic the interaction reads got. The rails themselves now load (entry (10)), so the
former is available on a freshly started session.

## 2026-09-27 (12) - the collection-children reading now has its line

The read had no diagnostic, so the device reading ③ owes for 合集子项 had nothing to look at. It now logs the
collection's id, the number of children the service returned, and up to twelve of their ids
(`data/steam/SteamContentSdkWorkshopRepository.kt`, `getPublicCollectionChildren`), in the same one-line shape
the interaction reads use. A refused read still answers null and therefore logs `count=null`, which is the
"unavailable" answer the contract allows and not the same reading as a collection that holds no children.
The line is app-side only, so it needs the next application build but no new AAR.

## 2026-09-27 (13) - the clean 0.1.58 build is on the device with the children line

Clean AAR **0.1.58** (`sha256=2b587816b0d872af42302ecf91959f6fe0b024c8dc8ef8ba04254fa94916f205`) now carries the
promoted favourite read/write fixes with no probe wording, the app was rebuilt against it
(`20260926T171238Z-1406915700`), and the collection-children diagnostic went with it. A fresh start reads
`phase_signed_in`, the public page (`rows=30 of 30`) and a discover rail (`rows=20 of 20 collection=public`,
tags including `Dual 7680 x 2160` and `Everyone`).

No `workshop collection children` line appeared for those taps because no collection was opened - the read runs
only when a collection item's page asks for its children. That is the one step left for this ③ item: open a
collection from the discover feed on a freshly started session (entry (11) explains why a fresh session), and
the line will report the collection id, the child count, and up to twelve child ids, with `count=null` meaning
the read was unavailable rather than an empty collection.

**Device provenance for the clean build.** The APK built against 0.1.58 carries a native library byte-identical
to that AAR's (`sha256=a5b39e19fdf17a11c3500b69768991f22c032a17ec71e326e649730a96874749` for
`lib/arm64-v8a/libsc_content_ffi.so`), and the device's own `lastUpdateTime=2026-09-27 01:15:01` is the install
recorded above. So what runs on the phone is exactly the clean 0.1.58 SDK plus the application build with the
collection-children diagnostic - not the earlier probe artifact.

## 2026-09-27 (14) - the collection-children reading, taken on a freshly started session

③'s 合集子项 row needed one device reading and the diagnostic from entry (13) produced it. The app was cold
started (`phase_signed_in` at `09:30:23.703`), 发现 was opened, and its rails were scrolled through:

```text
09:30:55.934 I/DiscoverFeed: generation=1 metadata=NETWORK version=b6fe847d4f81ce23 specs=86 focusCreators=78 focusCollections=20
09:31:31.989 I/WallHubSdkWorkshop: workshop collection children id=1884263373 count=39 ids=[1807555128, 996733973,
    1436407576, 1334736987, 1457581889, 1245412789, 1487388360, 1508891127, 1195626192, 1592822778, 1823900922, 1136243728]
09:31:55.524 I/DiscoverFeed: showcase=official:focus-collections pool=20 requested=20 resolved=19 durationMs=59587
```

**The row is available, not unavailable.** `count=39` - not `null` - so the read succeeded rather than being
refused, and the ids are the collection's own ordered children (`data/steam/SteamContentSdkWorkshopRepository.kt:219-235`,
capped at twelve by `MAX_LOGGED_CHILDREN`). The path that reaches it is the discover one, not the detail screen:
`SteamDiscoverCollectionResolver.kt:54` is the only caller, reached from `DiscoverFeedCoordinator.kt:143-146`
(a rail whose spec carries a collection id) and `:179-188` (a featured showcase's collection cover fallback),
and the id itself comes from the official discover descriptor (`DiscoverQueryAdapter.kt:199`). That metadata
names **20** focus collections (`focusCollections=20`), and the showcase resolved 19 of them, so this is one of
the feed's own collections.

**The ids are the service's, not the app's.** Four of the twelve were resolved anonymously afterwards with
`GetPublishedFileDetails`: `1807555128` "Kendo 日式女剑客", `996733973` "森 - 黄昏", `1436407576`
"Samurai - Motion [3840 x 2160]", `1334736987` "mount fuji in autumn sunrise" - every one `result=1` with
`consumer_app_id=431960`. One observation is recorded without a diagnosis: the collection id itself is not
anonymously readable (`GetCollectionDetails` and `GetPublishedFileDetails` both answer `result 9`), while the
session-authenticated read answered its children, so that item is visible to the signed-in account rather than to
an anonymous caller.

**Two further device facts from the same session.**

- **The connection died 92 seconds after `phase_signed_in`, during a burst, and said nothing.** The last
  successful read is `09:31:52.799` (`workshop page rows=20 of 20`), the first refusal is `09:31:55.494`
  (`temporarily_unavailable: the connection failed before the service answered`), and 28 refusals follow while
  `WallHubSteamSession` prints **no** line at all after `phase_signed_in` - the session state never moves, which
  is the entry (9) defect again, now with a much shorter healthy window (~92 s against that reading's ~6 min) and
  a burst of concurrent detail reads immediately before it (the focus-collections showcase resolves 20
  collections plus their covers through `getPublicDetails`, which the AAR serves as one single-item read per id,
  `SteamContentSdkWorkshopRepository.kt:207-208`).
- **A discover rail asks for a filter the SDK refuses on purpose.** Six refusals read
  `workshop read unavailable: unsupported: this provider cannot express a content-type or visibility filter`.
  That is a deliberate refusal rather than a regression (`crates/sc-content/src/workshop_session.rs:26-27` and
  `:498`), and the only place the app can send a content type is `sdkContentTypes()`
  (`SteamContentSdkWorkshopRepository.kt:917-930`), which maps `WorkshopType.VIDEO` to
  `WorkshopContentType.VIDEO` and lets `SCENE`/`WEB` fall through to tags. So a video-narrowed browse on this
  source is refused rather than served; the rail that asks for it is still to be named.

## 2026-09-27 (15) - the video filter was asking for a filter the SDK refuses on purpose

Entry (14) recorded the six `unsupported: this provider cannot express a content-type or visibility filter`
refusals without naming the rail. The cause is not a particular rail: it is `sdkContentTypes()`, which mapped
`WorkshopType.VIDEO` to `WorkshopContentType.VIDEO` and sent it as the search request's content-type filter.
The CM provider refuses **any** content-type filter by decision - the locked schema carries `filetype` only as
a bare `uint32`, and the enum naming the service's file-type numbers is outside the approved schema closure, so
the provider reports `Unsupported` rather than silently dropping the filter
(`crates/sc-content/src/workshop_session.rs:24-28` and `:493-499`). One refused field fails the whole request,
so a browse narrowed to video came back as an error instead of a narrower page.

**The type was already travelling as tags, which is why the filter was redundant as well as fatal.**
`steamTagCriteria()` requires `Wallpaper` and then excludes every type tag the user did not select, by
complement against `STEAM_WORKSHOP_TYPE_TAGS` (`WorkshopQuerySemantics.kt:75-80`); `steamWorkshopTag()` maps
`VIDEO` to the real Steam tag `"Video"` (`:190-196`, `:213`); `sdkExcludedTags()` hands that exclusion set to
the service (`SteamContentSdkWorkshopRepository.kt:943-944`); and the client-side gate still enforces the type
by name, `summary.type !in criteria.allowedTypes` (`WorkshopQuerySemantics.kt:167`). So the narrowing survives
without a content-type filter, on both the service side and the page gate.

**The change.** The SDK search now sends `contentTypes = emptyList()` with the reason recorded at the call site,
the author read sends the same (its filter was silently ignored anyway - `author_items` is `GetUserFiles`,
which has no content-type dimension), and `sdkContentTypes()` is deleted with its now-unused import. App-side
only: no AAR change is needed for this one, so it is not waiting on the SDK batch.

**Not yet read.** No Gradle runs on this host, so the worker's build is the first compiler this edit meets, and
the device reading is still owed: a video-narrowed browse should now answer `workshop page rows=…` instead of
the `unsupported` line, with the rows' own types still the video ones the tag gate admits.

## 2026-09-27 (16) - the video filter is fixed on the device, and the session fix is not wired yet

The SDK batch was built and published on the worker - **AAR 0.1.59**
(`sha256=9dec83d8fa4d0bf6d0f00a586bf4f1542c24bc8dc2d204e95f558ea4aa421253`), with the worker's own conformance
reporting `SC_WIN_ABI_LAYOUT=OK`, `SC_WIN_ABI_SYMBOLS=OK count=84`, `SC_WIN_SDK_CHECK=OK`, `SC_WIN_PARTIAL=none`,
`SC_WIN_RESULT=OK`. The app pinned it (`e113516`) and the release build succeeded at that revision:
`/root/builds/wallhub-release-20260927T020342Z-1220717.apk`, whose `lib/arm64-v8a/libsc_content_ffi.so` is
byte-identical to the AAR's (`883b0cb53d79de1ee5052399a0351726c2c02bc671448644b37f4a29e6391751`). That build is
the first compiler the entry (15) edit met, and it compiled.

**① is fixed, read on the device.** The same scenario that produced the refusals - cold start, 发现, scroll the
rails - now produces **none**:

| Reading, same scenario | Before (0.1.58) | After (0.1.59 + the fix) |
| --- | --- | --- |
| `unsupported: … content-type or visibility filter` | **6** | **0** |
| `workshop page rows=…` successes | 8 | **18** |
| rail fidelity | mostly `EXACT`, 2 `DEGRADED` | mostly `EXACT`, the same 2 `DEGRADED` |

The six refusals could only come from a query whose `effectiveWorkshopTypes()` named `VIDEO`, because
`sdkContentTypes()` mapped nothing else, so "0 refusals on the same rails" is the video-typed browse being
served rather than refused. Two side readings: the collection-children diagnostic reproduced the earlier
reading exactly (`id=1884263373 count=39`, same twelve ids), and the two `DEGRADED` rails
(`keyword:star wars`, `keyword:dragon ball`) are identical in both builds, so that degradation is pre-existing
and not something the filter change caused.

**B is carried but not driven, which is what the same log shows.** Only the six startup `WallHubSteamSession`
lines appear - `phase_signed_in` at `10:10:49.355` and **no state line after it** - and the first
`temporarily_unavailable` arrives at `10:12:18.211`, **89 seconds after sign-in**, with 26 refusals after it.
That is the entry (9) defect unchanged, and it is expected here: the AAR now exports `sc_session_maintain`,
`sc_session_pump`, and `sc_session_reconnect` (verified in the AAR: `SteamSession.maintain/pump/reconnect`),
but this application never calls them, so no heartbeat is sent and a closed link still publishes nothing.

**Next, and it needs this AAR.** Wire the session maintenance into the app: drive `maintain`/`pump` on the
engine that holds the session, and treat a `temporarily_unavailable` read as the cue to let the state change the
SDK now publishes reach the UI. Only then is B verifiable, and the reading it owes is named in the SDK's
`progress.md` §CD5w.

## 2026-09-27 (17) - the host takes over the session schedule

Entry (16) ended with the connection dying 89 seconds after sign-in while the AAR already carried the fix,
because nothing called the new entry points. This entry is the host half of that: the schedule is now driven
from the app, and a connection that cannot be rebuilt is reported instead of being left as a session that
claims to be signed in.

**Why nothing kept it alive.** `SteamContentSdkSessionBackend.pollUntilOutcome` is a per-attempt loop: it polls
events until the attempt reaches a terminal outcome, and `publishSignedIn` is one of those outcomes, so the
loop **ends at the moment the session becomes usable** (`SteamContentSdkSessionBackend.kt:437-445`). Nothing
owned the session after that, which is exactly what ADR 0025 leaves to the host - and what the host was not
doing: no heartbeat was ever sent, and no closed link was ever observed.

**The change.** `SteamContentSdkPlatformSession` gains `maintain`, `pump`, and `reconnect`, each carrying only
app-owned values (the three new enums and data classes in `SteamContentSdkSessionPlatform.kt`, with the SDK
ordinals resolved by total mappers in the same file, so no ordinal crosses the seam). `publishSignedIn` starts
one loop (`startMaintenance`, `SteamContentSdkSessionBackend.kt`), which every
`STEAM_SESSION_MAINTENANCE_INTERVAL_MS` (20 s, deliberately under the SDK's own 30 s heartbeat interval) drains
the socket with `pump` and sends the heartbeat that is due with `maintain(autoReconnect = true)`. A tick that
finds the session no longer `AUTHENTICATED` publishes `session_lost`, rebuilds once from the token the SDK
retained, and either logs `session_rebuilt` or - a rebuild that fails - closes the session and publishes
`SteamSessionPhase.FAILED` with a new user-facing string
(`backend_steam_session_connection_lost`, both locales). The loop is cancelled by `releaseSession` and by
`logout`, so it never drives a session being torn down.

**Two deliberate choices.** The cadence is a constructor input rather than a constant in the loop, because
scheduling is the host's business and because a maintenance loop is never idle: every existing unit test passes
zero and the one test that is about the cadence passes 1 s, which is also why the schedule needs its own test
rather than riding on the logon ones. And a rebuild is attempted **once**: the app's existing discipline on both
logon paths is one retry and no loop, and a rebuild that failed has already used the only token the app holds,
so a timer would only repeat a refusal.

**Owed reading, and it needs a build.** This entry has not been compiled or run: it is app-side only, so it
needs one application build on the already-pinned 0.1.59 AAR, no new SDK artifact. The reading it owes is the
one §CD5w names - an idle connection kept alive past the ~90 seconds it died at before, and a killed link
publishing `Disconnected`/`FAILED` instead of leaving `phase_signed_in` over a dead socket. The log lines to
look for are `session_maintenance pump=… step=…`, `session_lost`, and either `session_rebuilt` or
`session_rebuild_failed`.

## 2026-09-27 (18) - the host schedule holds the connection 4 minutes, and the rebuild needs a reason line

Entry (17) was built at revision `5c793f2`
(`/root/builds/wallhub-release-20260927T022352Z-1293321882.apk`, whose
`lib/arm64-v8a/libsc_content_ffi.so` is still `883b0cb5…`, the pinned 0.1.59 AAR's) and read on the device. This
was the first compiler that edit met, and it compiled.

**The connection is held far past where it used to die.** Cold start at `10:29:27`, `phase_signed_in` at
`10:29:28.448`, and then one maintenance tick every ~20.0 s with nothing driving the app:

```text
10:29:48.454 I/WallHubSteamSession: session_maintenance sdk=pump=read step=heartbeat_sent
10:30:08.459 … (the same line at 10:30:28, 10:30:48, 10:31:08, 10:31:28, 10:31:48, 10:32:08, 10:32:27 …)
10:33:26.794 I/WallHubSteamSession: session_maintenance sdk=pump=read step=idle
```

`pump=read` every tick means the CM's own heartbeats are being drained, so the SDK's liveness clock stays
fresh, and `step=heartbeat_sent` is our heartbeat going out when due. The session was still **serving reads**
long after the old death point: at `10:32:27` the discover rails answered `rows=20 of 20` with
`fidelity=EXACT`, and until the connection finally went at `10:33:36` this run had **0 read failures**.

| Same idle scenario | Before (entries 9/16) | After (this build) |
| --- | --- | --- |
| Session died at | **~89-92 s** after `phase_signed_in` | **4 m 08 s** (`10:29:28.448` -> `10:33:36.431`) |
| Loss visible to the app | no, `phase_signed_in` stayed | **yes** (see below) |
| Reads served after the death | none, 26-28 refusals until a restart | the loop reported it within one tick |

**The loss is no longer silent, which is the defect this work was for.** Ten seconds after the first failing
read the next tick found the dead link and said so:

```text
10:33:36.431 W/WallHubSdkWorkshop: workshop read unavailable: temporarily_unavailable: the connection failed…
10:33:46.796 I/WallHubSteamSession: session_maintenance sdk=pump=failed step=failed
10:33:46.797 I/WallHubSteamSession: session_lost
10:33:47.518 I/WallHubSteamSession: session_rebuild_failed error=IllegalStateException
10:33:47.522 I/WallHubSteamSession: phase_failed
```

So the app no longer claims `phase_signed_in` over a dead socket: it publishes `session_lost` and moves to
`phase_failed` with the new message, which is what the UI and a retry need.

**One thing this reading does not yet explain, and it is the next fix.** The automatic rebuild failed, and the
line does not say why. `session_rebuild_failed error=IllegalStateException` is all there is, because `record`
prints an exception's class name and not its message. The JNI bridge maps every non-OK status other than
`SC_STATUS_INVALID_ARGUMENT` to `IllegalStateException` and puts the SDK's own `sc_last_error_message` text in
it (`bindings/android/src/main/c/sc_content_jni.c:32-35`), so the reason existed and was thrown away at the log
line. Two candidates, both cheap to tell apart once the reason is printed: the FFI reported `UNSUPPORTED`
because the rebuild itself failed (the token or the transport), or the session was refused by a guard. The app
already logs `sdk=` detail on the logon paths, so this is the same treatment for the rebuild path.

Recorded as owed with that fix: whether the rebuild then succeeds, and - if it does - that a rebuilt session
serves reads again without a restart.

## 2026-09-27 (19) - a refused rebuild names its reason and hands over to the live-candidate restore

Entry (18) left one line unexplained: `session_rebuild_failed error=IllegalStateException`, with the reason
discarded at the log line. This entry is the follow-up, and it changes one part of entry (17): a refused
in-place rebuild no longer ends the session in `FAILED`; it hands recovery to the attempt path.

**The cause was not a missing token, and that was checked before changing anything.** `restore_session`
clears the retained token before it connects (`crates/sc-content/src/account_session.rs:3720`) and the token
logon sets it back on success (`:688`), so a session this app restored does hold one and
`AccountSessionProvider::reconnect` finds it (`:3972`). The refusal therefore lies in the rebuild's own
attempt, and only the SDK's text said which. The JNI bridge puts exactly that text in the exception
(`bindings/android/src/main/c/sc_content_jni.c:32-35`); `record` printed the class name and dropped it.

**The change.**

* `session_rebuild_failed` now carries `sdk=<the SDK's own words>`, bounded and stripped of control
  characters (`rebuildFailureReason`, `REBUILD_REASON_LENGTH = 240`). It is the same `sdk=` detail the logon
  paths already record, and the SDK's `set_error` text is already bounded and redacted before it leaves the
  frame.
* A refused in-place rebuild hands over instead of giving up. `reconnect()` reuses the discovery the session
  was opened with; the attempt path re-opens the engine - `SteamContentSdkEngine.open` closes and creates a
  fresh one - and `restore` already retries a transport-class failure against a candidate list read live from
  the directory. That live-candidate retry is the one thing an in-place rebuild cannot do, which is why the
  recovery belongs there rather than in another reconnect attempt.
* The hand-off cannot become a hot retry: the maintenance loop is already cancelled by `releaseSession`, and a
  restore that fails publishes its own failure and starts no loop. The loop only comes back if the restore
  succeeds, i.e. when recovery actually worked.
* `backend_steam_session_connection_lost` and its `SteamContentSdkSessionMessages` property are removed: with
  the hand-off, the restore path's own messages (`backend_steam_restore_failed`, `backend_steam_network_failed`)
  are the accurate thing to show, and an unused string with no caller is worse than none.

**Not yet built or read.** The two readings this owes: the `sdk=` text on `session_rebuild_failed` should name
why the in-place rebuild was refused, and - if the hand-off recovers - the log should go `session_lost` ->
`session_rebuild_failed sdk=…` -> `phase_signing_in` -> `phase_signed_in` with reads working again, with no
restart.

## 2026-09-27 (20) - the connection now outlives the window, so the rebuild path stayed unread

Entry (19) was built at revision `bfb699c`
(`/root/builds/wallhub-release-20260927T034034Z-327389332.apk`, native library still `883b0cb5…`) and the same
idle reading was taken. This build is faster and quieter than the last one - **and that is the whole finding:
the connection no longer died inside the observation window, so the two things entry (19) was written for were
never exercised.**

Cold start `11:45:33`, `phase_signed_in` at `11:45:46.940`, then the loop ran unattended:

```text
11:46:06.947 I/WallHubSteamSession: session_maintenance sdk=pump=read step=heartbeat_sent
… 17 ticks, every ~20.0 s, the last at 11:52:36.974 …
```

Nothing else happened: **0 `session_lost`, 0 `session_rebuild_failed`, 0 `temporarily_unavailable`**, and the
connection was still serving reads at `11:53` - `workshop page rows=20 of 20` with
`rail=… fidelity=EXACT` on 发现, roughly **eight minutes** after sign-in.

| Build | Session died at | Loss reported | Rebuild |
| --- | --- | --- | --- |
| 0.1.58, no host loop (entry 16) | **~89 s** | no | not attempted |
| `5c793f2`, host loop (entry 18) | **4 m 08 s** | yes, `session_lost` | refused, reason unreadable |
| `bfb699c`, host loop + hand-off (this entry) | **not within ~8 min** | n/a | n/a |

The last row is not evidence that the hand-off prolonged anything - it cannot, it only runs after a refusal -
so the difference from the 4 m 08 s run is the peer's own timing, not a fix. What it does mean is that the
`sdk=` reason on a refused rebuild and the live-candidate hand-off are **still unread**, and that the failure
they were written for is now hard to reproduce naturally.

**Two observations worth keeping.** A read fired *before* the restore finished at `11:45:43.705` and answered
`invalid_input: the session was not authenticated when the request was sent` - a startup ordering race between
the first screen's read and `phase_signed_in` at `11:45:46.940`, not the connection defect, and the only
refusal in the whole run. And the tick stream has one ~84-second gap (`11:50:32.373` -> `11:51:56.962`): the OS
had frozen the process with the screen off, which pauses the loop and resumes it cleanly afterwards. Neither
is a defect; both are recorded so a later reading does not misread them as one.

## 2026-09-27 (21) - the lost connection recovers on its own, twice by rebuild and once by restore

Entry (20) could not read the rebuild path because the connection outlived the window. A longer unattended run
- the same build, `bfb699c`, left alone for **23 minutes** - finally exercised every branch, and all of them
recovered **without a restart and without touching the phone for it**:

```text
11:53:54.128 I/WallHubSteamSession: session_maintenance sdk=pump=failed step=idle
11:53:54.129 I/WallHubSteamSession: session_lost
11:54:04.133 I/WallHubSteamSession: session_rebuild_failed error=IllegalStateException sdk=protocol adapter failed
11:54:04.140 I/WallHubSteamSession: phase_signing_in
11:54:04.146 I/WallHubSteamSession: credential_load present=true non_empty=true length_class=long stage=restore
11:54:04.970 I/WallHubSteamSession: phase_signed_in
11:54:24.973 I/WallHubSteamSession: session_lost
11:54:31.652 I/WallHubSteamSession: session_rebuilt sdk=replayable=0 abandoned=0
12:07:38.088 I/WallHubSteamSession: session_lost
12:07:46.553 I/WallHubSteamSession: session_rebuilt sdk=replayable=0 abandoned=0
```

**The reason entry (19) added a line for is now readable**: `sdk=protocol adapter failed`. That is the SDK's
own text for the in-place rebuild failing to re-establish the connection, which is exactly the case the hand-off
was written for - `reconnect` reuses the discovery the session was opened with, and that attempt could not
bring the link back.

**Both recovery paths work.** The hand-off restored the session in under a second
(`11:54:04.140 phase_signing_in` -> `credential_load … stage=restore` -> `11:54:04.970 phase_signed_in`), and
the two later deaths were recovered in place, `session_rebuilt` seven and eight seconds after `session_lost`.
Over the run there were **58 maintenance ticks**, three losses, three recoveries, and the session was healthy
at the end (`12:08:26 … step=heartbeat_sent`).

**What an outage costs, measured.** All 59 read refusals in the 23 minutes fall in a single ~11-second window
(`11:53:54` -> `11:54:05`), and they are 58 reads at `11:53` plus one at `11:54`: the 发现 feed was firing a
page of reads at the exact moment the link died. The `12:07` death, with nothing reading, cost **zero** read
refusals. So the recovery is real but not instant - a read that races the loss still fails, and the next tick
(within 20 s) is what puts it right.

With that, B is complete on the device: the connection is held through long idle periods, a death is observed
and published rather than hidden behind `phase_signed_in`, and the app rebuilds or restores itself with no
restart.

## 2026-09-27 (22) - a wide device sweep, and the findings backlog it produced

The connection work is finished, so this entry starts the next job: find out what actually works across the
whole app, and write down everything that does not. The result is a standing document -
[`device-verification-2026-09-27.md`](device-verification-2026-09-27.md) - with three lists: nine surfaces read
working, six defects with the log line behind each, and eleven features that still have no reading at all
together with what each one would take. `.gitignore` gained one whitelist line so that document is tracked; it
is the list the fixes will work from, and the log cannot hold it.

**One finding is critical and it is in this campaign's own code.** After the SDK recovered a connection in place
(`session_maintenance sdk=pump=failed step=reconnected` at `12:24:11.490`) the app went completely silent: no
further maintenance tick, no Workshop read, and 发现 produced nothing either - for over three minutes, while the
process stayed alive and in the foreground. Only a restart cleared it. That is the original silent-death defect
in a new place, and the two candidates to read first are the tick's one unguarded call (`session.state()`) and a
`pump`/`maintain` JNI call blocking while holding the session.

The rest are smaller and each has its reading in the document: an R8-obfuscated class name (`ne5 was cancelled`)
reported as a failure reason, the detail screen spinning forever with no error state, the settings card still
claiming the session lasts only until a restart, a data-source dropdown whose three options are all truncated to
"Steam …", and a cold-start read that fires before the restore finishes and is refused.









## 2026-09-27 (23) - the SDK side answers the "chunks per download" setting

The settings page has offered 12 to 48 chunks per download since before this campaign, and the finding backlog
recorded the copy as promising something the engine did not deliver. Reading the SDK end to end this time found
why, and the answer was not the chunk source at all: `download_depot` turned the setting into a schedule and
then ran the engine's **exclusive** provider, so the schedule reordered a strictly serial run. The engine's
concurrent provider existed and had no caller anywhere in the workspace. With that wired, the client pool
inside the CDN chunk source - which had been capping concurrency at eight clients - was raised to the top of the
range this application offers (48), and the request/join wave was replaced by a resident worker set that lets
the loop wait only for the chunk it asked for.

**No application source changed for this.** The setting already reached the SDK
(`SteamContentSdkEngine.startDownload(..., chunkConcurrency)`), the SDK now honours it, and the strings
(`settings_chunks_per_download`, "单项目分块并发" / "Chunks per download") are accurate as they stand. The
shared-vs-exclusive seam, the pool, and the worker window are all SDK-side and are recorded in the SDK's
`progress.md` (§CD5x-§CD5y); the SDK's own checks pass (764 tests) and two of them fail against the old wiring.

What this changes for the numbers a device reads: until now the value only reordered work, so a bandwidth
measurement at 12 and at 48 would have looked the same. After a preview AAR is built and installed they should
differ, which is the reading this entry owes. The pool opens connections lazily and never more than the window
has chunks in flight, so 48 is a permission and not a default: what the CDN actually sees is still whatever the
setting says. The first measurement should therefore be taken at the current setting and again at 12, and if 48
turns out to be read as abuse by the CDN the ceiling is a one-line change in
`crates/sc-content/src/cdn_chunk_source.rs`.

The SDK change also has to reach this repository the way every SDK change does: a new preview AAR published by
the worker, a version pin in `app/build.gradle.kts`, and a release build. None of that has been requested yet,
so nothing here has been built or installed, and the app on the device is unchanged.

## 2026-09-27 (24) - the app's unit tests had not compiled for a while

Verification of the SDK-path changes in entries (23) and this one ran `:app:testDebugUnitTest` on the build
worker, and the first result was a compile failure - not a test failure. Three test fixtures had fallen behind
interfaces the product code had already grown:

- `SteamContentSdkWorkshopTransferTest`'s `FakeSession` and `SteamContentSdkVideoStreamSessionTest`'s
  `FakeRangeSession` still declared `startDownload` without `chunkConcurrency` and `openRangeStream` without
  `cacheCapacityBytes` (the interface is in `SteamContentSdkWorkshopTransfer.kt`), and the video fixture was
  missing `manifestFiles` entirely;
- `DownloadRetryRoutingTest` used `java.nio.file.Files` without importing it.

Once those compiled, 184 tests ran and **13 failed**, and eleven of them failed for one reason that has nothing
to do with what they assert: `java.lang.RuntimeException: Method i in android.util.Log not mocked`. The transfer
logs one line per run (`download served by=steam-content-sdk app=... depot=...`) as the channel a device reading
uses, and the JVM tests exercise that path with no Android framework behind it. `app/build.gradle.kts` now sets
`testOptions { unitTests.isReturnDefaultValues = true }`, which is the documented remedy.

The suite is now **184 tests, 2 failed**; both failures are in `SteamContentSdkSessionBackendTest`
(`aTransportFailureOnLogonRetriesOnceWithLiveCandidatesAndTheSameSecret` and
`aTransportFailureOnLogonIsRetriedOnlyOnce`) and both assert `expected:<1> but was:<2>`: the transport-class
logon failure is retried twice where these tests expect one live-candidate retry. That is a session-domain
finding, recorded here rather than papered over - neither the test nor the retry policy was changed without a
reading that says which one is wrong. The eleven download-transfer tests now pass, including the new
`overlappingDownloadsShareOneSessionInsteadOfQueueingOnIt`.

## 2026-09-27 (25) - the chunk concurrency floor was 12, and the experiment needed 1

Three items were queued on the AAR 0.1.62 build to take the readings entry (23) owed. None of them overlapped
in time, so the two-item concurrency reading is still owed, and each failed differently:

- `3790379688` failed with `content corrupted: content resolution failed at manifest_decode
  (manifest=manifest_non_portable_path)` - a manifest with a non-ASCII file name, which the SDK refused
  outright; fixed on the SDK side and waiting for a build;
- `3776778760` failed with `Chunk source refused the request: CDN candidate cdn-2-https transport failed:
  InvalidResponse` **58 seconds into a real download**, and `3799142774` had failed the same way earlier at
  `18:43:55`: two items, two times, one candidate, so the transient CDN failure is not a one-off. Two SDK fixes
  target it (retry inside the run, and rotating each pooled client's starting candidate so a 48-chunk window
  does not aim 48 connections at one host), neither built yet;
- `3779168864` (the item that used to fail on the journal) failed with `invalid task handle` from
  `NativeBindings.taskGetStatus`, i.e. the task token was already released when the app polled it. Separate
  lifecycle finding, not yet diagnosed.

The diagnostic experiment - retry the CDN failure at **one** chunk at a time - turned out to be impossible from
the app: the settings row offers `12, 16, 24, 32, 48` (`DownloadSettingsContent.kt`) and `safeChunkConcurrency`
clamps everything into `12..48` (`SteamDownloadModels.kt`). The floor is now 1 and the row offers `1, 2, 4, 8, 12,
16, 24, 32, 48`: the link, not the product, decides how many connections a depot can take, the SDK's own window
is exactly this value, and its client pool (`MAX_LIVE_CLIENTS = 48`) is the ceiling, so nothing offered is
silently reduced. `theChunkConcurrencyRangeIsTheSdkOne` pins the range.

**The change to the row was not enough, and the device said so.** Selecting "1" stored 12 and redisplayed 12:
the range was enforced in three places - the row's option list, `safeChunkConcurrency` for the request, and the
preference store on **both** read and write (`AppPreferencesStore.kt`, `coerceIn(12, 48)`). The store is the one
that decides what the user actually gets, and it had never been told the floor moved. One definition now lives in
`AppModels.kt` beside the preference it bounds (`MIN_CHUNK_DOWNLOAD_CONCURRENCY`,
`MAX_CHUNK_DOWNLOAD_CONCURRENCY`, `DEFAULT_CHUNK_DOWNLOAD_CONCURRENCY`, and `safeChunkDownloadConcurrency`), and
the other two call it, so the row's offer and the stored value cannot disagree again.

## 2026-09-28 (26) - the network was fine, and the app could not trust the gateway

The device failures had been read as CDN trouble for several builds, and the reading that ended that was already
in the log: `CDN candidate cdn-2-https transport failed: InvalidResponse at transport_tls, candidates=6`. The SDK
side of that sentence had just been taught to keep the reason (SDK §CD5ah), and the word `transport_tls` says the
handshake failed on **every** candidate offered - not a timeout, not a DNS answer, not an address-policy refusal.

The device resolves CDN names correctly (Private DNS/DoT to `223.5.5.5`, verified on the phone itself), and the
gateway terminates TLS with a user-installed CA: the phone's browser trusts it because Android puts user-installed
CAs in the `AndroidCAStore` entry set. The app did not use that set. `SteamContentSdkEngine.systemTrustBundle()`
enumerated **only** `/system/etc/security/cacerts`, so the handshake had no issuer it could verify, on every
candidate, forever - a permanent failure wearing a transient failure's clothes.

`systemTrustBundle()` (`SteamContentSdkEngine.kt:542`) now enumerates `KeyStore.getInstance("AndroidCAStore")`
(`ANDROID_CA_STORE`, `:553`) and hands the DER blobs to a pure, JVM-testable `trustBundlePem(...)`
(`:578`) that wraps 64-column PEM with `java.util.Base64` (minSdk 26). The store holds system **and** user
issuers, aliases are sorted so the bundle is deterministic, and a single unreadable entry no longer fails the
whole set.

**Reading after the fix: the first device download completed.** Spider-Man (`1633721655`), 19.3 MB, reached
`COMPLETED` with no `transport_tls` line. That is the reading this campaign was missing: the SDK data plane works
on this device once the trust input is the platform's actual trust input.

## 2026-09-28 (27) - a desktop-shaped texture is not a reason to keep a blank scene

The second reported defect was a wallpaper that converted "successfully" and rendered as a white screen. The
item's only texture is 3862x2160, `fmt=0`, `imageFormat=2`, base mip uncompressed - an ordinary desktop JPEG in
a TEX envelope. It took the retention route (`canRetainOriginal = true`, `TexMobileConverter.kt:124/:140`), so
the MPKG shipped a texture the mobile renderer does not decode, and what the user saw was the near-white
`clearcolor` behind it.

Two things in the ETC2 path conspired: it required both axes to be block-aligned, and `locateEmbeddedImage`
(`:482`) searched for the PNG signature only. The fix is to name neither of those as a reason to skip:

- the encoder pads each axis to a 4-texel ETC2 block and replicates the last row/column (`:161-168`, explained at
  `:569-580`), and `writeEtc2` declares the padded dimensions;
- `locateEmbeddedImage` (`:498-506`) accepts a PNG **or** a JPEG SOI (`FF D8 FF`, `JPEG_SIGNATURE`, `:1105`)
  and takes the first of either.

`TexMobileConverterEmbeddedImageTest` covers the JPEG-backed, unaligned case.

**Still owed (F2): the retention decision is made on the stored length, not the decoded footprint.**
`MAX_DECOMPRESSED_TEX_BYTES` is `mobileRgbaLimit()` clamped to `2..8 MiB` (`:1112-1122`) and is compared against
the TEX's *stored* size (`:743`), while the ETC2 route accepts up to 48 Mpx (`MAX_ETC2_PIXELS`, `:1111`). Any
image-backed TEX whose stored size is under 8 MiB but whose decoded RGBA `w*h*4` exceeds the budget is therefore
silently retained - about 2.1 Mpx and up, which is most Workshop artwork. The route should be chosen on the
padded RGBA footprint. Not changed yet: it is a behaviour change on the conversion path and is waiting for an
explicit decision.

## 2026-09-28 (28) - the download UI now says what the SDK knows, and a pause stays resumable

Three defects, one root each, all in the app's reading of SDK state rather than in the SDK's transfers:

- **the bar did not move.** The session download path never published committed bytes, so the status snapshot
  stayed at 0 and the determinate bar sat at 0% - a live transfer that looks frozen. The SDK relay now publishes
  `TaskEvent::ChunkProgress` per committed chunk (SDK `1bccb95`), and the UI asks
  `downloadProgressFraction(task)` (`DownloadsScreen.kt:1044`): `null` while `DOWNLOADING` with no committed
  bytes, which renders the indeterminate bar (`:867-880`). A rate is shown only from a strictly positive delta
  and is dropped after `DOWNLOAD_SPEED_STALE_AFTER_MS = 10_000L` (`FormalWorkshopDownloadWorker.kt:899`,
  `measuredDownloadSpeed`, `DownloadsScreen.kt:1058`) - a speed nobody measured is zero, not a stale number.
- **a pause read as a restart.** SDK `committed_bytes` is per-run, and a journal-skipped chunk publishes no
  event, so the resume point has to come from the durable staging directory:
  `durableCheckpointBytes`/`durableDownloadedBytes` (`:326-332`) with a `taskId=... resume checkpoint=N bytes`
  log line. A paused task now resumes from that floor instead of zero.
- **a cancel did not stay cancelled.** `requestCancellation` wrote only `requestedAction`, and the `REPLACE`
  worker that started wrote `CANCELLED` while the original worker's handler then wrote `QUEUED` and asked for a
  retry. `FormalWorkshopDownloadWorker` now identifies a user cancellation (`isUserCancellation`, `:547`),
  refuses to overwrite `CANCELLED` with a non-terminal status (`mayPublishDownloadProgress`, `:713`), and the
  transfer calls `task.cancel()` before `close()` so the FFI handle goes away with the work. The SDK side had a
  matching loss - a cancel after a pause/resume cancelled a stale handle and the run kept fetching - fixed in
  `7b6595f` and pinned by `a_resumed_run_stops_when_the_host_cancels`.

## 2026-09-28 (29) - the second item can download now: one session, not one lock

"The second item of the same app never downloads" had an app-side root as well as the SDK's journal collision.
`SteamContentSdkWorkshopTransfer` held one `sessionMutex` across the whole download, so item two queued behind a
live item and could time out before it ever started. The mutex now guards a **ref-counted lease**
(`acquireSession`/`releaseSession`, `SteamContentSdkWorkshopTransfer.kt:183/:200`): concurrent downloads share one
authenticated session and the session closes when the last holder releases. Cancel and delete also remove the
SDK's staging directory, not just the destination (`deleteManagedDownloadDirectories`,
`RoomDownloadTaskRepository.kt:518`), so a cancelled item leaves no `.sc-<manifest>-*.part` behind.

The same batch turned the SDK's task-event stream into a device reading: each dropped event is logged as
`task event kind=<name> value=<n>` (`SteamContentSdkWorkshopTransfer.kt:377`, `sdkTaskEventName`, `:431`). On
AAR 0.1.67 that produced the number this campaign needed: `download served by=...` to the first `task event
kind=9` was **25.3 s**, against roughly 3 s of app-side work in a ~28 s tap-to-bytes path. The app was not the
delay; the resolution window was, which is what the SDK's establishment budget entry (SDK §CD5aj) then bounded.

## 2026-09-28 (30) - build 0.1.68 on the device, and the size of it

AAR **0.1.68** was built on the worker and installed on `192.168.2.234:33445` with `adb install -r` (never
`pm clear` - the app's session and download state is the thing under test). The install was verified as the
artifact, not assumed: `base.apk` pulled back hashes `c9e2add7...`, equal to the built artifact, and the packaged
`libsc_content_ffi.so` hashes `9cdceddf...`, equal to the AAR's `.so`.

Size of the installed APK: **13,498,661 bytes (12.87 MiB)** - `classes.dex` 6.79 MiB,
`libsc_content_ffi.so` 6.53 MiB, `resources.arsc` 1.23 MiB. The SDK's Rust core is half the download, and it is
arm64-v8a only, which is the whole preview-AAR story rather than a packaging defect.

Unit tests, on the worker: `:app:testDebugUnitTest` **225 run / 2 failed**, both failures in
`SteamContentSdkSessionBackendTest` and both `expected:<1> but was:<2>`. The cause is the fixture, not the
product: `FakeSdkPlatform.openWithLiveCandidates()` increments `liveOpenCalls` and then calls `open()`, which
increments `openCalls` as well (`SteamContentSdkSessionBackendTest.kt:597-600`), while the tests assert both
counters are 1 (`:285-286`, `:307-308`). Fix owed: don't funnel the live open through `open()`. Every new
download/mpkg test in entries (26)-(29) passes.

## 2026-09-29 (31) - AAR 0.1.69: the audit fixes reach the device, and lint had never run

The audit batch (`7b6595f`, `72fa580`) was built on the worker as **AAR 0.1.69**
(`SC_WIN_SDK_CHECK=OK`, `SC_WIN_SDK_AAR_BUILD=OK`, `SC_WIN_PARTIAL=none`, `SC_WIN_RESULT=OK`, published
`sha256=0731cd8cc2a0b8e400e8860e848362b188122a42b1e0028ef347f8dcde76a34f`) and pinned at `4244d79`. The Windows
worker is the second platform in this check: it runs the SDK's own suite there, so the same commit is green on
both toolchains.

The app release APK built from that pin and was installed with `adb install -r`:

- artifact `/root/builds/wallhub-release-20260929T041752Z-1982620740.apk`, sha256 `99ee43c4...`;
- the device's `base.apk`, pulled back and hashed, is the same `99ee43c4...` - the install is the artifact;
- the packaged `lib/arm64-v8a/libsc_content_ffi.so` is `9388cb77...`, equal to the `.so` inside AAR 0.1.69
  (0.1.68's was `9cdceddf...`), so the native core really changed;
- size **13,498,661 bytes**, the same as 0.1.68: the audit fixes are Rust-source changes, not new payload.

**Unit tests: 227 tests, 0 failures, 0 errors** (37 JUnit XML files) on the worker. That closes the two
`expected:<1> but was:<2>` failures for good: the fixture counted one live-candidate open twice
(`2f3ae1e`), and with the counter fixed the retry policy's own assertions pass unchanged - the test was wrong,
not the product. The earlier 225/2 reading was a different scope (`:app:testDebugUnitTest` only); this run is the
repo's documented `testDebugUnitTest lintDebug`.

**`lintDebug` had not been run in this campaign, and it failed: 4 `MissingTranslation` errors.** All four are the
newest `backend_steam_*` strings, present in `values/strings.xml` and absent from `values-zh-rCN/strings.xml`:
`backend_steam_auth_rejected` (`:960`), `backend_steam_challenge_unsupported` (`:961`), `backend_steam_network_failed`
(`:981`), `backend_steam_sdk_session_unavailable` (`:993`). The Chinese strings were added at `636d49a`, and
`:app:lintDebug` is now `BUILD SUCCESSFUL` (exit 0). The installed APK predates that commit, so these four
display strings are English on the device until the next build; nothing about the download path depends on them.

One trap worth recording because it looks like a product failure and is not: the first `:app:testDebugUnitTest`
invocation died in 5 seconds with `IOException: 文件名、目录名或卷标语法不正确` from
`SdkLocator.validateSdkPath`. The cause was my own `local.properties`, written as `sdk.dir=F:\AI-Studio\.android-sdk`.
A `.properties` file treats `\` as an escape character, so that reads back as `F:AI-Studio.android-sdk`. The
worker daemon writes `F\:\\AI-Studio\\.android-sdk` for exactly this reason; the hand-run must too.

## 2026-09-29 (32) - the account-persistence review: one dead cleanup path, and three silent failures

A read-only review of the "staying signed in" path (`AndroidKeystoreEncryptedStringStore`, `SteamCredentialStore`,
`SteamContentSdkCredentialProvider`, the credential half of `SteamContentSdkSessionBackend`, its DI wiring, the
backup rules, and the sibling API-key store) produced the findings below. Two independent adversarial reviews ran
against the same surface; where they disagreed with the code, the code won and the correction is in the finding.

**F1 (highest) - the only path that clears a dead credential cannot be reached, so a revoked token is replayed
forever.** `publishAttemptFailure` clears the store only for `SteamSessionPhase.EXPIRED`
(`SteamContentSdkSessionBackend.kt:971-979`); every other class, including `AUTHENTICATION_FAILED`, publishes
FAILED with `hasStoredSession` unchanged (`:989-999`), and `SteamContentSdkFailurePhase` maps
`AUTHENTICATION_FAILED` to FAILED (`SteamContentSdkSessionPlatform.kt:268-274`). EXPIRED is reachable only from
`SESSION_EXPIRED`, which the SDK emits for `ErrorCode::SessionExpired | AuthenticationRequired`
(`Steam Content SDK/crates/sc-content-ffi/src/session.rs:390`) - and `SessionExpired` is **never constructed
anywhere in the workspace** (only the variant, its display string, its retry advice, and the two consumers:
`sc-types/src/lib.rs:327/356/389`, `download.rs:261`, `session.rs:390`). The one real producer of
`AuthenticationRequired` is the content-target layer for `NotAuthenticated | SessionUnavailable`
(`crates/sc-content/src/content_target.rs:78`), not a logon rejection, which the SDK classifies as
`AuthenticationFailure` -> `AUTHENTICATION_FAILED` (`session.rs:375-376`). Meanwhile `LibraryScreen.refresh()`
calls `restorePersistedSession()` whenever `hasStoredSession && phase in (RESTORABLE, FAILED)`
(`LibraryScreen.kt:634-638`, `:1976-1981`), so the rejected token is re-presented on every refresh with no
backoff and no cap. Minimal fix: on the **restore** path, treat `AUTHENTICATION_FAILED`/`UNSUPPORTED` as a dead
stored credential - clear it and publish EXPIRED/`hasStoredSession=false` exactly as the EXPIRED branch does.

**F2 - `logout()` can fail to clear and still claim it did.** `clearStoredCredential()` is
`runCatching { credentialStore.clear() }` (`:1035-1037`), and `clear()` raises when the preferences commit fails
(`AndroidKeystoreEncryptedStringStore.kt:80`). Logout then publishes `SIGNED_OUT` with
`hasStoredSession = false` (`:221`) regardless, so the next launch restores the account the user just signed out
of. Minimal fix: make clear() report success and only publish the signed-out claim when it really cleared.

**F3 - `SteamDeviceSeedStore.seed()` returns a seed it failed to persist.** `runCatching { file.writeBytes(...) }`
swallows the failure and the generated seed is returned anyway (`SteamDeviceSeedStore.kt:35-43`). `seed()` is
called on every engine creation (`SteamContentSdkEngine.kt:135`), so on a device where that write fails the
derived `machine_id` changes on every open - the exact "Steam Guard sees a new device" failure the class's own
documentation exists to prevent - silently. Minimal fix: report the write failure instead of discarding it.

**F4 - a user-initiated sign-in is silently dropped while any attempt is running.** `launchAttempt` returns
without a trace when `attemptInProgress` or `logoutInProgress` is set (`:267-269`), and a background restore can
hold that gate for up to `SESSION_ATTEMPT_TIMEOUT_MS = 5 min` (`:1134`). `login()` (`:118`) and
`restorePersistedSession()` (`:102`) both go through it, so a tap can produce nothing at all. Minimal fix: keep
the single-attempt invariant but publish a visible reason, or supersede the running attempt when the user asks for
one.

**F5 - the sibling API-key store destroys what it cannot read, and can resurrect an older plaintext key.** On
`Unreadable`, `ensureMigrated` clears the encrypted blob (`SteamApiCredentialRepository.kt:78-84`) and then falls
back to the legacy plaintext DataStore (`:91-95`), so a transient keystore read failure both loses the current key
and can adopt a stale one. That policy is deliberate for the API key (pinned by
`SteamApiCredentialRepositoryTest.kt:55-62`), but the resurrection half is not: it is reachable precisely because
the legacy clear is skipped whenever the encrypted value is already non-empty (`:86-89`), so a legacy plaintext
key that survived an earlier failed clear stays on disk forever and is preferred the first time the encrypted read
fails. The Steam session store takes the opposite and better policy - an unreadable session is retained, never
cleared (`SteamCredentialStoreTest.kt:21-39`).

**F6 - the persistence test asserts a transaction the product does not implement.**
`SteamCredentialTransactionTest.kt:58-98` defines its own `FakeCredentialStore` with a generation counter, so
"stale expired-session clear cannot remove a newer login" (`:47-56`) holds only inside the test file. Production
has no generation guard: `writeRetainedToken` reads, compares, then saves (`:856-875`) with per-call locking only,
and the only `deleteKey()` caller is the recovery branch at `AndroidKeystoreEncryptedStringStore.kt:73`. There is
also no test of `AndroidKeystoreEncryptedStringStore` at all. This is a record-integrity finding: the suite reads
as if the guarantee were covered.

**F7 (low, latent) - unreadable state reaches the data plane as "no credential".** `load()` collapses `Unreadable`
to null (`SteamCredentialStore.kt:53-54`), so the download and range arms report "the stored Steam refresh token;
none was read" (`SteamContentSdkWorkshopTransfer.kt:221-226`, `SteamWorkshopVideoStreamRepository.kt:118-121`)
when the blob exists but cannot be decrypted - a different and less honest statement than the session layer's
`sessionStorageUnavailable` (`:294-301`).

**F8 (low, latent) - `PersistedSteamCredential` and `SteamContentCredential` are `data class`es whose generated
`toString()` prints the refresh token** (`SteamCredentialStore.kt:9-14`, `AppModels.kt:107-110`), unlike
`AppPreferences`, which hand-redacts its secrets (`AppModels.kt:157-177`). No current call site interpolates them,
so nothing leaks today.

**Corrections to the cross-checks, after reading the code:** (a) the recovery branch in `write()` cannot destroy a
*valid* stored account - it fires only on `KeyPermanentlyInvalidatedException`, i.e. on a key that has already made
the old ciphertext undecryptable, and for this non-auth-bound key that exception is unlikely to be thrown at all;
the real gap is that any *other* unusable-key condition is an unrecoverable, silent dead end because
`getOrCreateKey()` never replaces an existing key (`:88-105`); (b) overwriting a store that read as `Unreadable`
(`SteamContentSdkSessionBackend.kt:851-875`) is a documented recovery, and it discards nothing recoverable - the
only loss would be the currently-stored value, which in that state is already undecryptable; (c) logout does not
leave a stored credential behind: `persistRetainedToken` re-reads `activeSession` (`:833`), which logout has
already cleared, so the late write is skipped - what survives is the stale *publish*, since
`publishSignedIn` (`:801`, `:809`) never consults `logoutInProgress` and the Guard-answer coroutine
(`:131`) is not tracked by `attemptJob`, so a `SIGNED_IN` can land after `SIGNED_OUT` over a session that is
already closed; (d) the production credential store has exactly one constructor call site
(`SteamSessionBackendModule.kt:67`), so the "two writers" concern is not a duplicate-instance bug - the
check-then-act window in F6 is the real one, and it needs overlapping attempts to be reachable.

Reviewed and clean, so it is not re-audited: AES-256-GCM with a provider-generated per-write IV persisted in the
same `commit()` as the ciphertext and no nonce reuse (`AndroidKeystoreEncryptedStringStore.kt:107-121`);
`allowBackup="false"` plus blanket excludes in `backup_rules.xml` and `data_extraction_rules.xml`; the `\u001F`
record framing cannot be injected because every v2 field is Base64 (`SteamCredentialStore.kt:74-92`); malformed
records become `Unreadable`, never an empty credential (`:94-113`); no token reaches logcat or diagnostics (only a
coarse length bucket, `SteamContentSdkSessionBackend.kt:1109-1129`); passwords and Guard answers are converted for
one call and zeroed (`:137-147`, `:355-370`, `:421-431`); `SIGNED_IN` is published only from an SDK
`AUTHENTICATED` state (`:633-636`, `:704-707`); one live-candidate retry and no retry loop (`:388-391`,
`:316-326`); the success message never claims a save that did not happen, because `messages.signedIn` is
`backend_steam_sdk_session_only` (`SteamContentSdkSessionMessages.kt:78-79`) - the string that does claim it,
`backend_steam_login_success`, is dead and can be deleted with its `zh-rCN` entry.

## 2026-09-29 (33) - all eight account-persistence findings are fixed, and none of them on a device yet

The review in entry (32) left eight findings and two smaller inconsistencies. They are now all addressed, in two
rounds and nine commits. The readings below are unit-level; the device checks this path still owes are listed at
the end, and until they are taken none of this is a device conclusion.

**Round one** (`c8e94be`, `b0ac460`, `57726f9`): F1, F2, the engine/session-loss state bug, F3a, F4's first half
and F6.

**Round two** (`ea103c2`, `054d1cf`, `0c6e6c5`, `c0112d3`, `23622f0`, `c8c21fd`):

- **F5** - the API-key store kept none of its promises. An unreadable encrypted value was cleared, destroying
  the user's key over a transient key-store failure; the legacy plaintext value was then adopted, silently
  replacing the current key with an older one; and a legacy clear that failed was skipped forever afterwards,
  because the migration only ran when the encrypted value was empty. An unreadable value is now kept and the
  pass retries, the plaintext entry is consulted only when the encrypted store is empty, and it is removed
  whenever a readable encrypted value exists.
- **F4's second half** - `submitSteamGuardCode` ran in a coroutine of its own, which no sign-out cancelled and
  which could release a session a newer attempt had opened. It is an attempt now, `PendingChallenge` carries the
  session that asked for the challenge, a cancellation is rethrown instead of being reported as a refused code,
  and a queued answer that arrives after a sign-out is recorded rather than publishing a failure over the phase
  the sign-out just set. That last part is a defect this batch's own test exposed: the first version of the
  routing published `FAILED` over `SIGNED_OUT`.
- **F7** - the provider collapsed `Unreadable` into null through `load()`, so the download and range arms
  reported "none was read" while an undecryptable record sat in the store. It answers null only for a store that
  holds nothing and throws a named failure otherwise.
- **F8** - `PersistedSteamCredential` and `SteamContentCredential` are data classes whose generated `toString()`
  printed the refresh token. Both are hand-redacted now, as `AppPreferences` already was.
- **the unrevoked service logout** published `hasStoredSession=false` without clearing the store; it now reports
  what is stored.
- **the dead `backend_steam_login_success` string** is gone from both locales, referenced by nothing.
- **the shutdown path leaked the maintenance loop**: `close()` cancelled the attempt but not the loop driving the
  session it was about to close, so the loop kept ticking until its next interval noticed. This one was not in the
  review list - it was found re-reading the file - and it is fixed with a test that asserts no tick happens after
  the backend is closed.

Two hazards inside the batch are worth keeping in mind for any future work on the attempt gate. The first: the
gate must open from `Job.invokeOnCompletion`, not from the attempt body's own `finally` - a job cancelled before
its body ever runs never enters that `finally`, which leaves the gate closed for good; the first implementation
did exactly that. The second: `cancelAndJoin` means a *tracked* attempt can never publish after a sign-out, so the
only way a stale auth edge could land was the untracked Guard coroutine, which is what this batch removed.

Verification: `:app:testDebugUnitTest` + `:app:lintDebug` on the worker, `TESTS_EXIT=0`, **270 tests, 0 failures,
0 errors**, `BUILD SUCCESSFUL` with `Task :app:lintDebug` actually executed. The suite grew 227 -> 270 across the
two rounds, and the keystore layer - which had no test at all - now has ten.

**Device readings still owed for this path**, because nothing here has run on the phone yet:

1. an existing stored session still restores with no unexpected re-login (the one check that covers the
   `java.util.Base64` swap and the keystore seams, which only the device exercises);
2. a refused credential is cleared once and reported expired, and is not replayed on the next start;
3. a sign-out reports `sign_out_incomplete` only when the store really could not be cleared;
4. a lost connection lands in `RESTORABLE`, re-restores, and Workshop reads work again afterwards (the browse
   path needs the engine the session domain reopens);
5. pause -> resume, cancel, and a paused-then-cancelled task, which exercise the gate changes.

## 2026-09-30 (34) - the session that drops every few minutes, and the phase that lied while it recovered

The report was "登录会掉 while a Workshop download runs". Two hours of 0.1.71 capture say it is not occasional
and not caused by the download: the stored session is lost and recovered on a cycle, and the download is what
turns a recoverable cycle into a long sign-out.

**What 0.1.71 showed, 21:45 - 22:24.** Six `session_lost` events with 400 / 500 / 500 / 610 s between them.
Every one recovered through `restore_retry_live_candidates` in 3 s to 2m19s - except the first, at 21:37:57,
which stayed down for **7m36s** until the user retried by hand. The mechanism, from the 21:38 window:

```
21:38:10.050  phase_failed                        <- the first restore attempt failed
21:38:10.050  restore_retry_live_candidates
21:38:10.972  WallHubSdkRoute cm candidates=4 source=live retry=true
21:38:16.994  content session reused=false        <- the download plane opens its own route
21:38:17.006  WallHubSdkRoute cm candidates=4 source=cache
21:38:20.997  phase_failed                        <- the retry that was working is gone
```

The published phase was `FAILED` from 21:38:10.050 to 21:38:20.997 with **no `SIGNING_IN` in between**, while a
live-candidate retry was still connecting. The download path asked the phase, was told nobody was signing in, and
opened a competing session on the engine's single slot - which is what destroyed the retry that was working.

**Batch one (`e84dc96`, APK `e7ee8859`, installed 23:48).** The maintenance line now carries the SDK's own reason
(`pump=failed:<text> step=<text> error=sdk|none`), a transient restore failure is retried on a bounded schedule,
and the download path waits up to 20 s for an in-flight account attempt before opening its own session. The
diagnostic is what the rest of this entry rests on: 0.1.71 could only say `pump=failed`, which does not separate
"the link dropped and the SDK reconnected" from "the link dropped and the reconnect failed". On the 0.1.72-dev
build both appear - `step=reconnected` at 23:49:31 with no session loss at all, `step=failed` at 00:26:37 with
one - and only the second is an outage.

**What that build then showed, 23:48 - 00:39.** Four losses (00:10:51, 00:18:03, 00:26:37, 00:39:21), each
recovered on its own in 10 - 24 s, with **0 `credential_cleared`** and **0 `invalid task handle`** in the whole
window. One cycle in full:

```
00:26:37.555  session_maintenance sdk=pump=failed:protocol adapter failed step=failed:protocol adapter failed error=none
00:26:37.555  session_lost
00:26:46.398  session_rebuild_failed ... sdk=authentication failed during AccessTokenLogon cause=CmRoute (result=None)
00:26:59.191  attempt failure class=3 stage=restore sdk=... cause=CmRoute (result=None)
00:26:59.192  phase_failed
00:26:59.192  session_recovery_scheduled attempt=1 delay_ms=5000
00:26:59.192  restore_retry_live_candidates
00:26:59.993  WallHubSdkRoute cm candidates=4 source=live retry=true
00:27:01.053  phase_signed_in
```

Two honest notes about that. The recovery itself is the pre-existing live-candidate retry, not the new schedule -
the schedule was recorded and then stood down, which is what it is for. And the same 1.9 s hole is visible here
(`phase_failed` at 00:26:59.192, `phase_signed_in` at 00:27:01.053, no `SIGNING_IN` between), so the bug batch
two fixes was reproduced live on the build batch one produced.

**Batch two (not yet on a device).** Four changes, all from these readings:

1. **every restore attempt announces itself.** `restoreOnce` publishes `SIGNING_IN` as `logonOnPlatformRoute`
   already did; the phase can no longer report a failure while a retry is connecting. This alone closes the
   21:38 window.
2. **the download path asks the gate, not the phase.** `SteamSessionRepository.isAttemptInFlight()` reports
   `attemptInProgress || pendingAttempt != null`, which is true from the moment the request is made rather than
   from the first publish, and the phase is still consulted as a second signal.
3. **the schedule spans the outage the device showed.** 5 / 15 / 30 / 60 / 120 / 240 s = 7m50s. The first version
   stopped at 50 s, which does not cover the 7m36s the 0.1.71 capture contains.
4. **one schedule per restore, not one per attempt.** The first version scheduled from the first attempt's
   failure, so the live-candidate retry's own failure immediately replaced the 5 s retry with the 15 s one: the
   schedule the log advertised was not the schedule that ran. The schedule test found this, not a reading.

Two shutdown holes in the same mechanism went with it. `close()` now cancels the pending retry - without it a
retry would fire up to eight minutes later against a backend that is gone and open the session `close()` had just
released. `logout()` cancels it too, and that one was a real defect: when the store refuses the clear the
credential is still there, so a retry left running would have read it and **signed the user back in after they
signed out**. `aSignedOutAccountIsNotSignedBackInByTheRecoveryItHadScheduled` covers it.

**Verification.** `:app:testDebugUnitTest` on the worker: `GRADLE_EXIT=0`, **283 tests, 0 failures, 0 errors**
across 42 result files, `BUILD SUCCESSFUL in 1m 16s`. The backend test file grew 38 -> 41 `@Test`.

Two traps in the harness itself, both now avoided rather than remembered. First: `roboCopy` excludes `build`, and
the test-results directory survives across runs, so a *failed compile* leaves the previous run's green summary in
place - `e84dc96`'s message reports 278 tests from a run whose result files were the 21:12 run's, and the honest
number for a tree containing that revision is the 283 above. The script now empties the results directory before
it runs. Second: a fixture that answers an exhausted event queue with `DISCONNECTED` is one the backend is right
to keep waiting on, so a recovery retry that reaches that state never ends and `runTest` waits for it forever;
`aTransportFailureOnRestoreRetriesWhenTheSessionIsLeftDisconnected` now closes the backend, and the two new
schedule tests step the clock explicitly instead of draining it.

**Still owed:** a device run of batch two - specifically that the phase reads `SIGNING_IN` through a live retry
(the 00:26 shape above should lose its gap), that a download arriving in that window logs
`content session waiting for the account attempt to settle` and then `content session reused=after-recovery`, and
that a `session_recovery_scheduled` retry beyond the first actually runs. The five device checks listed at the end
of entry (33) are still owed too.

## 2026-09-30 (35) - download control moved behind durable commands and one live host

The first control-plane migration from `docs/download-engine-architecture.md` is now live. Room schema 8 keeps v7
rows as `LEGACY`, while a new task is accepted by one transaction that writes the task projection and a versioned
`RUN` row in `download_commands`. `PAUSE`, `CANCEL`, `RESUME`, and transfer `RETRY` likewise append an intent revision;
WorkManager enqueue failure is only a failed wake-up, not a reason to rewrite an already durable job as failed.

One `SingleProcessDownloadHostCoordinator` owns live runs by task id. Concurrent wake-ups join its Deferred instead
of opening another SDK task. Its first run acquires a durable Engine Epoch; commands are claimed by that epoch and
acknowledged with per-task receipt sequences. Command acknowledgement and projection of applied/quiesced revisions
are one Room transaction. This paragraph records the v8 intermediate design: later convergence stopped attributing
late opposing commands to an earlier run, bound the owned run to the WorkManager execution window, and queued a
successor global wake for commands accepted during that run.

The v8 cut initially kept NEW and LEGACY routes plus a compatibility repository. Room v9 supersedes that cut: it
absorbs legacy actions into Desired Intent, creates missing Durable Commands, physically removes both routing columns,
and exposes one `WorkshopDownloads` intent/query/admin interface. The old per-task Worker remains loadable only to
drain WorkSpecs persisted by pre-v9 installations and must enter the same Host/Runner seam.

Evidence: Room 7→8 upgraded in-place on the OnePlus 5T; Release builds succeeded through revision `b8c6ecb`; the
worker's `testDebugUnitTest` completed successfully. A real NEW task `8f8e8cef-f0f7-40ab-99ff-9181c8785d0c`
(Workshop `3799142774`) reached SDK `MANIFEST_RESOLVED`, `DOWNLOADING`, durable `CHUNK_PROGRESS`, and an interval-derived
`THROUGHPUT_UPDATED=137211`. The first nonzero speed was persisted. Force-stopping the process mid-transfer and
starting the activity produced a new PID and the same task resumed through `RESOLVING -> DOWNLOADING`, with no fatal
exception. The compatibility runner is still a per-task WorkRequest behind the singleton host and is the next piece
to extract; this entry does not claim that deletion gate is closed.

## 2026-09-30 (36) - NEW downloads no longer create per-task WorkRequests

The compatibility transfer body is now an injected application-scoped `FormalDownloadRunner`; it has no input data,
tags, WorkerParameters, Worker ID, or WorkManager lifecycle API. `FormalWorkshopDownloadWorker` is reduced to the
LEGACY adapter that resolves a task ID and enters the same host/runner seam. NEW commands are read by the one unique
`DownloadHostWakeWorker`, reconciled concurrently through the singleton host, and run directly by the runner while
the one wake WorkRequest holds the OS execution grant. The wake worker drains another pending-command wave after
each cohort. Quality convergence later changed wake submission to `APPEND_OR_REPLACE`, closing the final empty-read /
late-command race by guaranteeing a successor reconcile window.

Device evidence on the OnePlus 5T for task `2a35de1f-5f0d-4e52-a998-ff066661d06d`: WorkManager started only
`DownloadHostWakeWorker`; the host logged `epoch=2 commands=[1]`, the runner executed, and the host wrote
`revision=1 sequence=1`. The only WorkManager completion tag was the singleton wake tag, not a per-task worker tag.
Release `a10f6fa` built successfully and the complete JVM unit suite passed at the direct-host cut. The device Room
7→8 instrumentation migration also passed one test before this cut. The local Steam credential was intentionally
absent after instrumentation reset, so authenticated transfer/Pause/Resume/Cancel remains a separate device check;
no credential was requested or copied into the test environment.

## 2026-10-16 — Official Wallpaper Engine MPKG tier alignment

Read-only comparison of authorized official `3757572576` and `3808922316` MPKG samples established three dynamic tiers:
`high_quality` keeps full resolution, `balanced` writes `scene.general.texturereduction=2` and stores roughly half-resolution visual mips,
and `compatibility` writes `texturereduction=4` and stores roughly quarter-resolution visual mips. Official static visual TEX
entries use ETC2 RGBA8 + LZ4, while R8/RG88 masks and flow data retain their channel formats. The Android converter now uses
these three stable IDs with Chinese labels 高质量、更佳性能、高性能 (default 更佳性能), removes the Source Fidelity UI mode,
invalidates pre-v3 checkpoints through the converter identity, rewrites scene reduction metadata, and records the official ETC2
logical/physical dimension contract.

The implementation also adds path-and-structure-gated Android shader handling, including the observed `g_PointerState`
compatibility adapter for xray shaders; unknown shader/resources remain warning-complete rather than being silently rejected.
Known visual TEX conversion failures are fatal instead of producing a known desktop-TEX gray/solid-color output. Reduced RGBA
effect textures now match the official header contract as well: original logical `image/texture` dimensions remain in the header
while the only stored mip is 1/2 or 1/4 size. The authorized Windows build for commit `bdcddd2` succeeded
(`20261016T133816Z-71627976`). GitHub boundary CI for the preceding published implementation succeeded at `38879448235`; this
latest header correction remains subject to the next published CI run. Physical Wallpaper Engine revalidation is pending because the authorized ADB device
`192.168.2.234:33445` was initially unreachable (`No route to host`). After the device returned, final build `bdcddd2` installed in place and completed a physical Wallpaper Engine matrix for the two original failures:

- `3757572576`: High Quality produced 42,343,924 bytes with 7800×5200 stored ETC2 mips and no `texturereduction`; Better Performance produced 11,291,738 bytes with 3900×2600 mips and `texturereduction=2`; High Performance produced 3,116,225 bytes with 1952×1300 mips, official virtual width 7808, and `texturereduction=4`. All three imported and rendered the complete collage rather than the former cyan screen.
- `3299228616`: High Quality produced 8,451,678 bytes with a 3840×2160 main ETC2 mip; Better Performance produced 5,840,833 bytes with 1920×1080 and `texturereduction=2`; High Performance produced 5,045,879 bytes with 960×540 and `texturereduction=4`. All three imported with the cat background, clock, water ripple, and moving light effects visible rather than the former gray/white field.

The new `3808922316` source initially hit an upstream `CONNECT 502` on the Workshop detail request. To avoid claiming a network download that did not occur, the authorized source fixture supplied in `MPKG转换研究/3808922316` was staged privately into a temporary diagnostic task (never committed or published). WallHub generated and Wallpaper Engine imported all three tiers: High Quality `14,540,053` bytes with no reduction marker, Better Performance `11,016,986` bytes with `texturereduction=2`, and High Performance `9,926,856` bytes with `texturereduction=4`. All three showed the complete 麻匪 Elisa artwork without the prior blank/solid-color failure. The diagnostic branch/APK and fixture staging were removed afterward; the production APK was reinstalled and the original task metadata restored.

## 2026-10-01 (37) - Quality convergence removes dual download authority in source

The source-only convergence phase made Durable Command/Desired Intent the only download-control authority, moved
Conversion Cancel to the same revision model, and derived UI Pending Control from unapplied revisions. Download and
playback now share one application `SteamContentSdkSessionBroker`; Workshop delivery routing has one Direct/Depot
classifier; Staging, preset dependencies, progress projection, task projection, and attempt settlement are deep
modules behind the runner. WorkManager exposes only one global `wake()` interface. The upgrade-drain per-task Worker
remains loadable solely for WorkSpecs persisted by older installations and cannot create new per-task download work.

Room v9 source migration absorbs v8 `requestedAction` into Desired Intent, creates Durable Commands for old active
downloads, preserves Conversion control without waking the download runner, rebuilds the task table, and deletes
`requestedAction` plus `executorVersion`. The migration test source asserts the data conversion directly. The exported
`FormalTaskDatabase/9.json` and Room identity validation are intentionally pending the authorized Windows Gradle/Room
schema run; this entry is source evidence only and does not claim build or device verification.

## 2026-10-20 — Complete Steam enhanced-connectivity investigation

The Android enhanced-connectivity route now precedes Steam login in both Settings and clean-data onboarding. The one toggle routes account CM and authenticated SteamPipe through a stable process-private CONNECT bridge without creating another account session owner. CM uses strict platform trust with no-SNI first and original-host SNI fallback; CDN distinguishes directory routing host, virtual TLS/HTTP authority, and proxy CONNECT identity, with a validated non-secret vhost extension available only on the authenticated SDK proxy capability. TLS verification was never disabled and HTTPS never downgraded to HTTP. Explicit user HTTP proxy configuration still takes precedence.

SDK `0.1.138` is locked to commit `be223b2`; Manifest Request Code and all other CM control-plane transport/session losses preserve `SESSION_GENERATION_LOST` instead of being flattened into authorization. Android performs exactly the existing bounded account-session replacement or one data-plane retry, and adds a 45-second post-first-byte no-progress watchdog that pauses for a single checkpoint-preserving retry. Clean-data onboarding can enable enhanced access and CM WebSocket before the first account connection. A bounded official Steam CM bootstrap set is used only when both the live Directory response and fresh persisted Directory window are unavailable.

LAN Windows built the final source successfully as job `20261020T141713Z-198103700`. Wi-Fi device `192.168.2.234:33445` repeatedly completed enhanced CM/Manifest/chunk downloads, including typed Generation-loss recovery. China Mobile NR_SA device `192.168.2.127:33445` clean-data tests proved: enhanced-first account login and device confirmation can succeed; content directory, Depot Key, Manifest Request Code, CDN token, manifest TLS/decode, checkpoint resume, and initial chunk traffic all work. However, the carrier repeatedly terminated the long-lived CM transport in roughly 3–5 seconds even with strict no-SNI/SNI candidate fallback, four CM candidates, a 3-second SDK heartbeat experiment, and a 1-second host maintenance pump. The aggressive cadence was reverted to the conservative 10-second SDK cap and 5-second pump because it did not change the outcome.

Therefore pure local DNS/no-SNI/routing cannot honestly satisfy the product promise of eliminating every mainland direct-network Steam failure on this carrier. Full success on this path requires a network-external transport endpoint (configured HTTP CONNECT proxy, operated WallHub relay, or VPN/TUN upstream). The product already sends both CM and CDN through an explicit HTTP proxy when configured, retaining one backend-owned account session. No mobile-data completion is claimed for automatic local mode alone.
