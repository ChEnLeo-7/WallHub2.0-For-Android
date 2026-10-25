# WallHub 长生命周期 Steam 账号会话设计方案

> 状态：设计提案，不代表已实现
>
> 日期：2026-10-26
> 范围：WallHub for Android 的账号 CM 会话、登录恢复、心跳、断线重连、账号能力获取；不改变匿名 Workshop/Web 路由与 CDN 数据面。

## 1. 目标与可兑现边界

目标不是让头像和账号名长期看起来“已登录”，而是让所有账号功能共享一个可验证、可恢复、长期维护的认证会话：

- 个人订阅、收藏、账号交互与下载取得同一个已认证 generation；
- 会话空闲时仍持续处理回调、发送心跳并检测断线；
- 断线后由唯一所有者自动重连，业务调用方不能各自建立或恢复会话；
- 磁盘上的账号身份与内存中的实时传输状态分别建模；
- 用户退出、凭据失效与网络故障具有不同的终态和恢复策略；
- 恢复成功不自动重放语义不确定的写操作。

“始终在线”在 Android 上必须分成两个承诺：

1. **进程被允许执行时保持热会话**：可作为默认保证。前台 App、活动下载/播放或前台任务存在时，持续维护 CM 会话。
2. **App 退到后台后仍长期保持连接**：只有用户明确启用持续连接并运行带常驻通知的前台服务时，才能尽力保证。Android 仍可能因重启、强制停止、系统策略、Doze 或无网络终止进程/暂停执行，因此不能承诺物理上的 100% 永久在线；进程重建后必须使用保存的 refresh token 自动恢复，不能声称旧 Session 幸存。

## 2. SteamKit 官方逻辑中应继承的原则

详细一手资料和 38 个固定提交源码引用见 [SteamKit 长生命周期会话研究](research/steamkit-long-lived-session.md)。结论是：

- `CMClient.Connect()` 失败会发布 `DisconnectedCallback`；SteamKit 不替宿主无限重连，而是要求宿主短暂延迟后重新 `Connect()`。
- 每次 Connect 都拆除旧 Connection 并创建新的底层 Connection；长期存在的是 Client/Handler/Callback 层，不是旧 Socket。
- `ConnectedCallback` 只证明 CM 安全传输建立；只有 `SteamUser.LoggedOnCallback` 成功才形成账号可用 Session。
- CallbackManager 必须持续 pump；不消费回调就无法可靠观察登录、断线和服务器事件。
- 登录成功后按服务端协商间隔发送 heartbeat；`LoggedOff` 与物理 `Disconnected` 是不同事实。
- CM 候选由 `SmartCMServerList` 标记 good/bad 并轮换；坏端点不会被立即无限复用。
- 持久化账号名、refresh token 与 Guard data 只表示“可恢复身份”，不表示“当前连接已认证”。

WallHub 应复制这些生命周期原则，而不是复制 SteamKit 的 C# 类结构。

## 3. 当前实现与实机故障的差距

当前 `SteamContentSdkSessionBackend` 已具备单一账号 Session、2 秒 maintenance tick、refresh token 存储、generation fence 和按需恢复，但目标仍是“按需打开、失败后保留身份”，不是持续可用：

- `retireAccountSession()` 关闭坏 generation 后仍发布 `SIGNED_IN`，等待下一次业务请求再恢复；
- `preserveSignedInDuringRestore` 会把 `RESTORABLE`、`SIGNING_IN`、`FAILED` 投影为 `SIGNED_IN`；
- `SteamContentSdkAuthenticatedSessionRegistry.awaitCurrentUsable()` 由业务调用触发恢复；
- `SteamSessionRequestRecovery`、Registry 和 Backend 都参与恢复路径，恢复所有权未完全收拢；
- 两次 CM transport 恢复失败后没有持续后台恢复计划，下一次业务调用再次承担发现故障和等待恢复的成本；
- UI phase 同时承担“身份”“连接”“交互式认证”三类事实，容易出现头像仍在、phase 为 `SIGNED_IN`、但没有可借用 generation 的矛盾状态。

