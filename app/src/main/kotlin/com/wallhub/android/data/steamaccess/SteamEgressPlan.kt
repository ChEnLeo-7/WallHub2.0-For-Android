package com.wallhub.android.data.steamaccess

/**
 * One immutable decision about where Steam traffic leaves this device.
 *
 * This is the only place the app is allowed to answer "proxy, bridge, or direct" for Steam. Web API,
 * Workshop browsing, the CM directory, the Steam Content SDK's CM and Manifest/CDN planes all read the
 * same plan, so two planes can never disagree about the egress for one network generation.
 *
 * The type deliberately cannot carry proxy credentials: the SDK route and every diagnostic consume
 * [SteamProxyEndpoint.uri], which is host/port only.
 */
internal data class SteamProxyEndpoint(
    val kind: SteamProxyKind,
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "Proxy host must not be blank" }
        require(port in 1..MAX_PROXY_PORT) { "Proxy port must be in 1..$MAX_PROXY_PORT" }
    }

    val uri: String
        get() = "${kind.scheme}://${if (':' in host) "[$host]" else host}:$port"

    /** Never include credentials: this type has none, and the rendering is used in logs. */
    override fun toString(): String = uri

    private companion object {
        const val MAX_PROXY_PORT = 65_535
    }
}

internal enum class SteamProxyKind(val scheme: String) {
    HTTP("http"),
    SOCKS("socks"),
}

/**
 * How the current network answers a proxy query for the Steam targets.
 *
 * [Ambiguous] and [Unavailable] are explicit failures. They must never be collapsed into [Absent],
 * because that is what turns an unrepresentable proxy into a silent direct connection.
 */
internal sealed interface SteamSystemProxy {
    data object Absent : SteamSystemProxy

    data class Present(
        val endpoint: SteamProxyEndpoint,
    ) : SteamSystemProxy

    data class Ambiguous(
        val reason: SteamProxyAmbiguity,
    ) : SteamSystemProxy

    data object Unavailable : SteamSystemProxy
}

internal enum class SteamProxyAmbiguity {
    /** The selector answered every Steam target with an empty list. */
    EmptySelection,

    /** One target produced more than one proxy. */
    MultipleProxies,

    /** Different Steam targets produced different proxies. */
    InconsistentTargets,

    /** The selected proxy is a kind the app cannot represent (for example a direct passthrough type). */
    UnsupportedScheme,
}

/** Why a Steam probe could not reach Steam. Each value maps to a distinct operator action. */
internal enum class SteamBlockReason {
    DNS_FAILURE,
    CONNECT_FAILURE,
    TIMEOUT,
    TLS_FAILURE,
    PROXY_AUTH_REQUIRED,
    RATE_LIMITED,
    SERVER_REJECTED,
}

/**
 * The Steam verdict for the baseline egress. Google reachability is deliberately not part of this type:
 * it stays an auxiliary diagnostic and cannot decide whether Steam is blocked.
 */
internal sealed interface SteamEnvironment {
    data object Open : SteamEnvironment

    data object Unknown : SteamEnvironment

    data class Blocked(
        val reason: SteamBlockReason,
    ) : SteamEnvironment
}

internal enum class SteamEgressKind { DIRECT, PROXY, BRIDGE, UNAVAILABLE }

/** One redacted label for an environment, shared by diagnostics and the connection check. */
internal fun steamEnvironmentLabel(environment: SteamEnvironment): String =
    when (environment) {
        SteamEnvironment.Open -> "open"
        SteamEnvironment.Unknown -> "unknown"
        is SteamEnvironment.Blocked -> "blocked:${environment.reason.name.lowercase()}"
    }

internal enum class SteamEgressSource { USER, SYSTEM }

internal enum class SteamBridgeReason {
    /** The baseline is direct and cannot reach Steam, and the verified enhanced dial can. */
    STEAM_ONLY_REACHABLE,
}

