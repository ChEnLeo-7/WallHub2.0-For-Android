package com.wallhub.android.data.steamaccess

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wallhub.android.R
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.STEAM_ACCESS_DOH_BUILT_IN_LIMIT_PER_REGION
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessMode
import com.wallhub.android.core.model.SteamAccessPhase
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamDohCatalog
import com.wallhub.android.core.model.SteamDohEndpointStatus
import com.wallhub.android.core.model.SteamDohRegion
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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

private const val DOH_BENCHMARK_RETRY_MS = 60_000L

internal data class SteamDohWarmupResult(
    val domesticRoutes: Int,
    val foreignRoutes: Int,
    val measuredRoutes: Int,
    val elapsedMillis: Long,
) {
    val ready: Boolean
        get() =
            domesticRoutes >= STEAM_ACCESS_DOH_BUILT_IN_LIMIT_PER_REGION &&
                foreignRoutes >= STEAM_ACCESS_DOH_BUILT_IN_LIMIT_PER_REGION
}

/** Keeps Steam routing demand-only while asynchronously benchmarking the configured DoH catalog per network. */
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

    internal data class WebAcceleratedRoute(
        val originalAuthority: String,
        val networkType: String,
        val generation: Long,
        val port: Int,
        val targets: List<SteamWebDialTarget>,
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
        Executors.newFixedThreadPool(10),
    )
    internal val connectionPool = ConnectionPool(12, 10, TimeUnit.MINUTES)
    private val mutableState = MutableStateFlow(SteamAccessState())
    private val refreshGate = SteamAccessRefreshGate()
    private val dohSelectionLock = Mutex()

    @Volatile
    private var dohSelectionGeneration = -1L
    @Volatile
    private var dohSelectionFingerprint = ""
    @Volatile
    private var selectedDohEndpoints: List<String> = emptyList()
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
                var shouldBenchmark = false
                synchronized(this@SteamAccessManager) {
                    val changed = !preferencesInitialized ||
                        preferences.steamAccessEnabled != next.steamAccessEnabled ||
                        preferences.steamAccessMode != next.steamAccessMode ||
                        preferences.steamAccessHosts != next.steamAccessHosts ||
                        preferences.steamAccessDohEndpoints != next.steamAccessDohEndpoints ||
                        preferences.steamAccessDisabledDohEndpoints != next.steamAccessDisabledDohEndpoints
                    preferences = next
                    preferencesInitialized = true
                    if (changed) {
                        invalidate()
                        shouldBenchmark = next.steamAccessEnabled && next.steamAccessMode == SteamAccessMode.SMART_DOH
                    }
                }
                preferencesReady.complete(Unit)
                if (shouldBenchmark) scope.launch { ensureDohSelection(next) }
            }
        }
    }

    internal fun enhancedConnectionEnabled(): Boolean = preferencesInitialized && preferences.steamAccessEnabled

    internal suspend fun awaitEnhancedConnectionEnabled(expected: Boolean) {
        withTimeout(1_000L) {
            while (!preferencesInitialized || preferences.steamAccessEnabled != expected) delay(10L)
        }
    }

    internal suspend fun awaitDohConfiguration(expectedEnabledEndpoints: List<String>) {
        withTimeout(2_000L) {
            while (true) {
                val matches =
                    synchronized(this@SteamAccessManager) {
                        preferencesInitialized &&
                            preferences.steamAccessEnabled &&
                            preferences.steamAccessMode == SteamAccessMode.SMART_DOH &&
                            preferences.enabledSteamAccessDohEndpoints() == expectedEnabledEndpoints
                    }
                if (matches) return@withTimeout
                delay(10L)
            }
        }
    }

    internal suspend fun warmUpDohRoutes(force: Boolean = false): SteamDohWarmupResult {
        preferencesReady.await()
        val settings = synchronized(this) { preferences }
        require(settings.steamAccessEnabled && settings.steamAccessMode == SteamAccessMode.SMART_DOH) {
            "Automatic anti-blocking is not enabled"
        }
        val startedAt = System.nanoTime()
        ensureDohSelection(settings, force = force)
        val statuses = mutableState.value.dohEndpointStatuses
        fun selectedAvailable(region: SteamDohRegion): Int =
            statuses.count { status ->
                status.selected && status.available && SteamDohCatalog.endpointFor(status.endpoint).region == region
            }
        return SteamDohWarmupResult(
            domesticRoutes = selectedAvailable(SteamDohRegion.DOMESTIC),
            foreignRoutes = selectedAvailable(SteamDohRegion.FOREIGN),
            measuredRoutes = statuses.count { status -> status.available },
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        )
    }

    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname)

    internal suspend fun acceleratedOrWebCandidateRoute(hostname: String, port: Int): AcceleratedRoute {
        val host = SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
        lastEndpoint = host to port
        return resolve(host, port, web = true)
    }

    internal suspend fun acceleratedWebRoute(
        hostname: String,
        port: Int,
    ): WebAcceleratedRoute {
        val host = SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
        require(port == STEAM_HTTPS_PORT && !SteamDomainPolicy.isCmHost(host)) {
            "Authority-separated routes are restricted to Steam Web HTTPS"
        }
        val rules = SteamAccessRoutes.webUpstreams(host)
        require(rules.isNotEmpty()) { "No Steam Web route rules for $host" }
        lastEndpoint = host to port
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
            val authorities = rules.map(SteamWebUpstreamRule::resolveAuthority)
            val systemByAuthority =
                authorities.associateWith { authority ->
                    runCatching { Dns.SYSTEM.lookup(authority) }.getOrDefault(emptyList())
                }
            val resolvedByAuthority =
                if (!settings.steamAccessEnabled) {
                    emptyMap()
                } else {
                    when (settings.steamAccessMode) {
                        SteamAccessMode.HOSTS ->
                            mapOf(host to SteamHostsParser.parse(settings.steamAccessHosts)[host].orEmpty())
                        SteamAccessMode.SMART_DOH ->
                            dohResolver.resolveByHostname(
                                authorities,
                                currentDohEndpoints(settings),
                                currentNetworkSupportsIpv6(),
                            )
                    }
                }
            val selectedByAuthority =
                rules.associate { rule ->
                    val healthHost = steamWebRouteHealthHost(host, rule.tlsAuthority)
                    val cacheKey = steamWebRouteCacheKey(selectedNetwork, host, rule.tlsAuthority, port)
                    val addresses =
                        candidates.get(cacheKey, generation) ?: run {
                            val cooling = routeStore.coolingDownAddresses(selectedNetwork, healthHost, port)
                            val preferred =
                                routeStore.preferred(selectedNetwork, healthHost, port)
                                    .mapNotNull { address -> address.hostAddress }
                                    .toSet()
                            selectSteamCandidates(
                                systemByAuthority[rule.resolveAuthority].orEmpty(),
                                resolvedByAuthority[rule.resolveAuthority].orEmpty(),
                                preferred,
                                cooling,
                            ).also { resolved ->
                                if (!candidates.put(cacheKey, generation, resolved)) {
                                    throw IOException("Steam route changed during Web resolution")
                                }
                            }
                        }
                    rule.resolveAuthority to addresses
                }
            val targets = bindSteamWebTargets(rules, selectedByAuthority)
            if (targets.isEmpty()) throw IOException("No enhanced Web targets for $host")
            WebAcceleratedRoute(host, selectedNetwork, generation, port, targets)
        }
    }

    internal suspend fun enhancedWebAddresses(hostname: String, port: Int): List<InetAddress> =
        acceleratedWebRoute(hostname, port).targets.map(SteamWebDialTarget::address)

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
                        ensureDohSelection(settings),
                        currentNetworkSupportsIpv6(),
                    )
                }
            }
            val cooling = routeStore.coolingDownAddresses(selectedNetwork, host, port)
            // History ranks only currently resolved addresses; old built-in seeds cannot survive via persistence.
            val preferred = routeStore.preferred(selectedNetwork, host, port).mapNotNull { it.hostAddress }.toSet()
            val addresses =
                if (SteamDomainPolicy.isCmHost(host)) {
                    selectSteamCandidates(
                        preferIpv4Addresses(system),
                        preferIpv4Addresses(resolved),
                        preferred,
                        cooling,
                    )
                } else {
                    selectSteamCandidates(system, resolved, preferred, cooling)
                }
            if (!candidates.put(key, generation, addresses)) {
                throw IOException("Steam route changed during resolution")
            }
            if (addresses.isEmpty()) throw IOException("No enhanced candidates for $host")
            AcceleratedRoute(selectedNetwork, generation, port, addresses)
        }
    }

    internal fun isRouteCurrent(route: AcceleratedRoute): Boolean =
        candidates.generation == route.generation && networkType == route.networkType

    internal fun isRouteCurrent(route: WebAcceleratedRoute): Boolean =
        candidates.generation == route.generation && networkType == route.networkType

    @Synchronized
    internal fun commitWebRoute(
        route: WebAcceleratedRoute,
        commitTunnel: () -> Unit,
    ): Boolean {
        if (!isRouteCurrent(route)) return false
        commitTunnel()
        return true
    }

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
    internal fun recordWebResponse(
        route: WebAcceleratedRoute,
        target: SteamWebDialTarget,
        elapsedMs: Long,
    ) {
        if (!isRouteCurrent(route)) return
        val healthHost = steamWebRouteHealthHost(route.originalAuthority, target.rule.tlsAuthority)
        routeStore.recordSuccess(route.networkType, healthHost, route.port, target.address, elapsedMs)
        mutableState.value = mutableState.value.copy(
            phase = SteamAccessPhase.READY,
            networkType = route.networkType,
            activeHost = route.originalAuthority,
            selectedAddress = target.address.hostAddress,
            candidateCount = route.targets.size,
            message = context.getString(R.string.backend_steam_access_accelerated_connected),
            updatedAt = System.currentTimeMillis(),
        )
    }

    @Synchronized
    internal fun recordWebFailure(
        route: WebAcceleratedRoute,
        target: SteamWebDialTarget,
        error: Throwable,
        phase: String = "connect",
    ) {
        if (!isRouteCurrent(route)) return
        val healthHost = steamWebRouteHealthHost(route.originalAuthority, target.rule.tlsAuthority)
        routeStore.recordFailure(route.networkType, healthHost, route.port, target.address)
        candidates.removeAddress(
            steamWebRouteCacheKey(route.networkType, route.originalAuthority, target.rule.tlsAuthority, route.port),
            route.generation,
            target.address,
        )
        recordDiagnostic(
            "Steam Web upstream failed",
            mapOf(
                "host" to route.originalAuthority,
                "tlsAuthority" to target.rule.tlsAuthority,
                "address" to target.address.hostAddress.orEmpty(),
                "phase" to phase,
                "error" to error.javaClass.simpleName,
            ),
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

    private fun currentDohEndpoints(settings: AppPreferences): List<String> {
        val enabledUrls = settings.enabledSteamAccessDohEndpoints()
        val fingerprint = enabledUrls.joinToString("\u0000")
        return if (
            dohSelectionGeneration == candidates.generation &&
            dohSelectionFingerprint == fingerprint &&
            selectedDohEndpoints.isNotEmpty()
        ) {
            selectedDohEndpoints
        } else {
            fallbackDohEndpoints(enabledUrls.map(SteamDohCatalog::endpointFor))
        }
    }

    private suspend fun ensureDohSelection(
        settings: AppPreferences,
        force: Boolean = false,
    ): List<String> {
        val enabledUrls = settings.enabledSteamAccessDohEndpoints()
        val fingerprint = enabledUrls.joinToString("\u0000")
        val generation = candidates.generation
        if (
            !force &&
            dohSelectionGeneration == generation &&
            dohSelectionFingerprint == fingerprint
        ) {
            return selectedDohEndpoints
        }
        return dohSelectionLock.withLock {
            val currentGeneration = candidates.generation
            if (
                !force &&
                dohSelectionGeneration == currentGeneration &&
                dohSelectionFingerprint == fingerprint
            ) {
                return@withLock selectedDohEndpoints
            }
            val endpoints = enabledUrls.map(SteamDohCatalog::endpointFor)
            val results = dohResolver.benchmark(endpoints)
            val resultByUrl = results.associateBy { result -> result.endpoint.url }
            val selected = selectFastestDohEndpoints(endpoints, results)
            val effective = selected.ifEmpty { fallbackDohEndpoints(endpoints) }
            val latestFingerprint = preferences.enabledSteamAccessDohEndpoints().joinToString("\u0000")
            if (candidates.generation != currentGeneration || latestFingerprint != fingerprint) {
                return@withLock effective
            }
            if (selected.isNotEmpty()) {
                dohSelectionGeneration = currentGeneration
                dohSelectionFingerprint = fingerprint
                selectedDohEndpoints = selected
            }
            mutableState.value = mutableState.value.copy(
                dohEndpointStatuses = settings.steamAccessDohEndpoints.map { endpoint ->
                    val result = resultByUrl[endpoint]
                    SteamDohEndpointStatus(
                        endpoint = endpoint,
                        latencyMs = result?.latencyMs,
                        selected = endpoint in effective,
                        available = result?.available == true,
                    )
                },
                updatedAt = System.currentTimeMillis(),
            )
            if (selected.isEmpty() && endpoints.isNotEmpty()) {
                scope.launch {
                    delay(DOH_BENCHMARK_RETRY_MS)
                    val latest = preferences
                    if (latest.steamAccessEnabled && latest.steamAccessMode == SteamAccessMode.SMART_DOH) {
                        ensureDohSelection(latest)
                    }
                }
            }
            effective
        }
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
        dohSelectionGeneration = -1L
        dohSelectionFingerprint = ""
        selectedDohEndpoints = emptyList()
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
        val settings = preferences
        if (preferencesInitialized && settings.steamAccessEnabled && settings.steamAccessMode == SteamAccessMode.SMART_DOH) {
            scope.launch { ensureDohSelection(settings) }
        }
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
