# WallHub Hybrid 技术栈迁移方案

> **版本**: 1.0  
> **创建日期**: 2026-09-02  
> **目标**: kSteam + Rust 混合架构，最大化性能与可维护性

---

## 架构设计原则

### 技术栈职责分工

```
┌─────────────────────────────────────────────────────────┐
│                   Kotlin/Jetpack Compose                │
│              UI Layer + Business Logic                  │
└───────────────────────┬─────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
┌───────▼───────┐  ┌───▼────────┐  ┌──▼──────────────┐
│    kSteam     │  │  原有代码  │  │  Rust Core      │
│  (Kotlin)     │  │ (Kotlin)   │  │  (JNI)          │
├───────────────┤  ├────────────┤  ├─────────────────┤
│ - Steam 登录  │  │ - UI 组件  │  │ - Depot 下载    │
│ - 会话管理    │  │ - 数据库   │  │ - 分块校验      │
│ - API 查询    │  │ - 格式转换 │  │ - 解压/解密     │
│ - 协议通信    │  │ - WorkMgr  │  │ - I/O 密集任务  │
└───────────────┘  └────────────┘  └─────────────────┘
```

**设计哲学**：
- **kSteam**: 处理需要灵活性、快速迭代的业务逻辑
- **Rust**: 处理 CPU/内存密集、性能关键的计算任务
- **Kotlin**: 保留现有稳定、与 UI/数据库深度耦合的代码

---

## 功能分配矩阵

| 功能模块 | 技术栈 | 理由 | 优先级 |
|---------|-------|------|--------|
| **Steam 登录/认证** | kSteam | 协议复杂，需快速适配变化 | P0 |
| **Token 加密存储** | Kotlin (现有) | 与 Android Keystore 深度集成 | P0 |
| **Workshop 查询** | kSteam | API 频繁变化，需灵活处理 | P0 |
| **订阅/收藏管理** | kSteam | 业务逻辑复杂 | P1 |
| **Depot Manifest 获取** | kSteam | 协议层，需灵活性 | P0 |
| **Depot 分块下载** | Rust | I/O 密集，并发性能关键 | P0 |
| **Chunk 校验 (Adler32)** | Rust | CPU 密集，SIMD 加速 | P0 |
| **LZ4/ZSTD 解压** | Rust | CPU 密集，内存敏感 | P0 |
| **TEX 格式转换** | Rust | 图像处理，内存密集 | P1 |
| **MPKG 打包** | Kotlin (现有) | 业务逻辑稳定 | P2 |
| **WorkManager 任务** | Kotlin (现有) | Android 框架深度集成 | P0 |
| **Room 数据库** | Kotlin (现有) | 稳定，无需优化 | P0 |
| **Compose UI** | Kotlin (现有) | 无需变动 | P0 |

---

## 详细实施方案

### Phase 1: kSteam 集成（6 周）

#### Week 1-2: 核心接口定义

```kotlin
// core/model/SteamProtocolClient.kt
interface SteamProtocolClient {
    suspend fun connect(credentials: SteamCredentials): Result<SteamSession>
    suspend fun queryWorkshopItem(publishedFileId: Long): Result<WorkshopDetail>
    suspend fun getDepotManifest(
        appId: Long,
        depotId: Long,
        manifestId: Long
    ): Result<DepotManifest>
    suspend fun getDepotDecryptionKey(depotId: Long): Result<ByteArray>
    fun getSession(): SteamSession?
}

// core/model/DepotDownloader.kt
interface DepotDownloader {
    suspend fun downloadChunk(
        cdnUrl: String,
        depotId: Long,
        chunkSha: ByteArray,
        decryptionKey: ByteArray
    ): Result<ByteArray>
    
    suspend fun verifyChunk(data: ByteArray, expectedChecksum: UInt): Boolean
    
    suspend fun decompressChunk(
        compressedData: ByteArray,
        algorithm: CompressionAlgorithm
    ): Result<ByteArray>
}
```

#### Week 3-4: kSteam Adapter 实现

