# WallHub UI 与维护性改造计划

## 目标

本计划以当前 Kotlin 多模块 Android 工程为基础，继续使用 Jetpack Compose 和 Material 3，不引入跨平台 UI 框架或第三方全量状态管理框架。目标是统一视觉规范、降低页面之间的重复实现、提升宽屏适配质量，并把 UI 回归和工程质量检查纳入持续集成。

当前基线：`0.8.25 (35)`，`main` 分支，最低 Android 版本 API 26。

## 改造原则

- `core:designsystem` 是颜色、Typography、形状、间距、尺寸和共享组件的唯一入口。
- 页面层使用 Material 3 语义色和共享 Token，不直接定义业务颜色、间距或圆角。
- 保留 Hilt、Room、DataStore、WorkManager、Media3、OkHttp、JavaSteam 和 Protobuf。
- 采用 ViewModel + StateFlow + 单向数据流，不为了形式引入完整第三方 MVI 框架。
- 每个阶段都必须有可执行的测试门槛；未通过时不得进入下一阶段。
- 本计划的验证以 JVM、Lint、构建和 GitHub Actions 为主，不包含 ADB 连接、设备安装或真机启动测试。

## 阶段任务

### 阶段 0：基线与约束

- [x] 确认当前模块、依赖版本和发布流程。
- [x] 确认当前 `core:designsystem` 的主题和共享组件边界。
- [x] 建立本文件，记录改造范围、测试门槛和非目标。
- [x] 为 Design System、导航和状态模型补充维护规则文档。

测试检查：

- [x] `git diff --check`
- [x] 工作区只包含本阶段预期文件。

### 阶段 1：Design Token 收敛

- [x] 新增公共间距、形状、触控尺寸和组件尺寸 Token。
- [x] MaterialTheme 使用共享形状 Token。
- [x] Toast 和长按菜单等共享组件优先使用共享 Token。
- [x] 清理业务 Feature 中剩余的颜色和布局魔法数字。
- [x] 为 Token 建立浅色、深色和动态色使用边界。

测试检查：

- [x] `:core:designsystem:testDebugUnitTest`
- [x] 主题表面层级、形状和最小触控尺寸回归测试通过。

### 阶段 2：页面结构与状态流统一

- [x] 为每个 Feature 统一 `Route / Screen / ViewModel / UiState / Action / Effect` 结构。
- [x] 页面 Composable 不直接访问 Repository 或执行导航解析。
- [x] 将一次性导航、Snackbar、文件选择和系统 Intent 统一建模为 Effect。
- [x] 对下载、资料库、本地和设置页面统一状态恢复规则。

测试检查：

- [x] 各 Feature 的 JVM 单元测试通过。
- [x] ViewModel 对加载、刷新、失败、重试和返回状态有回归覆盖。
- [x] 不出现跨模块暴露 DAO、网络响应对象或 Android Context 的接口。

### 阶段 3：Adaptive 布局与导航规范

- [x] 引入 Material 3 Adaptive 和 Window Size Class。
- [x] 统一 Compact、Medium、Expanded 的顶层导航策略。
- [x] 详情页和本地管理页采用标准 List-Detail 布局。
- [x] 导航参数迁移为类型安全路由，集中维护路由定义。
- [x] 统一系统返回、预测性返回和跨工作区手势边界。

测试检查：

- [x] Compact、Medium、Expanded 的 Compose UI 测试通过。
- [x] 导航参数、返回栈、进程重建和状态恢复测试通过。
- [x] 主要页面在窄屏、平板和宽屏下无内容重叠或无限拉伸。

### 阶段 4：UI 回归与性能基线

- [x] 为主题、首页、详情、管理、设置建立 Compose Screenshot Test。
- [x] 覆盖浅色、深色、动态配色、中文和英文状态。
- [x] 为首页瀑布流、资料库列表和本地列表增加 UI 交互测试。
- [x] 为启动、首屏和大列表滚动建立 Macrobenchmark 或 Baseline Profile。
- [x] 为下载和 MPKG 转换增加 WorkManager 端到端取消与恢复测试。

测试检查：

- [x] 截图基线无未经评审的视觉漂移。
- [x] 关键交互测试通过。
- [x] 性能指标和 APK 体积预算未回归。

### 阶段 5：工程质量与发布自动化

- [x] 使用 Gradle Convention Plugin 收敛模块构建脚本。
- [x] 增加 Detekt 和 Ktlint，并在 CI 中强制执行。
- [x] 增加依赖升级检查和公共 API 兼容性检查。
- [x] 保持 GitHub Actions 的测试、Lint、签名构建、APK 体积和 SHA-256 校验。
- [x] 发布流程继续绑定源码 commit，不执行 ADB 安装作为本计划的验收条件。

测试检查：

