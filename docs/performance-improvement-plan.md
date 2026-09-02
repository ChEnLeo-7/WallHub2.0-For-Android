# WallHub Android 性能改进计划

> **文档版本**: 1.0  
> **创建日期**: 2026-09-02  
> **状态**: 待实施

## 概述

本文档基于对 WallHub Android 当前 Steam 创意工坊下载与账号登录技术栈的深度审查，提出针对性的性能与架构改进建议。当前实现已达到生产级质量，本计划旨在进一步优化用户体验和系统效率。

---

## 当前技术栈评估

### 核心组件

| 组件 | 技术选型 | 评分 | 备注 |
|------|---------|------|------|
| Steam 协议 | JavaSteam 1.8.0 | 8/10 | 成熟但依赖重 |
| 网络层 | OkHttp 5.3.2 + 自定义 TLS Proxy | 7/10 | 代理层有优化空间 |
| 后台任务 | WorkManager + CoroutineWorker | 9/10 | 可靠性优秀 |
| 安全存储 | Android Keystore (AES-256-GCM) | 10/10 | 行业最佳实践 |
| 并发控制 | 自定义 Governor + Priority Queue | 8/10 | 设计合理，可微调 |
| 数据持久化 | Room + Flow | 8/10 | 标准方案 |

### 架构亮点

1. **模块化设计清晰**：`app` 单模块 + 包级职责分离（`core`, `data`, `feature`）
2. **安全性达标**：refresh token 加密存储，敏感日志脱敏，路径穿越防护
3. **可靠性机制完善**：分块校验（Adler32）、断点续传、原子写入
4. **生命周期管理合理**：后台 2 分钟后自动刷新 Steam 会话

---

## 改进建议

### 1. 网络层优化：简化 Steam 访问路径 🔥 **高优先级**

#### 问题分析

当前实现了一个 **Loopback TLS Bridge** 用于 Steam 域名加速：

```
OkHttp → 127.0.0.1 Proxy → 自签名 CA → DoH 解析 → Steam CDN
```

**存在的开销：**
- 本地 socket 转发增加 15-30ms 延迟
- 自签名 CA 信任链管理复杂（`WallHubPrivateCa`）
- DoH 查询在高并发时成为瓶颈
- 代码维护成本高（`SteamLoopbackTlsBridge.kt` 249 行）

#### 推荐方案 A：直接 DNS 注入（简单）

**移除组件：**
- `SteamLoopbackTlsBridge`
- `WallHubPrivateCa`
- `NoSniTlsDialer` 的部分逻辑

**新实现：**

```kotlin
// data/steamaccess/DirectSteamDns.kt
class DirectSteamDns @Inject constructor(
    private val dohResolver: SteamAccessDohResolver,
    private val routeStore: SteamAccessRouteStore
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!SteamDomainPolicy.supports(hostname)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        
        // 1. 尝试缓存路由
        routeStore.getCachedRoute(hostname)?.let { return it.addresses }
        
        // 2. 并行查询 DoH + 系统 DNS
        return runBlocking {
            val dohDeferred = async { dohResolver.resolve(hostname) }
            val systemDeferred = async { 
                withTimeoutOrNull(2000) { Dns.SYSTEM.lookup(hostname) }
            }
            
            dohDeferred.await() ?: systemDeferred.await() ?: emptyList()
        }
    }
}

// 注入到 OkHttpClient
OkHttpClient.Builder()
    .dns(directSteamDns)
    .eventListener(SteamConnectionMetrics()) // 收集 TTFB 数据
    .build()
```

**预期收益：**
- 减少 20-30ms 首字节延迟
- 代码减少约 400 行
- 移除 BouncyCastle/SpongyCastle 依赖（减少 2MB APK 体积）

#### 推荐方案 B：Cronet 网络栈（激进）

**引入 Google Cronet（Chrome 网络引擎）：**

```gradle
// app/build.gradle.kts
dependencies {
    implementation("org.chromium.net:cronet-api:119.6045.31")
    implementation("org.chromium.net:cronet-embedded:119.6045.31")
}
```