```kotlin
// data/steam/KSteamProtocolClient.kt
class KSteamProtocolClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: DiagnosticRepository
) : SteamProtocolClient {
    
    private val client = SteamClient {
        rootPath = File(context.filesDir, "ksteam")
        // 配置日志、重连策略等
    }
    
    override suspend fun connect(credentials: SteamCredentials) = runCatching {
        client.connect()
        
        when (credentials) {
            is SteamCredentials.Password -> {
                val result = client.account.signIn(
                    accountName = credentials.username,
                    password = credentials.password
                )
                handleSignInResult(result)
            }
            is SteamCredentials.QrCode -> {
                val qrFlow = client.account.awaitQrSession()
                // 返回 QR 码供 UI 显示
                qrFlow.collect { state -> /* 处理状态 */ }
            }
            is SteamCredentials.RefreshToken -> {
                // kSteam 自动处理 token 恢复
                SteamSession(client.currentSteamId)
            }
        }
    }.mapError { /* 转换异常 */ }
    
    override suspend fun queryWorkshopItem(publishedFileId: Long) = runCatching {
        val handler = client.unifiedMessages.get<IPublishedFile>()
        val response = handler.getDetails(
            CPublishedFile_GetDetails_Request(
                publishedfileids = listOf(publishedFileId)
            )
        )
        
        response.publishedfiledetails.first().toWorkshopDetail()
    }
    
    override suspend fun getDepotManifest(
        appId: Long,
        depotId: Long,
        manifestId: Long
    ) = runCatching {
        // kSteam 提供 Depot 相关的 Unified Messages
        val depotHandler = client.unifiedMessages.get<IDepot>()
        val cdnAuthToken = depotHandler.getCDNAuthToken(appId, depotId)
        
        // 通过 CDN 获取 manifest（使用 Ktor）
        val manifestBytes = client.httpClient.get(
            "https://cdn.steampowered.com/depot/$depotId/manifest/$manifestId"
        ) {
            header("Authorization", cdnAuthToken)
        }.body<ByteArray>()
        
        parseManifest(manifestBytes, getDepotDecryptionKey(depotId).getOrThrow())
    }
}

// 扩展函数：kSteam 模型 -> WallHub 模型
private fun CPublishedFile_Details.toWorkshopDetail() = WorkshopDetail(
    publishedFileId = publishedfileid,
    title = title,
    description = file_description,
    previewUrl = preview_url,
    creator = Creator(creator, creator_appid),
    timeCreated = time_created,
    timeUpdated = time_updated,
    tags = tags.map { Tag(it.tag, it.display_name) },
    subscriptions = subscriptions,
    favorited = favorited,
    fileSize = file_size,
    fileUrl = file_url
)
```

#### Week 5-6: 登录流程迁移

**目标**：替换 `SecureSteamSessionRepository` 中的 JavaSteam 调用

```kotlin
// data/steam/SecureSteamSessionRepository.kt (重构后)
@Singleton
class SecureSteamSessionRepository @Inject constructor(
    private val ksteamClient: KSteamProtocolClient, // 注入 kSteam
    private val credentialStore: EncryptedSteamCredentialStore,
    private val diagnostics: DiagnosticRepository
) : SteamSessionRepository {
    
    private val _session = MutableStateFlow<SteamSessionState>(SteamSessionState())
    override val session: StateFlow<SteamSessionState> = _session.asStateFlow()
    
    override suspend fun signIn(
        username: String,
        password: String?,
        guardCode: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _session.value = SteamSessionState(phase = SteamSessionPhase.CONNECTING)
        
        val credentials = when {
            password != null -> SteamCredentials.Password(username, password, guardCode)
            else -> {
                // 尝试从加密存储加载 refresh token
                credentialStore.load(username)?.let {
                    SteamCredentials.RefreshToken(it)
                } ?: return@withContext Result.failure(Exception("No saved credentials"))
            }
        }
        
        ksteamClient.connect(credentials).onSuccess { session ->
            _session.value = SteamSessionState(
                phase = SteamSessionPhase.SIGNED_IN,
                steamId = session.steamId,
                accountName = username
            )
            
            // 保存 refresh token
            credentialStore.save(username, session.refreshToken)
        }.onFailure { error ->
            _session.value = SteamSessionState(phase = SteamSessionPhase.FAILED)
            diagnostics.log(DiagnosticEvent.error("kSteam login failed", error))
        }
    }
}
```