2026-10-26 远程设备证据正好命中该缺口：保存的 credential 存在，个人订阅触发两次 `CM transport failed`，Registry 没有可用 Session，下载报告账号 Session unavailable；同一 credential 在进程冷启动后完成 roundtrip、重新认证并成功下载和转换。因此不能把故障归因于凭据固化损坏。

## 4. 推荐模块：单所有者 `SteamAccountSession`

### 4.1 外部接口

使用一个深模块隐藏 native Session、CM 候选、heartbeat、回调 pump、重连、退避、凭据与 generation 发布：

```kotlin
internal interface SteamAccountSession {
    val state: StateFlow<SteamAccountSessionState>

    suspend fun apply(intent: SteamAccountSessionIntent)

    /** 返回时保证 snapshot 对应一个已认证、当前可用的 generation。 */
    suspend fun usableSnapshot(
        rejectedGeneration: SteamSessionGeneration? = null,
        deadline: SteamSessionDeadline,
    ): SteamAccountSessionSnapshot
}
```

意图为封闭集合：

```kotlin
internal sealed interface SteamAccountSessionIntent {
    data object ResumeRemembered : SteamAccountSessionIntent
    data class SignIn(val accountName: String, val password: CharArray) : SteamAccountSessionIntent
    data class AnswerChallenge(val answer: CharArray) : SteamAccountSessionIntent
    data object Logout : SteamAccountSessionIntent
}
```

Snapshot 不暴露 `SteamContentSdkEngine`、native Session、close、reconnect 或 Kotlin 侧能力 facade：

```kotlin
internal data class SteamAccountSessionSnapshot(
    val account: SteamAccountIdentity,
    val generation: SteamSessionGeneration,
)
```

Snapshot 只是“返回时该 generation 已认证”的证据，不可能仅靠 Kotlin 预检查保证后续 native 调用仍落在该 generation；预检查与发送之间存在 TOCTOU。每个账号 Gateway 必须把 `expectedGeneration` 传进 SDK，由 Rust 在与请求发送相同的 operation 临界区内原子验证。generation 已 retire 时，SDK 在发送前返回 typed `GENERATION_RETIRED_BEFORE_SEND`；它绝不能把写操作静默送到新 generation。下载任务和 range stream 的创建同样携带 expected generation，创建后的 native child 记录其 generation，并在失效时返回 typed generation loss。

### 4.2 三种接口设计的取舍

研究了三种根本不同的形状：

1. **最小 Snapshot 接口**：状态 + 意图 + 获取可用 generation。最贴合现有调用结构，接口最小，迁移成本可控。
2. **Actor operation broker**：所有账号请求都变成 sealed command。所有权最严格，但会复制 Workshop/player/content 全部接口，流式任务也很笨重。
3. **Capability lease**：类型安全最高，能防止能力越界和 stale handle，但要包装每个 native child，首轮改造范围较大。

推荐采用 **actor 作为内部实现、已认证 generation snapshot 作为外部接口、SDK 原子准入作为执行栅栏**：保留最小接口的深度，不把整个业务接口复制到 mailbox，也不假设 Kotlin facade 能消除检查与发送之间的竞态。

## 5. 状态模型：身份与传输正交

```kotlin
internal data class SteamAccountSessionState(
    val identity: SteamIdentityStatus,
    val transport: SteamTransportStatus,
    val challenge: SteamAccountChallenge? = null,
)

internal sealed interface SteamIdentityStatus {
    data object SignedOut : SteamIdentityStatus
    data class Remembered(val account: SteamAccountIdentity) : SteamIdentityStatus
    data class Expired(val accountName: String?) : SteamIdentityStatus
}

internal sealed interface SteamTransportStatus {
    data object Stopped : SteamTransportStatus
    data object WaitingForNetwork : SteamTransportStatus
    data class Connecting(val attempt: Int) : SteamTransportStatus
    data class Authenticating(val attempt: Int) : SteamTransportStatus
    data class Ready(val generation: SteamSessionGeneration) : SteamTransportStatus
    data class Recovering(
        val retiredGeneration: SteamSessionGeneration?,
        val attempt: Int,
        val retryAtElapsedMs: Long?,
    ) : SteamTransportStatus
    data class Suspended(val reason: SteamSessionSuspensionReason) : SteamTransportStatus
    data class Unavailable(val failure: SteamSessionAvailabilityFailure) : SteamTransportStatus
}
```