/** Routing-level failure that is reported to the UI and the diagnostics, never hidden. */
internal enum class SteamEgressProblem {
    AMBIGUOUS_SYSTEM_PROXY,
    SYSTEM_PROXY_UNREADABLE,
    ENVIRONMENT_UNKNOWN,
    VPN_POLICY_PRESERVED,
    ENHANCED_PATH_UNAVAILABLE,
}

/**
 * What the manager publishes for the session lifecycle: the semantic egress, and whether a decision is
 * still pending for it.
 *
 * The identity is published for the provisional baseline too, because that baseline is the egress
 * requests actually use - withholding it is what let a session opened on the baseline be mistaken for a
 * session opened on an unknown egress, so the enhancement decision that followed never reopened it.
 * [provisional] is the part that keeps a reopen from running twice: a reopen waits for the decision and
 * then runs once, for the egress that is final.
 */
internal data class SteamEgressPublication(
    val identity: String?,
    val provisional: Boolean,
)

internal sealed interface SteamEgress {
    val kind: SteamEgressKind

    data object Direct : SteamEgress {
        override val kind: SteamEgressKind get() = SteamEgressKind.DIRECT
    }

    data class Proxy(
        val endpoint: SteamProxyEndpoint,
        val source: SteamEgressSource,
    ) : SteamEgress {
        override val kind: SteamEgressKind get() = SteamEgressKind.PROXY
    }

    data class Bridge(
        val reason: SteamBridgeReason,
    ) : SteamEgress {
        override val kind: SteamEgressKind get() = SteamEgressKind.BRIDGE
    }

    data class Unavailable(
        val problem: SteamEgressProblem,
    ) : SteamEgress {
        override val kind: SteamEgressKind get() = SteamEgressKind.UNAVAILABLE
    }
}

internal data class SteamEgressInput(
    val generation: Long,
    val networkFingerprint: String,
    val networkType: String,
    val antiBlockingEnabled: Boolean,
    val userProxy: SteamProxyEndpoint?,
    val systemProxy: SteamSystemProxy,
    val environment: SteamEnvironment,
    val baselineReachable: Boolean,
    val enhancedReachable: Boolean,
)

internal data class SteamEgressPlan(
    val generation: Long,
    val networkFingerprint: String,
    val networkType: String,
    val baseline: SteamEgress,
    val effective: SteamEgress,
    val environment: SteamEnvironment,
    val problem: SteamEgressProblem?,
    val baselineReachable: Boolean,
    /** False for the placeholder the manager publishes before any decision exists for its generation. */
    val decided: Boolean = true,
) {
    fun isFor(expectedGeneration: Long): Boolean = generation == expectedGeneration

    val usesBridge: Boolean get() = effective.kind == SteamEgressKind.BRIDGE

    val isUnavailable: Boolean get() = effective.kind == SteamEgressKind.UNAVAILABLE

    /**
     * The semantic identity of the egress: two plans with the same identity send Steam traffic to the
     * same place, so a session opened on one is still valid on the other. Null while undecided.
     *
     * It carries no generation, network fingerprint or environment, because those change without moving
     * traffic - reopening a session for them would interrupt a download for nothing. A proxy identity is
     * its address only: whether the operator configured it or the system published it, that socket is the
     * same route, and this type cannot carry a credential. A bridge identity carries its reason, which is
     * what selects the loopback route and its process-scoped trust root.
     */
    val egressIdentity: String?
        get() =
            if (!decided) {
                null
            } else {
                when (val egress = effective) {
                    SteamEgress.Direct -> DIRECT_EGRESS_IDENTITY
                    is SteamEgress.Proxy -> "proxy:${egress.endpoint.uri}"
                    is SteamEgress.Bridge -> "bridge:${egress.reason.name}"
                    is SteamEgress.Unavailable -> "unavailable:${egress.problem.name}"
                }
            }

    /** The kind part of [egressIdentity], which is all a redacted diagnostic may repeat. */
    val egressKindLabel: String
        get() = egressIdentity?.substringBefore(':') ?: "undecided"

    /** The proxy the SDK route must be built with, or null when the plan is direct/bridged. */
    val sdkProxyUri: String?
        get() = (effective as? SteamEgress.Proxy)?.endpoint?.uri

    companion object {
        const val DIRECT_EGRESS_IDENTITY = "direct"
    }
}

