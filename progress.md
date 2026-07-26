# WallHub Android 对话进展

更新时间：2026-07-26

## 当前目标

本轮目标是完成发现页筛选交互、筛选 Chip、管理页工作区切换、下载选项、发现页与资料库长按菜单以及共享分页控件的优化，并完成真机回归、开发日志记录和最终 Release 构建安装。

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

### 文档和静态检查

- 本轮修改和真机验证已写入 `docs/development-log.md`。
- `git diff --check` 已通过。
- 当前源码已建立 Git 基线，首个提交为 `5e6f45f`；后续工程清理也已拆分为独立提交，可直接使用 `git diff`、`git revert` 和 `git bisect` 定位或回退。
- 已绑定 GitHub 远程仓库 `https://github.com/ChEnLeo-7/WallHub2.0-For-Android.git`，当前环境没有 GitHub 凭据，因此尚未推送；推送后现有 GitHub Actions 将首次运行全量 JVM 测试和 `lintDebug`。
- 构建依赖已集中到 `gradle/libs.versions.toml`，零消费者的 `:core:testing` 已移除，八份重复的 `AppLanguage.text(...)` 已收敛到 `core:designsystem`。
- GitHub Actions 工作流已调整为：完成首次推送并配置稳定签名 Secret 后，`main` 推送和手动触发将运行 `testDebugUnitTest lintDebug`、构建 Release 并上传带 commit SHA 的 APK artifact；PR 仅执行测试和 lint，不读取签名 Secret。当前 GitHub 远程仍为空，尚未实际触发过该工作流。
- 新增 `scripts/push-build-install.sh` 与 `scripts/install-github-release-apk.sh`：前者推送当前干净的 `main` commit，后者只等待并下载同一 SHA 的成功 Action artifact，校验 commit 标记、SHA-256 与设备上已安装 APK 的签名证书后才执行 `adb install -r`，绝不自动卸载或绕过签名冲突。安装前会实时读取 `adb devices -l`，仅在恰有一个 `device` 状态目标时自动选择；地址和端口不写死，零个或多个目标均停止并要求明确选择。使用和 Secret 设置见 `docs/github-actions-adb.md`。
- LAN 回退构建任务 `20260726T161036Z-991119252` 已验证 Gradle 的无 Secret debug 签名 fallback、完整 DEX、证书连续性、原位安装和冷启动；当前设备 APK 与 GitHub Actions Secret 所需证书 SHA-256 均为 `940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D`。

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

### 最终 Release

- 构建任务：`20260726T161036Z-991119252`
- 构建机：`MYCOLORFUL`
- APK：`/root/builds/wallhub-release-20260726T161036Z-991119252.apk`
- 大小：`30,200,591` 字节
- SHA-256：`3214353A05F1332852285D79B02D71C404DF9437D7870435CFE5405EFC8C60F8`
- DEX：`classes.dex` 至 `classes8.dex`
- 包名：`com.wallhub.android`
- 安装版本：`0.8.24 (34)`
- 安装设备：`192.168.2.190:40283`
- 设备型号：Samsung `SM_G9900`
- 安装结果：`adb install -r` 成功
- 最终冷启动 PID：`23835`
- 冷启动结果：进程保持存活，PID 定向日志未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。

## 构建和环境约束

- 不要在当前 LXC 中运行 Gradle。
- 修改 Android 源码后自动使用仓库技能 `android-release-build`。
- 远程 Release 命令：`/usr/local/bin/request-android-release-build --wait`。
- 远程工作流当前只执行 `:app:assembleRelease`，不执行 JVM 或仪器测试。
- 必须使用当前任务状态 JSON 返回的 artifact，不要按修改时间猜测最新 APK。
- 远程工作区曾因失败的 Hilt/ASM 增量状态生成缺少 app class 的异常 APK；每次最终构建必须确认包含完整 DEX，并执行安装和冷启动。
- 当前设备连接端口可能变化；开始工作前先运行 `adb devices -l`，只使用状态为 `device` 的明确目标。
- 保持现有 ADB server，不要无故重启，否则可能丢失无线调试会话。

## 未完成待办

- 当前用户提出的七项 UI 与交互需求已全部完成，没有已知功能阻塞项。
- 远程 Release 流程未运行新增或既有 JVM 测试；仓库已绑定 GitHub 远程并保留 `.github/workflows/verify.yml`，待从有凭据的环境首次推送后运行全量 `testDebugUnitTest lintDebug :app:assembleDebug`。
- GitHub Actions 首次部署前，必须将签发当前 Samsung 测试设备安装包的 keystore 配置为四个 `WALLHUB_RELEASE_*` Secret；设备当前证书 SHA-256 为 `940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D`。未配置时 Action 将显式失败而不会发布无法原位安装的 APK。
- Steam 会话在一次重新安装后的首次冷启动进入过可重试断线态，手动点击“重试恢复”后成功加载资料库。该现象不属于本轮 UI 回归，但如果再次出现，可单独对 Steam 会话恢复和 RPC 做故障注入诊断。
- 设备上的 `uiautomator` 每次 dump 结束可能在 Houdini 转译层产生自身 SIGSEGV；后续检查日志时应按 WallHub PID 或包名过滤。
- 若继续发布新 APK，需要再次更新 `docs/development-log.md`，重新远程构建、检查 DEX 和 SHA-256、安装到明确设备并完成冷启动验证。

## 新对话建议起点

1. 先阅读本文件及 `docs/development-log.md` 的 `0.8.24 (34)` 最新记录。
2. 运行 `adb devices -l` 确认当前设备序列号。
3. 根据新需求检查相关源码，不要重复实现本文件中已完成的功能。
4. 修改 Android 源码后加载 `android-release-build` 技能并执行完整远程构建、安装和冷启动验证流程。
