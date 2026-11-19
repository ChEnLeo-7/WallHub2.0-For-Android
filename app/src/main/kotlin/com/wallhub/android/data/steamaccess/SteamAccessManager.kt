package com.wallhub.android.data.steamaccess

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

private const val DOH_BENCHMARK_RETRY_MS = 60_000L
private const val ENHANCED_VERIFY_TIMEOUT_MS = 6_000L
private const val STEAM_VERIFY_HOST = "api.steampowered.com"

/** How long a caller waits for the decided egress before measuring whatever plan exists. */
internal const val STEAM_EGRESS_DECISION_WAIT_MS = 20_000L
private const val DECISION_POLL_INTERVAL_MS = 50L
private const val PREFERENCES_WAIT_MS = 2_000L

/**
 * The request head the relay sees for an anonymous Directory read.
 *
 * The enhanced dial is verified through the relay's own target selection, and that selection only allows
 * an alias authority for a request whose head the alias path accepts - so the verified attempt uses the
 * exact head the real Directory request produces rather than a hand-written approximation.
 */
internal val STEAM_DIRECTORY_VERIFY_REQUEST_HEAD: String =
    "GET $STEAM_DIRECTORY_PATH?$STEAM_DIRECTORY_QUERY HTTP/1.1\r\n" +
        "Host: $STEAM_VERIFY_HOST\r\n" +
        "Connection: close\r\n\r\n"

/** Maps a failed authority dial to the stage an operator has to act on. */
internal fun enhancedDialFailureStage(error: Throwable): SteamEnhancedPathStage {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        chain.any { it is UnknownHostException } -> SteamEnhancedPathStage.DNS_FAILURE
        chain.any { it is CertificateException || it is SSLException } -> SteamEnhancedPathStage.TLS_FAILURE
        chain.any { it is SocketTimeoutException } -> SteamEnhancedPathStage.TIMEOUT
        chain.any { it is ConnectException || it is NoRouteToHostException } -> SteamEnhancedPathStage.CONNECT_FAILURE
        else -> SteamEnhancedPathStage.CONNECT_FAILURE
    }
}

internal inline fun systemDnsCandidates(
    steamAccessEnabled: Boolean,
    lookup: () -> List<InetAddress>,
): List<InetAddress> = if (steamAccessEnabled) emptyList() else lookup()

/**
 * What the setup warm-up measured and which route it decided.
 *
 * [bypassed] and [bridged] are the two ways a decided plan is usable, and they are kept apart because the
 * page must say which one happened: "the direct baseline works" and "the verified bridge works" are
 * different answers, and neither is "warm-up incomplete".
 */
internal data class SteamDohWarmupResult(
    val domesticRoutes: Int,
    val foreignRoutes: Int,
    val measuredRoutes: Int,
    val elapsedMillis: Long,
    val bypassed: Boolean = false,
    val bridged: Boolean = false,
) {
    val ready: Boolean
        get() = bypassed || bridged
}

