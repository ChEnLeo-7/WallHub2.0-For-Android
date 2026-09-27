# Device verification sweep - 2026-09-27

A broad pass over the app on the test device to find out what actually works, and to record every failure,
bug, and gap with the reading behind it. Fixes come later, one at a time; this file is the backlog.

- Build under test: application `bfb699c`,
  `/root/builds/wallhub-release-20260927T034034Z-327389332.apk`, SDK AAR `0.1.59`
  (`lib/arm64-v8a/libsc_content_ffi.so` = `883b0cb53d79de1ee5052399a0351726c2c02bc671448644b37f4a29e6391751`).
- Device: OnePlus 5T, `192.168.2.234:33445`, account `adgjl1182`, app in the foreground throughout.
- Method: `adb` taps plus `adb logcat`; every claim below carries the log line or screen it came from.
  Nothing here is inferred from source alone unless it says so.
- Evidence captures: `/tmp/sweep.log`, `/tmp/rebuild-read.log`, `/tmp/rebuild-read2.log`.

## 1. Verified working

| # | Surface | Reading |
| --- | --- | --- |
| 1 | Cold-start session restore | `credential_load present=true non_empty=true stage=restore` -> `credential_roundtrip match=true unchanged=true` -> `credential_persisted persisted=true` -> `phase_signed_in` |
| 2 | Home browse (CM source) | header `steam-cm`, `已加载 30 · 约 2518611 项 · 第 1/1000 页`; `workshop page rows=30 of 30 collection=public`; cards typed 网站/场景/视频 with like/favourite counts and sizes |
| 3 | Profile (我的) | `adgjl1182`, `已记录的 Wallpaper 运行时长 1486 小时`, `我的订阅 1 / 我的收藏 239 / 我的投票 —` |
| 4 | Downloads list | 下载中 = 暂无下载任务; 已完成 = "Rhine Lab · 莱茵生命交互桌面" 43.8 MB, 已完成 |
| 5 | Local (本地) | the same item as `HTML · 32.1 MB · Download/WallHub`; the ZIP exists on disk (`/storage/emulated/0/Download/WallHub/Rhine Lab · 莱茵生命交互桌面 _ Interactive Desktop-3799142774 (1).zip`) |
| 6 | Data-source switch | `Steam CM WebSocket` -> `Steam Community HTML` -> home header changed `steam-cm` -> `steam-community` and 30 items loaded; switched back and the header returned to `steam-cm` |
| 7 | Discover rails | `rail=static:recent-approved … candidates=20 results=20 fidelity=EXACT`, `rail=keyword:samurai …`, `rail=static:trend-month-approved …`, `generation=1 metadata=NETWORK … specs=86 focusCreators=78 focusCollections=20` |
| 8 | Session maintenance and loss (23-minute run, entry 21) | 58 ticks; three `session_lost`; recovered twice in place (`session_rebuilt`) and once by the restore hand-off (`phase_signing_in` -> `phase_signed_in` in under a second) |
| 9 | No crashes | no `FATAL`/`AndroidRuntime` exception from the app in the whole session (the only `AndroidRuntime` lines are the `monkey` launcher process) |
| 10 | Item detail (CM source) | `理理` (id `3792056955`) resolved: title, preview with a play affordance, 简介, 作者 草莓粒子, 项目 ID, and a 评论 (61) tab |
| 11 | Comments read | the 评论 tab listed comments with authors and "13 小时以前" ages, and the tab label carries the count (61) |
| 12 | Interaction read on that item | `workshop interaction id=3792056955 subscribed=NOT_SUBSCRIBED favorited=NOT_FAVORITED subscribed-field=1 subscribed-value=0 favorited-field=1 favorited-value=0` |
| 13 | In-place recovery, read again | `12:36:29.311 pump=failed step=reconnected` -> healthy ticks from `12:36:49`; and `12:38:29.334 session_lost` -> `12:38:30.075 session_rebuilt` in under a second |

## 2. Defects found

### D1 (critical) - after an in-place auto-reconnect the app wedges and everything stops

