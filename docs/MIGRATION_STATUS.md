# WallHub 混合架构迁移 - 状态

## 当前状态（2026-09-02 Phase 1 完成）

### 已完成
- ✅ 源码快照（可回档）：
  - `archive/pre-ksteam-rust-migration-20260902/source-backup.tar.gz`（首轮备份）
  - `archive/wallhub-source-20260902T190409Z-pre-ksteam-rust-phase1.tar.gz` + `.sha256` + `.restore.txt`（Phase 1 动工前快照）
- ✅ **Phase 1: 接口抽象层（本次完成，零行为变更）**
  - `core/model/SteamProtocolClient.kt` — 引擎无关协议聚合接口（= 五个既有 session 契约的 union）
  - `core/model/DepotDownloader.kt` — 引擎无关 depot 接缝（`verifyChunk` / `decodeChunk` + 能力声明 `DepotDownloaderCapability`）
  - `SecureSteamSessionRepository` 直接实现 `SteamProtocolClient` 聚合（等价于适配器包装，省去 30+ 个纯转发方法及其遗漏风险）
  - `data/downloads/KotlinDepotDownloader.kt` — JavaSteam 引擎的默认 depot 实现（复用 `decodeDepotChunk` 硬化路径）
  - `di/RepositoryModule.kt` — `SteamProtocolClient` / `DepotDownloader` 接口绑定（与五个契约共享同一单例）
  - 单元测试 `KotlinDepotDownloaderTest`（Adler32 向量、损坏载荷、失败封闭、能力集合）
- ✅ **Phase 2 Week 1-2 前置：Rust depot 核心骨架 `wallhub-rust/`（已验证）**
  - `depot/verify.rs` — Adler-32（RFC 1950 向量测试）
  - `compression/` — LZ4 块解压（lz4_flex）、ZStandard（ruzstd）+ Steam `VSZa`/VZstd 容器解析（8 字节头 + 15 字节尾）
  - `crypto/aes.rs` — AES-256-ECB IV + AES-CBC + PKCS#7（对齐 JavaSteam `DepotChunk.process` 语义）
  - `depot/chunk.rs` — 完整 chunk 解码管线：解密 → 容器检测 → 解压 → 长度/校验验证；VZip(LZMA)/PKZip 暂返回 `UnsupportedCompression`（Kotlin 引擎现覆盖全量解码）
  - 本机验证：`cargo test` 16/16 通过（含字节级 Steam chunk 往返）、`cargo clippy` 0 警告、`cargo fmt --check` 通过
  - `scripts/build-rust.sh` — 宿主测试 + （配置 `ANDROID_NDK_HOME` 后）cargo-ndk 四 ABI 交叉编译
  - `.github/workflows/build-hybrid.yml` — 仅 `feature/ksteam-rust-hybrid` 分支触发（fmt/clippy/test/release）

### 更正此前状态文档的不实条目
- ❌ "已添加 kSteam + Ktor 依赖到 Gradle" — 此前不存在任何 kSteam 依赖，`libs.versions.toml` 未改动（本次依旧未添加）
- ❌ "已定义 SteamProtocolClient.kt / DepotDownloader.kt" — 此前不存在，本次才真正落地
- kSteam 依赖仍未引入：`bruhcollective.itaysonlab:ksteam-*` 需先确认 Maven Central 可用坐标或在本地 `publishToMavenLocal`；引入前 Phase 1 的接缝保持 JavaSteam 实现

## 环境约束（LXC）
- 禁止在 LXC 内运行 Gradle：Kotlin 侧编译/测试由 GitHub Actions（`verify.yml` / `debug-apk.yml`）验证，设备部署走 `scripts/push-build-install.sh`
- Rust 工具链已就绪（rustc 1.98.0）；Android NDK 未安装（cross-compile 属 Phase 3，CI 侧补充）

## 下一步
1. **Phase 2: kSteam 并行实验（4-6 周）**
   - 确认 kSteam 坐标可用后引入 `ksteam-core` 等依赖
   - 实现 `KSteamProtocolClient : SteamProtocolClient`
   - Feature Flag + `@Named` 限定符在两个引擎间切换，Beta 对比
2. **Phase 2 Week 3-8: Rust 下载/解压核心**
   - 引入 tokio + reqwest(rustls) 实现 `CHUNK_DOWNLOAD` 能力
   - VZip(LZMA)/PKZip 解码器补齐，使 Rust 引擎 `CHUNK_DECODE` 全覆盖
   - 配置 NDK + cargo-ndk，CI 出四 ABI `.so`
3. **Phase 3: UniFFI 绑定 + `RustDepotDownloader` + `FallbackDownloader`（连续失败回退 Kotlin 引擎）**