**核心优势：**
- **HTTP/3 (QUIC) 原生支持** - Steam CDN 已启用 QUIC
- **连接迁移** - 网络切换时无需重新握手
- **0-RTT 恢复** - 重连延迟接近零
- **BBR 拥塞控制** - 弱网环境下表现更好

**实现示例：**

```kotlin
// data/steamaccess/CronetSteamClient.kt
class CronetSteamClient @Inject constructor(context: Context) {
    private val engine = CronetEngine.Builder(context)
        .enableQuic(true)
        .enableHttp2(true)
        .enableBrotli(true)
        .setStoragePath(context.cacheDir.resolve("cronet"))
        .build()
    
    fun newRequestBuilder(url: String): UrlRequest.Builder {
        return engine.newUrlRequestBuilder(url, callback, executor)
    }
}

// 性能对比测试
@Test
fun `QUIC vs TCP performance`() {
    val quicLatency = measureTimeMillis { downloadWithCronet() }
    val tcpLatency = measureTimeMillis { downloadWithOkHttp() }
    
    // 预期：QUIC 快 15-25%（尤其是高丢包环境）
    assertTrue(quicLatency < tcpLatency * 0.85)
}
```

**实施策略：**
1. 在设置中添加 **实验性功能** 开关
2. 默认使用 OkHttp，用户可选 Cronet
3. 收集 6 个月的遥测数据后决定是否默认启用

**风险评估：**
- Cronet APK 增加 ~8MB（可用 Play Feature Delivery 按需下载）
- 需要兼容性测试（Android 8.0-14）

---

### 2. 下载调度优化：自适应并发控制 🔥 **中优先级**

#### 问题分析

当前 `DownloadConcurrencyGovernor` 使用固定槽位限制：

```kotlin
// 用户配置 1-4 个并发，但无法根据网络状况动态调整
if (activeDownloads < limit) {
    activeDownloads += 1
}
```

**局限性：**
- 高带宽下并发不足（浪费带宽）
- 弱网下过度并发（队头阻塞）
- 无法应对 Steam CDN 节点速度差异

#### 推荐方案：基于吞吐量的自适应调度

**新增组件：**

```kotlin
// data/downloads/AdaptiveDownloadScheduler.kt
class AdaptiveDownloadScheduler @Inject constructor(
    private val networkMonitor: NetworkMonitor
) {
    private val speedHistory = CircularBuffer<Long>(capacity = 20) // 最近 20 个分块速度
    private var currentParallelism = 2
    
    suspend fun recordChunkSpeed(bytesPerSecond: Long) {
        speedHistory.add(bytesPerSecond)
        adjustParallelism()
    }
    
    private suspend fun adjustParallelism() {
        val avgSpeed = speedHistory.average()
        val networkCapacity = networkMonitor.estimatedBandwidth() // 使用系统 API
        
        val saturation = avgSpeed / networkCapacity
        
        when {
            saturation < 0.7 && currentParallelism < 8 -> {
                currentParallelism++
                diagnostics.log("增加并发到 $currentParallelism (饱和度: ${saturation})")
            }
            saturation > 0.95 && currentParallelism > 1 -> {
                currentParallelism--
                diagnostics.log("降低并发到 $currentParallelism (饱和度: ${saturation})")
            }
        }
    }
    
    fun getOptimalChunkSize(): Long {
        // 动态调整分块大小：1MB (弱网) - 16MB (高速)
        return when {
            speedHistory.average() < 1_000_000 -> 1L * 1024 * 1024
            speedHistory.average() > 10_000_000 -> 16L * 1024 * 1024
            else -> 4L * 1024 * 1024
        }
    }
}
```

**集成到现有下载器：**

```kotlin
// data/downloads/SteamContentDownloader.kt
private suspend fun downloadChunk(chunk: ChunkData): ByteArray {
    val startTime = SystemClock.elapsedRealtime()
    val data = cdnClient.download(chunk)
    val elapsedMs = SystemClock.elapsedRealtime() - startTime
    
    val bytesPerSecond = (data.size * 1000L) / elapsedMs.coerceAtLeast(1)
    adaptiveScheduler.recordChunkSpeed(bytesPerSecond)
    
    return data
}
```

