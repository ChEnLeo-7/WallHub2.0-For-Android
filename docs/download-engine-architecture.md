# WallHub 下载引擎架构设计

状态：设计提案  
适用范围：WallHub for Android 与 Steam Content SDK 的 Workshop 内容下载  
目标：以较小、稳定、可维护的系统同时满足低启动延迟、高带宽利用、受控资源占用、多项目并发、暂停恢复和崩溃恢复。

---

## 1. 背景

当前下载链路跨越 UI、ViewModel、Room、WorkManager、Kotlin SDK Adapter、JNI/C ABI、Rust Session、内容解析、CDN、Chunk 解码、Checkpoint 和文件发布。每一层都承担了一部分状态与生命周期，导致以下问题难以统一解决：

- 点击后需要经过多段串行解析，冷路径或半失效 Session 可显著延迟首个内容字节；
- 每个下载任务容易独立持有 Worker、Session、Socket 和 Buffer，难以实施全局资源上限；
- Android、WorkManager 与 Native Task 可能同时持有相似但不完全一致的状态；
- Pause、Cancel、Retry、进程死亡和 Session 重建涉及多个所有者；
- 固定 Chunk 并发不能适配不同设备、网络、CDN 和存储速度；
- UI 的“解析中”“下载中”、进度和网速没有严格、统一的语义。

本设计不以继续扩展现有调用链为前提，而是定义一个新的下载子系统，再通过迁移 Adapter 逐步替换旧实现。

---

## 2. 产品目标与 SLO

### 2.1 核心目标

1. **点击立即反馈**：点击下载后，当前 UI 帧内显示任务；持久化接收结果应在 250ms 内可见。
2. **尽快开始真实传输**：在网络可用、凭据有效、Steam 可达的健康条件下，Warm 路径首个 CDN 内容字节 p75 不超过 2 秒，Cold 路径 p95 不超过 5 秒。
3. **尽量利用用户带宽**：稳定传输阶段，聚合有效吞吐应达到网络或存储中较慢一方基准的 85% 以上。
4. **内存和资源有硬上限**：排队任务数量不得线性增加线程、Socket、FD 或大 Buffer。
5. **稳定并发多个 Wallpaper**：大项目不能长期独占全部连接，小项目不能饥饿；空闲容量可以被任一活跃项目借用。
6. **恢复可信**：只把已经验证、写入并持久化 Checkpoint 的字节称为可恢复进度。
7. **生命周期单一所有权**：Session、Scheduler、网络资源、Buffer 和文件写入都只有一个明确所有者。

### 2.2 建议 SLO

| 指标 | 目标 |
| --- | --- |
| 点击到 UI 出现任务 | 同一 UI 帧，p95 ≤ 100ms |
| 点击到持久化 Receipt | p95 ≤ 250ms |
| Warm 点击到首个 CDN 请求 | p95 ≤ 500ms |
| Warm 点击到首个内容字节 | p75 ≤ 2s |
| Cold 点击到首个内容字节 | p95 ≤ 5s |
| 状态变化到 UI 可见 | p95 ≤ 200ms |
| 活跃进度/网速新鲜度 | ≤ 500ms |
| Pause 请求到 Durable Paused | p95 ≤ 1–2s |
| Cancel 到不再产生网络/文件活动 | p95 ≤ 1s |
| 进程启动后的任务恢复 | p95 ≤ 2s（正常任务规模） |
| 多项目可运行状态下的调度等待 | ≤ 2s |
| 长时间运行中的无界 RSS/线程/FD 增长 | 0 |

Cold 路径不包括需要用户手工登录、Steam 上游不可用、设备离线或 Android 禁止启动后台执行的情况；这些情况必须以明确 Blocker 展示，而不是伪装成“解析中”。

---

## 3. 设计原则

### 3.1 一个深模块

Android 业务代码只依赖 `WorkshopDownloads`。它不应了解：

- Steam CM Session；
- App、Depot、Manifest 的解析细节；
- Depot Key、CDN Token、Signed URL；
- Chunk、压缩格式、重试次数；
- Native Handle；
- Staging、Journal 和 Checkpoint 路径；
- WorkManager 的具体任务名称；
- 并发线程和连接数量。

复杂性必须集中在下载模块内部，而不是通过更多参数和回调泄漏给调用方。

### 3.2 一个逻辑引擎

运行时只存在一个逻辑下载引擎：

- 一个全局 Scheduler；
- 一个 Session Supervisor；
- 一个连接池体系；
- 一个 Memory Governor；
- 一组有界 Fetch/Decode/Write Worker；
- 一个状态协调器。

它可以被 Android 杀死，但下一次启动必须根据持久状态恢复。正确性来自持久协议，而不是依赖进程永远存活。

### 3.3 不创建 Actor 大爆炸

本设计采用 Actor 的“单一状态所有者和消息驱动”思想，但初始实现不要求每个 Chunk 或每个内部步骤都是独立 Actor。

推荐只保留三个串行状态所有者：

1. `JobCoordinator`：命令、状态转换和恢复；
2. `Scheduler`：公平性和资源 Lease；
3. `SessionSupervisor`：Session Generation 和授权生命周期。

Fetch、Decode、Write 是固定 Worker Pool，不是无限创建的 Actor。

### 3.4 不建立插件系统

内部只有在确实存在两个 Adapter 时才建立 Seam，例如：