- [x] `testDebugUnitTest`
- [x] `lintDebug`
- [x] `detekt`
- [x] `ktlintCheck`
- [x] `:app:assembleDebug`
- [x] GitHub Actions 成功上传 commit 绑定的 APK artifact。

## 当前阶段 Todo

- [x] 建立改造文档和阶段门槛。
- [x] 建立共享间距、形状和尺寸 Token。
- [x] 将主题形状迁移到共享 Token。
- [x] 将共享 Toast 和长按菜单的代表性尺寸迁移到 Token。
- [x] 增加 Token 和主题回归测试。
- [x] 将应用壳宽度判断提取为共享 Compact/Medium/Expanded 分类。
- [x] 完成 Feature 层通用尺寸与颜色审计。
- [x] 统一页面状态模型。
- [x] 统一 Adaptive 导航和类型安全路由。
- [x] 增加 Compose Screenshot Test。
- [x] 增加性能基线。
- [x] 增加真实 Steam 内容下载和 MPKG 转换的 WorkManager 端到端测试。
- [x] 增加静态质量工具并纳入 CI。

## 验证记录与已知限制

验证日期：2026-07-31。

- 当前计划进度为 60/60（100%）；阶段主清单为 47/47（100%），其余为便于跟踪的当前 Todo。只统计已经实现并取得本地证据的项目，远端 CI 不以“已配置”代替“已验证”。
- 本地质量门禁已通过：`testDebugUnitTest`、`verifyPaparazziDebug`、`lintDebug`、`detekt`、`ktlintCheck`、`apiCheck`、`dependencyUpdates`、`:app:assembleDebug` 和 `:benchmark:assembleBenchmark`。当前源码于 2026-07-31 最终联合执行结果为 `BUILD SUCCESSFUL`，共 1102 个任务，其中 50 个执行、1052 个为最新状态。
- 类型安全导航契约测试 3/3 通过；源码中已无字符串 `composable("...")`、`navArgument` 或手工拼接导航路径。
- 页面边界审计已通过：六个主 Feature 的 Route 只获取 ViewModel 并消费 `UiState`/`Effect`，Screen 不直接访问 Repository；Feature 源码无 `NavController`、`navigate`、字符串路由或 `navArgument`，导航解析仅由 App 壳层的类型安全目的地承担。
- 一次性操作审计已通过：Toast、权限与文件选择 Activity Result、剪贴板、外部 URI、安装器和本地资源 Intent 都只由各 Feature 的 `effects.collect` Handler 分发；导航回调亦仅由 App 壳层执行。
- Paparazzi 已生成并校验 15 张基线图：Design System 3 张、Home 的 Compact/Medium/Expanded 3 张、Detail 的 Compact/Expanded 3 张、Downloads 的 Compact/Medium 2 张、管理页 1 张、Settings 的 Compact/Medium/Expanded 3 张。基线覆盖浅色、深色、动态配色、中文和英文；新增 Detail Compact、Downloads Compact/Medium、Settings Medium/Expanded 已人工检查，未发现空白图、裁切、重叠或明显无限拉伸。Home、Detail、Downloads、Settings 因此均有对应的主尺寸覆盖。
- 当前 `SM-G9900`（Android 15 / API 35）已执行 `:app:connectedDebugAndroidTest`，导航 instrumentation 测试 3/3 通过。它使用本轮生成的 Debug APK，覆盖发现、管理、设置、Steam 设置页及返回栈、Activity 重建后的管理页目的地恢复，以及 `am kill` 后重新启动仍恢复管理页目的地。
- 当前 `SM-G9900` 已执行 `:benchmark:connectedBenchmarkAndroidTest`，冷启动和首页滚动 Macrobenchmark 2/2 通过。冷启动 `timeToInitialDisplayMs` 中位数为 598.5 ms（范围 536.2-651.8 ms）；首页滚动 CPU 帧耗时 P50/P90/P95/P99 为 4.7/8.3/8.7/11.9 ms。结果和 Perfetto trace 位于 `benchmark/build/outputs/connected_android_test_additional_output/`。
- 本地 `:app:assembleRelease` 已成功，采用默认 debug 签名的 Release APK 为 31,242,308 bytes（29.79 MiB），低于 CI 配置的 40 MiB 预算；远端正式签名包仍须在 GitHub Actions 复核。
- `WorkManagerRecoveryTest` 已使用正式下载与 MPKG 转换调度器验证唯一工作取消后可替换并恢复，覆盖 task ID、网络约束和 Worker 标签；另有正式 MPKG Worker 的取消测试，验证取消状态持久化与暂存目录清理。下载内容获取和系统导出仍由测试 Worker 挂起，不访问网络或公共下载目录，因此仍不是完整下载与 MPKG 转换链路的端到端测试。
- 新增 `RealSteamWorkshopEndToEndTest`（显式传入 `wallhub.realSteamE2e=true` 才执行）：它通过正式应用 Hilt 图获取生产 `WorkshopRepository`、`DownloadTaskRepository`、`SteamAccessRepository` 与 WorkManager，先预热 Steam 路由，再从公开 Workshop 动态选择不超过 32 MiB 的视频、下载、转换并回读 MediaStore MPKG magic；测试会删除测试任务、暂存文件和导出物，普通 CI 不访问第三方内容。入口 `WallHubApplicationEntryPoint` 位于 App 主源码，确保设备测试使用已编译的生产单例图。
- 在 `SM-G9900`（Android 15 / API 35）首次实际执行上述显式测试时，路由预热和公开 Workshop 浏览已成功，但匿名会话请求 Wallpaper Engine depot key 返回 `AccessDenied`。用户在设备上以拥有 Wallpaper Engine 的 Steam 账户完成交互式登录后，2026-07-31 重跑测试成功：正式下载 Worker、正式 MPKG 转换 Worker、MediaStore 写入和 MPKG magic 回读均通过；测试随后删除任务、暂存目录和导出物，未残留活动下载 Job。
- `data:downloads:connectedDebugAndroidTest` 已在 `SM-G9900`（Android 15 / API 35）新增通过 1 项真实转换端到端测试：正式 `WorkManagerConversionWorkScheduler` 调度正式 `FormalWorkshopConversionWorker`，将临时视频 Workshop 转为 MPKG，经 Android 15 MediaStore 导出后重新读取并校验 MPKG magic，最后删除测试导出物。该测试也覆盖前台 Worker 执行和视频暂存目录保留；它不伪造真实 Steam 内容下载。
- `data:downloads:connectedDebugAndroidTest` 已在同一设备通过新增下载与转换取消恢复测试，共 2 项设备测试均成功。新增用例使用正式 `WorkManagerDownloadWorkScheduler`、`FormalWorkshopDownloadWorker`、`WorkManagerConversionWorkScheduler` 与 `FormalWorkshopConversionWorker`：先验证已取消任务不会发起网络访问且会清理暂存目录，再以相同 task ID 重新排队，完成视频 Workshop 的 MPKG 转换、前台 Worker 执行、MediaStore 导出和 MPKG magic 回读。测试仅将不可重复的 Steam 会话替换为受控内容网关，任务状态、调度、取消、转换和系统导出均为正式实现，测试产物会删除。
- Feature 尺寸与颜色审计已完成：原始 `Dp` 从 125 处降至 64 处。`WallHubSpacing.none`、通用小图标、紧凑操作高度、紧凑图标按钮、列表最小高度、底部导航安全留白、卡片标题高度、Modal 最大宽度和可读内容最大宽度均收敛到 `WallHubSpacing` / `WallHubSizeTokens`。其余 64 处均为具名或上下文唯一的业务约束，包括媒体预览尺寸、最小网格列宽、窗口断点、折叠/动画距离、动态字号布局和编辑器控件尺寸，不作为共享 Design System Token；Feature 不再保留静态主题硬编码颜色，仅保留 `Color.Transparent`、Media3 透明背景以及设置页用户输入的 HSV/十六进制颜色解析。
- 主机直接请求 Steam Workshop browse 页面仍可能在 TLS 握手阶段失败（`curl: (35) schannel: failed to receive handshake`），但不影响真机通过 Steam 访问路由完成上述已授权的真实下载、转换和导出验收；受控 Worker E2E 继续作为可重复的补充回归覆盖。
- Downloads 已完成 `Route / Screen / ViewModel / UiState / Action / Effect` 闭环：筛选、排序、任务操作、播放和下载请求统一为 Action，权限、播放导航和一次性提示统一为 Effect；新增 3 个 ViewModel 回归测试并通过。
- Home 的受控仓库回归测试覆盖首次加载失败时清除 loading 标记和手动刷新后的成功恢复；Detail 的对应测试覆盖首次详情加载失败、错误消息写入与 `reload()` 后恢复详情。Library、Downloads、Local 和 Settings 的现有测试分别覆盖会话刷新、任务/筛选状态恢复、本地筛选选择恢复和一次性 Effect。由此加载、刷新、失败、重试和返回状态均有 JVM 或设备侧回归证据。
- 六个主 Feature（Home、Detail、Downloads、Library、Local、Settings）现均使用 `Route / Screen / ViewModel / UiState / Action / Effect` 结构；Local 的筛选、选择和标签操作已通过 `LocalWallpaperAction` 分发，不再是 Screen 对 ViewModel 的细粒度直接调用。
- Downloads、Library 与 Local 的筛选和选择状态通过 `SavedStateHandle` 保存，并分别具有重建回归测试；Settings 的可恢复用户偏好来自持久化的 `SettingsRepository.preferences`。短暂 Effect 和正在执行的工作不会被恢复为 UI 状态。
- Home 已将下载权限、一次性提示、详情与作者导航、复制和 Steam 外链收口为 `HomeAction / HomeEffect`；`actionMessage` 已从 `HomeUiState` 删除，Screen 和卡片菜单不再直接执行系统副作用。新增 3 个 Action→Effect 映射测试和 1 个真实 ViewModel Channel 测试，验证权限拒绝只提示一次且不写回 UiState。Home 的筛选、搜索和分页仍沿用细粒度方法，因此“每个 Feature 统一结构”总项继续保持未完成。
- Detail 已将下载和转换的旧版存储权限请求、权限拒绝提示、返回、作者搜索、复制、Steam 外链和本地视频导航收口为 `WorkshopDetailAction / WorkshopDetailEffect`；`pendingLocalVideoPlaybackTaskId` 已移除，3 个 Effect 载荷与权限分支测试及 Compact/Expanded 详情截图基线通过。`interactionMessage`、`downloadMessage` 仍是页面底部持续状态，CDN 首帧 Toast 是 Compose 生命周期提示，均不作为一次性系统 Effect 建模。详情页现已使用官方 `NavigableListDetailPaneScaffold` 的标准 Adaptive List-Detail 布局。
- Library 已将筛选、分页、刷新、导航、下载、复制和 Steam 外链统一为 `LibraryAction / LibraryEffect`；独立 LibraryRoute 和复合 ManagementRoute 共用 Effect Handler，长按菜单不再直接执行 Intent、剪贴板或 Toast。3 个 ViewModel 测试通过，其中新增用例验证多个一次性 Effect 的顺序和载荷。
- Local 已将目录选择、打开/分享资源、复制位置、Wallpaper Engine 导入和一次性错误提示收口为 Route 消费的 Effect，并新增 3 个 ViewModel 回归测试。筛选、选择与标签操作均经 `LocalWallpaperAction` 分发。
- 新增 Robolectric Compose 交互测试：首页瀑布流卡、资料库结果卡和本地列表项分别验证点击后发出预期的详情或选择 Action；三项测试均在本地 JVM 通过。真实设备导航 instrumentation 另已验证顶层页面切换与 Steam 设置返回栈。
- Settings 已将导出目录选择、诊断文档创建、通知权限、APK 系统安装器、Steam 登录导航和外部 URI 打开统一为 `SettingsAction / SettingsEffect`，About 内容不再直接执行 Intent 或 Toast；新增 Action→Effect payload 映射测试并通过。诊断文件的实际写入仍通过 Route 提供的 `ContentResolver` 执行，后续应再抽象为可测试端口。
- `apiCheck` 已通过；`dependencyUpdates` 本地执行成功并生成 `build/dependencyUpdates/report.txt`。报告包含稳定版及 alpha/RC 候选，本阶段只验证检查链路，不自动升级依赖。
- 依赖边界审计已通过：Feature 模块不直接依赖或导入 Data/Room，`core:model` 的公共契约不导入 Android、Room、HTTP 或 Data 层类型；DAO、网络响应和 Android `Context` 不会跨该契约暴露给 Feature。
- GitHub Actions 已配置签名 Release、40 MiB 体积门槛、SHA-256、commit SHA 文件和以 commit 命名的 artifact。`558a0fe7fe0438ca8a8baa325db9c422d9c365c8` 的 Android CI run `30579483452` 已成功完成全量验证、签名 Release、体积检查和 artifact 上传；未过期 artifact `wallhub-release-558a0fe7fe0438ca8a8baa325db9c422d9c365c8`（ID `8774639415`）包含 31,242,308-byte `wallhub-release.apk`、SHA-256 清单和内容同为该 commit 的 `commit-sha.txt`。此前 run `30577309421` 在 `:app:mergeExtDexDebug` 因 CI 命令行将 Gradle 堆覆盖为 1.5 GiB 而耗尽内存；修复已移除该覆盖并使用项目已验证的 3 GiB 默认堆设置。
- `FormalWallHubApp` 已将 Compact、Medium、Expanded 的导航壳抽为独立 Composable，保留原有导航目的地和交互语义，并使根函数重新通过 Detekt 圈复杂度门槛。
- 本次提交范围已逐项核验，只包含本计划的源码、测试、截图基线、构建约定、静态检查配置和维护文档；两份非预期 JVM 崩溃日志已删除并加入忽略规则。提交后工作区保持干净。

## 本阶段验收标准

- 共享 UI Token 位于 `core:designsystem`，业务模块无需复制基础间距、形状和触控尺寸。
- 主题继续保留浅色、深色、动态配色和现有 Material 3 层级。
- 现有页面行为、导航和业务请求不改变。
- JVM 测试、Lint、Debug 构建和 GitHub Actions Release 构建通过。
- 不连接 ADB 设备，不执行安装、冷启动或真机截图测试。