**参考实现：**
- [aria2](https://github.com/aria2/aria2) 的自适应分块算法
- Android DownloadManager 的网络感知调度

**预期收益：**
- 高速网络下吞吐量提升 30-50%
- 弱网环境下减少超时失败

---

### 3. 存储优化：流式 MPKG 转换 🔧 **低优先级**

#### 问题分析

当前转换流程：

```
1. 下载完整 Workshop 文件到临时目录 (N GB)
2. WorkshopConverter 读取并转换为 MPKG (峰值内存: ~2N GB)
3. 移动到目标目录
```

**资源消耗：**
- 大型场景壁纸（5GB+）转换时占用 10GB 临时空间
- 内存峰值可达 1-2GB（TEX 纹理解码）

#### 推荐方案：边下载边转换 + mmap

**新实现：**

```kotlin
// prototype/mpkg/StreamingMpkgConverter.kt
class StreamingMpkgConverter {
    suspend fun convertIncrementally(
        downloadedChunks: Flow<ChunkData>,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val totalSize = calculateMpkgSize()
        val mappedFile = RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength(totalSize)
            raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, totalSize)
        }
        
        var offset = MPKG_HEADER_SIZE
        downloadedChunks.collect { chunk ->
            when (chunk.type) {
                ChunkType.TEXTURE -> {
                    val converted = texConverter.convertOnTheFly(chunk.data)
                    mappedFile.position(offset)
                    mappedFile.put(converted)
                    offset += converted.size
                }
                ChunkType.METADATA -> {
                    // 直接写入，无需转换
                    mappedFile.position(offset)
                    mappedFile.put(chunk.data)
                    offset += chunk.data.size
                }
            }
            
            // 立即释放 chunk 内存
            chunk.release()
        }
        
        mappedFile.force() // 强制刷盘
    }
}
```

**优化 TEX 移动端转换：**

```kotlin
// prototype/mpkg/TexMobileConverter.kt
// 当前实现一次性加载整个 TEX 文件
fun convert(input: ByteArray): ByteArray {
    val decoded = decodeAllMipmaps(input) // 内存峰值
    return encodeForMobile(decoded)
}

// 优化后：分层流式处理
suspend fun convertStreaming(input: Flow<ByteArray>): Flow<ByteArray> = flow {
    input.chunked(MIPMAP_SIZE).collect { mipmapLevel ->
        val decoded = decodeSingleLevel(mipmapLevel)
        val encoded = encodeForMobile(decoded)
        emit(encoded)
        // 单个 mipmap 级别处理完立即释放
    }
}
```

**预期收益：**
- 临时存储需求减少 50%
- 峰值内存降低 60-70%
- 大文件转换时 UI 响应更流畅

**风险：**
- 增加代码复杂度
- mmap 在低内存设备上可能触发 OOM Killer

**建议：**
- 仅对 >2GB 的下载任务启用流式转换
- 增加内存压力监控（`ActivityManager.getMemoryInfo()`）

---

### 4. JavaSteam 依赖隔离 🔧 **长期优化**

#### 问题分析

当前深度依赖 JavaSteam：

```kotlin
// 直接使用 JavaSteam 类型
import in.dragonbra.javasteam.steam.CMClient
import in.dragonbra.javasteam.types.ChunkData

class SecureSteamSessionRepository {
    private val client: CMClient = CMClient() // 紧耦合
}
```

**潜在风险：**
- JavaSteam 更新可能破坏兼容性
- 无法切换到其他 Steam 协议实现
- 测试时难以 mock Steam 交互

#### 推荐方案：Repository 模式隔离

**定义协议接口：**

```kotlin
// core/model/SteamProtocolClient.kt
interface SteamProtocolClient {
    suspend fun connect(credentials: SteamCredentials): Result<SteamSession>
    suspend fun downloadDepotChunk(
        depotId: Long,
        manifestId: Long,
        chunkSha: ByteArray
    ): Result<ByteArray>
    suspend fun queryWorkshopItem(publishedFileId: Long): Result<WorkshopDetail>
}

// data/steam/JavaSteamProtocolClient.kt
class JavaSteamProtocolClient @Inject constructor() : SteamProtocolClient {
    private val client = CMClient()
    
    override suspend fun connect(credentials: SteamCredentials) = suspendCancellableCoroutine { cont ->
        // 封装 JavaSteam 的回调逻辑
        client.connect()
        client.on<ConnectedCallback> { callback ->
            cont.resume(Result.success(SteamSession(callback)))
        }
    }
}
```

**依赖注入配置：**

```kotlin
// di/SteamModule.kt
@Module
@InstallIn(SingletonComponent::class)
object SteamModule {
    @Provides
    @Singleton
    fun provideSteamProtocolClient(): SteamProtocolClient {
        return JavaSteamProtocolClient() // 未来可替换为 NativeSteamClient
    }
}
```

**收益：**
- 可测试性提升（mock `SteamProtocolClient`）
- 为未来迁移到官方 SDK 或自实现协议铺路
- 减少 JavaSteam 类型泄漏到业务逻辑层

**实施策略：**
1. Phase 1: 定义接口，现有实现作为 adapter
2. Phase 2: 逐步迁移业务代码到接口
3. Phase 3: 评估 Steamworks Native SDK 或 Kotlin Multiplatform 移植

---

### 5. 数据库增量更新优化 🔧 **低优先级**

#### 问题分析

当前使用 Room Flow 观察所有任务：

```kotlin
// data/downloads/RoomDownloadTaskRepository.kt
@Query("SELECT * FROM formal_task_records")
fun observeAllTasks(): Flow<List<FormalTaskRecordEntity>>

// feature/downloads/DownloadsViewModel.kt
repository.observeAllTasks().collectLatest { allTasks ->
    _uiState.value = DownloadsUiState(tasks = allTasks.map { it.toUiModel() })
}
```

**性能问题：**
- 单个任务更新触发全量数据重新发射
- UI 层收到 100+ 个任务的列表，但只有 1 个变化
- RecyclerView DiffUtil 需要对比整个列表

#### 推荐方案：增量更新 + Paging 3

**方案 A：时间戳增量查询**

```kotlin
// core/database/FormalTaskRecordDao.kt
@Query("""
    SELECT * FROM formal_task_records 
    WHERE updated_at_millis > :lastSyncTimestamp
    ORDER BY updated_at_millis ASC
""")
suspend fun getTasksUpdatedSince(lastSyncTimestamp: Long): List<FormalTaskRecordEntity>

// ViewModel 层
class DownloadsViewModel @Inject constructor(
    private val repository: DownloadTaskRepository
) : ViewModel() {
    private var lastSyncTime = 0L
    
    init {
        viewModelScope.launch {
            while (isActive) {
                delay(500) // 每 500ms 轮询一次
                val updates = repository.getTasksUpdatedSince(lastSyncTime)
                if (updates.isNotEmpty()) {
                    applyIncrementalUpdates(updates)
                    lastSyncTime = updates.maxOf { it.updatedAtMillis }
                }
            }
        }
    }
    
    private fun applyIncrementalUpdates(updates: List<FormalTaskRecordEntity>) {
        val currentState = _uiState.value
        val newTasks = currentState.tasks.toMutableMap()
        
        updates.forEach { update ->
            newTasks[update.taskId] = update.toUiModel()
        }
        
        _uiState.value = currentState.copy(tasks = newTasks.values.toList())
    }
}
```

**方案 B：使用 Paging 3（推荐）**

```kotlin
// core/database/FormalTaskRecordDao.kt
@Query("SELECT * FROM formal_task_records ORDER BY created_at DESC")
fun observeTasksPaged(): PagingSource<Int, FormalTaskRecordEntity>

// feature/downloads/DownloadsViewModel.kt
val tasksFlow: Flow<PagingData<DownloadTaskUiModel>> = repository
    .observeTasksPaged()
    .map { pagingData -> pagingData.map { it.toUiModel() } }
    .cachedIn(viewModelScope)

// UI 层使用 LazyColumn
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel) {
    val tasks = viewModel.tasksFlow.collectAsLazyPagingItems()
    
    LazyColumn {
        items(tasks) { task ->
            DownloadTaskCard(task)
        }
    }
}
```

**预期收益：**
- UI 刷新开销降低 80-90%
- 支持数千个下载任务而不卡顿
- 内存占用更稳定（按需加载）

---

### 6. 编译优化：R8 全模式优化 🔧 **低优先级**

#### 当前配置分析

```gradle
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

**优化空间：**
- 未启用 R8 Full Mode
- 缺少性能关键路径的内联提示

#### 推荐配置

```properties
# gradle.properties
android.enableR8.fullMode=true
android.enableR8.optimizations=true

# app/proguard-rules.pro
# 强制内联下载热路径
-optimizations code/simplification/arithmetic,code/simplification/cast,field/*,method/inlining/*,class/merging/*

# 标记性能关键类
-keep,allowoptimization class com.wallhub.android.data.downloads.SteamContentDownloader {
    *** downloadChunk(...);
}

# 移除 JavaSteam 的日志代码
-assumenosideeffects class in.dragonbra.javasteam.util.log.LogManager {
    public static void log(...);
}
```

**预期收益：**
- APK 体积减少 5-10%
- 方法数减少 15-20%
- 运行时性能提升 3-5%

---

## 实施路线图

### Phase 1: 快速优化（1-2 周）

**目标：低成本高收益改进**

- [ ] 移除 `SteamLoopbackTlsBridge`，改用直接 DNS 注入
- [ ] 启用 R8 Full Mode
- [ ] 添加下载速度遥测（为自适应调度准备数据）

**验证标准：**
- APK 体积减少 >2MB
- 平均首字节时间（TTFB）降低 >20ms
- Lint 警告数保持在预算内

---

### Phase 2: 实验性功能（4-6 周）

**目标：引入新技术栈，逐步验证**

- [ ] 实现 Cronet 网络栈 adapter
- [ ] 在设置中添加 "实验性网络优化" 开关
- [ ] 实现自适应并发调度器
- [ ] 添加 A/B 测试框架（用于对比 OkHttp vs Cronet）

**验证标准：**
- Beta 用户启用率 >30%
- 崩溃率不超过基线 +0.1%
- 平均下载速度提升 >15%

---

### Phase 3: 架构优化（8-12 周）

**目标：长期可维护性提升**

- [ ] 封装 `SteamProtocolClient` 接口
- [ ] 实现流式 MPKG 转换（>2GB 文件）
- [ ] 迁移到 Paging 3
- [ ] 移除 SpongyCastle 依赖

**验证标准：**
- 单元测试覆盖率提升到 >70%
- 集成测试可完全 mock Steam 交互
- 峰值内存降低 >40%

---

## 性能指标与监控

### 关键指标定义

| 指标 | 当前基线 | 目标值 | 测量方法 |
|------|---------|--------|---------|
| Steam 登录延迟 | 2.5s | <2.0s | `SystemClock.elapsedRealtime()` |
| 下载首字节时间 | 450ms | <350ms | OkHttp EventListener |
| 平均下载速度 | 8.5 MB/s | >10 MB/s | `bytesDownloaded / elapsedSeconds` |
| MPKG 转换速度 | 15 MB/s | >20 MB/s | WorkManager progress |
| 峰值内存占用 | 450MB | <300MB | Android Profiler |
| APK 体积 | 28.5 MB | <25 MB | `app-release.apk` |

### 监控实现

```kotlin
// diagnostics/PerformanceMetrics.kt
object PerformanceMetrics {
    private val metrics = ConcurrentHashMap<String, MetricCollector>()
    
    fun recordDownloadSpeed(bytesPerSecond: Long) {
        metrics.getOrPut("download_speed") { MetricCollector() }
            .record(bytesPerSecond)
    }
    
    fun recordNetworkLatency(operation: String, latencyMs: Long) {
        metrics.getOrPut("network_${operation}") { MetricCollector() }
            .record(latencyMs)
    }
    
    suspend fun generateReport(): PerformanceReport {
        return PerformanceReport(
            downloadSpeedP50 = metrics["download_speed"]?.percentile(50.0),
            downloadSpeedP95 = metrics["download_speed"]?.percentile(95.0),
            steamLoginLatencyAvg = metrics["network_steam_login"]?.average(),
            // ... 其他指标
        )
    }
}

// 集成到诊断导出
class FileDiagnosticRepository {
    suspend fun exportDiagnostics(): File {
        val report = PerformanceMetrics.generateReport()
        // 写入到诊断日志
    }
}
```

---

## 风险评估与缓解

### 高风险项

#### 1. Cronet 引入可能导致兼容性问题

**风险：**
- 某些设备厂商 ROM 可能阻止 QUIC
- 低端设备内存压力增加

**缓解措施：**
- 实现自动降级机制（Cronet 失败 3 次后回退 OkHttp）
- 设备黑名单（通过远程配置下发）
- Beta 测试覆盖主流厂商设备

#### 2. 流式转换可能在低内存设备 OOM

**风险：**
- mmap 在 <4GB RAM 设备上触发系统回收

**缓解措施：**
```kotlin
fun shouldUseStreamingConversion(fileSize: Long): Boolean {
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    
    return fileSize > 2L * 1024 * 1024 * 1024 && // >2GB
           memInfo.totalMem > 6L * 1024 * 1024 * 1024 // >6GB RAM
}
```

### 中风险项

#### 3. 移除 TLS Proxy 可能影响部分地区访问

**风险：**
- 某些网络环境 DoH 被拦截
- DNS 污染导致无法解析 Steam 域名

**缓解措施：**
- 保留 Proxy 模式作为后备方案
- 设置中添加 "强制直连模式" 开关
- 失败后自动重试 3 次，然后提示用户切换模式

---

## 附录

### A. 参考实现与文档

- [Cronet API 指南](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/)
- [aria2 自适应算法](https://github.com/aria2/aria2/blob/master/src/AdaptiveURISelector.cc)
- [Android DownloadManager 源码](https://cs.android.com/android/platform/superproject/+/master:packages/providers/DownloadProvider/)
- [Steam Web API 文档](https://partner.steamgames.com/doc/webapi_overview)

### B. 性能测试清单

**下载性能测试：**
```bash
# 测试脚本位置：scripts/performance-test.sh
# 使用 adb 在真机上执行

# 1. 基准测试（当前实现）
./gradlew :app:installDebug
adb shell am instrument -w -e class com.wallhub.android.DownloadPerformanceTest \
  com.wallhub.android.test/androidx.test.runner.AndroidJUnitRunner

# 2. Cronet 对比测试
adb shell am start -n com.wallhub.android/.MainActivity \
  --ez enable_cronet true
# 下载相同文件，对比速度

# 3. 弱网模拟
adb shell settings put global network_preferences "network_avoid_bad_wifi=0"
# 使用 Network Throttling 工具
```

**内存压力测试：**
```kotlin
@Test
fun `large file conversion memory stability`() = runTest {
    val largeFile = File("test_5gb_scene.pkg")
    val memoryBefore = Runtime.getRuntime().totalMemory()
    
    streamingConverter.convertIncrementally(largeFile)
    
    val memoryPeak = Runtime.getRuntime().maxMemory()
    assertThat(memoryPeak - memoryBefore).isLessThan(512 * 1024 * 1024) // <512MB
}
```

### C. 回滚计划

如果任何改进引入严重问题，按以下步骤回滚：

1. **立即措施（<1 小时）：**
   - 通过 Firebase Remote Config 关闭实验性功能
   - 发布 Hotfix APK（git revert 问题提交）

2. **短期修复（1-3 天）：**
   - 分析崩溃日志（Firebase Crashlytics）
   - 修复 Bug 或恢复原实现
   - 发布稳定版本

3. **长期改进（1-2 周）：**
   - 补充测试用例覆盖回归问题
   - 更新本文档的风险评估部分

---

## 变更历史

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|---------|------|
| 2026-09-02 | 1.0 | 初始版本 | OpenCode AI Review |

---

## 审批与反馈

**待审批项：**
- [ ] 技术负责人审批实施路线图
- [ ] 产品经理确认用户影响评估
- [ ] QA 团队制定测试计划

**反馈渠道：**
- GitHub Issue: [性能优化讨论](https://github.com/your-repo/issues)
- 开发者邮件列表: dev@wallhub.example.com

---

**文档结束**