- Production Fetcher 与 Fault-Test Fetcher；
- Real Clock 与 Virtual Clock；
- File Storage 与 Fault-Injection Storage；
- Android Credential Adapter 与 Test Credential Adapter。

不把每个内部函数都公开成可替换接口。

---

## 4. 外部 Interface

```kotlin
interface WorkshopDownloads {
    /** 持久记录下载意图后返回；不会等待 Steam 或网络。 */
    suspend fun enqueue(
        item: WorkshopItemId,
        policy: DownloadPolicy = DownloadPolicy.Auto,
    ): EnqueueReceipt

    /** 持久记录用户期望；实际生效状态通过 observe() 返回。 */
    suspend fun control(
        id: DownloadId,
        action: DownloadControl,
    ): ControlReceipt

    /** 首先返回持久快照，随后返回合并后的实时更新。 */
    fun observe(
        query: DownloadQuery = DownloadQuery.All,
    ): Flow<List<DownloadSnapshot>>
}
```

### 4.1 Interface 语义

#### `enqueue`

返回前保证：

- Job ID 已确定；
- 意图已经持久化；
- UI 可以立即观察到任务；
- 进程在下一刻死亡也不会丢失任务；
- 对同一账号、Workshop Item 和版本策略的重复调用是幂等的。

它不等待网络、Session、Manifest 或首个 Chunk。

#### `control`

`Pause` 成功表示“Pause 意图已经持久化”，而不是“所有 I/O 已停止”。UI 应观察：

```text
Transferring → Pausing → Paused
```

只有 `Paused` 才表示：

- 不再授予新工作；
- 当前 Job 没有活动 Lease；
- 需要保留的进度已经写入持久边界。

`Cancel` 同理：

```text
Transferring → Cancelling → Cancelled
```

清理文件可以在 `Cancelled` 后通过 Tombstone 异步完成。

#### `observe`

- 首先发出当前持久快照；
- 高频进度可以合并；
- Phase、Blocker、错误和终态不得被进度覆盖；
- 慢消费者不能制造无界事件队列；
- 每个快照包含单调递增 `revision`，消费者可忽略过期值。

---

## 5. 总体结构

```text
Android UI / Notification
          │
          │ enqueue / control / observe
          ▼
    WorkshopDownloads
          │
          ▼
 Android DownloadHost
 ├── Durable Job Store
 ├── OS Lifecycle Adapter
 └── JNI Event Dispatcher
          │
          ▼
 Rust DownloadEngine
 ├── JobCoordinator
 ├── Scheduler
 ├── SessionSupervisor
 ├── PreparationRegistry
 ├── OriginPool
 ├── MemoryGovernor
 ├── FetchPool
 ├── DecodeVerifyPool
 ├── StagingWriter
 ├── DurabilityCommitter
 ├── AtomicPublisher
 └── ProjectionHub
```

### 5.1 Android 与 Rust 的权威划分

为避免双重真相，职责不能重叠：

- **Android Job Store** 是用户意图、Job 生命周期和 UI Projection 的权威；
- **Rust Journal/Staging** 是 Chunk 级可恢复数据的权威；
- Android 不推测哪些 Chunk 已持久化；
- Rust 不自行决定用户期望是 RUN、PAUSE 还是 CANCEL；
- `JobCoordinator` 只在收到 Rust 的 Durability Receipt 后推进 Android 的 `durableBytes`。

跨存储一致性采用明确的 Command/Receipt 协议，而不是依赖两个写入恰好同时成功：

- Android Job Store 为每条命令分配单调递增 `commandRevision`，在同一事务中写入 Command Outbox 和 UI Projection；
- JNI 向 Rust 发送 `(jobId, commandRevision, command)`；Rust 对同一 Revision 幂等；
- Rust 每次持久边界产生单调递增 `receiptSequence`，Receipt 包含 `jobId`、有效 Journal Sequence、`durableBytes`、Phase 和已应用的 Command Revision；
- Android 在事务中应用 Receipt 并保存 `lastReceiptSequence`，重复 Receipt 是无操作，发现序号缺口则请求完整 Native Snapshot；
- Android 崩溃在 Journal Commit 之后、Projection 更新之前时，重启后从 Rust 的有效 Journal 前缀重建 `durableBytes`；
- Android 已持久化 Pause/Cancel 但尚未送达 JNI 时，Outbox 会在 Host 重启后重放；
- Rust 不把 Android Receipt Ack 当作 Chunk 持久性的组成部分，因此 UI Store 落后只影响展示，不影响恢复正确性。

第一版继续使用 Android Job Store + Rust Chunk Journal，是为了复用 Android 生命周期和 UI 查询能力；两者通过上述单调协议分工。若未来将完整 Job Store 下沉到 Rust SQLite，应作为一次明确迁移完成，不能长期维护两份可修改的 Job 状态。

---

## 6. 状态模型

不要使用一个包含所有组合的巨大 Enum。状态拆分为三个正交维度。

### 6.1 Desired Intent

```text
RUN
PAUSE
CANCEL
```

### 6.2 Execution Phase

```text
ACCEPTED
QUEUED
PREPARING
READY
TRANSFERRING
VERIFYING
COMMITTING
COMPLETED
FAILED
CANCELED
```

### 6.3 Blocker