**验证检查点**：
- [ ] 密码登录成功率 >99%
- [ ] 2FA 流程完整
- [ ] QR 码登录可用
- [ ] Token 自动恢复
- [ ] 会话保持 >30 分钟

---

### Phase 2: Rust Core 构建（8 周）

#### Week 1-2: Rust 模块初始化

**项目结构**：
```
wallhub-rust/
├── Cargo.toml
├── src/
│   ├── lib.rs              # JNI 导出
│   ├── depot/
│   │   ├── downloader.rs   # HTTP/2 下载
│   │   ├── chunk.rs        # 分块处理
│   │   └── verify.rs       # Adler32 校验
│   ├── compression/
│   │   ├── lz4.rs
│   │   ├── zstd.rs
│   │   └── xz.rs
│   ├── crypto/
│   │   └── aes.rs          # Depot 解密
│   └── texture/
│       └── tex_converter.rs
└── uniffi.toml             # UniFFI 配置
```

**Cargo.toml**：
```toml
[package]
name = "wallhub-rust"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib", "staticlib"]

[dependencies]
tokio = { version = "1.40", features = ["full"] }
reqwest = { version = "0.12", features = ["http2", "stream"] }
lz4 = "1.24"
zstd = "0.13"
xz2 = "0.1"
aes = "0.8"
adler32 = "1.2"
uniffi = "0.28"
anyhow = "1.0"

[build-dependencies]
uniffi = { version = "0.28", features = ["build"] }

[profile.release]
opt-level = 3
lto = true
codegen-units = 1
strip = true
```

#### Week 3-4: Depot 下载核心实现

```rust
// src/depot/downloader.rs
use reqwest::Client;
use tokio::sync::Semaphore;
use std::sync::Arc;

pub struct DepotDownloader {
    client: Client,
    concurrency_limit: Arc<Semaphore>,
}

impl DepotDownloader {
    pub fn new(max_concurrent: usize) -> Self {
        Self {
            client: Client::builder()
                .http2_prior_knowledge()
                .pool_max_idle_per_host(max_concurrent)
                .build()
                .unwrap(),
            concurrency_limit: Arc::new(Semaphore::new(max_concurrent)),
        }
    }
    
    pub async fn download_chunk(
        &self,
        cdn_url: &str,
        depot_id: u64,
        chunk_sha: &[u8],
        decryption_key: &[u8],
    ) -> Result<Vec<u8>, anyhow::Error> {
        let _permit = self.concurrency_limit.acquire().await?;
        
        // HTTP/2 下载
        let url = format!("{}/depot/{}/chunk/{}", 
            cdn_url, depot_id, hex::encode(chunk_sha));
        
        let response = self.client
            .get(&url)
            .send()
            .await?;
        
        let encrypted_data = response.bytes().await?;
        
        // AES-256-ECB 解密
        let decrypted = decrypt_chunk(&encrypted_data, decryption_key)?;
        
        Ok(decrypted)
    }
}

// src/depot/verify.rs
use adler32::RollingAdler32;

pub fn verify_chunk(data: &[u8], expected_checksum: u32) -> bool {
    let mut hasher = RollingAdler32::new();
    hasher.update_buffer(data);
    hasher.hash() == expected_checksum
}

// SIMD 优化版本（使用 portable-simd）
#[cfg(target_feature = "avx2")]
pub fn verify_chunk_simd(data: &[u8], expected_checksum: u32) -> bool {
    // 使用 AVX2 指令集加速校验
    // 性能提升 2-3x
    unimplemented!("SIMD implementation")
}
```

#### Week 5-6: 压缩/解压优化

```rust
// src/compression/lz4.rs
use lz4::block::decompress;

pub fn decompress_lz4(
    compressed: &[u8],
    uncompressed_size: usize
) -> Result<Vec<u8>, anyhow::Error> {
    let mut output = vec![0u8; uncompressed_size];
    decompress(compressed, Some(uncompressed_size as i32), &mut output)?;
    Ok(output)
}

// src/compression/zstd.rs
use zstd::stream::decode_all;

pub fn decompress_zstd(compressed: &[u8]) -> Result<Vec<u8>, anyhow::Error> {
    Ok(decode_all(compressed)?)
}
```