关键不变量：

- `Remembered + Recovering` 合法；UI 保留头像昵称并显示“正在重连”。
- 只有 `transport is Ready` 才能启用账号能力。
- `Remembered` 不能映射成“当前已连接”。
- `Ready(G)` 必须与内部 authenticated generation 一致。
- 认证失败/refresh token 被服务端拒绝进入 `Expired`，不继续网络退避。
- 网络故障保留 `Remembered`，绝不清除有效 token。

旧 `SteamSessionState` 可在迁移期由 adapter 投影，但应删除 `preserveSignedInDuringRestore`，不能再把恢复失败对外伪装成 `SIGNED_IN`。

## 6. 内部 actor 与唯一恢复所有权

`SteamContentSdkSessionBackend` 演进为单一 mailbox actor：

```text
SteamAccountSessionActor owns:
  remembered identity / credential generation
  exact native account-session handle
  authenticated generation
  interactive challenge + attempt token
  maintenance/pump timer
  one connect/auth attempt
  one reconnect backoff timer
  usableSnapshot waiters
  network epoch + execution policy
```

输入事件包括 `ApplyIntent`、`AcquireSnapshot`、`MaintenanceTick`、`NativeEvent`、`NativeAttemptCompleted`、`OperationReportedLoss`、`NetworkChanged`、`ExecutionPolicyChanged` 和 `RetryTimerFired`。所有 native 完成结果都携带 attempt token；晚到的旧登录、旧挑战或旧重连结果不能覆盖新的登录/退出。

### 6.1 恢复流程

```text
Ready(G)
  -> heartbeat/pump/request 检测 generation loss
  -> 原子 retire G，拒绝 G 上的新控制面请求
  -> Recovering(G)
  -> 短路径：原 Session 显式 reconnect（底层必须重建 Connection）
  -> 若失败：关闭精确 Session handle，以 live CM candidates 打开新 Session 并 restore token
  -> LoggedOn/Authenticated 成功
  -> 发布 Ready(G+1)，完成等待者
```

只要身份仍可恢复、网络可用且执行策略允许，失败后就按有界频率继续恢复，而不是两次失败后永久等待下一次业务请求。

### 6.2 退避策略

采用指数上限 + full jitter：

```text
cap = min(60s, 1s * 2^attempt)
delay = random(0..cap)
```

- 只有 `Ready` 连续稳定至少 60–120 秒才清零失败债务；TCP connect 成功但认证未完成不能清零。
- validated network epoch 变化触发一次立即重试，但必须 single-flight；随后回到普通退避。
- 无网络时进入 `WaitingForNetwork`，不运行连接风暴。
- credential expired/revoked、账号受限或需要用户挑战时停止自动网络重试。
- 调用方 deadline 不重置退避，也不取消 policy-owned 共享恢复。

### 6.3 心跳与回调

- 保留 2 秒 host tick；SDK 决定 heartbeat 是否到期。
- actor 调用 `maintain(autoReconnect = false)`，由 actor 统一决定重连，避免 SDK、Registry 和请求层同时成为恢复所有者。
- `HEARTBEAT_SENT` 后执行有界 pump，并持续消费 native Session events，直到本轮 idle 或达到消息上限。
- `LoggedOff`、`Disconnected`、heartbeat timeout、write failure、protocol failure 分别映射 typed cause。
- maintenance 不执行长业务操作；慢 native 操作异步执行，并将带 generation/attempt token 的结果送回 actor。