```text
NONE
OFFLINE
SESSION_REFRESH
AUTH_REQUIRED
RATE_LIMITED
BACKOFF
NO_SPACE
STORAGE_PERMISSION
RESOURCE_WAIT
THERMAL
BATTERY_POLICY
INTERNAL
```

例如：

```text
desiredIntent = RUN
phase = PREPARING
blocker = SESSION_REFRESH
```

UI 应显示“正在恢复 Steam 会话”，而不是泛化成“解析中”。

```text
desiredIntent = RUN
phase = READY
blocker = RESOURCE_WAIT
```

表示内容计划已准备完成，只是在等待全局传输资源。

### 6.4 状态转换

```text
Enqueue
  → ACCEPTED
  → QUEUED
  → PREPARING
  → READY
  → TRANSFERRING
  → VERIFYING
  → COMMITTING
  → COMPLETED
```

可恢复失败：

```text
任意非终态
  → blocker = BACKOFF / OFFLINE / SESSION_REFRESH
  → 条件恢复
  → QUEUED / PREPARING / READY
```

暂停：

```text
RUN + 非终态
  → desiredIntent = PAUSE
  → Pausing（派生 UI 状态）
  → 停止新 Lease
  → 收敛当前 I/O
  → 完成持久批次
  → Paused
```

取消：

```text
非终态
  → desiredIntent = CANCEL
  → 写入 Tombstone
  → 停止新 Lease并收敛 I/O
  → CANCELED
  → 异步清理
```

持久 Job 还包含：

```text
desiredIntentRevision
appliedIntentRevision
quiescedIntentRevision
```

- `Pausing` 表示 `desiredIntent = PAUSE` 且 `quiescedIntentRevision < desiredIntentRevision`；
- `Paused` 表示 Pause Revision 已应用、经过文件/Journal Reconcile 后活动 Lease 为零，并已持久写入 `quiescedIntentRevision`；
- 进程重启后不能使用内存中的“Lease 为零”作为证据，必须先废弃旧 Engine Epoch 的 Lease、对账 Staging/Journal，再确认 Quiesced；
- Cancel 接收与 I/O 收敛分开计时：Command Ack 目标为 100ms 内；Socket/Decode/Write 应可协作取消；正在执行的系统 `fsync` 可能不可中断，因此“1 秒内完全静止”是尽力 SLO，不是硬正确性保证；终态只能在该 Job 的 Writer Fence 完成后发布。

`COMPLETED`、`CANCELED` 和不可重试 `FAILED` 不允许回退。Retry 创建新 Attempt，而不是反向修改历史终态。

---

## 7. Android 运行模型

### 7.1 DownloadHost

推荐使用同进程的 Started + Bound Host：

- Bound：UI 前台时提供低延迟命令和热进度；
- Started：UI 离开后允许继续下载；
- 被杀死后由持久 Job Store 恢复；
- Host 不是正确性来源，持久状态才是。

Android 版本适配封装在 Host 内，并在实现前固定执行矩阵：

| 场景 | 执行宿主 | 失败/时限后的动作 |
| --- | --- | --- |
| UI 前台点击 | Bound Service 立即接收命令，并在平台允许时提升为 `dataSync` Foreground Service | 提升失败则保留 Durable Job，显示 `POLICY`，调度合法的系统 Job |
| 支持且满足条件的 User-Initiated Data Transfer | UIDT `JobService` 承载同一个 Engine Epoch | `onStopJob` 先停止 Admission、Checkpoint，再持久化下一次唤醒 |
| 普通后台恢复 | WorkManager/JobScheduler 只启动 Reconcile Slice | 若后台禁止启动 FGS，不强行启动；保持 Job 持久化并等待用户前台或下一合法窗口 |
| 活跃 FGS 达到平台时限 | Host 请求引擎 Yield：停止新 Lease、完成当前 Durability Batch、关闭 Session Idle Lease | 保存 Retry/Resume 条件并重新调度合法执行窗口 |
| Boot/Package Replaced | 安排一次 Reconcile，不直接假设可以启动长时 FGS | 恢复 Projection、清理 Tombstone、等待约束满足 |

UIDT、Foreground Service 和 WorkManager 是三个 Android Host Adapter，共用同一个 Job Store 和 Rust Engine Interface；任何时刻最多一个 Adapter 持有 Engine Epoch。Host 必须支持安全 Checkpoint、Yield 和重新唤醒，而不能假设 Started Service 可以无限后台运行。

### 7.2 WorkManager

WorkManager 只负责：

- 重启/进程死亡后唤醒；
- Boot 或 Package Update 后 Reconcile；
- 网络约束恢复；
- Backoff 到期；
- 延迟清理。

禁止每个 Wallpaper 创建一套完整 WorkManager 下载执行链。WorkManager Worker 只能唤醒同一个 DownloadHost，不能创建第二个 Scheduler、第二个 Session 或第二个 Native Engine。

### 7.3 Notification

通知栏 Pause、Resume、Cancel 必须提交与 UI 相同的持久命令，不能直接修改 Service 内存状态。

---

## 8. Session 与 Preparation

### 8.1 SessionSupervisor

整个引擎共享一个账号 Session 状态机：

```text
DISCONNECTED
→ CONNECTING
→ AUTHENTICATING
→ HEALTHY(generation)
```

旁路状态：

```text
SUSPECT
REFRESHING
BACKOFF
NEEDS_USER
CLOSED
```

