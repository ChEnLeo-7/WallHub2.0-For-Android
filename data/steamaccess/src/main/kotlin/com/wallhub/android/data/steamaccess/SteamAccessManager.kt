package com.wallhub.android.data.steamaccess

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessMode
import com.wallhub.android.core.model.SteamAccessPhase
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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
class SteamAccessManager @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
    private val diagnostics: DiagnosticRepository,
) : Dns, SteamAccessRepository {
    private data class CachedRoute(
        val addresses: List<InetAddress>,
        val enhancedAddressCount: Int,
        val expiresAt: Long,
        val fallbackAddresses: Set<String>,
        val enhancedAddresses: Set<String>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val routeStore = SteamAccessRouteStore(context)
    private val queryExecutor = Executors.newFixedThreadPool(6)
    private val dohClient = OkHttpClient.Builder()
        .dns(Dns.SYSTEM)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    private val dohResolver = SteamAccessDohResolver(dohClient, queryExecutor)
    private val routeProbe = SteamAccessProbe(dohClient, queryExecutor)
    private val routeCache = ConcurrentHashMap<String, CachedRoute>()
    private val routeCacheLock = Any()
    private val routeGeneration = AtomicLong()
    private val connectionPool = ConnectionPool()
    private val mutableState = MutableStateFlow(SteamAccessState())

    @Volatile
    private var preferences = AppPreferences()

    @Volatile
    private var parsedHosts: Map<String, List<InetAddress>> = emptyMap()

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
                val routeSettingsChanged = preferences.steamAccessEnabled != next.steamAccessEnabled ||
                    preferences.steamAccessMode != next.steamAccessMode ||
                    preferences.steamAccessDohEndpoints != next.steamAccessDohEndpoints ||
                    preferences.steamAccessHosts != next.steamAccessHosts ||
                    preferences.downloadProxyEnabled != next.downloadProxyEnabled
                preferences = next
                parsedHosts = SteamHostsParser.parse(next.steamAccessHosts)
                if (routeSettingsChanged) refresh()
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val generation = routeGeneration.get()
        val settings = preferences
        if (!settings.steamAccessEnabled || settings.downloadProxyEnabled || !SteamAccessRoutes.supports(hostname)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val networkType = currentNetworkType()
        val cacheKey = "$networkType|${hostname.lowercase()}"
        val now = System.currentTimeMillis()
        synchronized(routeCacheLock) {
            routeCache[cacheKey]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        }

        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.RESOLVING,
            networkType = networkType,
            activeHost = hostname,
            message = null,
            updatedAt = now,
        )

        return runCatching {
            val route = buildRoute(hostname, networkType, settings)
            val routeIsCurrent = synchronized(routeCacheLock) {
                if (routeGeneration.get() != generation) {
                    false
                } else {
                    routeCache[cacheKey] = route
                    true
                }
            }
            if (!routeIsCurrent) return@runCatching Dns.SYSTEM.lookup(hostname)
            mutableState.value = mutableState.value.copy(
                phase = if (route.enhancedAddressCount > 0) SteamAccessPhase.READY else SteamAccessPhase.DEGRADED,
                networkType = networkType,
                activeHost = hostname,
                candidateCount = route.enhancedAddressCount,
                message = if (route.enhancedAddressCount > 0) null else "增强线路不可用，已使用系统网络",
                updatedAt = System.currentTimeMillis(),
            )
            route.addresses
        }.getOrElse { error ->
            recordDiagnostic(
                level = DiagnosticLevel.WARNING,
                message = "Steam route resolution failed",
                attributes = mapOf("host" to hostname, "error" to error.javaClass.simpleName),
            )
            mutableState.value = mutableState.value.copy(
                phase = SteamAccessPhase.DEGRADED,
                networkType = networkType,
                activeHost = hostname,
                message = "智能解析失败，已使用系统网络",
                updatedAt = System.currentTimeMillis(),
            )
            Dns.SYSTEM.lookup(hostname)
        }
    }

    override fun refresh() {
        synchronized(routeCacheLock) {
            routeGeneration.incrementAndGet()
            routeCache.clear()
        }
        connectionPool.evictAll()
        val settings = preferences
        if (!settings.steamAccessEnabled || settings.downloadProxyEnabled) {
            mutableState.value = SteamAccessState(phase = SteamAccessPhase.DISABLED)
            return
        }
        mutableState.value = SteamAccessState(
            phase = SteamAccessPhase.RESOLVING,
            networkType = currentNetworkType(),
            message = "正在预热 Steam 核心连接",
        )
        scope.launch {
            CORE_WARMUP_HOSTS.forEach { host -> runCatching { lookup(host) } }
        }
    }

    fun newClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .dns(this)
        .connectionPool(connectionPool)
        .eventListenerFactory { SteamAccessEventListener() }

    private fun buildRoute(
        hostname: String,
        networkType: String,
        settings: AppPreferences,
    ): CachedRoute {
        val systemAddresses = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val enhancedAddresses = when (settings.steamAccessMode) {
            SteamAccessMode.HOSTS -> parsedHosts[hostname.lowercase()].orEmpty()
            SteamAccessMode.SMART_DOH -> {
                val targets = listOf(hostname) + SteamAccessRoutes.aliases(hostname)
                dohResolver.resolve(
                    hostnames = targets,
                    endpoints = settings.steamAccessDohEndpoints,
                    includeIpv6 = currentNetworkSupportsIpv6(),
                )
            }
        }
        val coolingDownAddresses = routeStore.coolingDownAddresses(networkType, hostname)
        val enhancedCandidates = buildList {
            addAll(routeStore.preferred(networkType, hostname))
            addAll(enhancedAddresses)
            addAll(SteamAccessRoutes.seeds(hostname))
        }.distinctBy(InetAddress::getHostAddress)
            .filterNot { address -> address.hostAddress in coolingDownAddresses }
        val probeResults = routeProbe.rank(hostname, enhancedCandidates)
        probeResults.forEach { result ->
            if (result.successful) {
                routeStore.recordSuccess(networkType, hostname, result.address)
            } else {
                routeStore.recordFailure(networkType, hostname, result.address)
            }
        }
        val successfulAddresses = probeResults
            .filter(SteamProbeResult::successful)
            .map(SteamProbeResult::address)
        val ordered = buildList {
            addAll(successfulAddresses)
            addAll(systemAddresses)
        }.distinctBy(InetAddress::getHostAddress)
        if (ordered.isEmpty()) throw IOException("No addresses for $hostname")
        return CachedRoute(
            addresses = ordered,
            enhancedAddressCount = successfulAddresses.size,
            expiresAt = System.currentTimeMillis() + ROUTE_TTL_MS,
            fallbackAddresses = systemAddresses.map(InetAddress::getHostAddress).toSet(),
            enhancedAddresses = successfulAddresses
                .map(InetAddress::getHostAddress)
                .toSet(),
        )
    }

    private fun invalidateNetworkState() {
        synchronized(routeCacheLock) {
            routeGeneration.incrementAndGet()
            routeCache.clear()
        }
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
            val address = connectedAddress ?: return
            val host = response.request.url.host
            if (!preferences.steamAccessEnabled || !SteamAccessRoutes.supports(host)) return
            val networkType = currentNetworkType()
            val cached = routeCache["$networkType|${host.lowercase()}"] ?: return
            val usedFallback = cached?.fallbackAddresses?.contains(address.hostAddress) == true &&
                address.hostAddress !in cached.enhancedAddresses
            if (address.hostAddress in cached.enhancedAddresses) {
                scope.launch { routeStore.recordSuccess(networkType, host, address) }
            }
            mutableState.value = mutableState.value.copy(
                phase = if (cached.enhancedAddressCount == 0) SteamAccessPhase.DEGRADED else SteamAccessPhase.READY,
                networkType = networkType,
                activeHost = host,
                selectedAddress = address.hostAddress,
                fallbackCount = mutableState.value.fallbackCount + if (usedFallback) 1 else 0,
                message = if (usedFallback) "优选地址不可用，已回退系统网络" else null,
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
            if (!preferences.steamAccessEnabled || !SteamAccessRoutes.supports(host)) return
            val networkType = currentNetworkType()
            val cacheKey = "$networkType|${host.lowercase()}"
            val cached = routeCache[cacheKey] ?: return
            if (inetSocketAddress.address.hostAddress !in cached.enhancedAddresses) return
            routeStore.recordFailure(networkType, host, inetSocketAddress.address)
            routeCache.remove(cacheKey, cached)
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
        const val ROUTE_TTL_MS = 10 * 60_000L
    }
}

@Singleton
class SteamHttpClientFactory @Inject constructor(
    private val manager: SteamAccessManager,
) {
    fun newBuilder(): OkHttpClient.Builder = manager.newClientBuilder()
}