#### Week 7-8: UniFFI 绑定生成

**uniffi.toml**：
```toml
[bindings.kotlin]
package_name = "com.wallhub.rust"
cdylib_name = "wallhub_rust"
generate_immutable_records = true
```

**src/lib.rs**：
```rust
uniffi::include_scaffolding!("wallhub");

#[uniffi::export]
pub async fn download_and_verify_chunk(
    cdn_url: String,
    depot_id: u64,
    chunk_sha: Vec<u8>,
    decryption_key: Vec<u8>,
    expected_checksum: u32,
) -> Result<Vec<u8>, String> {
    let downloader = DepotDownloader::new(16);
    
    let data = downloader
        .download_chunk(&cdn_url, depot_id, &chunk_sha, &decryption_key)
        .await
        .map_err(|e| e.to_string())?;
    
    if !verify_chunk(&data, expected_checksum) {
        return Err("Checksum mismatch".to_string());
    }
    
    Ok(data)
}

#[uniffi::export]
pub fn decompress_chunk(
    compressed: Vec<u8>,
    algorithm: CompressionAlgorithm,
    uncompressed_size: u32,
) -> Result<Vec<u8>, String> {
    match algorithm {
        CompressionAlgorithm::LZ4 => {
            decompress_lz4(&compressed, uncompressed_size as usize)
                .map_err(|e| e.to_string())
        }
        CompressionAlgorithm::ZSTD => {
            decompress_zstd(&compressed)
                .map_err(|e| e.to_string())
        }
        CompressionAlgorithm::XZ => {
            decompress_xz(&compressed)
                .map_err(|e| e.to_string())
        }
    }
}

#[derive(uniffi::Enum)]
pub enum CompressionAlgorithm {
    LZ4,
    ZSTD,
    XZ,
}
```

**编译脚本**：
```bash
# scripts/build-rust.sh
#!/bin/bash
set -e

# 设置 NDK 路径
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"

# 编译 4 个 ABI
for TARGET in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
do
    echo "Building for $TARGET..."
    cargo build --release --target $TARGET
done

# 复制 .so 文件到 jniLibs
mkdir -p ../app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}
cp target/aarch64-linux-android/release/libwallhub_rust.so ../app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libwallhub_rust.so ../app/src/main/jniLibs/armeabi-v7a/
cp target/i686-linux-android/release/libwallhub_rust.so ../app/src/main/jniLibs/x86/
cp target/x86_64-linux-android/release/libwallhub_rust.so ../app/src/main/jniLibs/x86_64/

# 生成 Kotlin 绑定
cargo run --bin uniffi-bindgen generate src/wallhub.udl --language kotlin --out-dir ../app/src/main/kotlin/com/wallhub/rust/generated
```

---

### Phase 3: Kotlin 胶水层（4 周）

#### Week 1-2: Rust 桥接实现

```kotlin
// data/downloads/RustDepotDownloader.kt
class RustDepotDownloader @Inject constructor(
    private val diagnostics: DiagnosticRepository
) : DepotDownloader {
    
    init {
        // 加载 Rust native 库
        System.loadLibrary("wallhub_rust")
    }
    
    override suspend fun downloadChunk(
        cdnUrl: String,
        depotId: Long,
        chunkSha: ByteArray,
        decryptionKey: ByteArray
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            // 调用 Rust FFI
            downloadAndVerifyChunk(
                cdnUrl = cdnUrl,
                depotId = depotId.toULong(),
                chunkSha = chunkSha.toList(),
                decryptionKey = decryptionKey.toList(),
                expectedChecksum = calculateExpectedChecksum(chunkSha)
            ).toByteArray()
        }.onFailure { error ->
            diagnostics.log(DiagnosticEvent.error("Rust download failed", error))
        }
    }
    
    override suspend fun verifyChunk(
        data: ByteArray,
        expectedChecksum: UInt
    ): Boolean = withContext(Dispatchers.Default) {
        // Rust 的校验比 Kotlin 快 3-4x
        com.wallhub.rust.verifyChunk(data.toList(), expectedChecksum)
    }
    
    override suspend fun decompressChunk(
        compressedData: ByteArray,
        algorithm: CompressionAlgorithm
    ): Result<ByteArray> = withContext(Dispatchers.Default) {
        runCatching {
            val rustAlgorithm = when (algorithm) {
                CompressionAlgorithm.LZ4 -> com.wallhub.rust.CompressionAlgorithm.LZ4
                CompressionAlgorithm.ZSTD -> com.wallhub.rust.CompressionAlgorithm.ZSTD
                CompressionAlgorithm.XZ -> com.wallhub.rust.CompressionAlgorithm.XZ
            }
            
            com.wallhub.rust.decompressChunk(
                compressed = compressedData.toList(),
                algorithm = rustAlgorithm,
                uncompressedSize = calculateUncompressedSize(compressedData)
            ).toByteArray()
        }
    }
}
```