The whole app goes quiet and only a restart brings it back. This is the original "silent death" defect in a new
place: the maintenance loop this campaign added stops, and with it every read.

```text
12:24:11.490 I/WallHubSteamSession: session_maintenance sdk=pump=failed step=reconnected
12:24:12.378 W/WallHubSdkWorkshop: workshop read unavailable: ne5 was cancelled
12:24:12.379 W/WallHubSdkWorkshop: workshop read unavailable: ne5 was cancelled
（nothing after this - no tick, no read, for 3+ minutes）
```

At the time of the last line the app was alive (`pid 9148`), in the foreground
(`topResumedActivity=com.wallhub.android/.MainActivityThemedIcon`), and the screen was `mWakefulness=Awake`.
Tapping 发现 afterwards produced **no reads at all**, so it is not one screen failing: the session path is
blocked.

`step=reconnected` is the SDK's own in-place recovery having run; the very next thing the loop does is unwrapped
(`session.state()`), so the two candidates to read first are (a) that call throwing and ending the loop
coroutine silently, and (b) a `pump`/`maintain` JNI call blocking inside the SDK while holding the session, which
would also explain why later reads are cancelled rather than answered.

**Correction from the second sweep segment: the trigger is not the reconnect itself.** The same step happened
again at `12:36:29.311` (`session_maintenance sdk=pump=failed step=reconnected`) and the loop **kept ticking**
normally afterwards - `12:36:49.319`, `12:37:09.321`, `12:37:29.324`, `12:37:49.328`, all
`pump=read step=heartbeat_sent`. So D1 is intermittent, not deterministic, and the in-place reconnect is not
sufficient to cause it. The wedged episode differed in one visible way: its reads were failing as
`ne5 was cancelled` (a cancellation) where this one's failed as `temporarily_unavailable`. That is the thread to
pull next, and D1 stays critical because when it does happen the app is unusable until a restart.


### D2 - an R8-obfuscated class name is reported as the reason for a read failure

```text
W/WallHubSdkWorkshop: workshop read unavailable: ne5 was cancelled
```

`ne5` is a minified internal class name. It is useless to anyone reading the log and it leaks implementation
detail into a diagnostic that is otherwise deliberately phrased in the app's own words. The read that produced
it is a cancellation, which the adapter should name as such.

### D3 - the item detail screen stays on a spinner and never shows an error

壁纸详情 was opened, its detail read failed, and the screen spun indefinitely - over three minutes across two
separate entries into it - with no error, no retry affordance, and no content. Whatever the read path does with
a cancelled or unavailable detail, the UI has no state for it.

Reproduced with a second, unrelated cause, which is what makes this a UI defect rather than a symptom of one
read failure:

```text
12:36:28.361 W/WallHubSdkWorkshop: workshop read unavailable: temporarily_unavailable: the connection failed before the service answered
12:36:28.361 W/WallHubSdkWorkshop: workshop detail unavailable id=3790379688
（the screen spun on from there; no retry, no error）
```

The earlier episode failed as `ne5 was cancelled`, this one as `temporarily_unavailable`; both leave the same
permanent spinner. No reads were attempted again while that screen was open, so the screen neither retries nor
offers the user a way to.


### D4 - the Steam settings card states something that is no longer true

The signed-in card reads "Signed in to Steam. This session lasts until WallHub is restarted." That was true
before this campaign; the session now survives an idle period and rebuilds or restores itself after a loss, so
the sentence contradicts both the behaviour and the rest of this file.

### D5 - the data-source dropdown truncates every option

The three sources render as `Steam …`, `Steam Web`, `Steam …` in the popup
(`settings_steam_cm_websocket` = "Steam CM WebSocket", `settings_steam_web_api` = "Steam Web API",
`settings_steam_community_html` = "Steam Community HTML"). With two of three indistinguishable, the control
cannot be used with confidence.

### D6 - a read fires before the session is up, and on the home screen that costs the whole page

```text
11:45:43.705 W/WallHubSdkWorkshop: workshop read unavailable: invalid_input: the session was not authenticated when the request was sent
11:45:46.940 I/WallHubSteamSession: phase_signed_in
```