不能只使用 `Authenticated` 判断 Session 健康。Session 还应跟踪：

- 最近一次成功请求时间；
- 最近一次 Heartbeat/Pump；
- 连续协议超时；
- 当前 Transport Generation；
- 是否允许接收新的交互式请求。

所有短期授权材料都绑定 `sessionGeneration`。Generation 失效后：

1. 停止授予新的旧 Generation 工作；
2. 旧 Completion 通过 Generation Fence 被拒绝；
3. 受影响 Job 进入 `SESSION_REFRESH`；
4. 全局只执行一次重连；
5. 重新获取短期授权；
6. 从 Durable Checkpoint 继续。

Job 无权独立登录、重连或关闭 Session。

Job 的持久账号范围使用不可逆的本地 Account Scope ID，而不是显示名称或 Secret。去重 Key 为：

```text
(accountScopeId, workshopItemId, destinationPolicy, requestedVersionPolicy)
```

`Auto` 只在 Preparation 开始时解析为具体 Manifest，随后该 Attempt 固定 Manifest。重新 Enqueue 时：若当前 Completed Generation 仍满足 Version Policy，可直接返回；若解析到新 Manifest，则创建新 Attempt/Generation。切换或退出账号后，属于其他 Account Scope 的 Job 进入 `AUTH_REQUIRED`，绝不能借用当前账号的 Session 执行。

### 8.2 交互式超时

下载热路径不能使用单一 60 秒服务超时。应拆分：

```text
DNS Timeout
Connect Timeout
TLS Timeout
Headers Timeout
First Byte Timeout
No Progress Timeout
Resolve/ProductInfo Timeout
Payload Deadline
```

Workshop Resolve/Product Info 的交互式 Deadline 建议为 3–5 秒。失败后：

- Session 标记为 `SUSPECT`；
- 单飞重连；
- 原解析自动重试一次；
- 不立即把 Job 写成终态失败。

### 8.3 PreparationRegistry

相同内容的 Preparation 使用 Single Flight，避免并发项目重复执行：

- Workshop Details；
- App/Depot/Manifest Identity；
- Product Info；
- Manifest；
- Content Directory；
- Depot Key；
- CDN Authorization；
- Download Plan。

安全、不可变的材料可以按严格 Key 缓存：

```text
account scope（如果授权相关）
app/depot/manifest
platform/language/options
parser/schema version
```

短期 Token、Signed URL 和 Session Handle 只能在内存中，不能进入 Job Store。

### 8.4 预热

详情页可发送 Hint，但预热仍由模块内部决定。优先预热：

1. Session Health；
2. Workshop/App/Depot/Manifest Identity；
3. Manifest 和 Download Plan；
4. DNS 与非敏感连接；
5. 最近 CDN Origin Health。

不预先申请无关内容授权，不允许无限缓存。

---

## 9. 全局调度

### 9.1 Latency Lane

新点击任务拥有一次受控启动优先权：

- 一个 Metadata Permit；
- 一个 Preparation Permit；
- 一个首次 Fetch Permit。

原则：

> 每个新进入 Active Cohort 的项目先获得一个网络 Stream，任何项目才能持续获得更多 Stream。

Scheduler 在全局 Fetch Budget 内保留一个 Startup Slot；Bulk Job 不能占用该槽。若没有新任务，Startup Slot 可临时借给 Bulk，但 Lease 设有较短 Service Quantum，到期后不再续租，使新任务能在 Chunk/请求边界收回容量。网络系统调用和单次响应仍可能超过两秒，因此“可运行任务调度等待 ≤2s”只统计 Scheduler 可控的 Lease 等待，不包含已经发出的有界请求完成时间。

已有大项目不会无限阻止新点击任务发出首个请求。

### 9.2 Active Cohort

排队任务只占数据库记录。只有 Active Cohort 持有 Session 引用、网络工作、Buffer 和文件句柄。

```text
activeLimit = min(
    policyMaxActiveJobs,
    effectiveNetworkSlots,
    memoryBudget / perJobMemoryFloor
)
```

推荐初始值：2–3 个活跃项目。

Cohort 应具有一定粘性，避免频繁切换破坏连接和缓存局部性。若有其他任务等待，长任务可在固定服务窗口后于 Chunk 边界轮换；阻塞或暂停任务立即离开 Cohort。

### 9.3 Weighted Deficit Round Robin

多项目公平调度按预计压缩字节计费，而不是按 Chunk 数量计费。

每轮：

1. 为每个可运行 Job 增加 Quantum；
2. 如果下一 Chunk 的预计成本不超过 Deficit，则授予一个 Lease；
3. 扣除预计成本；
4. 完成后用实际字节校正；
5. 每轮每个 Job 先获得最多一个 Lease。

建议权重：

```text
普通任务 = 1
用户显式前台任务 = 2
```

权重不可无限增加。Aging 防止长期饥饿。其他任务阻塞时，活跃任务可借用空闲容量，以保证总带宽利用率。

---

## 10. 自适应并发

用户不应直接配置“48 个 Chunk”。产品级选项应是：

```text
AUTO
BATTERY_SAVER
MAX_SPEED
```

内部初始配置建议：

| 资源 | 建议初始值 |
| --- | ---: |
| Active Jobs | 2–3 |
| Global Fetches | 4–6 |
| Per Job Fetches | 1–2 |
| Per Origin Fetches | 2–4 |
| Decode Workers | `max(1, min(4, cores - 1))` |
| Writers | 1–2 |

