# SteamKit2 / DepotDownloader CM 恢复机制研究

> 研究日期：2026-10-08  
> 目的：针对 WallHub 实机出现的 `protocol adapter failed` 后持续恢复失败问题，寻找简单、可靠、可维护的上游设计思路。本文仅研究，不复制或翻译上游实现。

## 固定来源

- SteamKit2 官方仓库，固定提交 [`3a9a5d2001c37eb4f80578fc4e3614ca84bb881d`](https://github.com/SteamRE/SteamKit/tree/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d)。
- DepotDownloader 官方仓库，固定提交 [`989f37b1fbc012798bf41bdf0e66a60adf785ae5`](https://github.com/SteamRE/DepotDownloader/tree/989f37b1fbc012798bf41bdf0e66a60adf785ae5)。
- DepotDownloader 在该提交使用 SteamKit2 3.4.0：[DepotDownloader.csproj L23-L31](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/DepotDownloader.csproj#L23-L31)。

## SteamKit2 的核心做法

### 1. SteamKit 不替应用偷偷无限重连

`CMClient.Connect()`文档明确说明：连接失败会发布 `DisconnectedCallback`，SteamKit 本身不会自动重连，宿主应在短暂延迟后再次调用 `Connect()`：[CMClient.cs L157-L170](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/CMClient.cs#L157-L170)。

这使重试预算、取消、UI状态和凭据策略留在宿主，而不是分散在协议层。

### 2. 每次 Connect 都创建新的底层 Connection

`Connect()`先完整执行 `Disconnect()`，随后选择CM候选，并调用 `CreateConnection(...)` 创建新的连接对象，再挂载事件和连接端点：[CMClient.cs L170-L238](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/CMClient.cs#L170-L238)。

断开时旧连接会：

- 从当前连接槽位原子移除；
- 取消事件订阅；
- 如果实现了 `IDisposable` 则释放；
- 停止心跳；
- 发布断开回调。

来源：[CMClient.cs L460-L490](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/CMClient.cs#L460-L490)。

**关键点不是每次都创建新的 SteamClient，而是旧的网络/加密 Connection 不会在 Connect 之间复用。**

### 3. 协议或数据异常会主动断开当前连接

SteamKit若无法解析收到的数据包，会直接调用非用户发起的Disconnect，而不是让当前连接继续工作：[CMClient.cs L343-L353](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/CMClient.cs#L343-L353)。

连接事件处理自身抛异常时也会断开当前连接：[CMClient.cs L440-L457](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/CMClient.cs#L440-L457)。

### 4. Callback泵与Transport连接分层

SteamClient持有持续存在的Callback队列，CallbackManager负责消费；官方示例通常每秒以内持续泵取。Transport的Connected/Disconnected与账号的`SteamUser.LoggedOnCallback`是不同事件，不能把“Socket连接成功”当成“账号Session可用”： [SteamClient.cs L203-L263](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/SteamClient/SteamClient.cs#L203-L263)、[CallbackMgr.cs L45-L114](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/SteamClient/CallbackMgr/CallbackMgr.cs#L45-L114)、[SteamUser callbacks L23-L79](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/Handlers/SteamUser/Callbacks.cs#L23-L79)。

### 5. CM候选有健康评分而不是固定复用

意外断开的端点被标记为Bad，成功连接则标记为Good：[CMClient.cs L440-L474](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/CMClient.cs#L440-L474)。

候选选择优先使用最近没有失败的端点，旧Bad评分会随时间重置；也支持强制刷新服务器列表：[SmartCMServerList.cs L345-L398](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/Discovery/SmartCMServerList.cs#L345-L398)、[L421-L433](https://github.com/SteamRE/SteamKit/blob/3a9a5d2001c37eb4f80578fc4e3614ca84bb881d/SteamKit2/SteamKit2/Steam/Discovery/SmartCMServerList.cs#L421-L433)。

## DepotDownloader 的核心做法

### 1. 一个长生命周期SteamClient，但每次重连仍重建底层Connection

`Steam3Session`创建一次SteamConfiguration、SteamClient、Handlers和CallbackManager，订阅一次回调后开始连接：[Steam3Session.cs L60-L88](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L60-L88)。

恢复时复用SteamClient对象，但调用的是SteamKit的 `Disconnect()` / `Connect()`；如上所述，SteamKit会释放旧Connection并创建新Connection：[Steam3Session.cs L370-L419](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L370-L419)。

因此不能简单归纳成“上游总是重建整个Client”，准确描述是：

- Handler/Callback层长期存在；
- 每次连接尝试有新的Transport/Envelope Connection；
- 顶层Client通常只在创建新的Steam3Session时重建。

### 2. 断开回调是唯一重连入口

意外断开最多尝试10次，使用1至10秒线性等待，然后再次调用 `steamClient.Connect()`；连接成功会重置计数：[Steam3Session.cs L421-L429](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L421-L429)、[L554-L591](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L554-L591)。

`TryAnotherCM`会触发相同对象的Disconnect/Reconnect，而 `ServiceUnavailable` 和其他确定性登录失败则终止，不盲目重试：[Steam3Session.cs L594-L683](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L594-L683)。

### 3. Callback必须持续驱动

初始登录阶段以1秒窗口同步处理Callback，成功后启动异步Callback泵：[Steam3Session.cs L90-L141](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L90-L141)。

断开时还会继续处理Callback直到收到Disconnected事件：[Steam3Session.cs L388-L413](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L388-L413)。

WallHub当前由宿主定时执行pump/maintain，方向与此一致；差异是SDK内部适配器失败后，新的Session仍可能复用同一失败适配器。

## 不应照搬的上游做法

DepotDownloader是命令行工具，不是移动端常驻App，存在不应复制的取舍：

1. 断开回调中使用阻塞 `Thread.Sleep`；移动端应使用可取消挂起和抖动退避。
2. 每次TCP连接成功即清零失败计数，即使认证阶段尚未结束；WallHub应以“认证可用”作为恢复成功。
3. 部分CDN manifest/chunk循环缺少总尝试预算。
4. CDN Token Single-flight在非OK结果上存在未完成Promise风险，不应复制。
5. `abortedToken`是长期对象，某些Abort后再次Connect的路径可能继续使用已取消Token；WallHub应每个运行generation使用新的取消域。
6. CDN服务器列表刷新函数追加记录而非原子替换；若照搬为反复刷新会产生重复候选。

相关来源：[Steam3Session.cs L47-L55](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L47-L55)、[L126-L141](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/Steam3Session.cs#L126-L141)、[CDNClientPool.cs L32-L91](https://github.com/SteamRE/DepotDownloader/blob/989f37b1fbc012798bf41bdf0e66a60adf785ae5/DepotDownloader/CDNClientPool.cs#L32-L91)。

## 与WallHub当前故障的对照

ADB证据表明：

```text
健康：DETAILS约0.95–1.02秒
老化：session_maintenance -> protocol adapter failed
恢复：5/15/30/60秒退避，每次立即以同样错误失败
重启进程：相同凭据重新认证成功
```

当前Android恢复路径：

1. maintenance调用 `session.maintain(autoReconnect=true)`；
2. 非Authenticated时又调用 `session.reconnect()`；
3. 失败后release Session并走restore；
4. 同Route下 `SteamContentSdkEngine.open()`复用现有Engine，只替换Session。

但当前最重要的信息缺口是：`protocol adapter failed`丢失了原始失败类型。SDK内部typed错误在映射时被压成`SessionError::AdapterFailure`，FFI同步reconnect又只返回通用状态和字符串，Android最终只得到Boolean。涉及：

- `Steam Content SDK/crates/sc-content/src/account_session.rs:760-765`
- `Steam Content SDK/crates/sc-session/src/lib.rs:168-173,185-208`
- `Steam Content SDK/crates/sc-content-ffi/src/session.rs:1436-1471`
- `WallHub for Android/app/src/main/kotlin/com/wallhub/android/data/steam/SteamContentSdkEngine.kt:209-216`

因此目前不能严谨地区分：

- Token/认证Session失败；
- CM端点或网络失败；
- Transport/加密Connection失效；
- Engine内部协议适配器缺陷。

## 推荐的最小方案

### 不新增“恢复框架”

保留当前深模块：

```text
SteamContentSdkSessionBackend
└── SteamContentSdkPlatformSession.reconnect()
```

只补一条窄的失败分类：

```text
SESSION
TRANSPORT
PROTOCOL_ADAPTER
INTERNAL
```

可复用现有 `SessionFailure` ordinal，不需要新增Manager、Policy层级或页面级分支。

### 改动顺序

1. SDK：让同步 `sc_session_reconnect` 写入现有per-session `failure_ordinal`，保留bounded detail仅用于诊断。
2. Kotlin/JNI：`SteamContentSdkReconnect`携带一个failure family，或失败时提供可读取的现有SessionFailure。
3. Backend在现有`rebuildSession()`内做唯一决策：
   - `SESSION`：保持Engine，重新认证或要求用户登录；
   - `TRANSPORT`：保持高层Engine，但必须创建全新底层Connection并换CM候选；
   - `PROTOCOL_ADAPTER` / `INTERNAL`：废弃Engine并创建新Engine；
   - 未知：一次完整Engine重建，不在已失败Engine上指数循环。
4. 只有完整Engine重建失败后才进入5/15/30/60秒退避。

### 与SteamKit的一致性

这一方案借鉴的是SteamKit的两个原则，而不是复制它的类结构：

- 每次Connect必须拥有新的底层Connection，旧Connection彻底释放；
- 重试策略属于宿主，但需要底层提供可分类的断开原因。

## 建议测试

1. `PROTOCOL_ADAPTER`失败后只执行一次Engine重建，不在旧Engine上再次openSession。
2. `TRANSPORT`失败轮换CM候选，并创建新Connection。
3. `SESSION_EXPIRED`保留Engine但进入认证流程。
4. 新Engine认证成功后前台等待请求恢复一次。
5. 完整重建失败后退避，Cancellation立即结束。
6. 失败分类和日志不得包含Token、代理认证或签名URL。

## 结论

不建议盲目增加更多重连Coordinator或Recovery Policy类。SteamKit/DepotDownloader最值得借鉴的是**生命周期清晰**：长生命周期Client/Handlers之下，每次Connect都替换并释放底层Connection；宿主通过一个断开回调决定有限重试。

WallHub当前最小且可靠的改进不是“所有协议失败都重建Engine”，而是先让已有reconnect接口保留失败来源，然后在已有Backend单点决定：重建Connection、重新认证，还是最后手段重建Engine。这样代码改动集中、易测、易定位，也避免架构臃肿。
