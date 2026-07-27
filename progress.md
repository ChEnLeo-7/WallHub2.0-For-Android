# WallHub Android 对话进展

更新时间：2026-07-27

## 当前目标

发现页筛选、管理页交互、菜单、分页、MPKG 修复和 GitHub Actions 到 ADB 流程均已完成。MPKG 无损减积研究已通过三个真实场景将最终生产候选收敛到 HC3；源码已接入固定 HC3、协作取消和 `EXPORTING` 提交边界，等待构建、直接生产转换、取消延迟和热量验证，ETC2 保持实验状态。

## 已完成事项

### 发现页筛选

- 将筛选工作区内容切换改为 Foundation `HorizontalPager`。
- 顶部“浏览 / 内容 / 主题 / 屏幕”标签点击使用 `animateScrollToPage(...)`。
- 支持左右手势切换筛选板块，标签选中状态与 Pager 同步。
- 紧凑布局与宽屏布局复用同一个 Pager 状态。
- 删除筛选内容区重复显示的板块大标题，板块名称只保留在顶部导航。
- 紧凑布局的 `PrimaryTabRow` / `Tab` 关闭额外方形波纹反馈。
- 真机已确认从“浏览”横向滑动到“内容”后标签同步选中，并显示“壁纸类型 / 年龄评级”。

### 筛选 Chip

- `WallHubFilterChip(...)` 未选中时不再预留固定勾选空间。
- 选中时勾选淡入并缩放入场，Chip 通过 `animateContentSize` 平滑扩展。
- 取消选中时先淡出和缩小勾选，再收回 Chip 宽度。
- 保留共享 `WallHubAnimatedSelectionCheck(...)`，避免破坏设置页等既有调用。
- 真机测得“视频”Chip 从未选中的 `128 px` 扩展到选中的 `167 px`；相邻未选项不保留固定前导空槽。

### 管理页工作区切换

- 下载、资料库和本地三段滑动选择器直接读取：
  - `pagerState.currentPage`
  - `pagerState.currentPageOffsetFraction`
- 滑块随手势实时移动，不再等待 Pager 停稳后补动画。
- 点击工作区使用无回弹 spring 调用 `animateScrollToPage(...)`。
- 标题说明与三段导航之间增加 `6.dp` 顶部间距。
- 真机确认 1080 px 紧凑宽度下三段导航完整等分，资料库选中状态与 Pager 同步。

### 下载选项

- 删除详情页下载选项中的“仅转换 MPKG 文件”。
- 删除对应的未下载提示。

### 发现页与资料库菜单

- 删除发现页长按菜单中可见的“查看详情”。
- 删除资料库长按菜单中可见的“查看详情”。
- 普通点击卡片仍进入详情页。
- 卡片的“查看详情”无障碍点击语义继续保留，因为它描述普通点击行为，不是可见菜单操作。
- 资料库菜单新增并确认以下操作顺序：
  - 下载
  - 视频项目的“视频播放”
  - 打开 Steam
- 资料库下载已接入正式下载链路，复用：
  - 当前 `outputTreeUri`
  - `DownloadTaskRepository`
  - `ExportFormat.AUTO`
  - Android 旧版公共存储权限处理
- 资料库“视频播放”不再打开详情页，已新增 `workshopId` 回调并贯通到应用导航的 `OnlineVideoPlayerRoute`。
- 真机点击资料库视频菜单的“视频播放”后直接进入全屏在线播放器，界面出现播放器控件和返回按钮，没有经过详情页。

### 分页控件

- 共享分页控件固定显示四个角色：
  - 首页：显示 `1`
  - 当前：始终显示当前页
  - 末页：显示当前已知最大页
  - 跳页：打开自定义页码输入框
- 首页和末页一击直达。
- 当前和跳页均可打开页码输入对话框。
- 当前页与首页或末页重合时不再去重隐藏任何角色。
- 保留方向切换动效和按压弹簧反馈。
- 真机在发现页 Web 页码模式下确认四个触控区完整且互不重叠；当前为第 `1` 页时“首页”和“当前”仍同时显示。
- 验证后已恢复用户原来的“瀑布流拼接”偏好。

### MPKG 相关既有成果

- Workshop `3742497499`（“麻匪 白泽夢”）修复后的 MPKG SHA-256：`DDBA460F1FCCC10C7B0F0344772C2F712E4BEAB2B669FE159A48100B3F9DE8F6`。
- 已在官方 Wallpaper Engine `2.8.8 (4354)` 中完整渲染，黑块和黄色缺失纹理消失。
- MPKG/ZIP 已使用原子写入。
- payload 长度固定，TEX 内存和解压尺寸有界。
- 透明 Popup 的无障碍和触摸生命周期问题已修复。

