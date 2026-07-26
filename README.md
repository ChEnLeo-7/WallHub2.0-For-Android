<div align="center">
  <img src="docs/assets/wallhub-logo.svg" width="128" height="128" alt="WallHub Logo">
  <h1>WallHub for Android</h1>
  <p><strong>把 Wallpaper Engine 创意工坊带到 Android：原生浏览、在线播放、可靠下载与移动端壁纸管理，一站完成。</strong></p>
  <p>
    <a href="README_EN.md">English</a>
    · <a href="docs/development-log.md">开发日志</a>
    · <a href="LICENSE">MIT License</a>
  </p>
</div>

## 项目特色

WallHub for Android 是面向 Wallpaper Engine 创意工坊的独立原生客户端。应用基于 Kotlin 与 Jetpack Compose 构建，不依赖 WallHub Web 服务，不使用 WebView，也不会在手机上运行 Node.js、Python 或 DepotDownloader。

- **原生 Material 3 体验**：发现、详情、管理、设置与播放器均为 Compose 原生界面，支持深浅主题、系统动态配色、紧凑与宽屏布局。
- **完整创意工坊发现能力**：支持搜索、排序、时间、类型、年龄评级、主题、官方标签、分辨率筛选，以及瀑布流与 Web 页码模式。
- **Steam 账户与资料库**：支持 Steam 登录、会话加密恢复、个人订阅/收藏读取、订阅与收藏写操作、作者结果与评论。
- **在线与本地播放**：使用 Media3 播放本地视频，也可通过 JavaSteam Depot 分块读取实现在线播放与有界缓存。
- **可靠下载流水线**：WorkManager 前台任务、并发分块下载、校验、暂停/续传、队列排序、失败重试和持久化任务状态。
- **移动端格式转换**：支持视频/场景 MPKG 与网站 ZIP 导出，包含 PKG 索引读取、TEX 移动端转换、定向 Shader 兼容与原子写入。
- **本地壁纸管理**：扫描公共下载目录与 SAF 目录，支持格式分类、搜索、筛选、排序、收藏、标签、批量操作、分享、删除与 Wallpaper Engine 导入。
- **安全与可诊断性**：Steam refresh token 使用 Android Keystore 加密，诊断日志在落盘前脱敏，导出和路径处理包含原子替换与目录穿越防护。

## 核心功能

| 模块 | 能力 |
|---|---|
| 发现 | 浏览、搜索、作者检索、完整筛选、分页与瀑布流、长按快捷操作 |
| 详情 | 项目元数据、评论、订阅、收藏、下载选项、视频预览与在线播放 |
| 下载 | Depot 内容获取、并发分块、断点续传、转换、导出、暂停/重试/删除 |
| 资料库 | 订阅、收藏、投票分类，搜索筛选、下载、播放和 Steam 跳转 |
| 本地 | MPKG、PKG、视频与网站资源管理，收藏标签、批量分享删除和导入 |
| 设置 | 主题语言、动态配色、目录、并发代理、Steam 会话、诊断与实验功能 |

## 快速开始

### 安装要求

- Android 8.0（API 26）或更高版本。
- 与创意工坊和 Steam 账户相关的功能需要可访问 Steam 服务的网络环境。
- MPKG 导入需要设备安装官方 Wallpaper Engine Android 客户端。

项目通过 GitHub Actions 生成签名 Release APK artifact。进入仓库的 **Actions > Android CI**，打开成功的 `main` 构建并下载 `wallhub-release-<commit-sha>` artifact；解压后安装其中的 `wallhub-release.apk`。请只安装你信任的构建产物，并注意 Android 不允许不同签名的 APK 直接覆盖现有安装。

### 构建开发环境

准备以下环境：

- Android Studio 与 JDK 17 或更高版本。
- Android SDK Platform 36。
- Android SDK Build Tools 35.0.0。

克隆项目后，在 Windows 执行：

```powershell
./gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug
```

在 Linux 或 macOS 执行：

```bash
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

依赖与插件版本集中维护在 `gradle/libs.versions.toml`。Release 签名材料、`local.properties`、APK/AAB、构建缓存和本地快照均不应提交到仓库。维护者的 GitHub Actions 到 ADB 自动验证流程见 [`docs/github-actions-adb.md`](docs/github-actions-adb.md)。

## 参考与鸣谢

本项目基于并感谢以下开源项目与平台：

- [Android Jetpack Compose](https://developer.android.com/compose) 与 [Material 3](https://m3.material.io/)
- [JavaSteam](https://github.com/Longi94/JavaSteam)
- [AndroidX Media3](https://developer.android.com/media/media3)
- [Room](https://developer.android.com/training/data-storage/room)、[WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) 与 [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [Coil](https://coil-kt.github.io/coil/) 与 [Haze](https://github.com/chrisbanes/haze)

Steam、Wallpaper Engine 及相关名称、商标、内容与服务归各自权利人所有。本项目为独立的开源客户端，与 Valve Corporation、Wallpaper Engine 团队及创意工坊内容作者不存在官方隶属、授权或背书关系。

## 免责说明

本软件按 MIT License 以“现状”提供，不附带任何明示或默示担保。使用者应遵守 Steam、Wallpaper Engine、创意工坊内容作者及所在地法律法规的相关条款，并自行承担账户、网络、存储、下载、转换、导入和内容使用风险。请尊重创作者权益，不要将本项目用于规避平台规则、未经授权分发内容或其他侵权行为。

许可证全文见 [`LICENSE`](LICENSE)。