A screen asks for its content before the session is up, gets a refusal three seconds before sign-in completes,
and the user sees a load failure that a second later would not have happened.

**On a cold start it is worse than one refused read.** A later launch had the home browse attempt while the
session was still connecting, and the page landed on a full error state that never clears itself:

```text
12:38:28.267 W/WallHubSdkWorkshop: workshop read unavailable: temporarily_unavailable: the connection failed before the service answered
12:38:28.308 W/WallHubHome: home load failed stage=browse error=Failed to establish a Steam CM WebSocket session
12:38:29.334 I/WallHubSteamSession: session_lost
12:38:30.075 I/WallHubSteamSession: session_rebuilt sdk=replayable=0 abandoned=0
12:38:50.078 I/WallHubSteamSession: session_maintenance sdk=pump=read step=heartbeat_sent
```

Home showed 无法加载 Steam 创意工坊，请稍后重试 with a 重试 button, `已加载 0 · 约 0 项`. The session then
rebuilt itself inside a second and was healthy - and the page still did not reload: no further browse was issued
while it stayed open. So the app is now good at repairing the connection and bad at telling the screens that it
did, which is the same shape as D3 one level up. Tapping 重试 does work (`workshop page rows=30 of 30` straight
after), so the affordance is fine - the missing piece is the automatic one.


## 3. Observations that are not yet defects

- **订阅数 / 收藏数 showed `--`** on the `理理` detail (id `3792056955`) while an earlier campaign's detail read
  showed real counts (99.8万 / 12.7万) for another item. Either those fields are genuinely unknown for this item
  or the CM detail read drops them; it needs one more item to compare before it is called either way.
- **The comment-write entry point is reachable**: the 评论 tab has a 发表评论 field and a send button, so the
  path the roadmap lists as unread is present in the UI. It was **not** exercised, because it would post a public
  comment.


## 4. Not yet verified

Nothing below has a reading. "Code present" is a source-level observation only and is not a claim that the
feature works.

| Feature | State | What a reading needs |
| --- | --- | --- |
| 好友动态排序 | sorts are mapped (`SteamContentSdkWorkshopRepository.kt:970-971`, `workshop_session.rs:80-83`); the UI entry was not located on 发现 | find the category/sort control and pick a friend sort |
| 数据源切换 - WEB_API | the third source exists and the switch works for the other two; this branch requires a Steam Web API key (`CommunityWorkshopRepository.kt`) | an operator-supplied key entered on the device, never in chat |
| Guard 三分支 (email/mobile/device) | all five challenge kinds are mapped (`SteamContentSdkSessionPlatform.kt:99-104`) | a real logon that actually raises a challenge |
| 评论写入 | end-to-end path exists (`WorkshopDetailViewModel.kt:356` -> SDK `post_comment`) | it writes a **public** comment, so it needs explicit approval first |
| 暂停/继续/取消 | cooperative app-side pause (`SteamContentSdkEngineSession.kt:256-258`); `sc_task_pause/resume` is not used | start a download and drive the controls mid-flight |
| 重启续传 | the SDK skips already-published hash-verified files (`sc-download/src/lib.rs:694-723`); no app-persisted checkpoint was found | download, `am force-stop`, relaunch, watch whether it resumes (never `pm clear`) |
| MPKG/ZIP end to end | the 本地 item on screen predates this arm | download a fresh item and watch the conversion |
| 直接文件下载与播放 | `SteamRemoteVideoStream` and friends exist | a direct-file item, download and play |
| Range rebuffer | start/seek/release were read in an earlier campaign (0.1.41); a rebuffer was explicitly never forced | a scenario that actually stalls playback |
| Filters (type/tags/rating/resolution/genres/NSFW/created window/days/language/tag groups) | code paths exist and one gap (video type) was fixed this campaign | walk the filter sheet per dimension and read the request the device sends |

## 5. Immediate consequence

The device was left wedged by D1 and needs `adb shell am force-stop com.wallhub.android` plus a relaunch before
the sweep can continue (never `pm clear`, which would destroy the stored credential).