/**
 * Whether the enhancement may even be verified for this network.
 *
 * It exists as its own function because it is the gate in front of the one expensive and intrusive
 * probe the app can run: the DoH resolve plus verified TLS dial. "Steam is unreachable" is never enough
 * on its own - a representable proxy, a VPN default network, a disabled switch or a non-SMART_DOH mode
 * each keep the verified path from running, so a proxy that answers 407 (or any other way) is never
 * probed around, never dialled directly and never asked for a DoH address.
 */
internal fun steamBridgeMayBeVerified(
    baseline: SteamEgress,
    antiBlockingEnabled: Boolean,
    smartDohEnabled: Boolean,
    baselineReachable: Boolean,
    networkType: String,
): Boolean =
    antiBlockingEnabled &&
        smartDohEnabled &&
        baseline is SteamEgress.Direct &&
        !baselineReachable &&
        networkType != SteamEgressPlanner.VPN_NETWORK_TYPE

/**
 * Pure decision function. No Android type, no I/O, no clock: every input is explicit so the whole
 * policy matrix is testable without a device.
 */
internal object SteamEgressPlanner {
    fun plan(input: SteamEgressInput): SteamEgressPlan {
        val baseline = baselineFor(input.userProxy, input.systemProxy)
        var effective: SteamEgress = baseline
        var problem: SteamEgressProblem? =
            when (baseline) {
                is SteamEgress.Unavailable -> baseline.problem
                else -> null
            }

        if (baseline is SteamEgress.Direct && input.antiBlockingEnabled && !input.baselineReachable) {
            problem =
                when {
                    input.environment is SteamEnvironment.Unknown -> SteamEgressProblem.ENVIRONMENT_UNKNOWN
                    input.networkType == VPN_NETWORK_TYPE -> SteamEgressProblem.VPN_POLICY_PRESERVED
                    !input.enhancedReachable -> SteamEgressProblem.ENHANCED_PATH_UNAVAILABLE
                    else -> {
                        effective = SteamEgress.Bridge(SteamBridgeReason.STEAM_ONLY_REACHABLE)
                        null
                    }
                }
        }

        return SteamEgressPlan(
            generation = input.generation,
            networkFingerprint = input.networkFingerprint,
            networkType = input.networkType,
            baseline = baseline,
            effective = effective,
            environment = input.environment,
            problem = problem,
            baselineReachable = input.baselineReachable,
        )
    }

    /**
     * A representable user proxy always wins; then a representable system proxy; only then direct.
     * An unrepresentable system proxy is a failure, because "the app could not work out the egress"
     * is not the same fact as "there is no proxy".
     */
    private fun baselineFor(
        userProxy: SteamProxyEndpoint?,
        systemProxy: SteamSystemProxy,
    ): SteamEgress =
        when {
            userProxy != null -> SteamEgress.Proxy(userProxy, SteamEgressSource.USER)
            systemProxy is SteamSystemProxy.Present ->
                SteamEgress.Proxy(systemProxy.endpoint, SteamEgressSource.SYSTEM)
            systemProxy is SteamSystemProxy.Ambiguous ->
                SteamEgress.Unavailable(SteamEgressProblem.AMBIGUOUS_SYSTEM_PROXY)
            systemProxy is SteamSystemProxy.Unavailable ->
                SteamEgress.Unavailable(SteamEgressProblem.SYSTEM_PROXY_UNREADABLE)
            else -> SteamEgress.Direct
        }

    /** Kept in one place so the planner and the network owner cannot disagree on the VPN label. */
    internal const val VPN_NETWORK_TYPE = "vpn"
}