每 1–2 秒评估：

- 聚合 Goodput；
- 每 Origin RTT 和 TTFB；
- Timeout/Reset/429/5xx；
- Decode Queue；
- Writer Queue 和 Sync 延迟；
- Buffer 压力；
- CPU；
- Thermal；
- 网络类型和 Metering；
- Battery Policy。

增大条件：

```text
吞吐仍上升
且错误率低
且下游无积压
且内存/温度允许
→ +1 Fetch Slot
```

减小条件：

```text
吞吐下降或边际收益消失
或 RTT/错误/429 上升
或 Decode/Write/Memory/Thermal 出现压力
→ 并发乘 0.5～0.75
```

使用 Hysteresis 和 Cooldown，避免频繁振荡。硬资源上限永远优先于自适应策略。

---

## 11. 内存与 Backpressure

### 11.1 按字节而不是 Chunk 数量管理

建议 Native Payload Budget：

- 前台默认 64MiB；
- 后台默认 32MiB；
- 低内存设备 16–32MiB；
- 根据 Android Memory Class 和实测调整。

建议分区：

| Pool | 比例 |
| --- | ---: |
| Encoded/Network | 35% |
| Decoded/Write | 45% |
| Decode Workspace | 10% |
| Metadata/Control Reserve | 10% |

### 11.2 Buffer Lease

```text
EncodedLease
DecodedLease
WorkspaceLease
```

Permit 随 Lease 移动，Buffer 归还后 Permit 自动释放。

低复制路径：

```text
Socket
→ EncodedLease
→ 原地解密（允许时）
→ DecodedLease（压缩格式需要）
→ Checksum
→ Positioned Write
→ Buffer Pool
```

禁止：

- Stage 间无必要 `Vec<u8>` Clone；
- Chunk Bytes 跨 JNI；
- 整文件载入内存；
- Event/Log 携带 Payload；
- 队列无限持有已完成 Chunk。

### 11.3 Backpressure

```text
Writer 变慢
→ Decoded Pool 占满
→ Decode 停止
→ Encoded Pool 占满
→ Fetch Admission 停止
```

网络并发不能绕过 Memory Permit。控制消息和 Journal 使用独立小型 Reserve，避免 Payload 压力阻塞 Pause/Cancel/Terminal。

### 11.4 超大 Chunk 与磁盘预算

每个 Chunk 在进入 Scheduler 前必须用 checked `u64` 验证：Encoded Size、Decoded Size、Workspace、目标 Offset 和响应体上限。

- 正常 Chunk 必须能取得 Encoded + Decoded + Workspace Permit 后才能发请求；
- 单个合法 Chunk 大于普通 Job 子预算时，可以独占一个预留的 Oversized Lane，但仍不能超过全局 Hard Maximum；
- 超过 Hard Maximum 的 Manifest 在网络请求前以 `UnsupportedContent` 拒绝；
- 支持流式解码的格式可以使用有界 Spill-to-Staging Adapter；不支持流式解码的格式不得临时突破内存上限；
- HTTP Body 超过 Manifest 声明或配置上限时立即终止。

Admission 同时执行磁盘 Reservation。Reservation 至少覆盖：当前 Generation 剩余逻辑字节、一个未提交 Durability Batch、Publication Marker，以及替换期间临时保留的上一代。多项目 Reservation 总量不能超过可用空间减安全水位。Cancel、Failed、旧 Generation 和 Export 使用明确的清理/回收优先级；没有 Reservation 的任务保持 `NO_SPACE`，不能边下边赌空间。

---

## 12. 数据流管线

```text
Resolve/Auth
→ Manifest/Plan
→ Cache Probe
→ Fetch
→ Decrypt/Decompress
→ Verify
→ Positional Write
→ Durability Batch
→ File Verify
→ Atomic Publish
```

内部 Stage：

- `Resolver`
- `Fetcher`
- `DecoderVerifier`
- `StagingWriter`
- `DurabilityCommitter`
- `Publisher`

Scheduler 是唯一可以驱动 Stage 和授予资源 Lease 的模块。Stage 不允许自行创建新的下游工作。

网络连接池按以下维度隔离：

```text
scheme + host + port + proxy + authorization scope
```

支持时使用 HTTP/2 Multiplex；否则使用有界 HTTP/1.1 Keep-Alive。不能假设所有 Steam CDN 都支持 H2。

### 12.1 多级依赖

Preset/Scene 依赖建模为有界 DAG，而不是递归启动新的外部 Job：

- 一个用户 Job 拥有一个 `planRevision` 和一张持久依赖图；
- Node Key 使用 Workshop/Manifest Identity；同一 Node 在一张图中只准备和下载一次；
- 建图时执行环检测，并限制最大深度、Node 数和总逻辑字节；
- Scheduler 把 Node 的 Chunk 当作同一个 Job 的工作单元，因此共享 Job 权重和资源预算，不产生额外 Session/Scheduler；
- 父内容只有在所有 Required Node 完成并成功映射后才能进入 COMMITTING；可选依赖必须在 Manifest/项目模型中明确标记；
- 任一 Required Node 的永久失败使整个 Job 失败，并携带不含 Secret 的 Dependency Path；
- Pause/Cancel 作用于整张图；共享 Content Object 通过引用计数保留，不因一个 Job Cancel 而删除其他 Job 正在使用的内容；
- `totalLogicalBytes` 是 DAG 中去重后的 Required Node 总量，进度不得重复计算共享 Node。

