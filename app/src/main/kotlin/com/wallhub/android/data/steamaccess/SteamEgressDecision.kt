package com.wallhub.android.data.steamaccess

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which half of the enhanced path failed, named rather than folded into one boolean.
 *
 * The four checks report a timeout for every one of these, so the stage is the only thing that says
 * whether the answer is "DoH is unreachable", "the edge refuses our TCP", "the TLS identity is not the
 * one we dialled" or "the decision ran out of its own budget".
 */
internal enum class SteamEnhancedPathStage {
    /** The verified authority dial completed. */
    REACHED,
    DNS_FAILURE,
    CONNECT_FAILURE,
    TIMEOUT,
    TLS_FAILURE,
    BUDGET_EXHAUSTED,
}

/** One verified (or refused) enhanced dial, with the stage that decided it and how long it took. */
internal data class SteamEnhancedPath(
    val stage: SteamEnhancedPathStage,
    val elapsedMillis: Long,
) {
    val reachable: Boolean get() = stage == SteamEnhancedPathStage.REACHED

    companion object {
        fun reachable(elapsedMillis: Long): SteamEnhancedPath = SteamEnhancedPath(SteamEnhancedPathStage.REACHED, elapsedMillis)

        fun unreachable(
            stage: SteamEnhancedPathStage,
            elapsedMillis: Long,
        ): SteamEnhancedPath {
            require(stage != SteamEnhancedPathStage.REACHED) { "An unreachable verdict needs a failure stage" }
            return SteamEnhancedPath(stage, elapsedMillis)
        }
    }
}

/** Everything the decision needs that is not an observation of the network. */
internal data class SteamEgressDecisionInput(    val generation: Long,
    val networkFingerprint: String,
    val networkType: String,
    val antiBlockingEnabled: Boolean,
    /** Only the SMART_DOH mode has a DoH route the enhancement can verify. */
    val smartDohEnabled: Boolean,
    val userProxy: SteamProxyEndpoint?,
    val systemProxy: SteamSystemProxy,
)

internal data class SteamEgressDecisionOutcome(
    val plan: SteamEgressPlan,
    /** Null when the enhanced dial was never attempted for this decision. */
    val enhancedPath: SteamEnhancedPath? = null,
)

/**
 * The egress a caller may measure or consume, without depending on the Android network owner.
 *
 * It exists so the connection check can be tested with a scripted egress: waiting for a decision and
 * discarding a result produced for a superseded one are the behaviours the field report needed, and
 * neither needs a socket to be worth pinning.
 */
internal interface SteamEgressDecisionSource {
    /**
     * The plan every Steam plane is using right now, including an undecided placeholder.
     *
     * A side-effect-free read of the published plan: it must be callable from a predicate on the main
     * thread, so it never schedules a probe or resolves the system proxy.
     */
    fun publishedEgressPlan(): SteamEgressPlan

    /**
     * The first *decided* plan for the current network generation, waiting at most [budgetMillis].
     *
     * Null means no decision landed in the budget. That is not a plan: measuring the undecided placeholder
     * would present an egress the app has not chosen as if it were the verdict.
     */
    suspend fun awaitDecidedEgressPlan(budgetMillis: Long = STEAM_EGRESS_DECISION_WAIT_MS): SteamEgressPlan?
}

/** The one adapter that lets a caller read the egress without depending on the Android network owner. */
@Singleton
internal class SteamEgressDecisionSourceAdapter
    @Inject
    internal constructor(
        private val manager: SteamAccessManager,
    ) : SteamEgressDecisionSource {
        override fun publishedEgressPlan(): SteamEgressPlan = manager.publishedEgressPlan()

        override suspend fun awaitDecidedEgressPlan(budgetMillis: Long): SteamEgressPlan? =
            manager.awaitDecidedEgressPlan(budgetMillis)
    }

/**
 * One egress decision, from two explicit observations.
 *
 * This is the whole policy the field report exercised, with no Android type and no I/O: the baseline is
 * probed over the egress the app would otherwise use, the enhanced dial is only attempted when the gate in
 * [steamBridgeMayBeVerified] allows it, and the plan is still [SteamEgressPlanner]'s decision. Splitting it
 * out is what lets "blocked baseline, DoH resolved, bridged dial verified" be asserted without a device on
 * a blocked network.
 */
internal class SteamEgressDecisionPlanner(
    private val probeEnvironment: suspend (SteamEgress) -> SteamEnvironment,
    private val verifyEnhancedPath: suspend () -> SteamEnhancedPath,
) {
    suspend fun decide(input: SteamEgressDecisionInput): SteamEgressDecisionOutcome {
        val baselineOnly =
            SteamEgressPlanner.plan(
                SteamEgressInput(
                    generation = input.generation,
                    networkFingerprint = input.networkFingerprint,
                    networkType = input.networkType,
                    antiBlockingEnabled = false,
                    userProxy = input.userProxy,
                    systemProxy = input.systemProxy,
                    environment = SteamEnvironment.Unknown,
                    baselineReachable = false,
                    enhancedReachable = false,
                ),
            )
        val environment =
            if (input.antiBlockingEnabled && input.smartDohEnabled) {
                probeEnvironment(baselineOnly.baseline)
            } else {
                SteamEnvironment.Unknown
            }
        val baselineReachable = environment is SteamEnvironment.Open
        val mayBridge =
            steamBridgeMayBeVerified(
                baseline = baselineOnly.baseline,
                antiBlockingEnabled = input.antiBlockingEnabled,
                smartDohEnabled = input.smartDohEnabled,
                baselineReachable = baselineReachable,
                networkType = input.networkType,
            )
        // An unknown environment is not a real verdict: the planner refuses to bridge on it anyway, so
        // spending the one intrusive probe (DoH resolve plus verified TLS) would buy nothing.
        if (environment is SteamEnvironment.Unknown) {
            return SteamEgressDecisionOutcome(
                plan =
                    SteamEgressPlanner.plan(
                        SteamEgressInput(
                            generation = input.generation,
                            networkFingerprint = input.networkFingerprint,
                            networkType = input.networkType,
                            antiBlockingEnabled = input.antiBlockingEnabled,
                            userProxy = input.userProxy,
                            systemProxy = input.systemProxy,
                            environment = environment,
                            baselineReachable = baselineReachable,
                            enhancedReachable = false,
                        ),
                    ),
            )
        }
        val enhancedPath = if (mayBridge) verifyEnhancedPath() else null
        val plan =
            SteamEgressPlanner.plan(
                SteamEgressInput(
                    generation = input.generation,
                    networkFingerprint = input.networkFingerprint,
                    networkType = input.networkType,
                    antiBlockingEnabled = input.antiBlockingEnabled,
                    userProxy = input.userProxy,
                    systemProxy = input.systemProxy,
                    environment = environment,
                    baselineReachable = baselineReachable,
                    enhancedReachable = enhancedPath?.reachable == true,
                ),
            )
        return SteamEgressDecisionOutcome(plan = plan, enhancedPath = enhancedPath)
    }
}
