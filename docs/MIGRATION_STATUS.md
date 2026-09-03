# WallHub 混合架构迁移 - 状态

## 当前状态（2026-09-03 JavaSteam 完全移除，kSteam + Rust 双引擎上线）

### 快照与回档
- `archive/pre-ksteam-rust-migration-20260902/source-backup.tar.gz`（首轮备份）
- `archive/wallhub-source-20260902T190409Z-pre-ksteam-rust-phase1.tar.gz` + `.sha256` + `.restore.txt`（全部编码工作动工前）

### 已完成并验证
- ✅ **Phase 1: 接口抽象层**（commit `36524d2`）
  - `SteamProtocolClient`（五个 session 契约的聚合）与 `DepotDownloader`（能力声明式接缝）
  - Hilt 接口绑定，零行为变更
- ✅ **Phase 2: Rust depot 核心**（commits `36524d2`→`91991a6`）
  - 校验（Steam 0 种子 Adler-32）、LZ4/ZSTD + `VSZa` 容器、AES-256-ECB/CBC chunk 管线
  - 网络分块下载（tokio + reqwest HTTP/2 rustls）、Android JNI 导出（`ffi.rs`）
  - 本地验证：cargo test 19/19、clippy 0 警告、fmt、NDK 27 四 ABI 交叉编译（arm64 3.4 MB）
- ✅ **Phase 3: Kotlin 胶水与混合路由**（commit `d452868` 等）
  - `WallHubRust` JNI 桥（运行时可用性检测）、`RustDepotDownloader` 全能力
  - `HybridDepotDownloader`：能力门控路由 + 诊断 + 5 分钟失败复探
  - CI/CD：debug-apk.yml 与 verify.yml 均安装 Rust + NDK 27 并先构建原生核再跑 Gradle
- ✅ **Phase 4: JavaSteam 完全移除**（2026-09-03）
  - **依赖**：`in.dragonbra:javasteam`、protobuf-javalite 工具链、lz4-java、xz 全部移除；
    新增 Wire 5.5.1（app 内 `src/wire/proto/steam_depot.proto` 生成 depot 协议消息）
  - **会话层**：`KSteamSessionRepository` 成为唯一 `SteamProtocolClient` 实现
    - 登录/Steam Guard（代码 + 设备确认）/refresh-token 恢复走 kSteam `Account` handler
    - 前后台 resume/pause、kSteam 自动重连；EXPIRED 由 kSteam saved-account 清除信号推导
    - 旧版（JavaSteam 时代）加密凭据自动迁移：JWT `sub` 解析 SteamID → `signInWithRefreshToken`
    - Workshop/Community/Player RPC 全部走 kSteam Wire gRPC（`grpc.publishedFile`/`grpc.community`/`grpc.player`）
    - 作者资料解析改用 `Player.GetPlayerLinkDetails`（persona 名 + 头像哈希），替代 SteamFriends 回调
  - **匿名会话**：kSteam 不支持匿名登录，新增裸包 `k_EMsgClientLogon` 匿名 CM 客户端
    （`MemoryPersistenceDriver` + 内存目录），未登录时的公共浏览与公共 depot 下载保持可用
  - **下载层**：`ContentServerDirectory` 服务方法（servers/manifest code/CDN token）+
    裸包 `k_EMsgClientGetDepotDecryptionKey(5438)` 获取 depot key；
    manifest 经 HTTPS 下载后按 magic 容器解析（`ContentManifestPayload/Metadata`，Wire 生成），
    文件名 AES/ECB+CBC 解密内联实现；chunk 解码统一走 Rust 引擎（`HybridDepotDownloader` → `RustDepotDownloader`）
    - `KotlinDepotDownloader`（JavaSteam `DepotChunk`/`Adler32` 基线）删除，Rust 成为必需引擎
    - JavaSteam `SteamCdnClient`/`Server`/`ChunkData`/`FileData`/`DepotManifest` 由
      `CdnServer`/`DepotChunkSpec`/`DepotFileSpec`/`DepotManifestSpec` 引擎中立类型替代
  - **清理**：`SecureSteamSessionRepository`/`SecureSteamSessionRuntime`/`OkHttpSteamWebSocketConnection`/
    `SteamSessionConnectionPolicy`/`CommunityUnifiedService`/`community_messages.proto` 删除；
    ProGuard 移除 javasteam/AES keep，新增 ksteam/Wire keep
- ✅ **真机验证（Phase 3，commit `91991a6`）**
  - verify.yml：131 项单元测试、lint 预算、detekt、ktlint、依赖审计、签名 Release、40 MiB 体积预算全绿
  - debug-apk.yml 产物：commit 绑定 + SHA-256 + 证书校验通过，四 ABI `libwallhub_rust.so` 已打包
  - 真机（OnePlus 5T / arm64 / `192.168.2.211:33445`）：`adb install -r` 成功，冷启动 PID 存活，无 FATAL/ANR/OOM

### 架构说明（相对原方案的偏差）
- 方案原定 UniFFI 绑定；实际采用轻量手写 JNI（无 JNA 依赖、CI 单次编译即可验证），接缝接口不变
- 方案原定适配器包装 JavaSteam 会话；实际以聚合接口直绑单例，最终 kSteam 直营全部会话能力
- kSteam pinned SHA 无 `ClientGetDepotManifest` EMsg —— 无需 patch：manifest 走 HTTPS CDN 下载
  （JavaSteam 1.8.0 亦如此），CM 仅承载 `ContentServerDirectory` 服务方法与 depot key 裸包
- CM 代理：kSteam 的 ktor 传输未暴露代理配置（旧 JavaSteam CM 支持 download proxy）；
  CDN/HTTP 下载路径保留代理设置，CM 控制面暂直连

### 下一步
1. CI 验证 JavaSteam 移除后的全量构建（debug-apk），真机回归：登录恢复、浏览、订阅、下载、在线视频
2. 性能基准（Adler32/LZ4/ZSTD/下载速度 vs JavaSteam 基线）与内存池/自适应并发（Phase 4）
3. kSteam 升级跟进：上游若补齐匿名登录/CM 代理，可删除本地匿名裸包实现