第一版如果不能实现上述 DAG，不应宣称支持多级依赖；应在 Preparation 阶段明确返回 `UnsupportedContent`。

---

## 13. 持久性与恢复

### 13.1 Chunk 持久性不变量

只有满足以下顺序后，Chunk 才计入 `durableBytes`：

1. 数据解码并校验；
2. 写入 Staging；
3. 同步涉及的 Staging 文件；
4. 写入 Checkpoint/Journal；
5. 同步 Journal；
6. 发布 Durability Receipt；
7. 更新 UI Projection。

允许磁盘领先于 Journal，因为最坏只会安全重下；禁止 Journal 领先于磁盘。

### 13.2 Group Commit

为兼顾吞吐和可靠性，建议按以下任一条件提交：

```text
累计 4–8MiB
或经过 100–250ms
或收到 Pause/Cancel/Shutdown
```

一次 Batch：

1. 写入多个已验证 Chunk；
2. 对本批涉及的文件执行一次同步；
3. 批量写 Journal；
4. 同步 Journal；
5. 更新 `durableBytes`。

崩溃最多重下一个尚未 Commit 的 Batch，不会高报可恢复进度。

### 13.3 Journal

Journal 必须：

- 带版本；
- 绑定 App/Depot/Manifest 和 Manifest Digest；
- 使用 Sequence；
- 使用 Length Prefix 和 CRC；
- 每条记录包含 Chunk Identity、文件、Offset、长度和校验值；
- Torn Tail 可截断；
- 有效前缀中的身份矛盾必须使 Checkpoint 失效，而不是猜测。

长期运行时可压缩为 Snapshot + Short Journal Tail。

### 13.4 进程恢复

启动时：

1. 取得唯一 Engine Epoch/Lock；
2. 读取未完成 Job；
3. 旧 Epoch 的 Completion 全部无效；
4. 校验 Journal 与 Staging；
5. 丢弃未验证尾部；
6. 恢复 Retry Timer；
7. 继续 Cancel Tombstone 清理；
8. 重建 Session；
9. 重新进入 Scheduler。

不恢复旧 Native Handle、旧 Socket 或旧 Session。

---

## 14. 文件发布

安装身份与 Attempt 身份必须分离。稳定安装 Key 建议为：

```text
(accountScope, workshopItemId, destinationPolicy)
```

一个安装可以有多个 Download Attempt 和多个 Manifest Generation，但只有一个 Active Generation。推荐布局：

```text
install-<stable-key>/
├── generations/
│   ├── manifest-41/
│   └── manifest-42.tmp/
├── active-generation.a
├── active-generation.b
└── publication-journal
```

`active-generation` 使用双槽或等价的版本化小文件，包含 Generation ID、Manifest Digest、Sequence 和 CRC；更新时写入非活动槽、同步文件、再通过最高有效 Sequence 选择当前代，避免指针文件自身 Torn Write。

完成流程：

1. 所有文件和 Chunk 校验通过；
2. 同步文件；
3. 写入并同步 Completion Marker；
4. 同步 Generation 目录；
5. 原子 Rename 临时 Generation；
6. 原子切换 Active Generation；
7. 同步父目录；
8. 写入 `COMPLETED`；
9. 延迟删除旧 Generation。

`publication-journal` 记录 `Prepared → GenerationRenamed → PointerSwitched → JobCommitted`。恢复规则固定：

- Marker 已同步但 Generation 尚未 Rename：验证后继续 Rename，或删除无效临时代；
- Generation 已 Rename、Pointer 未切换：旧代仍可见，验证新代后继续切换；
- Pointer 已切换、Android Job 尚未写 `COMPLETED`：以有效 Pointer 和 Completion Marker 为准补写 Receipt/Projection；
- Android 写成 `COMPLETED` 但 Pointer/Generation 无效：这是存储不变量破坏，回退到最后一个有效 Generation 并把 Job 标为 `INTERNAL`，不得暴露缺失内容；
- 任一步重放都必须幂等。

播放器和本地管理只读取 Active Generation，避免逐文件发布时出现半新半旧目录。

SAF/MediaStore 输出应作为单独 Export Job：

- 先在 App Private Storage 完整下载；
- 再复制到外部目标；
- `Download Completed` 与 `Export Completed` 分开；
- 不对 SAF 宣称无法保证的目录级原子性。

---

## 15. 进度与速度语义

Snapshot 建议包含：

```text
wireBytes
uniqueNetworkBytes
decodedBytes
writtenBytes
durableBytes
reusedBytes
duplicateBytes
wastedBytes
totalLogicalBytes
networkBytesPerSecond
commitBytesPerSecond
```

### 15.1 进度条

```text
(durableBytes + reusedBytes) / totalLogicalBytes
```

只显示可恢复或可信复用的内容。

`durableBytes` 与 `reusedBytes` 必须是互斥集合：