但 actor 定时器本身不能保证心跳及时：当前 SDK 的长业务操作、恢复和 heartbeat 共用 account-session operation mutex，部分 JNI 网络调用又持有 Engine monitor。若一个服务请求长期等待，2 秒 tick 也可能无法取得锁，发生 heartbeat starvation。**因此在实现 actor 之前必须先完成 SDK 调度修复**：长等待循环要协同发送到期 heartbeat、处理服务器消息，或者 SDK 提供一个不会被长业务 operation 互锁的安全 maintenance seam。验收必须包含“业务请求持续时间超过 heartbeat timeout，Session 仍保持认证”的故障测试，不能用更多 Kotlin timer 掩盖这个问题。

## 7. SDK 层需要补齐和审计的窄能力

Android 不应为修复账号 Session 而关闭共享 Engine，因为 Engine 还承载下载、range stream 和其他 native child。当前 SDK 已经具备关闭旧 Connection、放弃 pending correlation、使用 retained token 重新登录、typed reconnect failure 以及 `replayableRequests` / `abandonedRequests` disposition；不能再新增一套平行 reconnect 抽象。应先做 Rust -> C ABI -> JNI -> Kotlin 的 conformance audit，只补以下确定缺口：

1. **原子 generation 准入**：所有账号控制面操作和 native child 创建接受 `expectedGeneration`，并在与发送相同的 operation 临界区检查；已 retire 时必须在发送前返回 typed `GENERATION_RETIRED_BEFORE_SEND`。
2. **heartbeat 不被长业务操作饿死**：长等待循环协同维护 heartbeat/服务器消息，或提供不与长 operation 互锁的 maintenance seam。
3. **同一 network epoch 保留失败健康记录**：普通 reconnect 应记录并避开失败 endpoint，不能无条件 `reset_health()`；只有 validated network epoch 变化或 live candidate set 确实替换时才重置健康状态。
4. 审计 reconnect 确实为每次尝试创建新 Connection、清理旧 pending correlation，并把现有 typed failure family 与 disposition 完整传到 Kotlin；只修复丢失 ordinal/映射，不重复建设接口。
5. Session generation loss 必须从 JNI 以 typed ordinal 到达 Kotlin，业务层不得解析异常 message substring。
6. 只有证据证明同 Engine 内反复创建 Session 仍复用损坏适配器时，才在 SDK 内新增“只重建账号 control-plane”的能力；不能让 Android 直接重建整个 Engine。

## 8. 业务调用语义

### 8.1 个人订阅/收藏与账号读取

- 通过 `usableSnapshot(deadline)` 等待共享恢复，不自行 restore；
- Gateway 将 snapshot 的 `generation` 作为 `expectedGeneration` 传给 SDK 原子准入；
- generation 丢失后只允许一次 `rejectedGeneration=G` 的新 snapshot 获取；
- 失败是 typed unavailable，绝不映射为空列表或零数量。

### 8.2 下载

- 下载开始前获取可用 snapshot；
- Manifest/Depot/CDN 授权与 native task 创建把 `expectedGeneration` 传给 SDK，并由 SDK 原子准入；
- generation 丢失后通过 supervisor 获取 `G+1`，利用 durable checkpoint 恢复下载；
- 下载模块不调用 Session reconnect；
- Engine 数据面不随账号 Session retire 一起关闭。

### 8.3 写操作

- 订阅、收藏、评论等 mutation 只有错误明确是 `REFUSED_BEFORE_SEND` 时才允许重试；
- 发送后断线导致结果未知时返回 `OUTCOME_UNKNOWN`，不得自动重放；
- generation 替换不能让写操作静默切到新 generation。

### 8.4 UI

- `Remembered + Ready`：账号在线；
- `Remembered + Recovering/Connecting`：保留身份，显示正在重新连接；
- `Remembered + WaitingForNetwork`：显示等待网络；
- `Expired`：提示重新登录；
- 账号按钮仅在 `Ready` 时立即执行，其余状态触发一次有界等待并显示真实进度。

## 9. Android 执行策略

新增内部 `SteamSessionExecutionPolicy` 流：

```text
ALLOWED:
  App 在前台
  OR 活动下载/播放需要账号 control-plane
  OR 用户开启“后台保持 Steam 连接”且前台服务正在运行

SUSPENDED:
  无上述条件
```