### MPKG 纹理减积研究

- 完整研究与 ADB 隔离原型记录在 `docs/research/mpkg-texture-size-reduction.md`。
- 研究原型脚本和测试包只存在于 `/tmp/opencode`，设备临时测试目录已清理；研究完成后已修改 Android 生产转换源码，但尚未构建或部署。
- 官方客户端 `2.8.8 (4354)` 已真机接受用户生成的 format-5 `TEXB0004` 和 `etcpak 0.9.15` ETC2 RGBA block。
- 奇数尺寸 `1037x1037 -> 1040x1040`、`1920x1281 -> 1920x1284` 使用边缘复制后正常渲染，无条纹、拉伸或透明黑边。
- 真实 Workshop `3746422401` 的 MPKG：
  - 源大小 `206,602,536` 字节。
  - SHA-256：`2CDF782D172E31999C0005F63C620DBEC830ACF55D29C9FE68D8219ECA8EC227`。
  - 174 个条目，53 个 format-0 `TEXB0004` TEX。
  - 权威 RGBA 合计 `191,626,564` 字节。
- Java `lz4-java 1.8.0` 基准：
  - Fast payload `19,204,806` 字节。
  - HC6 payload `12,863,776` 字节，减少 `33.02%`。
  - HC7 payload `12,773,623` 字节，减少 `33.49%`。
  - HC9 payload `12,706,916` 字节，减少 `33.83%`，但主机耗时明显高于 HC6/7。
  - HC12 仅比 HC9 多省约 32 KB，主机耗时约 77 秒，不值得继续考虑。
- 完整无损 HC7 MPKG 为 `200,172,304` 字节，比源包减少 `6,430,232` 字节，约 `3.11%`；53 个 TEX 解压后逐字节一致，并在官方客户端完整渲染。
- 保守 ETC2 混合包只转换“烟雾”和“顶部水珠”两张明确颜色纹理，其余 51 张保持无损 HC7：
  - 大小 `200,001,989` 字节。
  - 比无损 HC7 只额外减少 `170,315` 字节，约 `0.085%`。
  - 官方客户端完整渲染，但额外收益不足以支持默认启用 ETC2。
- 当前决策：HC3 是最终无损生产候选；HC6/HC7 的边际收益不足以补偿 CPU。生产源码已切换 HC3，等待 CI、直接三场景和真机验证；ETC2 保持实验状态，等待语义分类、多场景和多 GPU 验证。
- Android ART 临时 DEX 冷进程基准已完成，没有修改或重装 WallHub：
  - 真实 `3746422401` 的 53 TEX、`191,626,564` 字节 RGBA 全部逐字节往返成功。
  - Fast/HC6/HC7 各 3 次交错冷进程，中位总耗时为 `720.368 ms` / `2,543.772 ms` / `3,500.066 ms`。
  - 中位压缩阶段为 `190.962 ms` / `2,038.121 ms` / `3,002.190 ms`。
  - HC6/HC7 payload 为 `12,863,776` / `12,773,623` 字节；HC7 只再少 `90,153` 字节，却比 HC6 多约 `47%` 压缩时间。
  - HC6 Java heap 峰值只比 Fast 多约 `244 KiB`；PSS 峰值中位数多 `7,048 KiB`，约 `4.18%`，没有内存阻断项。
  - 所有重复运行的 payload SHA-256 均稳定一致；临时 `app_process` 输出完整结果后发生的 exit `139` 来自设备 Houdini 退出过程，不是 WallHub 或压缩失败。
- ART Pareto 补测结论：
  - HC3 中位基准总耗时约 Fast `1.83x`，按 payload 差额粗估完整包减少约 `2.57%`。
  - HC5 约 `2.74x`，粗估完整包减少约 `2.98%`。
  - HC6 约 `3.53x`，粗估完整包减少约 `3.07%`。
  - 当前基准不是源 `scene.pkg` 的完整生产转换，不能直接应用完整转换 `2x` 门槛；HC3 源码已接入但仍需直接生产验证。