- `durableBytes`：本 Attempt 通过 Write + Journal Commit 接受的逻辑范围；
- `reusedBytes`：从有效旧 Generation 或 Content Cache 验证并接受、尚未计入本 Attempt 新写集合的逻辑范围；
- 一旦复用内容被物化到本 Attempt 的 Generation，Checkpoint 记录其来源和接受集合，但 UI 总进度仍按逻辑 Range 去重计算一次；
- Reuse 只有在 Content Identity、Manifest Mapping、Length 和 Checksum/Hash 全部匹配后才可进入进度；
- 接受的 Reuse Set 必须持久化，崩溃恢复时重新验证引用目标存在；无效引用降级为 MISSING。

### 15.2 网速

- `networkBytesPerSecond`：真实网络接收速度，用 1–2 秒 EWMA；
- `commitBytesPerSecond`：有效持久化速度，用 2–5 秒 EWMA；
- 主 UI 可显示有效速度，诊断页显示两者。

第一批 CDN Payload 到达后可以立即显示网络活动；进度条只在 Durable Commit 后移动。不要强迫“网速出现”和“进度可恢复”在同一个时间点发生。

### 15.3 更新频率

- 热进度最多约 4Hz；
- Room/持久 Projection 每 500–1000ms、每至少 1MiB 或 Phase 改变时更新；
- Notification 约 1–2Hz；
- Phase、Blocker、Error、Terminal 立即发布；
- Progress 使用 Conflation，控制事件使用独立不可丢失通道。

---

## 16. 错误与重试

错误必须包含稳定分类：

```text
AuthenticationRequired
SessionExpired
EntitlementDenied
NotFound
DnsFailure
ConnectFailure
TlsFailure
HeaderTimeout
FirstByteTimeout
NoProgressTimeout
RateLimited
CdnUnavailable
ContentCorrupted
UnsupportedCompression
StorageFull
StorageUnavailable
CheckpointInvalid
UnsafePath
PolicyPaused
Cancelled
InternalError
```

策略：

- DNS/Connect/Reset/408/5xx：切换健康 Origin，使用带抖动退避，通常最多两次；
- 429：尊重 `Retry-After`，降低该 Origin 和全局并发；
- 401/403：单飞刷新授权，原请求回放一次；再次拒绝才终止；
- Checksum/Decode 失败：丢弃数据并换 Origin；多 Origin 重复失败才判定损坏；
- Session Timeout：标记 Session Suspect，统一重连并自动重试一次；
- Offline/Policy：进入 Blocker，不轮询；由系统事件唤醒；
- Storage Full、Unsafe Path、Unsupported Compression 和 Entitlement Denied 不盲目重试；
- Retry 重新进入 Scheduler，等待期间不得持有 Socket、Buffer 或 Lease。

日志和 Projection 不得包含 Token、Signed URL、密码、Guard Code 或原始服务响应。

---

## 17. 简化后的模块清单

初始实现建议只保留以下模块：

```text
workshop-downloads
├── interface          # Android 深模块 Interface 和公共模型
├── host               # Android 生命周期、通知、WorkManager 唤醒
├── coordinator        # Job 命令、状态机、恢复
├── scheduler          # 公平性、资源 Lease、自适应并发
├── session            # Session Supervisor 和 Preparation
├── pipeline           # Fetch/Decode/Verify/Write
├── storage            # Journal、Staging、Publish、Recovery
└── projection         # Snapshot、Flow、Telemetry
```

不建议第一版引入：

- 独立下载进程；
- 通用插件系统；
- 每 Job 独立线程池；
- 每 Chunk Actor；
- 自定义通用事件溯源框架；
- 无上限 Content-Addressed 全局缓存；
- 必须依赖 HTTP/2 或 `io_uring` 的实现；
- 用户直接调节线程和 Chunk 数量；
- 同时维护 Android 与 Rust 两份可写 Job 状态。

这些功能只有在真实测量证明需要后再增加。

---

## 18. 验证策略

通过 `WorkshopDownloads` 外部 Interface 进行黑盒验证，内部使用可控 Adapter 和 Virtual Clock。

### 18.1 必须证明的不变量

- 任何队列长度下，线程、Socket、FD 和 Payload Memory 不超过硬上限；
- 每个 Lease 只释放一次；
- Pause/Cancel Intent 持久化后不再授予新工作；
- `Paused` 之后没有网络和写入活动；
- `Cancelled` 不会复活；
- Terminal State 不回退；
- `durableBytes` 不超过真实 Checkpoint 数据；
- 已 Commit Chunk 崩溃后不重下；
- Session Generation 失效后旧 Completion 不能提交；
- 多项目不会饥饿；
- 发布失败不会破坏上一代有效内容；
- 任何日志和事件不泄漏 Secret。

### 18.2 Fault Injection

覆盖：

- DNS、Connect、TLS、Header、First Byte 和 Body Stall；
- 401/403、429、5xx；
- Session 在 Fetch 中失效；
- Manifest 更新；
- Chunk 损坏；
- Slow Disk、ENOSPC；
- Journal Torn Tail；
- 每个持久步骤后的进程死亡；
- Rename/Publish 中断；
- Worker Panic；
- Wi-Fi/蜂窝切换；
- Thermal Severe；
- Android Process Eviction；
- 重复 JNI/Binder Command。

### 18.3 性能测试

- 单项目大文件吞吐；
- 2、3、4 项目公平性；
- 低 RAM 设备；
- 慢磁盘 + 快网络；
- 快磁盘 + 高 RTT；
- 8 小时 Soak；
- 点击到首请求和首字节分位数；
- Buffer、RSS、Thread、FD、Socket 高水位；
- Group Commit 大小对吞吐和恢复重下载量的影响。