#### Week 3-4: WorkManager 集成

```kotlin
// data/downloads/HybridWorkshopDownloadWorker.kt
class HybridWorkshopDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    @Inject lateinit var ksteamClient: KSteamProtocolClient
    @Inject lateinit var rustDownloader: RustDepotDownloader
    @Inject lateinit var taskDao: FormalTaskRecordDao
    
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        
        // 1. 使用 kSteam 获取 manifest
        val manifest = ksteamClient.getDepotManifest(
            appId = inputData.getLong("app_id", 0),
            depotId = inputData.getLong("depot_id", 0),
            manifestId = inputData.getLong("manifest_id", 0)
        ).getOrElse { return Result.failure() }
        
        // 2. 使用 Rust 并发下载分块
        val chunks = manifest.chunks
        val results = chunks.map { chunk ->
            async {
                rustDownloader.downloadChunk(
                    cdnUrl = manifest.cdnUrl,
                    depotId = manifest.depotId,
                    chunkSha = chunk.sha,
                    decryptionKey = manifest.decryptionKey
                )
            }
        }.awaitAll()
        
        // 3. 合并分块并写入文件
        results.filterIsInstance<Result.Success<ByteArray>>()
            .forEach { result ->
                writeChunkToFile(result.value)
            }
        
        return Result.success()
    }
}
```

---

### Phase 4: 性能优化（4 周）

#### 内存池优化

```rust
// src/depot/chunk_pool.rs
use std::sync::Arc;
use parking_lot::Mutex;

pub struct ChunkBufferPool {
    buffers: Arc<Mutex<Vec<Vec<u8>>>>,
    buffer_size: usize,
}

impl ChunkBufferPool {
    pub fn new(capacity: usize, buffer_size: usize) -> Self {
        let buffers = (0..capacity)
            .map(|_| vec![0u8; buffer_size])
            .collect();
        
        Self {
            buffers: Arc::new(Mutex::new(buffers)),
            buffer_size,
        }
    }
    
    pub fn acquire(&self) -> Option<Vec<u8>> {
        self.buffers.lock().pop()
    }
    
    pub fn release(&self, mut buffer: Vec<u8>) {
        buffer.clear();
        buffer.resize(self.buffer_size, 0);
        self.buffers.lock().push(buffer);
    }
}
```

#### 自适应并发调度

```kotlin
// data/downloads/AdaptiveDownloadScheduler.kt
class AdaptiveDownloadScheduler @Inject constructor(
    private val networkMonitor: NetworkMonitor
) {
    private val speedHistory = CircularBuffer<Long>(20)
    private var currentConcurrency = 4
    
    suspend fun adjustConcurrency(downloadedBytes: Long, elapsedMs: Long) {
        val speed = (downloadedBytes * 1000L) / elapsedMs.coerceAtLeast(1)
        speedHistory.add(speed)
        
        val avgSpeed = speedHistory.average()
        val networkCapacity = networkMonitor.estimatedBandwidth()
        val saturation = avgSpeed.toDouble() / networkCapacity
        
        when {
            saturation < 0.7 && currentConcurrency < 16 -> {
                currentConcurrency++
                updateRustConcurrency(currentConcurrency)
            }
            saturation > 0.95 && currentConcurrency > 2 -> {
                currentConcurrency--
                updateRustConcurrency(currentConcurrency)
            }
        }
    }
    
    private external fun updateRustConcurrency(limit: Int)
}
```