- HC7 已淘汰为默认候选；后续三场景完整比较也淘汰 HC6，并最终选择 HC3。
- 取消响应审计后的源码接入已覆盖 archive entry、目录遍历和每 `1 MiB` MPKG/ZIP payload 复制；已有输出继续受原子临时文件保护。单个受限 TEX 解码/HC3 压缩仍不可中断。转换后新增 `EXPORTING` 状态并隐藏取消操作，使外部文件提交与任务完成不可被中途取消。
- 热量采样期间 battery temperature 保持 `36.0 C`、thermal status 为 `0`，但设备报告 thermal HAL 未就绪，结果不足以通过生产热量门槛。
- 已补测官方 `dino_run.mpkg` 中 23 个严格普通 RGBA TEX；HC6/HC7 继续逐字节往返并显示 HC7 边际收益有限，但该样本很小且不是源 `scene.pkg`，不能替代额外真实 Workshop 语料。
- 已通过 WallHub 正式 ZIP 导出路径重新取得三个真实 `scene.pkg`：`3742497499`、`3746422401` 和 `3768443264`，合计 151 个 TEX、`587,959,836` 字节权威 RGBA。
- 三场景完整 MPKG 聚合：Fast `448,291,956` 字节，HC3 `428,552,671` 字节，减少 `19,739,285` 字节，约 `4.40%`；HC6 为 `425,450,459` 字节，只比 HC3 再少 `3,102,212` 字节。
- 三场景校正完整时间：HC3 约为 Fast `1.60x`，HC6 约为 `2.87x`。该时间由真实 Fast 全链路减去 Fast 压缩阶段并加回同设备候选压缩阶段得到，尚不是直接 HC3 生产转换实测。
- 独立 comparator 已验证三个 HC3/HC6 MPKG 的索引和顺序一致、非 RGBA 条目逐字节不变、TEX header 除 stored length 外不变，151 个 TEX 的解压 RGBA 全部逐字节一致。
- 三个 HC3 MPKG 均从官方客户端强制停止状态冷启动进入 `PreviewActivity`：`3742497499` 为 `4,266 ms`，`3746422401` 为 `3,697 ms`，`3768443264` 为 `1,574 ms`。人物、服装、头发、水流、透明层、飞机、尾焰、雨、反射、粒子和文字完整，无 LZ4/包损坏错误、崩溃、ANR 或 OOM；没有点击应用壁纸。
- HC3 输出 SHA-256：`3742497499` 为 `C6D648C302A4E4902F786DAA5C2C031327E2CE505EF4C46231FBB08A8C684970`，`3746422401` 为 `E7E0E38823981A35E89345CB65A75F6670C7D72F620F03183F9BE35CE150E421`，`3768443264` 为 `95B07DC966C7F23A94FEA9383D0FB2A6E5C6296902EA1923C88EE55408F078F4`。
- 另有两个含动画/视频或 tail profile 的特殊场景在临时 `app_process` 转换中被系统 `Killed`，没有错误 MPKG 残留；原因未确定，不计入 HC3 收益与兼容性统计。

### 文档和静态检查

- 本轮修改和真机验证已写入 `docs/development-log.md`。
- `git diff --check` 已通过。
- 当前源码已建立 Git 基线，首个提交为 `5e6f45f`；后续工程清理也已拆分为独立提交，可直接使用 `git diff`、`git revert` 和 `git bisect` 定位或回退。
- 已绑定并推送 GitHub 远程仓库 `https://github.com/ChEnLeo-7/WallHub2.0-For-Android.git`；GitHub API Token 由 LXC 外部配置文件按需加载，不写入仓库或日志。
- 构建依赖已集中到 `gradle/libs.versions.toml`，零消费者的 `:core:testing` 已移除，八份重复的 `AppLanguage.text(...)` 已收敛到 `core:designsystem`。
- GitHub Actions 工作流已投入使用：`main` 推送和手动触发运行 `testDebugUnitTest lintDebug`、构建签名 Release 并上传带 commit SHA 的 APK artifact；PR 仅执行测试和 lint，不读取签名 Secret。
- 新增 `scripts/push-build-install.sh` 与 `scripts/install-github-release-apk.sh`：前者推送当前干净的 `main` commit，后者只等待并下载同一 SHA 的成功 Action artifact，校验 commit 标记、SHA-256 与设备上已安装 APK 的签名证书后才执行 `adb install -r`，绝不自动卸载或绕过签名冲突。安装前会实时读取 `adb devices -l`，仅在恰有一个 `device` 状态目标时自动选择；地址和端口不写死，零个或多个目标均停止并要求明确选择。使用和 Secret 设置见 `docs/github-actions-adb.md`。
- LAN 回退构建任务 `20260726T161036Z-991119252` 已验证 Gradle 的无 Secret debug 签名 fallback、完整 DEX、证书连续性、原位安装和冷启动；当前设备 APK 与 GitHub Actions Secret 所需证书 SHA-256 均为 `940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D`。
- 首次 GitHub Actions 端到端验收已在 commit `97817431aabca35fa456d1e168c38224f94bfc1f` 完成；日常入口又在 commit `9b1b0b6c8c32c32a091460e08fe7b663da46ac39`、run `30214074910` 完成同 SHA artifact 下载、动态 ADB 选择、原位安装和冷启动验收，PID `27171` 存活且日志无致命异常、ANR 或 OOM。
- 新增 `.opencode/skills/github-actions-adb-deploy/SKILL.md` 并在 `AGENTS.md` 设为 Android 源码修改后的默认验证流程；`android-release-build` 仅保留为 GitHub 不可用或用户明确要求时的 LAN 回退。
- `README.md` 已重构为 GitHub 默认中文入口，新增 `README_EN.md`；两份 README 均使用由 `app/src/main/res/drawable/ic_wallhub.xml` 同源转换的 `docs/assets/wallhub-logo.svg`。