必须保留固定调度参数模式，用于确定性回归测试；自适应模式用于真实设备性能验证。

---

## 19. 分阶段迁移路线

迁移期间采用 Single-Writer Cutover；Room v8 曾以 `executorVersion = LEGACY | NEW` 隔离两条路径。质量收口后的 Room v9 完成最终切换：

- v8→v9 事务先把旧 `requestedAction` 吸收到 Desired Intent/Revision；
- 旧下载执行态生成唯一 Durable Command，旧 Conversion Cancel 只生成 Conversion Desired Intent；
- 随后重建 Job 表并物理删除 `requestedAction`、`executorVersion`；
- 新任务和迁移任务都只由一个 DownloadHost 消费 Durable Command；
- WorkManager 只提供一个全局 Wake/Reconcile 执行窗口，不再创建 per-project Download Work；
- 旧 `FormalWorkshopDownloadWorker` 仅用于排空升级前已存在于系统 WorkManager 数据库中的 WorkSpec，不再是调度入口；
- Engine Epoch 继续约束 Command Claim/Ack，错误 Epoch 无写权；
- 回滚不得重新启用旧写路径；需要回滚的数据必须通过明确导出工具处理。

### Phase 0：建立基线

采集：

- 点击到持久 Receipt；
- 点击到首 CDN 请求/首内容字节；
- 第一内容字节到第一 Durable Byte；
- 总吞吐与每项目吞吐；
- Native RSS、线程、Socket、FD；
- Pause/Cancel 延迟；
- Crash Recovery 重下载量。

### Phase 1：统一外部 Interface

引入 `WorkshopDownloads`，现有链路实现为 `LegacyWorkshopDownloadsAdapter`。UI 只依赖新 Interface，行为暂不改变。

### Phase 2：单一 DownloadHost 和 JobCoordinator

建立：

- 持久命令；
- Projection；
- Desired Intent；
- Engine Epoch；
- 一个 Host；
- WorkManager 只唤醒 Host。

该阶段最初以版本字段隔离试验 Job；Room v9 已完成 Cutover，所有 Job 都由唯一 Host 调用 Native Transfer。升级前残留 Worker 也必须进入同一个 Host/Runner Seam，不得自行拥有执行权。

### Phase 3：SessionSupervisor 和 PreparationRegistry

集中 Session、Product Info、Manifest 和授权生命周期，实现：

- Session Health；
- 3–5 秒交互式 Deadline；
- 一次有界自动重连；
- Single Flight Preparation；
- 详情页安全预热。

这是降低点击到首字节时间的最高收益阶段。

### Phase 4：字节预算 Pipeline

实现：

```text
Fetch → Decode → Verify → Write → Commit
```

先使用固定保守并发，验证内存、Pause、Cancel 和 Crash Durability。

### Phase 5：多项目全局 Scheduler

实现：

- Latency Lane；
- Active Cohort；
- Weighted DRR；
- Per Job/Per Origin Cap；
- 全局 Memory/FD/Worker Budget。

### Phase 6：自适应并发

基于真实设备遥测启用 AIMD、Thermal、Writer Backlog 和 Network Type 反馈。保留固定模式作为回归基线。

### Phase 7：Generation Publication 与 Export 分离

完成 App Private Generation 原子发布；SAF/MediaStore 改为独立 Export Job。

### Phase 8：删除旧链路

达到功能、SLO、Crash Test、Soak Test 和设备验证目标后，删除：

- 每项目 WorkManager 下载栈；
- Android Session Lease；
- 重复 Room 状态机；
- 每任务 Native Worker Pool；
- Kotlin 对 SDK 私有路径的推导；
- 旧 Transfer Adapter。

---

## 20. 验收标准

新引擎只有同时满足以下条件才能成为默认实现：

1. 功能覆盖下载、Pause、Resume、Cancel、Retry、并发、多级依赖、完成发布和进程恢复；
2. 健康路径首内容字节达到目标；
3. 单项目与多项目吞吐达到目标；
4. 全部资源硬上限有自动化测试证明；
5. Crash Injection 不产生高报进度或损坏发布；
6. Session 失效不会要求每个 Job 独立重连；
7. 多项目公平性达标且不会死锁；
8. Android 真机完成长时间 Soak；
9. 日志与持久状态不含 Secret；
10. 旧下载实现可以完全删除，而不是永久保留两套生产链路。

---

## 21. 最终决策摘要

推荐采用：

> **`WorkshopDownloads` 深模块 + 单一可重启 DownloadHost + 全局 Scheduler + SessionSupervisor + 字节预算流式 Pipeline + 持久 Job/Checkpoint + Generation Publication。**

最重要的约束是：

1. 一个调度器；
2. 一个 Session 所有者；
3. 一个明确的 Job 状态权威；
4. 内存按字节 Permit 管理；
5. 新任务有 Latency Lane；
6. Bulk 下载使用公平调度；
7. WorkManager 只负责唤醒和恢复；
8. 只有真正持久的数据才能推进进度；
9. Session 半失效必须快速失败并统一重连；
10. 从第一版就保留硬资源上限、Fault Injection 和可测 SLO。

这套设计将下载复杂性集中到一个深模块内，在保持外部 Interface 简单的同时，覆盖低延迟、高吞吐、资源安全、多项目公平、可靠控制和崩溃恢复。