严格常驻模式要求：

- 用户显式开启；
- 常驻通知说明 Steam 账号连接和电量/流量影响；
- 前台服务只授予 actor 执行许可，不成为第二个 Session owner；
- `ConnectivityManager.NetworkCallback` 只上报 network epoch/validated 状态，不直接 reconnect；
- 进程死亡后创建全新 generation 并从安全存储恢复；
- 不用 WorkManager 模拟长连接；
- wake lock 只覆盖有界 connect/auth 或活动传输，不能在 Ready/Backoff 空闲期永久持有。

如果产品不接受常驻通知，应明确降级为“前台及任务期间热会话”，不能宣称后台永久在线。

## 10. 文件级实施切片

### Slice A：状态真相与兼容投影

- `app/src/main/kotlin/com/wallhub/android/core/model/AppModels.kt`：添加 identity/transport 正交状态，保留旧模型 adapter 直至 UI 迁移。
- `app/src/main/kotlin/com/wallhub/android/core/model/RepositoryContracts.kt`：将登录 UI 适配到 `apply(intent)`。
- `SteamContentSdkSessionBackend.kt`：删除 `preserveSignedInDuringRestore` 和虚假 `SIGNED_IN` 投影。

验收：保存身份但 CM 断开时必须是 `Remembered + Recovering/Unavailable`，且不可借用账号能力。

### Slice B：单 actor 与持续恢复

- 将 attempt gate、maintenance Job、pending recovery、challenge 和 retry debt 收敛为 actor state；
- disconnect/maintenance/request loss 都投递同一 `Recover` 事件；
- execution policy 允许时，失败后自动按 jitter 退避继续恢复。

验收：并发十个账号读取只产生一个 reconnect/open；恢复两次失败后仍有下一次计划，不依赖新业务请求。

### Slice C：替换 Registry/Broker 恢复权

- `SteamContentSdkAuthenticatedSessionRegistry.kt`：退化为只读 generation publication，或由 snapshot 完全替换；删除 `installRecoveryOwner/recoverStoredSession`。
- `SteamContentSdkSessionBroker.kt`：改为 snapshot adapter，把 expected generation 交给 SDK，不再成为恢复入口。
- `SteamWorkshopSdkGateway.kt`：通过 supervisor 获取 snapshot，并把 `expectedGeneration` 传入 SDK；operation wrapper 只负责“一次安全重试”。
- `SteamSessionRequestRecovery.kt`：接收 typed generation loss；移除异常 message substring 分类和 reconnect mutex。

验收：源码中只有 actor 可以调用 restore/reconnect/open/close account Session；所有账号请求都由 SDK 在发送前原子检查 expected generation。

### Slice D：SDK reconnect conformance audit 与窄修复

先审计现有 Rust reconnect、C ABI、JNI/Kotlin binding 的行为与字段传递，不新增平行 reconnect abstraction。只实施审计证实的缺口：原子 expected-generation 准入、长业务操作期间 heartbeat 调度、同 network epoch 的失败 endpoint 健康保留、缺失的 typed ordinal 映射。

验收：

- 检查与发送在同一临界区，retire 竞态只能得到 `GENERATION_RETIRED_BEFORE_SEND`，不能落到新 generation；
- 一个持续超过 heartbeat timeout 的业务请求不会让认证 Session 因 maintenance 饥饿死亡；
- 同一 network epoch 重连保留失败 endpoint 的冷却并轮换候选；只有 epoch/candidate set 变化才 reset health；
- 故障注入证明旧 Connection 不复用，现有错误 family 与 disposition 完整到达 Kotlin，且无 secret 进入日志。

### Slice E：Android execution policy 与可选常驻模式

- Application lifecycle、下载/播放状态、网络 callback 合成为内部 policy；
- 若启用严格后台保持，新增唯一前台服务与设置项；
- 服务只授予 actor 执行许可，不拥有 Session。

验收：前台、活动任务和常驻模式下 maintenance 持续运行；Suspended 时关闭 CM、保留 remembered identity；恢复时只开一个 Session。