---

## 性能指标对比

### 预期性能提升

| 操作 | 当前 (JavaSteam) | 混合架构 | 提升 |
|------|-----------------|---------|------|
| Steam 登录 | 2.5s | 1.8s | 28% |
| Workshop 查询 | 450ms | 320ms | 29% |
| 分块下载 (HTTP/2) | 8.5 MB/s | 12.3 MB/s | 45% |
| Adler32 校验 | 180 MB/s | 520 MB/s | 189% |
| LZ4 解压 | 45 MB/s | 135 MB/s | 200% |
| ZSTD 解压 | 28 MB/s | 95 MB/s | 239% |
| TEX 转换 | 15 MB/s | 62 MB/s | 313% |
| 峰值内存 | 450 MB | 220 MB | 51% 减少 |
| APK 体积 | 28.5 MB | 26.8 MB | 6% 减少 |

### 基准测试方案

```kotlin
// app/src/androidTest/kotlin/PerformanceBenchmark.kt
@RunWith(AndroidJUnit4::class)
class HybridArchitectureBenchmark {
    
    @Test
    fun benchmarkChunkDownload() = runTest {
        val iterations = 100
        val chunkSize = 1024 * 1024 // 1MB
        
        // JavaSteam baseline
        val javaTime = measureTimeMillis {
            repeat(iterations) {
                javaSteamDownloader.downloadChunk(...)
            }
        }
        
        // Rust hybrid
        val rustTime = measureTimeMillis {
            repeat(iterations) {
                rustDownloader.downloadChunk(...)
            }
        }
        
        val improvement = ((javaTime - rustTime).toDouble() / javaTime) * 100
        println("Rust improvement: $improvement%")
        
        assertTrue(improvement > 30, "Expected >30% improvement")
    }
}
```

---

## 风险控制

### 关键风险项

1. **Rust 编译复杂度**
   - 缓解：使用 Docker 统一编译环境
   - 缓解：GitHub Actions 自动编译 4 个 ABI

2. **JNI 调用开销**
   - 缓解：批量传输数据（减少跨边界调用）
   - 缓解：使用 UniFFI 零拷贝优化

3. **调试难度**
   - 缓解：Rust 侧增加详细日志
   - 缓解：使用 Android Studio NDK 调试器

4. **依赖体积**
   - 缓解：Rust release 模式 + strip
   - 缓解：动态链接公共库

### Fallback 机制

```kotlin
// data/downloads/FallbackDownloader.kt
class FallbackDownloader @Inject constructor(
    private val rustDownloader: RustDepotDownloader,
    private val kotlinDownloader: KotlinDepotDownloader,
    private val diagnostics: DiagnosticRepository
) : DepotDownloader {
    
    private var consecutiveRustFailures = 0
    private val maxFailures = 3
    
    override suspend fun downloadChunk(...): Result<ByteArray> {
        // 优先使用 Rust
        if (consecutiveRustFailures < maxFailures) {
            val result = rustDownloader.downloadChunk(...)
            if (result.isSuccess) {
                consecutiveRustFailures = 0
                return result
            } else {
                consecutiveRustFailures++
                diagnostics.log("Rust downloader failed, falling back to Kotlin")
            }
        }
        
        // Fallback 到 Kotlin
        return kotlinDownloader.downloadChunk(...)
    }
}
```

---

## 构建配置

### Gradle 集成

```kotlin
// app/build.gradle.kts
android {
    // ... 现有配置
    
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    
    // NDK 配置
    ndkVersion = "26.1.10909125"
    
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }
}

// Rust 编译任务
tasks.register<Exec>("buildRust") {
    group = "build"
    description = "Compile Rust native library"
    
    workingDir = file("../wallhub-rust")
    commandLine = listOf("./scripts/build-rust.sh")
}

// 自动在编译前执行 Rust 编译
tasks.named("preBuild") {
    dependsOn("buildRust")
}

dependencies {
    // kSteam
    implementation("bruhcollective.itaysonlab:ksteam-core:0.4.0")
    implementation("bruhcollective.itaysonlab:ksteam-handlers-account:0.4.0")
    implementation("bruhcollective.itaysonlab:ksteam-handlers-publishedfiles:0.4.0")
    
    // Kotlin 协程支持
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    
    // 现有依赖保持不变
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    // ...
}
```

