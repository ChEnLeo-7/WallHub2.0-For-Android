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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    private data class CachedRoute(
        val accelerated: Boolean,
        val addresses: List<InetAddress>,
        val expiresAt: Long,
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
    private val routeCache = ConcurrentHashMap<String, CachedRoute>()
    private val routeLocks = ConcurrentHashMap<String, Any>()
    private val routeGeneration = AtomicLong()
    private val mutableState = MutableStateFlow(SteamAccessState())

    internal val connectionPool = ConnectionPool()

    @Volatile
    private var preferences = AppPreferences()

    @Volatile
    private var parsedHosts: Map<String, List<InetAddress>> = emptyMap()

    private var preferencesInitialized = false
    // evictAll can close live TLS sockets, so refresh work must never run on the UI caller.
    private val refreshQueue = SteamAccessRefreshQueue(scope, ::performRefresh)

    override val state: StateFlow<SteamAccessState> = mutableState.asStateFlow()

    init {
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = invalidateNetworkState()

                override fun onLost(network: Network) = invalidateNetworkState()

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    invalidateNetworkState()
            },
        )
        scope.launch {
            settingsRepository.preferences.collectLatest { next ->
                val routeSettingsChanged = !preferencesInitialized ||
                    preferences.steamAccessEnabled != next.steamAccessEnabled ||
                    preferences.steamAccessMode != next.steamAccessMode ||
                    preferences.steamAccessDohEndpoints != next.steamAccessDohEndpoints ||
                    preferences.steamAccessDisabledDohEndpoints != next.steamAccessDisabledDohEndpoints ||
                    preferences.steamAccessHosts != next.steamAccessHosts
                preferences = next
                preferencesInitialized = true
                parsedHosts = SteamHostsParser.parse(next.steamAccessHosts)
                if (routeSettingsChanged) refresh()
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname)

    internal fun shouldAccelerate(hostname: String): Boolean {
        val host = hostname.lowercase().trimEnd('.')
        if (!preferences.steamAccessEnabled || !SteamDomainPolicy.supports(host)) return false
        return routeFor(host).accelerated
    }

    internal fun acceleratedAddresses(hostname: String): List<InetAddress> {
        val host = SteamDomainPolicy.requireSupported(hostname)
        val route = routeFor(host)
        if (!route.accelerated || route.addresses.isEmpty()) {
            throw IOException("No accelerated route for $host")
        }
        return route.addresses
    }

    internal fun eventListener(): EventListener = SteamAccessEventListener()

    internal fun recordAcceleratedSuccess(hostname: String, address: InetAddress) {
        val host = SteamDomainPolicy.requireSupported(hostname)
        routeStore.recordSuccess(currentNetworkType(), host, address)
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.READY,
            networkType = currentNetworkType(),
            activeHost = host,
            selectedAddress = address.hostAddress,
            message = "内置无 SNI 线路已连接",
            updatedAt = System.currentTimeMillis(),
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
            invalidateRoutes()
            val settings = preferences
            if (!settings.steamAccessEnabled) {
                mutableState.value = SteamAccessState(phase = SteamAccessPhase.DISABLED)
                return
            }
            mutableState.value = SteamAccessState(
                phase = SteamAccessPhase.RESOLVING,
                networkType = currentNetworkType(),
                message = "正在检测 Steam 服务直连状态",
            )
            CORE_WARMUP_HOSTS.forEach { host -> runCatching { routeFor(host) } }
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

    private fun routeFor(hostname: String): CachedRoute {
        val host = SteamDomainPolicy.requireSupported(hostname)
        val networkType = currentNetworkType()
        val cacheKey = "$networkType|$host"
        val now = System.currentTimeMillis()
        routeCache[cacheKey]?.takeIf { route -> route.expiresAt > now }?.let { return it }
        val lock = routeLocks.getOrPut(cacheKey) { Any() }
        return synchronized(lock) {
            routeCache[cacheKey]?.takeIf { route -> route.expiresAt > System.currentTimeMillis() }
                ?: buildRoute(host, networkType, preferences).also { route -> routeCache[cacheKey] = route }
        }
    }

    private fun buildRoute(
        hostname: String,
        networkType: String,
        settings: AppPreferences,
    ): CachedRoute {
        val generation = routeGeneration.get()
        val startedAt = System.currentTimeMillis()
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.RESOLVING,
            networkType = networkType,
            activeHost = hostname,
            message = "正在检测 $hostname",
            updatedAt = startedAt,
        )
        val systemAddresses = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val directHealthy = directProbe.rank(hostname, systemAddresses).any(SteamProbeResult::successful)
        if (directHealthy) {
            val route = CachedRoute(
                accelerated = false,
                addresses = emptyList(),
                expiresAt = System.currentTimeMillis() + DIRECT_ROUTE_TTL_MS,
            )
            if (routeGeneration.get() == generation) {
                mutableState.value = mutableState.value.copy(
                    phase = SteamAccessPhase.READY,
                    networkType = networkType,
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
            SteamAccessMode.HOSTS -> parsedHosts[hostname].orEmpty()
            SteamAccessMode.SMART_DOH -> dohResolver.resolve(
                hostnames = listOf(hostname) + SteamAccessRoutes.aliases(hostname),
                endpoints = settings.enabledSteamAccessDohEndpoints(),
                includeIpv6 = currentNetworkSupportsIpv6(),
            )
        }
        val coolingDown = routeStore.coolingDownAddresses(networkType, hostname)
        val candidates = buildList {
            addAll(routeStore.preferred(networkType, hostname))
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
                routeStore.recordSuccess(networkType, hostname, result.address)
            } else {
                routeStore.recordFailure(networkType, hostname, result.address)
            }
        }
        val successfulAddresses = probeResults.filter(SteamProbeResult::successful).map(SteamProbeResult::address)
        val accelerated = successfulAddresses.isNotEmpty()
        val route = CachedRoute(
            accelerated = accelerated,
            addresses = successfulAddresses,
            expiresAt = System.currentTimeMillis() + if (accelerated) ACCELERATED_ROUTE_TTL_MS else FAILED_ROUTE_TTL_MS,
        )
        if (routeGeneration.get() == generation) {
            mutableState.value = mutableState.value.copy(
                phase = if (accelerated) SteamAccessPhase.READY else SteamAccessPhase.FAILED,
                networkType = networkType,
                activeHost = hostname,
                selectedAddress = successfulAddresses.firstOrNull()?.hostAddress,
                candidateCount = successfulAddresses.size,
                message = if (accelerated) {
                    "检测到直连异常，已启用内置无 SNI 线路"
                } else {
                    "直连与内置线路均不可用"
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

    private fun invalidateNetworkState() {
        refresh()
    }

    private fun invalidateRoutes() {
        routeGeneration.incrementAndGet()
        routeCache.clear()
        routeLocks.clear()
        connectionPool.evictAll()
    }

    private fun currentNetworkType(): String {
        val network = connectivityManager.activeNetwork ?: return "offline"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

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
                networkType = currentNetworkType(),
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
            val cacheKey = "${currentNetworkType()}|${host.lowercase()}"
            routeCache.remove(cacheKey)
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
        const val DIRECT_ROUTE_TTL_MS = 5 * 60_000L
        const val ACCELERATED_ROUTE_TTL_MS = 15 * 60_000L
        const val FAILED_ROUTE_TTL_MS = 60_000L
        const val MAX_NO_SNI_PROBE_ADDRESSES = 4
        const val NO_SNI_PROBE_BUDGET_MS = 6_000L
    }
}
