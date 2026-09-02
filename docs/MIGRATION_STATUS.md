# WallHub 混合架构迁移 - 状态

## 当前状态（2026-09-02 Phase 3 Rust 引擎上线并真机验证）

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
  - `WallHubRust` JNI 桥（运行时可用性检测，缺库自动降级）
  - `RustDepotDownloader`（`CHUNK_DOWNLOAD`/`CHUNK_VERIFICATION`/`CHUNK_DECODE` 全能力）
  - `HybridDepotDownloader`：按操作能力门控路由，Rust 优先，连续 3 次失败回退 Kotlin 并记录诊断，5 分钟后复探
  - 默认 `DepotDownloader` 绑定 = 混合引擎；R8 keep 规则保护 JNI 面
- ✅ **CI/CD**：debug-apk.yml 与 verify.yml 均安装 Rust + NDK 27 并先构建原生核再跑 Gradle
- ✅ **最终验证（commit `91991a6`）**
  - verify.yml：131 项单元测试、lint 预算、detekt、ktlint、依赖审计、签名 Release、40 MiB 体积预算全绿
  - debug-apk.yml 产物：commit 绑定 + SHA-256 + 证书校验通过，四 ABI `libwallhub_rust.so` 已打包
  - 真机（OnePlus 5T / arm64 / `192.168.2.211:33445`）：`adb install -r` 成功，冷启动 PID 存活，无 FATAL/ANR/OOM，首页正常加载约 249 万项 Workshop 内容

### kSteam 协议引擎（未完成，硬阻塞）
- kSteam 未发布任何坐标（Maven Central/JitPack 均无），只能源码 `publishToMavenLocal`
- 其源码要求 **Kotlin 2.3.20 / AGP 8.13.2**；WallHub 为 Kotlin 2.1.21 / AGP 9.1.1，元数据不兼容
- 解除路径：WallHub 升级 Kotlin ≥2.3（需同步 kapt/KSP/Compose 全链路升级）或在 CI 以兼容工具链源码构建 kSteam
- 接缝已就绪：`SteamProtocolClient` 聚合接口 + 单实现绑定，未来 `KSteamProtocolClient` 即插即用

### 架构说明（相对原方案的偏差）
- 方案原定 UniFFI 绑定；实际采用轻量手写 JNI（无 JNA 依赖、CI 单次编译即可验证），接缝接口不变
- 方案原定适配器包装 JavaSteam 会话；实际以聚合接口直绑单例，消除 30+ 纯转发方法的遗漏风险

### 下一步
1. 将既有下载管线（`SteamWorkshopContentClient`/`FormalWorkshopDownloadWorker`）切换为消费 `DepotDownloader`（当前为并行接缝，旧管线未动 —— 这是保守设计，保证生产路径零回归）
2. 解除 kSteam 工具链阻塞后实现 `KSteamProtocolClient` + Feature Flag 双引擎
3. 性能基准（Adler32/LZ4/ZSTD/下载速度 vs 基线）与内存池/自适应并发（Phase 4）
