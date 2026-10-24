package com.wallhub.android.data.steamaccess

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wallhub.android.R
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves only actual requests (or an explicit diagnostic refresh), never lifecycle intent. */
@Singleton
class SteamAccessManager @Inject internal constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
    private val diagnostics: DiagnosticRepository,
) : Dns, SteamAccessRepository {
    internal data class AcceleratedRoute(
        val networkType: String,
        val generation: Long,
        val port: Int,
        val addresses: List<InetAddress>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val routeStore = SteamAccessRouteStore(context)
    private val candidates = SteamRequestRouteCache()
    // Bounded single-flight stripes avoid serializing every unrelated hostname behind one DNS query.
    private val resolutionLocks = Array(16) { Mutex() }
    private val preferencesReady = CompletableDeferred<Unit>()
    private val dohResolver = SteamAccessDohResolver(
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build(),
        Executors.newFixedThreadPool(6),
    )
    internal val connectionPool = ConnectionPool(12, 10, TimeUnit.MINUTES)
    private val mutableState = MutableStateFlow(SteamAccessState())
    private val refreshGate = SteamAccessRefreshGate()
    override val state: StateFlow<SteamAccessState> = mutableState.asStateFlow()

    @Volatile
    private var preferences = AppPreferences()
    @Volatile
    private var preferencesInitialized = false
    @Volatile
    private var activeNetwork: Network? = connectivityManager.activeNetwork
    @Volatile
    private var networkType = detectNetworkType(activeNetwork)
    @Volatile
    private var lastEndpoint: Pair<String, Int>? = null

    init {
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged(network)

                override fun onLost(network: Network) {
                    if (activeNetwork == network) networkChanged(null)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (network == activeNetwork) networkChanged(network)
                }
            },
        )
        scope.launch {
            settingsRepository.preferences.collectLatest { next ->
                synchronized(this@SteamAccessManager) {
                    val changed = !preferencesInitialized ||
                        preferences.steamAccessEnabled != next.steamAccessEnabled ||
                        preferences.steamAccessMode != next.steamAccessMode ||
                        preferences.steamAccessHosts != next.steamAccessHosts ||
                        preferences.steamAccessDohEndpoints != next.steamAccessDohEndpoints ||
                        preferences.steamAccessDisabledDohEndpoints != next.steamAccessDisabledDohEndpoints
                    preferences = next
                    preferencesInitialized = true
                    if (changed) invalidate()
                }
                preferencesReady.complete(Unit)
            }
        }
    }

    internal fun enhancedConnectionEnabled(): Boolean = preferencesInitialized && preferences.steamAccessEnabled

    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname)

    internal suspend fun acceleratedOrWebCandidateRoute(hostname: String, port: Int): AcceleratedRoute {
        val host = SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
        lastEndpoint = host to port
        return resolve(host, port, web = true)
    }

    internal suspend fun enhancedWebAddresses(hostname: String, port: Int): List<InetAddress> =
        acceleratedOrWebCandidateRoute(hostname, port).addresses

    internal suspend fun enhancedAddresses(hostname: String): List<InetAddress> {
        val host = SteamDomainPolicy.requireAuthenticatedCdnEndpoint(hostname, STEAM_HTTPS_PORT)
        return resolve(host, STEAM_HTTPS_PORT, web = false).addresses
    }

    private suspend fun resolve(host: String, port: Int, web: Boolean): AcceleratedRoute {
        preferencesReady.await()
        val lock = resolutionLocks[(host.hashCode() and Int.MAX_VALUE) % resolutionLocks.size]
        return lock.withLock {
            val generation: Long
            val selectedNetwork: String
            val settings: AppPreferences
            synchronized(this) {
                generation = candidates.generation
                selectedNetwork = networkType
                settings = preferences
            }
            val key = steamRouteCacheKey(selectedNetwork, host, port)
            candidates.get(key, generation)?.let {
                return@withLock AcceleratedRoute(selectedNetwork, generation, port, it)
            }
            val system = runCatching { Dns.SYSTEM.lookup(host) }.getOrDefault(emptyList())
            val resolved = if (!settings.steamAccessEnabled) {
                emptyList()
            } else {
                when (settings.steamAccessMode) {
                    SteamAccessMode.HOSTS -> SteamHostsParser.parse(settings.steamAccessHosts)[host].orEmpty()
                    SteamAccessMode.SMART_DOH -> dohResolver.resolve(
                        listOf(host) + if (web) SteamAccessRoutes.aliases(host) else emptyList(),
                        settings.enabledSteamAccessDohEndpoints(),
                        currentNetworkSupportsIpv6(),
                    )
                }
            }
            val cooling = routeStore.coolingDownAddresses(selectedNetwork, host, port)
            // History ranks only currently resolved addresses; old built-in seeds cannot survive via persistence.
            val preferred = routeStore.preferred(selectedNetwork, host, port).mapNotNull { it.hostAddress }.toSet()
            val allAddresses = if (SteamDomainPolicy.isCmHost(host)) {
                preferIpv4Addresses(system + resolved)
            } else {
                system + resolved
            }
            val addresses = selectSteamCandidates(allAddresses, emptyList(), preferred, cooling)
            if (!candidates.put(key, generation, addresses)) {
                throw IOException("Steam route changed during resolution")
            }
            if (addresses.isEmpty()) throw IOException("No enhanced candidates for $host")
            AcceleratedRoute(selectedNetwork, generation, port, addresses)
        }
    }

    internal fun isRouteCurrent(route: AcceleratedRoute): Boolean =
        candidates.generation == route.generation && networkType == route.networkType

    @Synchronized
    internal fun commitAcceleratedRoute(
        route: AcceleratedRoute,
        commitTunnel: () -> Unit,
    ): Boolean {
        if (!isRouteCurrent(route)) return false
        commitTunnel()
        return true // TLS success is not evidence that the HTTP response worked.
    }

    @Synchronized
    internal fun recordResponse(
        route: AcceleratedRoute,
        hostname: String,
        address: InetAddress,
        elapsedMs: Long,
    ) {
        if (!isRouteCurrent(route)) return
        routeStore.recordSuccess(route.networkType, hostname, route.port, address, elapsedMs)
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.READY,
            networkType = route.networkType,
            activeHost = hostname,
            selectedAddress = address.hostAddress,
            candidateCount = route.addresses.size,
            message = context.getString(R.string.backend_steam_access_accelerated_connected),
            updatedAt = System.currentTimeMillis(),
        )
    }

    @Synchronized
    internal fun recordAcceleratedFailure(
        hostname: String,
        selectedNetworkType: String,
        generation: Long,
        port: Int,
        address: InetAddress,
        error: Throwable,
        phase: String = "connect",
    ) {
        if (candidates.generation != generation || networkType != selectedNetworkType) return
        routeStore.recordFailure(selectedNetworkType, hostname, port, address)
        candidates.removeAddress(steamRouteCacheKey(selectedNetworkType, hostname, port), generation, address)
        recordDiagnostic(
            "Steam upstream failed",
            mapOf(
                "host" to hostname,
                "address" to address.hostAddress.orEmpty(),
                "phase" to phase,
                "error" to error.javaClass.simpleName,
            ),
        )
    }

    internal fun recordBridgeFailure(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.DEGRADED,
            message = context.getString(R.string.backend_steam_access_route_failed, error.javaClass.simpleName),
            updatedAt = System.currentTimeMillis(),
        )
        recordDiagnostic("Steam loopback bridge failed", mapOf("error" to error.javaClass.simpleName))
    }

    private fun recordRefreshFailure(error: Throwable, hadUsableRoute: Boolean) {
        mutableState.value = mutableState.value.copy(
            phase = steamAccessRefreshFailurePhase(error, hadUsableRoute),
            message = context.getString(R.string.backend_steam_access_route_failed, error.javaClass.simpleName),
            updatedAt = System.currentTimeMillis(),
        )
        recordDiagnostic("Steam route refresh failed", mapOf("error" to error.javaClass.simpleName))
    }

    /** Explicit UI action: publish intent only; all cleanup and resolution run on the manager IO scope. */
    override fun refresh() {
        val enabled = preferencesInitialized && preferences.steamAccessEnabled
        if (!enabled) {
            mutableState.value = SteamAccessState(phase = SteamAccessPhase.DISABLED)
            return
        }
        if (!refreshGate.tryStart()) return
        val hadUsableRoute = mutableState.value.run {
            phase == SteamAccessPhase.READY && selectedAddress != null
        }
        mutableState.value = mutableState.value.copy(
            phase = steamAccessRefreshStartPhase(enabled = true),
            message = null,
            updatedAt = System.currentTimeMillis(),
        )
        scope.launch {
            var refreshGeneration: Long? = null
            try {
                preferencesReady.await()
                val endpoint = lastEndpoint
                refreshGeneration = synchronized(this@SteamAccessManager) {
                    invalidate(phase = SteamAccessPhase.RESOLVING, schedulePoolEviction = false)
                    candidates.generation
                }
                connectionPool.evictAll()
                if (endpoint == null) {
                    throw IOException("No Steam endpoint has been requested")
                }
                val route = acceleratedOrWebCandidateRoute(endpoint.first, endpoint.second)
                synchronized(this@SteamAccessManager) {
                    if (isRouteCurrent(route)) {
                        mutableState.value = mutableState.value.copy(
                            phase = SteamAccessPhase.READY,
                            activeHost = endpoint.first,
                            candidateCount = route.addresses.size,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                synchronized(this@SteamAccessManager) {
                    if (refreshGeneration == null || candidates.generation == refreshGeneration) {
                        recordRefreshFailure(error, hadUsableRoute)
                    }
                }
            } finally {
                refreshGate.finish()
            }
        }
    }

    @Synchronized
    private fun invalidate(
        phase: SteamAccessPhase = invalidatedSteamAccessPhase(),
        schedulePoolEviction: Boolean = true,
    ) {
        candidates.invalidate()
        mutableState.value = SteamAccessState(phase = phase, networkType = networkType)
        if (schedulePoolEviction) scope.launch { connectionPool.evictAll() }
    }

    private fun invalidatedSteamAccessPhase(): SteamAccessPhase = when {
        !preferences.steamAccessEnabled -> SteamAccessPhase.DISABLED
        lastEndpoint == null -> SteamAccessPhase.FAILED
        else -> SteamAccessPhase.DEGRADED
    }

    @Synchronized
    private fun networkChanged(network: Network?) {
        val type = detectNetworkType(network)
        if (network == activeNetwork && type == networkType) return
        activeNetwork = network
        networkType = type
        invalidate()
    }

    private fun detectNetworkType(network: Network?): String {
        if (network == null) return "offline"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun currentNetworkSupportsIpv6(): Boolean = activeNetwork?.let {
        connectivityManager.getLinkProperties(it)?.linkAddresses?.any { link -> link.address.address.size == 16 }
    } == true

    private fun recordDiagnostic(message: String, attributes: Map<String, String>) {
        scope.launch {
            try {
                diagnostics.record(
                    DiagnosticEvent(
                        source = "steam-access",
                        level = DiagnosticLevel.WARNING,
                        message = message,
                        attributes = attributes,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Diagnostics are best-effort and must never become a second process-fatal failure.
            }
        }
    }
}

internal fun preferIpv4Addresses(addresses: List<InetAddress>): List<InetAddress> {
    val ipv4 = addresses.filterIsInstance<Inet4Address>()
    return if (ipv4.isEmpty()) addresses else ipv4
}