## 关键决策

- 发现筛选继续使用原生 Compose Material 3 组件和 Foundation Pager，不引入自定义 Web UI 或第三方分页框架。
- 筛选板块名称只在顶部导航出现一次，避免内容区重复标题占用空间。
- Chip 未选中时不保留固定图标槽，优先满足用户要求的紧凑布局；选中产生的宽度变化使用受控内容尺寸动效。
- 管理页滑块位置直接由 Pager 连续偏移驱动，避免手势结束后滑块二次追赶。
- 菜单中的“视频播放”应是一击直达播放器，而不是语义含混地进入详情页。
- 删除的是可见菜单“查看详情”操作；普通点击卡片和对应无障碍语义保留。
- 资料库下载必须复用现有正式下载和权限链路，不创建第二套下载实现。
- 分页角色固定显示，角色语义优先于页码去重；即使首页与当前页同为 `1`，也保留两个独立角色。
- 远程构建成功本身不足以验收 APK，必须继续检查 DEX、安装、包版本和冷启动。
- 设备日志中的 `uiautomator` SIGSEGV 来自 Samsung x86/Houdini 转译层退出过程，进程和 UID 均不是 WallHub；不能误报为应用崩溃。

## 主要修改文件

- `feature/home/src/main/kotlin/com/wallhub/android/feature/home/HomeScreen.kt`
  - 发现筛选 `HorizontalPager`
  - 无额外波纹的顶部 Tabs
  - 重复标题移除
  - 发现页菜单调整
- `core/designsystem/src/main/kotlin/com/wallhub/android/core/designsystem/Components.kt`
  - `WallHubFilterChip(...)`
  - 条件式勾选与内容尺寸动效
  - 管理页滑动选择器位置
- `core/designsystem/src/main/kotlin/com/wallhub/android/core/designsystem/WallHubPagination.kt`
  - 首页 / 当前 / 末页 / 跳页分页控件
  - 页码输入对话框
- `app/src/main/java/com/wallhub/android/ManagementScreen.kt`
  - 管理 Pager 与实时滑块
  - 资料库下载权限回调
  - 在线视频导航回调传递
- `app/src/main/java/com/wallhub/android/FormalWallHubApp.kt`
  - 资料库在线视频回调接入 `ONLINE_VIDEO_PLAYER_ROUTE`
- `feature/library/src/main/kotlin/com/wallhub/android/feature/library/LibraryScreen.kt`
  - 下载和在线播放回调逐层传递
- `feature/library/src/main/kotlin/com/wallhub/android/feature/library/LibraryContextMenu.kt`
  - 下载、视频播放和 Steam 操作
  - 可见“查看详情”移除
- `feature/downloads/src/main/kotlin/com/wallhub/android/feature/downloads/DownloadsScreen.kt`
  - `DownloadsViewModel.enqueueWorkshop(...)`
- `feature/detail/src/main/kotlin/com/wallhub/android/feature/detail/WorkshopDetailScreen.kt`
  - “仅转换 MPKG 文件”移除
- `docs/development-log.md`
  - 本轮变更、快照、构建和真机验证记录

## 快照与最终产物

### 修改前快照

- 文件：`local-snapshots/wallhub-android-source-before-filter-swipe-chips-menus-pagination-20260726.tar.gz`
- SHA-256：`4361B68FA13AAD801C0734F8DB976E9F3F5318496DCF7F39437D8EA8159EAD00`

### 最终 GitHub Actions Release

