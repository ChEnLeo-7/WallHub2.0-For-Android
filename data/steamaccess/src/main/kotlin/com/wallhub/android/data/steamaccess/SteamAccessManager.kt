package com.wallhub.android.data.steamaccess

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessMode
import com.wallhub.android.core.model.SteamAccessPhase
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.enabledSteamAccessDohEndpoints
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response

@Singleton
class SteamAccessManager @Inject internal constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
    private val diagnostics: DiagnosticRepository,
    private val noSniTlsDialer: NoSniTlsDialer,
) : Dns, SteamAccessRepository {
    internal data class AcceleratedRoute(
        val networkType: String,
        val generation: Long,
        val addresses: List<InetAddress>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val routeStore = SteamAccessRouteStore(context)
    private val queryExecutor = Executors.newFixedThreadPool(6)
    private val directProbeClient = OkHttpClient.Builder()
        .dns(Dns.SYSTEM)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    private val dohResolver = SteamAccessDohResolver(directProbeClient, queryExecutor)
    private val directProbe = SteamAccessProbe(directProbeClient, queryExecutor)
    private val routeSnapshots = SteamRouteSnapshotCache()
    private val refreshInFlight = ConcurrentHashMap.newKeySet<String>()
    private val scheduledRefreshes = ConcurrentHashMap<String, Job>()
    private val routeRefreshMutex = Mutex()
    private val routeGeneration = AtomicLong()
    private val mutableState = MutableStateFlow(SteamAccessState())

    internal val connectionPool = ConnectionPool(
        MAX_IDLE_CONNECTIONS,
        CONNECTION_KEEP_ALIVE_MINUTES,
        TimeUnit.MINUTES,
    )

    @Volatile
    private var preferences = AppPreferences()

    @Volatile
    private var parsedHosts: Map<String, List<InetAddress>> = emptyMap()

    @Volatile
    private var activeNetwork: Network? = connectivityManager.activeNetwork

    @Volatile
    private var networkType: String = detectNetworkType(activeNetwork)

    private var preferencesInitialized = false

    // Route discovery is background-only; ProxySelector reads published snapshots.
    private val refreshQueue = SteamAccessRefreshQueue(scope, ::performRefresh)

    override val state: StateFlow<SteamAccessState> = mutableState.asStateFlow()

    init {
        restorePersistedRoutes()
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleDefaultNetwork(network)

                override fun onLost(network: Network) {
                    if (activeNetwork == network) handleDefaultNetwork(null)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (activeNetwork != network) {
                        handleDefaultNetwork(network)
                    } else {
                        val nextType = detectNetworkType(capabilities)
                        if (nextType != networkType) handleDefaultNetwork(network)
                    }
                }
            },
        )
        scope.launch {
            settingsRepository.preferences.collectLatest { next ->
                val wasInitialized = preferencesInitialized
                val accessEnabledChanged = wasInitialized &&
                    preferences.steamAccessEnabled != next.steamAccessEnabled
                val routeSettingsChanged = !wasInitialized ||
                    accessEnabledChanged ||
                    preferences.steamAccessMode != next.steamAccessMode ||
                    preferences.steamAccessDohEndpoints != next.steamAccessDohEndpoints ||
                    preferences.steamAccessDisabledDohEndpoints != next.steamAccessDisabledDohEndpoints ||
                    preferences.steamAccessHosts != next.steamAccessHosts
                preferences = next
                preferencesInitialized = true
                parsedHosts = SteamHostsParser.parse(next.steamAccessHosts)
                if (routeSettingsChanged) {
                    handleRouteSettingsChanged(accessEnabledChanged)
                }
            }
        }
    }

    private fun restorePersistedRoutes() {
        val now = System.currentTimeMillis()
        CORE_WARMUP_HOSTS.forEach { host ->
            val candidates = routeStore.preferred(networkType, host).take(MAX_NO_SNI_PROBE_ADDRESSES)
            if (candidates.isNotEmpty()) {
                routeSnapshots.publish(
                    routeKey(networkType, host),
                    SteamCachedRoute(
                        available = true,
                        accelerated = true,
                        addresses = candidates,
                        freshUntil = 0L,
                        staleUntil = now + BOOTSTRAP_ROUTE_STALE_MS,
                    ),
                )
            }
        }
    }

    @Synchronized
    private fun handleRouteSettingsChanged(accessEnabledChanged: Boolean) {
        routeGeneration.incrementAndGet()
        routeSnapshots.markAllStale()
        cancelScheduledRefreshes()
        if (accessEnabledChanged) connectionPool.evictAll()
        refresh()
    }

    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname)

    internal fun shouldAccelerate(hostname: String): Boolean {
        val host = hostname.lowercase().trimEnd('.')
        if (!preferences.steamAccessEnabled || !SteamDomainPolicy.supports(host)) return false
        val lookup = routeSnapshots.lookup(routeKey(networkType, host))
        if (lookup.shouldRefresh) requestRouteRefresh(host)
        return lookup.route?.accelerated == true
    }

    internal fun acceleratedRoute(hostname: String): AcceleratedRoute {
        val host = SteamDomainPolicy.requireSupported(hostname)
        val selectedNetworkType = networkType
        val selectedGeneration = routeGeneration.get()
        val lookup = routeSnapshots.lookup(routeKey(selectedNetworkType, host))
        if (lookup.shouldRefresh) requestRouteRefresh(host)
        val route = lookup.route
        if (
            route == null ||
            !route.accelerated ||
            route.addresses.isEmpty() ||
            routeGeneration.get() != selectedGeneration ||
            networkType != selectedNetworkType
        ) {
            throw IOException("No accelerated route for $host")
        }
        return AcceleratedRoute(
            networkType = selectedNetworkType,
            generation = selectedGeneration,
            addresses = route.addresses,
        )
    }

    internal fun isRouteCurrent(route: AcceleratedRoute): Boolean =
        routeGeneration.get() == route.generation && networkType == route.networkType

    internal fun eventListener(): EventListener = SteamAccessEventListener()

    @Synchronized
    internal fun commitAcceleratedRoute(
        route: AcceleratedRoute,
        hostname: String,
        address: InetAddress,
        elapsedMs: Long,
        commitTunnel: () -> Unit,
    ): Boolean {
        if (routeGeneration.get() != route.generation || networkType != route.networkType) return false
        commitTunnel()
        val host = SteamDomainPolicy.requireSupported(hostname)
        routeStore.recordSuccess(route.networkType, host, address, elapsedMs)
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.READY,
            networkType = route.networkType,
            activeHost = host,
            selectedAddress = address.hostAddress,
            message = "内置无 SNI 线路已连接",
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    internal fun recordAcceleratedFailure(
        hostname: String,
        selectedNetworkType: String,
        generation: Long,
        address: InetAddress,
        error: Throwable,
    ) {
        if (routeGeneration.get() != generation || networkType != selectedNetworkType) return
        val host = SteamDomainPolicy.requireSupported(hostname)
        routeStore.recordFailure(selectedNetworkType, host, address)
        val key = routeKey(selectedNetworkType, host)
        if (routeSnapshots.removeAddress(key, address)) requestRouteRefresh(host)
        recordDiagnostic(
            level = DiagnosticLevel.WARNING,
            message = "Steam no-SNI candidate failed",
            attributes = mapOf(
                "host" to host,
                "address" to address.hostAddress.orEmpty(),
                "error" to error.javaClass.simpleName,
            ),
        )
    }

    internal fun recordBridgeFailure(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.DEGRADED,
            message = "内置线路失败：${error.javaClass.simpleName}",
            updatedAt = System.currentTimeMillis(),
        )
        recordDiagnostic(
            level = DiagnosticLevel.WARNING,
            message = "Steam loopback bridge failed",
            attributes = mapOf("error" to error.javaClass.simpleName),
        )
    }

    override fun refresh() {
        refreshQueue.request()
    }

    private fun performRefresh() {
        runCatching {
            routeSnapshots.markAllStale()
            val settings = preferences
            if (!settings.steamAccessEnabled) {
                mutableState.value = SteamAccessState(phase = SteamAccessPhase.DISABLED)
                return
            }
            mutableState.value = SteamAccessState(
                phase = SteamAccessPhase.RESOLVING,
                networkType = networkType,
                message = "正在后台检测 Steam 服务线路",
            )
            CORE_WARMUP_HOSTS.forEach(::requestRouteRefresh)
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(
                phase = SteamAccessPhase.FAILED,
                message = "Steam 线路检测失败：${error.javaClass.simpleName}",
                updatedAt = System.currentTimeMillis(),
            )
            recordDiagnostic(
                level = DiagnosticLevel.WARNING,
                message = "Steam route refresh failed",
                attributes = mapOf("error" to error.javaClass.simpleName),
            )
        }
    }

    private fun requestRouteRefresh(hostname: String) {
        val host = SteamDomainPolicy.requireSupported(hostname)
        val settings = preferences
        if (!settings.steamAccessEnabled) return
        val selectedNetworkType = networkType
        val generation = routeGeneration.get()
        val cacheKey = routeKey(selectedNetworkType, host)
        val refreshKey = "$generation|$cacheKey"
        if (!refreshInFlight.add(refreshKey)) return
        val hostsSnapshot = parsedHosts
        val fallbackAvailable = routeSnapshots.lookup(cacheKey).route?.available == true
        scope.launch {
            try {
                val route = routeRefreshMutex.withLock {
                    if (routeGeneration.get() != generation || networkType != selectedNetworkType) {
                        return@withLock null
                    }
                    buildRoute(
                        hostname = host,
                        selectedNetworkType = selectedNetworkType,
                        settings = settings,
                        hostsSnapshot = hostsSnapshot,
                        generation = generation,
                        fallbackAvailable = fallbackAvailable,
                    )
                } ?: return@launch
                if (routeGeneration.get() == generation && networkType == selectedNetworkType) {
                    val selected = routeSnapshots.publishKeepingUsable(cacheKey, route)
                    val nextRefreshAt = if (selected == route) {
                        route.freshUntil
                    } else {
                        System.currentTimeMillis() + FAILED_REFRESH_RETRY_MS
                    }
                    scheduleBackgroundRefresh(
                        hostname = host,
                        selectedNetworkType = selectedNetworkType,
                        generation = generation,
                        freshUntil = nextRefreshAt,
                    )
                }
            } catch (error: Throwable) {
                recordDiagnostic(
                    level = DiagnosticLevel.WARNING,
                    message = "Steam route background refresh failed",
                    attributes = mapOf(
                        "host" to host,
                        "error" to error.javaClass.simpleName,
                    ),
                )
            } finally {
                refreshInFlight.remove(refreshKey)
            }
        }
    }

    private fun scheduleBackgroundRefresh(
        hostname: String,
        selectedNetworkType: String,
        generation: Long,
        freshUntil: Long,
    ) {
        val cacheKey = routeKey(selectedNetworkType, hostname)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay((freshUntil - System.currentTimeMillis()).coerceAtLeast(MIN_REFRESH_DELAY_MS))
            if (routeGeneration.get() == generation && preferences.steamAccessEnabled) {
                val lookup = routeSnapshots.lookup(cacheKey)
                if (lookup.shouldRefresh) requestRouteRefresh(hostname)
            }
        }
        scheduledRefreshes.put(cacheKey, job)?.cancel()
        job.invokeOnCompletion { scheduledRefreshes.remove(cacheKey, job) }
        job.start()
    }

    private fun cancelScheduledRefreshes() {
        scheduledRefreshes.values.forEach { job -> job.cancel() }
        scheduledRefreshes.clear()
    }

    private fun buildRoute(
        hostname: String,
        selectedNetworkType: String,
        settings: AppPreferences,
        hostsSnapshot: Map<String, List<InetAddress>>,
        generation: Long,
        fallbackAvailable: Boolean,
    ): SteamCachedRoute {
        val startedAt = System.currentTimeMillis()
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.RESOLVING,
            networkType = selectedNetworkType,
            activeHost = hostname,
            message = "正在检测 $hostname",
            updatedAt = startedAt,
        )
        val systemAddresses = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val directHealthy = directProbe.rank(hostname, systemAddresses).any(SteamProbeResult::successful)
        if (directHealthy) {
            val now = System.currentTimeMillis()
            val route = SteamCachedRoute(
                available = true,
                accelerated = false,
                addresses = emptyList(),
                freshUntil = now + DIRECT_ROUTE_FRESH_MS,
                staleUntil = now + DIRECT_ROUTE_STALE_MS,
            )
            if (routeGeneration.get() == generation) {
                mutableState.value = mutableState.value.copy(
                    phase = SteamAccessPhase.READY,
                    networkType = selectedNetworkType,
                    activeHost = hostname,
                    selectedAddress = systemAddresses.firstOrNull()?.hostAddress,
                    candidateCount = systemAddresses.size,
                    message = "Steam 服务直连正常",
                    updatedAt = System.currentTimeMillis(),
                )
            }
            return route
        }

        val resolvedAddresses = when (settings.steamAccessMode) {
            SteamAccessMode.HOSTS -> hostsSnapshot[hostname].orEmpty()
            SteamAccessMode.SMART_DOH -> dohResolver.resolve(
                hostnames = listOf(hostname) + SteamAccessRoutes.aliases(hostname),
                endpoints = settings.enabledSteamAccessDohEndpoints(),
                includeIpv6 = currentNetworkSupportsIpv6(),
            )
        }
        val coolingDown = routeStore.coolingDownAddresses(selectedNetworkType, hostname)
        val candidates = buildList {
            addAll(routeStore.preferred(selectedNetworkType, hostname))
            addAll(resolvedAddresses)
            addAll(systemAddresses)
            addAll(SteamAccessRoutes.seeds(hostname))
        }.distinctBy(InetAddress::getHostAddress)
            .filterNot { address -> address.hostAddress.orEmpty() in coolingDown }
            .take(MAX_NO_SNI_PROBE_ADDRESSES)
        val tasks = candidates.map { address -> Callable { noSniTlsDialer.probe(hostname, address) } }
        val probeResults = if (tasks.isEmpty()) {
            emptyList()
        } else {
            queryExecutor.invokeAll(tasks, NO_SNI_PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
                .mapIndexed { index, future ->
                    runCatching { future.get() }.getOrElse {
                        SteamProbeResult(candidates[index], false, NO_SNI_PROBE_BUDGET_MS)
                    }
                }
                .sortedWith(compareByDescending<SteamProbeResult> { it.successful }.thenBy { it.elapsedMs })
        }
        probeResults.forEach { result ->
            if (result.successful) {
                routeStore.recordSuccess(selectedNetworkType, hostname, result.address, result.elapsedMs)
            } else {
                routeStore.recordFailure(selectedNetworkType, hostname, result.address)
            }
        }
        val successfulAddresses = probeResults.filter(SteamProbeResult::successful).map(SteamProbeResult::address)
        val accelerated = successfulAddresses.isNotEmpty()
        val now = System.currentTimeMillis()
        val route = SteamCachedRoute(
            available = accelerated,
            accelerated = accelerated,
            addresses = successfulAddresses,
            freshUntil = now + if (accelerated) ACCELERATED_ROUTE_FRESH_MS else FAILED_ROUTE_TTL_MS,
            staleUntil = now + if (accelerated) ACCELERATED_ROUTE_STALE_MS else FAILED_ROUTE_TTL_MS,
        )
        if (routeGeneration.get() == generation) {
            mutableState.value = mutableState.value.copy(
                phase = when {
                    accelerated -> SteamAccessPhase.READY
                    fallbackAvailable -> SteamAccessPhase.DEGRADED
                    else -> SteamAccessPhase.FAILED
                },
                networkType = selectedNetworkType,
                activeHost = hostname,
                selectedAddress = successfulAddresses.firstOrNull()?.hostAddress,
                candidateCount = successfulAddresses.size,
                message = when {
                    accelerated -> "检测到直连异常，已启用内置无 SNI 线路"
                    fallbackAvailable -> "线路刷新失败，继续使用最近可用线路"
                    else -> "直连与内置线路均不可用"
                },
                updatedAt = System.currentTimeMillis(),
            )
        }
        recordDiagnostic(
            level = if (accelerated) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            message = if (accelerated) "Steam no-SNI route selected" else "Steam routes unavailable",
            attributes = mapOf(
                "host" to hostname,
                "candidateCount" to successfulAddresses.size.toString(),
            ),
        )
        return route
    }

    @Synchronized
    private fun handleDefaultNetwork(nextNetwork: Network?) {
        val nextType = detectNetworkType(nextNetwork)
        if (activeNetwork == nextNetwork && networkType == nextType) return
        activeNetwork = nextNetwork
        networkType = nextType
        routeGeneration.incrementAndGet()
        routeSnapshots.clear()
        cancelScheduledRefreshes()
        connectionPool.evictAll()
        refresh()
    }

    private fun detectNetworkType(network: Network?): String {
        if (network == null) return "offline"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "unknown"
        return detectNetworkType(capabilities)
    }

    private fun detectNetworkType(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    private fun routeKey(selectedNetworkType: String, hostname: String): String =
        "$selectedNetworkType|${hostname.lowercase().trimEnd('.')}"

    private fun currentNetworkSupportsIpv6(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.any { linkAddress -> linkAddress.address.address.size == 16 }
            ?: false
    }

    private inner class SteamAccessEventListener : EventListener() {
        private var connectedAddress: InetAddress? = null
        private var connectingHost: String? = null

        override fun dnsStart(call: Call, domainName: String) {
            connectingHost = domainName
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            connectedAddress = connection.route().socketAddress.address
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            connectedAddress = inetSocketAddress.address
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            val host = response.request.url.host
            if (!preferences.steamAccessEnabled || !SteamDomainPolicy.supports(host)) return
            val address = connectedAddress ?: return
            if (address.isLoopbackAddress) return
            mutableState.value = mutableState.value.copy(
                phase = SteamAccessPhase.READY,
                networkType = networkType,
                activeHost = host,
                selectedAddress = address.hostAddress,
                message = "Steam 服务直连正常",
                updatedAt = System.currentTimeMillis(),
            )
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            val host = connectingHost ?: return
            if (!preferences.steamAccessEnabled || !SteamDomainPolicy.supports(host)) return
            val cacheKey = routeKey(networkType, host)
            routeSnapshots.remove(cacheKey)
            requestRouteRefresh(host)
            mutableState.value = mutableState.value.copy(
                phase = SteamAccessPhase.DEGRADED,
                activeHost = host,
                message = "Steam 直连异常，将重新检测内置线路",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun recordDiagnostic(
        level: DiagnosticLevel,
        message: String,
        attributes: Map<String, String>,
    ) {
        scope.launch {
            diagnostics.record(
                DiagnosticEvent(
                    source = "steam-access",
                    level = level,
                    message = message,
                    attributes = attributes,
                ),
            )
        }
    }

    private companion object {
        val CORE_WARMUP_HOSTS = listOf(
            "steamcommunity.com",
            "api.steampowered.com",
            "community.steam-api.com",
        )
        const val DIRECT_ROUTE_FRESH_MS = 5 * 60_000L
        const val DIRECT_ROUTE_STALE_MS = 30 * 60_000L
        const val ACCELERATED_ROUTE_FRESH_MS = 10 * 60_000L
        const val ACCELERATED_ROUTE_STALE_MS = 2 * 60 * 60_000L
        const val FAILED_ROUTE_TTL_MS = 60_000L
        const val BOOTSTRAP_ROUTE_STALE_MS = 5 * 60_000L
        const val MAX_IDLE_CONNECTIONS = 12
        const val CONNECTION_KEEP_ALIVE_MINUTES = 10L
        const val MIN_REFRESH_DELAY_MS = 30_000L
        const val FAILED_REFRESH_RETRY_MS = 60_000L
        const val MAX_NO_SNI_PROBE_ADDRESSES = 4
        const val NO_SNI_PROBE_BUDGET_MS = 6_000L
    }
}