### Slice F：UI 与诊断

- Profile/Settings 分别展示身份和传输状态；
- 日志记录 transition seq、generation、network epoch、attempt、backoff、typed cause、Session livedMs、heartbeat/pump outcome、waiter timeout；
- 不记录 token、密码、Guard answer、代理认证或完整请求 payload。

验收：一次报告能回答“身份是否存在、何时丢 generation、何种 cause、重连次数、CM 路由和恢复时刻”。

## 11. 必须覆盖的测试

### 状态机与竞态

1. 保存身份 + execution allowed 自动恢复一次并进入 Ready；
2. 两个相同 rejected generation 的请求共享一个恢复并得到同一个新 generation；
3. 旧 generation 的 late result 不能覆盖新 Session；
4. reconnect 成功但未认证不能发布 Ready；
5. network change、maintenance loss、request loss 合流为一个恢复；
6. logout 与 reconnect 竞态时 logout 获胜且不能随后重新发布 Ready；
7. challenge answer 只对精确 attempt 有效，秘密在所有路径清零；
8. 一个 waiter 取消/超时不能取消共享恢复；
9. 恢复多次失败后仍按上限退避，稳定 Ready 后才清零债务；
10. 凭据明确 expired 后停止自动重试。

### 业务、generation 与 SDK 调度

11. `expectedGeneration=G` 的原子准入与 retire 竞态时，操作要么在 G 上已明确发送，要么返回 `GENERATION_RETIRED_BEFORE_SEND`，绝不能落到 G+1；
12. read 的 pre-send failure 可在 G+1 重试一次；
13. mutation 的 outcome unknown 不重放；
14. 下载在 generation loss 后使用 checkpoint 和 G+1 恢复；
15. range stream 不静默跨 generation；
16. Session retire 不关闭共享 Engine 或其他数据面 child；
17. 业务请求持续超过 heartbeat timeout 时，SDK 仍发送到期 heartbeat、处理服务端消息并保持认证；
18. 同一 network epoch 的重连保留坏 endpoint 冷却，网络 epoch/candidate set 变化才清健康状态。

### Android 生命周期

19. 前台/后台策略变化按模式保持或 Suspended；
20. 活动下载即使 UI 后台仍保持 actor 许可；
21. 进程重建后只使用 refresh token 恢复，绝不恢复旧 Ready/generation；
22. 网络丢失期间无连接风暴，validated 网络恢复触发一次立即重试。

### 设备验收

- 同一账号连续保持前台 2 小时和后台常驻 2 小时；
- 每 10–15 分钟读取订阅/收藏并启动一个小下载，要求无人工重启；
- 覆盖 Wi-Fi/移动网络切换、飞行模式 2 分钟后恢复、CM 远端关闭和前后台切换；
- 记录 generation、心跳、重连延迟和业务成功率；
- 强制停止/重启后验证同一 credential 恢复；
- 区分“网络不可达期间不可用”与“恢复机制失效”。

## 12. 推荐实施顺序

推荐顺序：**D SDK conformance audit 与 heartbeat/generation/CM-health 窄修复 -> A 状态真相 -> B actor/持续恢复 -> C 调用方收敛 -> F 诊断/设备验证**。D 是阻断前置项：没有原子 generation 准入和不受长操作饿死的 maintenance，Kotlin actor 只能改善表面状态，不能兑现正确性和长期可用性。只有产品确认接受后台常驻通知时再实施 E 的严格常驻模式。

首轮不建议：

- 不重建整个共享 Engine；
- 不增加第二个账号 Session owner；
- 不让每个 Repository 自己重试连接；
- 不使用 WorkManager 保活 Socket；
- 不因网络失败清除 token；
- 不把 `Remembered` 再显示成实时 `SIGNED_IN`。

最终架构核心是：**一个 actor 长期拥有账号控制面；每次断线都替换底层 Connection；只有认证成功才发布新 generation；身份持久化与实时可用性分开；调用方只取得认证 generation，SDK 在发送临界区原子执行 generation 准入，调用方不参与恢复。**