- Commit：`9b1b0b6c8c32c32a091460e08fe7b663da46ac39`
- Action run：`30214074910`
- Artifact：`wallhub-release-9b1b0b6c8c32c32a091460e08fe7b663da46ac39`（ID `8635348246`）
- 大小：`30,184,207` 字节
- SHA-256：`4F380EE17E6F2D0513BDFE0C5445E5EB29610DA2867E3E07E8BAD29427D81E4F`
- DEX：`classes.dex` 至 `classes8.dex`
- 签名证书 SHA-256：`940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D`
- 包名：`com.wallhub.android`
- 安装版本：`0.8.24 (34)`
- 安装设备：脚本执行时唯一处于 `device` 状态的 Samsung `SM_G9900`
- 安装结果：`adb install -r` 成功
- 最终冷启动 PID：`27171`
- 冷启动结果：进程保持存活，PID 定向日志未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。

## 构建和环境约束

- 不要在当前 LXC 中运行 Gradle。
- 修改 Android 源码后自动使用仓库技能 `github-actions-adb-deploy`，唯一日常入口为 `scripts/push-build-install.sh`。
- 默认工作流必须从干净的本地 `main` commit 出发，只接受同一 SHA 的成功 Action run 和 `wallhub-release-<commit-sha>` artifact。
- GitHub Actions 必须通过 `testDebugUnitTest lintDebug`、稳定签名 Release 组装和 artifact 上传；LXC 脚本继续验证 commit 标记、SHA-256、ZIP、DEX、签名、安装版本与冷启动日志。
- `android-release-build` 与 `/usr/local/bin/request-android-release-build --wait` 仅在 GitHub Actions 或 artifact 访问不可用、或用户明确要求时作为 LAN 回退；LAN 成功不能替代默认 CI 验收。
- 当前设备连接端口可能变化；开始工作前先运行 `adb devices -l`，只使用状态为 `device` 的明确目标。
- 保持现有 ADB server，不要无故重启，否则可能丢失无线调试会话。

## 未完成待办

- 当前用户提出的七项 UI 与交互需求已全部完成，没有已知功能阻塞项。
- MPKG ART 压缩阶段、Java heap、PSS、RSS、GC、三场景完整包体、独立逐字节比较和官方客户端画面验证已完成；HC3 源码接入和取消边界也已完成，但尚未构建部署。
- 需要先通过 GitHub Actions 的全量 JVM 测试、`lintDebug` 和 Release 组装，再安装同一 commit artifact。
- 需要直接复跑三个源 `scene.pkg`，以真实生产时间替代当前校正估算，并测 WorkManager 真实取消延迟。
- 需要在 thermal HAL 可用的设备上测真实完整场景连续转换；当前 Samsung 设备只能提供 coarse battery temperature 和 thermal status。
- ETC2 自动生产化仍缺少可靠的材质、scene、shader uniform 和采样语义分类，以及 Mali/Xclipse 等独立 GPU 家族验证。
- GitHub Actions 签名 Secret、全量 JVM 测试、`lintDebug`、Release artifact、动态 ADB 安装和冷启动流程此前已完成基线验收；当前 HC3 变更仍需以新 commit 重新跑完整流程。
- 新增项目 Skill 属于 OpenCode 启动时配置；提交后需退出并重启 OpenCode，新会话才会自动发现 `github-actions-adb-deploy`。
- Steam 会话在一次重新安装后的首次冷启动进入过可重试断线态，手动点击“重试恢复”后成功加载资料库。该现象不属于本轮 UI 回归，但如果再次出现，可单独对 Steam 会话恢复和 RPC 做故障注入诊断。
- 设备上的 `uiautomator` 每次 dump 结束可能在 Houdini 转译层产生自身 SIGSEGV；后续检查日志时应按 WallHub PID 或包名过滤。
- 若继续发布新 APK，需要再次更新 `docs/development-log.md`，提交干净的 `main` 后运行 `scripts/push-build-install.sh`，并要求同 SHA artifact、动态 ADB 安装和冷启动验证全部成功。

## 新对话建议起点

1. 先阅读本文件及 `docs/development-log.md` 的 `0.8.24 (34)` 最新记录。
2. 运行 `adb devices -l` 确认当前设备序列号。
3. 根据新需求检查相关源码，不要重复实现本文件中已完成的功能。
4. 修改 Android 源码后加载 `github-actions-adb-deploy` 技能并运行 `scripts/push-build-install.sh`；仅在 GitHub 不可用或用户明确要求时使用 `android-release-build`。