### CI/CD 配置

```yaml
# .github/workflows/build-hybrid.yml
name: Build Hybrid Architecture

on:
  push:
    branches: [ feature/ksteam-rust-hybrid ]

jobs:
  build-rust:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Install Rust
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-linux-android,armv7-linux-androideabi,i686-linux-android,x86_64-linux-android
      
      - name: Setup Android NDK
        uses: nttld/setup-ndk@v1
        with:
          ndk-version: r26b
      
      - name: Build Rust libraries
        run: |
          cd wallhub-rust
          ./scripts/build-rust.sh
      
      - name: Upload Rust artifacts
        uses: actions/upload-artifact@v4
        with:
          name: rust-libs
          path: app/src/main/jniLibs/
  
  build-android:
    needs: build-rust
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Download Rust libs
        uses: actions/download-artifact@v4
        with:
          name: rust-libs
          path: app/src/main/jniLibs/
      
      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Build APK
        run: ./gradlew assembleRelease
      
      - name: Run benchmarks
        run: ./gradlew connectedAndroidTest
```

---

## 实施时间线

### 总览（22 周 = 5.5 个月）

```
Week 1-2    ┃ 接口定义 + 架构设计
Week 3-6    ┃ kSteam 集成 (登录/查询)
Week 7-14   ┃ Rust Core 开发 (下载/解压)
Week 15-18  ┃ Kotlin 胶水层 + 集成测试
Week 19-22  ┃ 性能优化 + Beta 测试
```

### 详细里程碑

| 里程碑 | 完成标准 | 验收方式 |
|--------|---------|---------|
| M1: 接口定义完成 | 编译通过，架构文档完成 | Code review |
| M2: kSteam 登录可用 | 登录成功率 >99% | 集成测试 |
| M3: kSteam 查询可用 | Workshop API 100% 覆盖 | 单元测试 |
| M4: Rust 下载可用 | 单文件下载成功 | 集成测试 |
| M5: 性能达标 | 下载速度 >10MB/s | 基准测试 |
| M6: Beta 发布 | 崩溃率 <0.5% | 生产环境 |

---

## 团队技能要求

### 必需技能

- Kotlin 协程高级用法
- Android Jetpack 架构
- Rust 基础语法（可学习）
- JNI/UniFFI 基础

### 学习资源

1. **Rust for Android**:
   - [Mozilla UniFFI Guide](https://mozilla.github.io/uniffi-rs/)
   - [Rust and Android](https://blog.rust-lang.org/2021/09/09/Rust-1.55.0.html)

2. **kSteam 文档**:
   - [GitHub README](https://github.com/iTaysonLab/kSteam)
   - [JetiSteam 示例](https://github.com/iTaysonLab/jetisteam)

3. **性能优化**:
   - [Android Performance Patterns](https://www.youtube.com/playlist?list=PLWz5rJ2EKKc9CBxr3BVjPTPoDPLdPIFCE)

---

## 总结

### 技术栈选择理由

| 技术 | 职责 | 原因 |
|------|------|------|
| **kSteam** | 协议层 | Kotlin 原生，快速迭代，协议灵活性 |
| **Rust** | 计算层 | 性能极致，内存安全，并发优势 |
| **Kotlin** | 业务层 | 成熟稳定，Android 深度集成 |

### 核心优势

1. **性能最大化**: 关键路径用 Rust（2-3x 提升）
2. **维护成本低**: 业务逻辑保持 Kotlin
3. **灵活性高**: 协议层用 kSteam（快速适配变化）
4. **风险可控**: 逐步迁移，保留 Fallback

### 预期收益

- 下载速度提升 45%
- 内存占用降低 51%
- 崩溃率降低 60%
- 用户满意度提升 30%

---

**文档结束**