/** Keeps Steam routing demand-only while asynchronously benchmarking the configured DoH catalog per network. */
@Singleton
class SteamAccessManager
    @Inject
    internal constructor(
        @ApplicationContext private val context: Context,
        settingsRepository: SettingsRepository,
        private val diagnostics: DiagnosticRepository,
        private val environmentProbe: SteamNetworkEnvironmentProbe,
        private val systemProxyResolver: SteamSystemProxyResolver,
        private val noSniTlsDialer: NoSniTlsDialer,
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
            foregroundExecutor = Executors.newFixedThreadPool(10),
            benchmarkExecutor = Executors.newFixedThreadPool(10),
        )
        internal val connectionPool = ConnectionPool(12, 10, TimeUnit.MINUTES)
        private val mutableState = MutableStateFlow(SteamAccessState())
        private val refreshGate = SteamAccessRefreshGate()
        private val dohSelectionLock = Mutex()
        private var dohRetryJob: Job? = null

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

        /** The one egress decision every Steam plane reads. Never re-interpreted per request. */
        @Volatile
        private var networkFingerprint = networkFingerprint(activeNetwork)

        @Volatile
        private var plan: SteamEgressPlan = undecidedPlan()

        /**
         * The semantic egress the session lifecycle reads, or null while no plan exists at all.
         *
         * It is a separate value from [plan] on purpose: the session must reopen when traffic moves, and only
         * then. The plan also changes for diagnostics (a new network fingerprint, a fresh probe result for the
         * same route), and those must not cost an established session.
         */
        private val mutableEgress = MutableStateFlow(SteamEgressPublication(identity = null, provisional = true))
        internal val egressPublication: StateFlow<SteamEgressPublication> = mutableEgress.asStateFlow()

        /** The last egress a plan named, so a new one can be told apart from a plain network change. */
        private var lastPublishedIdentity: String? = null

        @Volatile
        private var planDecidedAtElapsedMs = 0L

        /** Generation whose baseline plan is already published; -1 until the first decision. */
        @Volatile
        private var baselineEpoch = -1L

        /** Generation whose *decided* plan is already published; -1 until an enhancement decision lands. */
        @Volatile
        private var decidedGeneration = -1L

        /** The last verified enhanced dial, so the decision and its diagnostics describe one attempt. */
        @Volatile
        private var enhancedPath: SteamEnhancedPath? = null
        private var environmentProbeJob: Job? = null
        private var environmentProbeGeneration = 0L
        private var lastWarningTriggeredProbeAtElapsedMs = 0L

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

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        if (network == activeNetwork) networkChanged(network)
                    }
                },
            )
            scope.launch {
                settingsRepository.preferences.collectLatest { next ->
                    var shouldClassify = false
                    var changed = false
                    synchronized(this@SteamAccessManager) {
                        changed = !preferencesInitialized ||
                            preferences.steamAccessEnabled != next.steamAccessEnabled ||
                            preferences.steamAccessMode != next.steamAccessMode ||
                            preferences.steamAccessHosts != next.steamAccessHosts ||
                            preferences.steamAccessDohEndpoints != next.steamAccessDohEndpoints ||
                            preferences.steamAccessDisabledDohEndpoints != next.steamAccessDisabledDohEndpoints ||
                            // The baseline is built from the user proxy, so a proxy-only change is a plan
                            // change: without it a newly configured proxy kept answering with the old Direct
                            // egress for the whole process.
                            preferences.downloadProxyEnabled != next.downloadProxyEnabled ||
                            preferences.downloadProxyUrl != next.downloadProxyUrl
                        preferences = next
                        preferencesInitialized = true
                        if (changed) {
                            environmentProbeGeneration += 1L
                            lastWarningTriggeredProbeAtElapsedMs = 0L
                            environmentProbeJob?.cancel()
                            environmentProbeJob = null
                            if (next.steamAccessEnabled && next.steamAccessMode == SteamAccessMode.SMART_DOH) {
                                clearPlan()
                                shouldClassify = true
                            }
                            invalidate()
                        }
                    }
                    if (changed) {
                        // Disabling the enhancement must not leave the bridge published: a reader that only asks
                        // `enhancedConnectionEnabled()` (the SDK route, the CM endpoint source) would keep the
                        // loopback egress it just turned off. Republishing the baseline instead of the undecided
                        // placeholder is deliberate - the placeholder answers "direct", which would silently
                        // bypass a configured proxy.
                        republishBaseline()
                    }
                    preferencesReady.complete(Unit)
                    if (shouldClassify) scheduleEnvironmentProbe(force = true)
                }
            }
        }

        internal fun enhancedConnectionEnabled(): Boolean {
            maybeRefreshEnvironmentProbe()
            return preferencesInitialized && plan.usesBridge
        }

        /** The snapshot every Steam plane reads: Web selector, SDK route factory, diagnostics, UI. */
        internal fun egressPlan(): SteamEgressPlan {
            // A request may arrive before DataStore emitted the first preferences (cold start). Waiting here is
            // bounded and happens on the caller's IO thread, and it is the only way a configured proxy can be
            // known before the SDK builds its first route.
            if (!preferencesInitialized) {
                runBlocking { withTimeoutOrNull(PREFERENCES_WAIT_MS) { preferencesReady.await() } }
            }
            ensureBaselinePlan()
            maybeRefreshEnvironmentProbe()
            return plan
        }

        internal fun currentEgressPlan(): SteamEgressPlan = egressPlan()

        /** Side-effect-free read of the published plan, safe from a main-thread predicate. */
        internal fun publishedEgressPlan(): SteamEgressPlan = plan

        /**
         * Publishes the baseline egress synchronously, before any Steam probe finishes.
         *
         * Without this the plan is Direct until the probe returns, so a first request on a proxy network
         * would leave directly. The probe may only refine a baseline plan; it can never replace a proxy.
         *
         * Synchronized with [publishPlan] because the two are the same decision's two writers: this method
         * resolves the proxy in the middle, and without one lock a decision published in that window would
         * be overwritten by the provisional baseline it was supposed to replace - which is a direct window
         * for every request that reads the plan in between.
         */
        @Synchronized
        private fun ensureBaselinePlan() {
            if (!preferencesInitialized) return
            val generation = candidates.generation
            if (baselineEpoch == generation) return
            val settings = preferences
            val next =
                SteamEgressPlanner.plan(
                    SteamEgressInput(
                        generation = generation,
                        networkFingerprint = networkFingerprint,
                        networkType = networkType,
                        antiBlockingEnabled = false,
                        userProxy = steamProxyEndpointOrNull(
                            settings.downloadProxyUrl.takeIf { settings.downloadProxyEnabled },
                        ),
                        systemProxy = systemProxyResolver.resolve(STEAM_SYSTEM_PROXY_TARGETS),
                        environment = SteamEnvironment.Unknown,
                        baselineReachable = false,
                        enhancedReachable = false,
                    ),
                )
            baselineEpoch = generation
            if (generation != candidates.generation) return
            // The baseline names the egress requests use right now, so it is published as such. It is
            // provisional only in the sense that an enhancement decision is still running for it: a session
            // reopen waits for that decision instead of running twice.
            applyPlan(next, provisional = enhancementDecisionPending(settings))
            mutableState.value =
                mutableState.value.copy(
                    phase = phaseFor(next),
                    networkType = next.networkType,
                    updatedAt = System.currentTimeMillis(),
                )
        }

        /**
         * Publishes the plan together with the egress identity the session lifecycle reads.
         *
         * A changed identity also drops every pooled connection: a socket belongs to the egress that opened
         * it, and the pool is shared with the Web plane.
         */
        private fun applyPlan(
            next: SteamEgressPlan,
            provisional: Boolean = false,
        ) {
            val identity = next.egressIdentity
            val previous = lastPublishedIdentity
            if (identity != null) lastPublishedIdentity = identity
            plan = next
            mutableEgress.value = SteamEgressPublication(identity = identity, provisional = provisional)
            if (previous != null && identity != null && previous != identity) {
                scope.launch { connectionPool.evictAll() }
            }
        }

        /** Publishes the placeholder used while no decision exists; it names no egress. */
        private fun clearPlan() {
            plan = undecidedPlan()
            planDecidedAtElapsedMs = 0L
            decidedGeneration = -1L
            enhancedPath = null
            mutableEgress.value = SteamEgressPublication(identity = null, provisional = true)
        }

        private fun enhancementDecisionPending(settings: AppPreferences): Boolean =
            settings.steamAccessEnabled && settings.steamAccessMode == SteamAccessMode.SMART_DOH

        /**
         * Republished after a settings change that does not start a new decision.
         *
         * Resetting [baselineEpoch] is what makes [ensureBaselinePlan] run for the current generation again: the
         * published plan was decided for the previous preference set, and its bridge must not outlive the switch
         * that asked for it.
         */
        private fun republishBaseline() {
            synchronized(this) {
                baselineEpoch = -1L
                decidedGeneration = -1L
                enhancedPath = null
            }
            ensureBaselinePlan()
        }

        override suspend fun awaitRouteDecision() {
            awaitEnhancedConnectionDecision()
        }

        /**
         * Waits, bounded, for the decided egress of the current network generation.
         *
         * The provisional baseline is not a decision: it is Direct with an unknown environment, and a caller that
         * starts work on it measures an egress the enhancement is about to replace. "Waiting for the plan" is
         * therefore waiting for a *decided* generation, not for the environment to leave Unknown. Null means the
         * budget expired with no decision - the caller must report that instead of measuring the placeholder.
         */
        internal suspend fun awaitDecidedEgressPlan(
            budgetMillis: Long,
        ): SteamEgressPlan? {
            preferencesReady.await()
            val settings = preferences
            if (!settings.steamAccessEnabled || settings.steamAccessMode != SteamAccessMode.SMART_DOH) {
                return egressPlan()
            }
            scheduleEnvironmentProbe(force = false)
            val deadline = SystemClock.elapsedRealtime() + budgetMillis
            while (true) {
                val current = plan
                if (decidedGeneration == candidates.generation && current.generation == candidates.generation) {
                    return current
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                delay(minOf(DECISION_POLL_INTERVAL_MS, remaining))
            }
        }

        internal suspend fun awaitEnhancedConnectionDecision(): Boolean =
            awaitDecidedEgressPlan(STEAM_EGRESS_DECISION_WAIT_MS)?.usesBridge == true

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
            // The wizard promises to measure the safe DNS lines *then* check Steam. Measuring only after a
            // bridge existed made the first decision run on the unmeasured fallback order, so a network whose
            // first two fallback lines fail could never produce the bridge that would have measured them.
            ensureDohSelection(settings, force = force)
            publishPlan(decidePlan())
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
                bypassed = plan.environment is SteamEnvironment.Open,
                bridged = plan.usesBridge,
            )
        }

        override fun lookup(hostname: String): List<InetAddress> =
            if (SteamDomainPolicy.supports(hostname) && plan.usesBridge) {
                throw UnknownHostException("Steam traffic is planned through the loopback bridge")
            } else {
                Dns.SYSTEM.lookup(hostname)
            }

        internal fun systemDnsLookupWhenDisabled(hostname: String): List<InetAddress> {
            val host = SteamDomainPolicy.requireSupported(hostname)
            runBlocking { preferencesReady.await() }
            if (plan.usesBridge) {
                throw UnknownHostException("Steam traffic is planned through the loopback bridge")
            }
            return Dns.SYSTEM.lookup(host)
        }

        /**
         * The same lookup for a CDN endpoint, which the Web allowlist does not own.
         *
         * A CDN host (for example a Steam-issued `*.steamusercontent.com` or a partner edge) never matches
         * [SteamDomainPolicy.requireSupported], so resolving it with the Web rule threw and turned a CDN
         * request that the plan said was direct into a 502. The CDN rule validates the host and the port.
         */
        internal fun systemDnsLookupForCdnWhenDisabled(hostname: String, port: Int): List<InetAddress> {
            val host = SteamDomainPolicy.requireAuthenticatedCdnTunnel(hostname, port)
            runBlocking { preferencesReady.await() }
            if (plan.usesBridge) {
                throw UnknownHostException("Steam traffic is planned through the loopback bridge")
            }
            return Dns.SYSTEM.lookup(host)
        }

        internal suspend fun acceleratedOrWebCandidateRoute(hostname: String, port: Int): AcceleratedRoute {
            val host = SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
            lastEndpoint = host to port
            return resolve(host, port, web = true)
        }

        internal suspend fun acceleratedWebRoute(
            hostname: String,
            port: Int,
            reuseCachedTargets: Boolean = false,
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
                val enhanced = plan.usesBridge
                val authorities = rules.map(SteamWebUpstreamRule::resolveAuthority)
                if (reuseCachedTargets) {
                    val cachedByAuthority =
                        rules.associate { rule ->
                            val cacheKey = steamWebRouteCacheKey(selectedNetwork, host, rule.tlsAuthority, port)
                            rule.resolveAuthority to candidates.get(cacheKey, generation).orEmpty()
                        }
                    val cachedTargets = bindSteamWebTargets(rules, cachedByAuthority)
                    if (cachedTargets.isNotEmpty()) {
                        return@withLock WebAcceleratedRoute(host, selectedNetwork, generation, port, cachedTargets)
                    }
                }
                val systemByAuthority =
                    authorities.associateWith { authority ->
                        systemDnsCandidates(enhanced) {
                            runCatching { Dns.SYSTEM.lookup(authority) }.getOrDefault(emptyList())
                        }
                    }
                val resolvedByAuthority =
                    if (!enhanced) {
                        emptyMap()
                    } else {
                        when (settings.steamAccessMode) {
                            SteamAccessMode.HOSTS ->
                                mapOf(host to SteamHostsParser.parse(settings.steamAccessHosts)[host].orEmpty())
                            SteamAccessMode.SMART_DOH ->
                                dohResolver.resolveByHostname(
                                    authorities,
                                    dohEndpointsForProduction(settings),
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
                                val cooling = routeStore.coolingDownAddresses(networkFingerprint, healthHost, port)
                                val preferred =
                                    routeStore.preferred(networkFingerprint, healthHost, port)
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
                val enhanced = plan.usesBridge
                val key = steamRouteCacheKey(selectedNetwork, host, port)
                candidates.get(key, generation)?.let {
                    return@withLock AcceleratedRoute(selectedNetwork, generation, port, it)
                }
                val system =
                    systemDnsCandidates(enhanced) {
                        runCatching { Dns.SYSTEM.lookup(host) }.getOrDefault(emptyList())
                    }
                val resolved = if (!enhanced) {
                    emptyList()
                } else {
                    when (settings.steamAccessMode) {
                        SteamAccessMode.HOSTS -> SteamHostsParser.parse(settings.steamAccessHosts)[host].orEmpty()
                        SteamAccessMode.SMART_DOH -> dohResolver.resolve(
                            listOf(host) + if (web) SteamAccessRoutes.aliases(host) else emptyList(),
                            dohEndpointsForProduction(settings),
                            currentNetworkSupportsIpv6(),
                        )
                    }
                }
                val cooling = routeStore.coolingDownAddresses(networkFingerprint, host, port)
                // History ranks only currently resolved addresses; old built-in seeds cannot survive via persistence.
                val preferred = routeStore.preferred(networkFingerprint, host, port).mapNotNull { it.hostAddress }.toSet()
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
            routeStore.recordSuccess(plan.networkFingerprint, hostname, route.port, address, elapsedMs)
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
            routeStore.recordSuccess(plan.networkFingerprint, healthHost, route.port, target.address, elapsedMs)
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
            routeStore.recordFailure(plan.networkFingerprint, healthHost, route.port, target.address)
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
            routeStore.recordFailure(plan.networkFingerprint, hostname, port, address)
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

        private fun dohEndpointsForProduction(settings: AppPreferences): List<String> =
            // A production request path must never run the full catalog benchmark: that belongs to the explicit
            // "check routes again" action, not to browsing or a download.
            currentDohEndpoints(settings)

        private suspend fun ensureDohSelection(
            settings: AppPreferences,
            force: Boolean = false,
            scheduleRetry: Boolean = true,
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
                dohSelectionGeneration = currentGeneration
                dohSelectionFingerprint = fingerprint
                selectedDohEndpoints = selected
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
                recordDiagnostic(
                    message = "Steam DoH selection completed",
                    attributes = mapOf(
                        "networkType" to networkType,
                        "configuredCount" to endpoints.size.toString(),
                        "availableCount" to results.count(SteamDohProbeResult::available).toString(),
                        "selectedCount" to selected.size.toString(),
                        "fallbackUsed" to selected.isEmpty().toString(),
                        "latencyRangeMs" to results.mapNotNull(SteamDohProbeResult::latencyMs)
                            .let { values -> if (values.isEmpty()) "none" else "${values.min()}-${values.max()}" },
                    ),
                    level = if (selected.isEmpty()) DiagnosticLevel.WARNING else DiagnosticLevel.INFO,
                )
                if (scheduleRetry && selected.isEmpty() && endpoints.isNotEmpty()) {
                    synchronized(this@SteamAccessManager) {
                        dohRetryJob?.cancel()
                        dohRetryJob =
                            scope.launch {
                                delay(DOH_BENCHMARK_RETRY_MS)
                                val latest = preferences
                                val latestFingerprint = latest.enabledSteamAccessDohEndpoints().joinToString("\u0000")
                                if (
                                    plan.usesBridge &&
                                        latest.steamAccessMode == SteamAccessMode.SMART_DOH &&
                                    candidates.generation == currentGeneration &&
                                    latestFingerprint == fingerprint
                                ) {
                                    ensureDohSelection(latest, force = true, scheduleRetry = true)
                                }
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
                    val settings = preferences
                    if (settings.steamAccessMode == SteamAccessMode.SMART_DOH) {
                        // "Check the routes again" measures the lines first, then decides: measuring after the
                        // decision could only confirm the lines a bridge had already chosen.
                        ensureDohSelection(settings, force = true)
                        publishPlan(decidePlan())
                        if (!plan.usesBridge) {
                            synchronized(this@SteamAccessManager) {
                                invalidate(phase = SteamAccessPhase.BYPASSED, schedulePoolEviction = false)
                            }
                            connectionPool.evictAll()
                            return@launch
                        }
                    }
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

        private fun planTtlMs(): Long =
            when (plan.environment) {
                is SteamEnvironment.Open -> STEAM_ENVIRONMENT_OPEN_TTL_MS
                else -> STEAM_ENVIRONMENT_RESTRICTED_TTL_MS
            }

        /**
         * Builds the plan from one network snapshot.
         *
         * The baseline egress is probed with real Steam targets. Only a direct baseline that cannot reach
         * Steam - and only on a network that is not a VPN - may escalate to the bridge, and only after the
         * enhanced dial has itself verified a Steam TLS handshake.
         */
        private suspend fun decidePlan(): SteamEgressPlan {
            val generation = candidates.generation
            val fingerprint = networkFingerprint
            val type = networkType
            val settings = synchronized(this) { preferences }
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = environmentProbe::probe,
                    verifyEnhancedPath = { verifyEnhancedPath(settings) },
                ).decide(
                    SteamEgressDecisionInput(
                        generation = generation,
                        networkFingerprint = fingerprint,
                        networkType = type,
                        antiBlockingEnabled = settings.steamAccessEnabled,
                        smartDohEnabled = settings.steamAccessEnabled &&
                            settings.steamAccessMode == SteamAccessMode.SMART_DOH,
                        userProxy = steamProxyEndpointOrNull(
                            settings.downloadProxyUrl.takeIf { settings.downloadProxyEnabled },
                        ),
                        systemProxy = systemProxyResolver.resolve(STEAM_SYSTEM_PROXY_TARGETS),
                    ),
                )
            enhancedPath = outcome.enhancedPath
            return outcome.plan
        }

        /**
         * Proves that the enhanced route the relay will actually use works, and names the stage when it does not.
         *
         * The relay does not dial Steam with a hidden SNI: its Web plane dials each allowed authority with that
         * authority's own SNI, which is what lets an edge serve an otherwise blocked Steam host. Verifying a
         * hidden-SNI handshake therefore proved a path no request uses - on a China direct network it failed
         * while the authority dial succeeded, so the app reported the enhanced path unavailable and every plane
         * stayed on a baseline that could not reach Steam. This verifies the authority dial instead, over the
         * same target list the relay builds, and reports DNS, TCP, TLS or budget separately.
         *
         * Cancellation is rethrown rather than folded into "unreachable": a superseded probe that reported the
         * enhanced path unreachable would decide the network without ever having tried it.
         */
        private suspend fun verifyEnhancedPath(settings: AppPreferences): SteamEnhancedPath =
            withContext(Dispatchers.IO) {
                val startedAt = SystemClock.elapsedRealtime()
                fun elapsed(): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                withTimeoutOrNull(ENHANCED_VERIFY_TIMEOUT_MS) {
                    val rules = SteamAccessRoutes.webUpstreams(STEAM_VERIFY_HOST)
                    if (rules.isEmpty()) {
                        return@withTimeoutOrNull SteamEnhancedPath.unreachable(
                            SteamEnhancedPathStage.DNS_FAILURE,
                            elapsed(),
                        )
                    }
                    val resolved =
                        try {
                            dohResolver.resolveByHostname(
                                rules.map(SteamWebUpstreamRule::resolveAuthority),
                                currentDohEndpoints(settings),
                                currentNetworkSupportsIpv6(),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            emptyMap()
                        }
                    val targets = bindSteamWebTargets(rules, resolved)
                    if (targets.isEmpty()) {
                        return@withTimeoutOrNull SteamEnhancedPath.unreachable(
                            SteamEnhancedPathStage.DNS_FAILURE,
                            elapsed(),
                        )
                    }
                    try {
                        noSniTlsDialer
                            .connectWebAuthorities(
                                steamWebTargetsForRequest(
                                    STEAM_VERIFY_HOST,
                                    STEAM_DIRECTORY_VERIFY_REQUEST_HEAD,
                                    targets,
                                ),
                            ).socket
                            .close()
                        SteamEnhancedPath.reachable(elapsed())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        SteamEnhancedPath.unreachable(enhancedDialFailureStage(error), elapsed())
                    }
                } ?: SteamEnhancedPath.unreachable(SteamEnhancedPathStage.BUDGET_EXHAUSTED, elapsed())
            }

        /** See [ensureBaselinePlan]: the baseline and the decided plan are serialized against each other. */
        @Synchronized
        private fun publishPlan(next: SteamEgressPlan) {
            // A plan decided against an older generation must never overwrite the newer network's decision.
            if (next.generation != candidates.generation) return
            applyPlan(next)
            // A decided plan subsumes the baseline for its generation: without this the next request would
            // republish a provisional baseline over it, which is a real direct window on a network whose
            // decision was the bridge - and, for the session, a reopen onto the wrong route.
            baselineEpoch = next.generation
            planDecidedAtElapsedMs = SystemClock.elapsedRealtime()
            decidedGeneration = next.generation
            val verified = enhancedPath
            mutableState.value =
                mutableState.value.copy(
                    phase = phaseFor(next),
                    networkType = next.networkType,
                    message = next.problem?.let { context.getString(R.string.backend_steam_access_route_failed, it.name) },
                    updatedAt = System.currentTimeMillis(),
                )
            recordDiagnostic(
                message = "Steam egress plan decided",
                attributes =
                    mapOf(
                        "egress" to next.effective.kind.name.lowercase(),
                        "baseline" to next.baseline.kind.name.lowercase(),
                        "environment" to steamEnvironmentLabel(next.environment),
                        "problem" to next.problem?.name.orEmpty(),
                        "enhancedPath" to (verified?.stage?.name?.lowercase() ?: "not_attempted"),
                        "enhancedPathMs" to (verified?.elapsedMillis?.toString() ?: ""),
                        "networkType" to next.networkType,
                        "generation" to next.generation.toString(),
                    ),
                level = if (next.problem == null) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            )
        }

        private fun phaseFor(next: SteamEgressPlan): SteamAccessPhase =
            when {
                !preferences.steamAccessEnabled -> SteamAccessPhase.DISABLED
                next.isUnavailable -> SteamAccessPhase.DEGRADED
                next.usesBridge -> SteamAccessPhase.RESOLVING
                next.environment is SteamEnvironment.Open -> SteamAccessPhase.BYPASSED
                next.problem != null -> SteamAccessPhase.DEGRADED
                else -> SteamAccessPhase.RESOLVING
            }

        private fun undecidedPlan(): SteamEgressPlan =
            SteamEgressPlan(
                generation = candidates.generation,
                networkFingerprint = networkFingerprint,
                networkType = networkType,
                baseline = SteamEgress.Direct,
                effective = SteamEgress.Direct,
                environment = SteamEnvironment.Unknown,
                problem = SteamEgressProblem.ENVIRONMENT_UNKNOWN,
                baselineReachable = false,
                // Not a decision: the placeholder names no egress, so nothing may reopen a session on it.
                decided = false,
            )

        private fun maybeRefreshEnvironmentProbe() {
            val settings = preferences
            if (!preferencesInitialized || !settings.steamAccessEnabled || settings.steamAccessMode != SteamAccessMode.SMART_DOH) {
                return
            }
            // "Never decided" is only a reason to resolve while no decision exists for this generation. Asking
            // again for every request after a decision that legitimately keeps environment Unknown (an
            // unrepresentable system proxy) would publish a plan and a diagnostic per Steam request.
            val undecided = decidedGeneration != candidates.generation
            val stale = !undecided && SystemClock.elapsedRealtime() - planDecidedAtElapsedMs >= planTtlMs()
            if (undecided || stale) scheduleEnvironmentProbe(force = false)
        }

        @Synchronized
        private fun scheduleEnvironmentProbe(force: Boolean, debounceMs: Long = 0L) {
            val settings = preferences
            if (!preferencesInitialized || !settings.steamAccessEnabled || settings.steamAccessMode != SteamAccessMode.SMART_DOH) {
                return
            }
            if (!force && environmentProbeJob?.isActive == true) return
            environmentProbeGeneration += 1L
            val generation = environmentProbeGeneration
            val fingerprint = networkFingerprint
            environmentProbeJob?.cancel()
            environmentProbeJob =
                scope.launch {
                    if (debounceMs > 0L) delay(debounceMs)
                    if (fingerprint != networkFingerprint) return@launch
                    if (generation != environmentProbeGeneration) return@launch
                    val decided = decidePlan()
                    // decidePlan suspends (probe, then the verified enhanced dial). A newer probe or a new
                    // network owns the plan by now, and its decision is the one that may be published.
                    if (generation != environmentProbeGeneration) return@launch
                    if (fingerprint != networkFingerprint) return@launch
                    publishPlan(decided)
                }
        }

        // The old Google-only environment classifier was removed: the plan above is now the only place that
        // decides whether Steam is reachable and which egress serves it.

        private fun invalidate(
            phase: SteamAccessPhase = invalidatedSteamAccessPhase(),
            schedulePoolEviction: Boolean = true,
        ) {
            candidates.invalidate()
            dohRetryJob?.cancel()
            dohRetryJob = null
            dohSelectionGeneration = -1L
            dohSelectionFingerprint = ""
            selectedDohEndpoints = emptyList()
            mutableState.value = SteamAccessState(phase = phase, networkType = networkType)
            if (schedulePoolEviction) scope.launch { connectionPool.evictAll() }
        }

        private fun invalidatedSteamAccessPhase(): SteamAccessPhase =
            when {
                !preferences.steamAccessEnabled -> SteamAccessPhase.DISABLED
                preferences.steamAccessMode == SteamAccessMode.SMART_DOH &&
                    plan.environment is SteamEnvironment.Open -> SteamAccessPhase.BYPASSED
                lastEndpoint == null -> SteamAccessPhase.FAILED
                else -> SteamAccessPhase.DEGRADED
            }

        @Synchronized
        private fun networkChanged(network: Network?) {
            val type = detectNetworkType(network)
            val fingerprint = networkFingerprint(network)
            if (fingerprint == networkFingerprint) return
            val previousType = networkType
            activeNetwork = network
            networkType = type
            networkFingerprint = fingerprint
            clearPlan()
            lastWarningTriggeredProbeAtElapsedMs = 0L
            recordDiagnostic(
                message = "Active network changed",
                attributes = mapOf("from" to previousType, "to" to type),
                level = DiagnosticLevel.INFO,
            )
            invalidate(
                phase = if (preferences.steamAccessEnabled) SteamAccessPhase.RESOLVING else SteamAccessPhase.DISABLED,
            )
            if (
                preferencesInitialized &&
                preferences.steamAccessEnabled &&
                preferences.steamAccessMode == SteamAccessMode.SMART_DOH
            ) {
                scheduleEnvironmentProbe(force = true, debounceMs = STEAM_ENVIRONMENT_NETWORK_DEBOUNCE_MS)
            }
        }

        private fun networkFingerprint(network: Network?): String {
            if (network == null) return "offline"
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val links = connectivityManager.getLinkProperties(network)
            val transports =
                listOf(
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_WIFI,
                    NetworkCapabilities.TRANSPORT_BLUETOOTH,
                    NetworkCapabilities.TRANSPORT_ETHERNET,
                    NetworkCapabilities.TRANSPORT_VPN,
                    NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                    NetworkCapabilities.TRANSPORT_LOWPAN,
                ).filter { transport -> capabilities?.hasTransport(transport) == true }
            val proxy = links?.httpProxy
            return listOf(
                network.toString(),
                transports.joinToString(","),
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).toString(),
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL).toString(),
                links?.interfaceName.orEmpty(),
                links?.dnsServers.orEmpty().joinToString(",") { address -> address.hostAddress.orEmpty() },
                proxy?.host.orEmpty(),
                proxy?.port?.toString().orEmpty(),
                proxy?.pacFileUrl?.toString().orEmpty(),
            ).joinToString("|")
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

        internal fun recordHttpDiagnostic(
            message: String,
            attributes: Map<String, String>,
            warning: Boolean,
        ) {
            if (
                warning &&
                preferences.steamAccessEnabled &&
                preferences.steamAccessMode == SteamAccessMode.SMART_DOH &&
                (plan.usesBridge || plan.environment is SteamEnvironment.Open)
            ) {
                synchronized(this) {
                    val now = SystemClock.elapsedRealtime()
                    if (
                        lastWarningTriggeredProbeAtElapsedMs == 0L ||
                        now - lastWarningTriggeredProbeAtElapsedMs >= STEAM_ENVIRONMENT_WARNING_REPROBE_COOLDOWN_MS
                    ) {
                        lastWarningTriggeredProbeAtElapsedMs = now
                        scheduleEnvironmentProbe(force = false)
                    }
                }
            }
            recordDiagnostic(
                message = message,
                attributes = attributes + ("networkType" to networkType),
                source = "steam-http",
                level = if (warning) DiagnosticLevel.WARNING else DiagnosticLevel.INFO,
            )
        }

        private fun recordDiagnostic(
            message: String,
            attributes: Map<String, String>,
            source: String = "steam-access",
            level: DiagnosticLevel = DiagnosticLevel.WARNING,
        ) {
            scope.launch {
                try {
                    diagnostics.record(
                        DiagnosticEvent(
                            source = source,
                            level = level,
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
