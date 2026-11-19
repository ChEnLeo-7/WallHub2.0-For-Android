package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamVerificationMethod
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The cadence of the host-scheduled session maintenance loop.
 *
 * Well under the SDK's negotiated heartbeat interval (which can be capped at 10 s), so ordinary service writes
 * cannot postpone the next due-heartbeat check until the peer's close boundary. It also notices a genuinely
 * closed connection within seconds rather than when the next business read happens to fail.
 */
internal const val STEAM_SESSION_MAINTENANCE_INTERVAL_MS = 2_000L

/** SteamHelper-style bounded reconnect debt: 1, 2, 4, 8, 16, 32, then 60 seconds. */
private fun steamAccountRecoveryDelayMs(shortSessionFailures: Long): Long {
    if (shortSessionFailures <= 0L) return 0L
    val exponent = (shortSessionFailures - 1L).coerceAtMost(MAX_RECOVERY_BACKOFF_EXPONENT).toInt()
    return (FIRST_RECOVERY_BACKOFF_MS shl exponent).coerceAtMost(MAX_RECOVERY_BACKOFF_MS)
}

private const val FIRST_RECOVERY_BACKOFF_MS = 1_000L
private const val MAX_RECOVERY_BACKOFF_MS = 60_000L
private const val MAX_RECOVERY_BACKOFF_EXPONENT = 6L
private const val STABLE_ACCOUNT_SESSION_MS = 60_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

/**
 * The Steam Content SDK as this app's session owner.
 *
 * It presents the same [SteamSessionRepository] shape kSteam does, so the login UI and every other domain
 * keep one model: a [SteamSessionState] flow plus logon, Guard answer, restore, and logout. Nothing here
 * learns a second state machine.
 *
 * Credentials exist only for the call that needs them. A stored refresh token is read at the existing
 * [EncryptedSteamCredentialStore] boundary, passed to the SDK once, and that byte copy is zeroed. A password
 * and a Guard answer are the same: each is converted to bytes for the one SDK call, zeroed afterwards, and
 * persisted nowhere; the caller's own string is not retained by this class beyond that operation. The
 * challenge identifier is the only SDK value retained across a call, because answering later requires it,
 * and it is cleared as soon as it is used or the session ends.
 *
 * The backend never falls back to kSteam. A missing or unreadable token, an unavailable platform route, or
 * a refused SDK capability publishes a failure that the UI can render instead of silently answering from
 * another backend, which is what a later parity comparison depends on.
 *
 * A logon or restore round that fails in the NETWORK class is retried exactly once against a candidate list
 * read live from the directory. Interactive logon ends after that bounded round. A remembered-session restore
 * that exhausts both attempts keeps one Backend-owned retry plan with capped exponential delay; foreground,
 * business, maintenance and timer triggers coalesce through the same attempt gate. No other failure class is
 * scheduled automatically.
 */
internal class SteamContentSdkSessionBackend(
    private val platform: SteamContentSdkPlatform,
    private val credentialStore: EncryptedSteamCredentialStore,
    private val messages: SteamContentSdkSessionMessages,
    private val diagnostics: DiagnosticRepository,
    private val authenticatedSessions: SteamContentSdkAuthenticatedSessionRegistry =
        SteamContentSdkAuthenticatedSessionRegistry(),
    private val scope: CoroutineScope,
    private val admissionStore: SteamAccountAdmissionStore,
    private val networkReadiness: SteamSessionNetworkReadinessSource,
    private val cooldownNowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /**
     * How often an established session is drained and maintained, or zero to leave the schedule to nobody.
     *
     * Scheduling belongs to the host, so the cadence is the host's own input rather than a constant buried in
     * the loop. A JVM test passes zero: a maintenance loop is never idle, so `advanceUntilIdle` would run it
     * forever, and the tests that are about logon and restore have nothing to say about the cadence.
     */
    private val maintenanceIntervalMs: Long = STEAM_SESSION_MAINTENANCE_INTERVAL_MS,
    private val nowNanos: () -> Long = System::nanoTime,
    private val beforeRecoveryPlanPublish: () -> Unit = {},
) : SteamSessionRepository {
    private val mutableSession = MutableStateFlow(SteamSessionState())
    override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

    private val attemptInProgress = AtomicBoolean(false)
    private val logoutInProgress = AtomicBoolean(false)
    private val attemptLock = Any()
    private val attemptJob = AtomicReference<Job?>(null)
    /** The sole delayed plan allowed to reopen the remembered account session. */
    private val recoveryJob = AtomicReference<Job?>(null)
    private val manualGuardFallbackJob = AtomicReference<Job?>(null)
    private val activeSession = AtomicReference<SteamContentSdkPlatformSession?>(null)
    private val pendingChallenge = AtomicReference<PendingChallenge?>(null)

    /**
     * The newest request that arrived while an attempt was already running.
     *
     * Only one is kept, and a later request replaces an earlier one, so the user's last intent is the one
     * that runs. Dropping it instead is what made a tap on sign-in do nothing at all while a background
     * restore held the gate for up to its whole five-minute deadline.
     */
    private enum class AttemptIntent { RECOVERY, USER }

    private class PendingAttempt(
        val intent: AttemptIntent,
        val block: suspend () -> Unit,
    )

    private data class RecoveryLease(
        val generation: Long,
        val epoch: Long,
        /**
         * True when this attempt replaces a session whose route no longer matches the published plan.
         *
         * It is the only difference between "check the session I have" and "the egress moved, so the
         * connection this session holds is stale by definition": the second one may not be kept even
         * when its socket still answers maintenance.
         */
        val reopenForEgress: Boolean = false,
    )

    private val pendingAttempt = AtomicReference<PendingAttempt?>(null)
    /** A restore request is durable for this process until one ready network generation consumes it. */
    private val restoreWanted = AtomicBoolean(false)
    private val currentNetworkGeneration = AtomicLong(networkReadiness.state.value.generation)
    private val lastNetworkReadiness = AtomicReference(networkReadiness.state.value)
    /**
     * The last known semantic egress, or null before any plan has named one.
     *
     * Seeded from the source's first value and re-seeded whenever a session is opened, so the comparison
     * is against the egress the live session actually has rather than against whatever the flow happened
     * to deliver last. A null snapshot never advances it: it says the plan is unknown, not that traffic
     * moved.
     */
    private val lastEgressIdentity = AtomicReference(networkReadiness.state.value.egressIdentity)
    /**
     * The egress the live session was opened on, which is what a reopen actually compares against.
     *
     * [lastEgressIdentity] follows the plan; this follows the session. They differ exactly when a reopen
     * is owed, and a reopen that would land on the egress the session already has is skipped instead of
     * costing a sign-in.
     */
    private val activeSessionEgress = AtomicReference<String?>(null)
    /** A reopen the input has asked for but the network was not ready to run. */
    private val egressReopenOwed = AtomicBoolean(false)
    /**
     * Set by an explicit sign-out and cleared only by an explicit sign-in.
     *
     * It is separate from the stored credential because a sign-out whose store clear failed leaves that
     * credential on disk while reporting `SIGNED_OUT`. Without this flag an egress change would replay it
     * and sign the account back in - the "signed-out app signs itself back in" failure class.
     */
    private val signOutRequested = AtomicBoolean(false)
    private val networkEpoch = AtomicLong(0L)
    private val consumedReadyGeneration = AtomicLong(NO_NETWORK_GENERATION)
    private val scheduledRecoveryWanted = AtomicBoolean(false)
    private val explicitRecoveryWanted = AtomicBoolean(false)
    private val recoveryPlansThisNetwork = AtomicLong(0L)
    private val appWasBackgrounded = AtomicBoolean(false)
    private val runningAttemptIntent = AtomicReference<AttemptIntent?>(null)
    private val activeRecoveryLease = AtomicReference<RecoveryLease?>(null)

    /**
     * The credential-store generation the running attempt read its stored credential at, or
     * [NO_CREDENTIAL_GENERATION] when the attempt never read one.
     *
     * A decision to clear a refused credential is only valid for the value it was taken against: if a
     * logged-on credential was written in the meantime, that newer one must survive the stale clear.
     */
    private val attemptCredentialGeneration = AtomicLong(NO_CREDENTIAL_GENERATION)

    /** The one host-scheduled loop, bound to the exact session generation it maintains. */
    private class MaintenanceLoop(
        val session: SteamContentSdkPlatformSession,
        val job: Job,
    )

    private val maintenanceJob = AtomicReference<MaintenanceLoop?>(null)

    /** The failure class of the attempt that just finished, so a transport failure earns one retry. */
    private val lastAttemptFailure = AtomicReference(SteamContentSdkSessionFailure.NONE)
    /** Consecutive account CM generations that died before the stability window. */
    private val shortSessionFailures = AtomicLong(0L)
    /** Monotonic start of the authenticated generation, used only to decide whether retry debt resets. */
    private val authenticatedAtNanos = AtomicLong(0L)
    private val sessionMutex = Mutex()
    // A durable marker restarts a full monotonic cooldown on every process start. This intentionally
    // over-waits after process death, but cannot be shortened by wall-clock changes or reboot.
    private var rateLimitStartedAt: Long? = null
    private var rateLimitMarkerRequired = false

    private sealed interface Admission {
        data object Allowed : Admission
        data class Limited(val waitMs: Long) : Admission
        data object StorageUnavailable : Admission
    }

    private fun admission(): Admission {
        try {
            if (rateLimitMarkerRequired) {
                admissionStore.writeLimited(true)
                rateLimitMarkerRequired = false
            }
            if (!admissionStore.readLimited() && rateLimitStartedAt == null) return Admission.Allowed
            val now = cooldownNowMillis()
            val started = rateLimitStartedAt ?: now.also { rateLimitStartedAt = it }
            val elapsed = (now - started).coerceAtLeast(0L)
            if (elapsed < ACCOUNT_RATE_LIMIT_COOLDOWN_MS) return Admission.Limited(ACCOUNT_RATE_LIMIT_COOLDOWN_MS - elapsed)
            admissionStore.writeLimited(false)
            rateLimitStartedAt = null
            return Admission.Allowed
        } catch (error: Throwable) {
            record("account_admission_storage_failure", error)
            return Admission.StorageUnavailable
        }
    }

    private fun rememberRateLimit() {
        rateLimitStartedAt = cooldownNowMillis()
        rateLimitMarkerRequired = true
        recoveryJob.getAndSet(null)?.cancel()
        try {
            admissionStore.writeLimited(true)
            rateLimitMarkerRequired = false
        } catch (error: Throwable) {
            record("account_admission_storage_failure", error)
        }
    }

    init {
        authenticatedSessions.installRecoveryOwner(
            object : SteamContentSdkAccountSessionRecoveryOwner {
                override fun recoverIfCurrent(
                    accountName: String,
                    generation: Long,
                ) = retireAccountSession(accountName, generation)

                override fun recoverStoredSession() = requestOnDemandRecovery()
            },
        )
        scope.launch {
            networkReadiness.state.collectLatest(::onNetworkReadinessChanged)
        }
    }

    private class PendingChallenge(
        val kind: SteamContentSdkChallengeKind,
        val id: ByteArray,
        /** The session that asked for this challenge, so an answer can never be sent to a later one. */
        val session: SteamContentSdkPlatformSession,
    )

    // ------------------------------------------------------------------
    // Session transitions
    // ------------------------------------------------------------------

    private fun retireAccountSession(
        accountName: String,
        generation: Long,
    ) {
        launchAttempt {
            val current = activeSession.get() ?: return@launchAttempt
            if (!accountName.equals(mutableSession.value.accountName, ignoreCase = true)) return@launchAttempt
            if (current.authorizationGeneration() != generation) return@launchAttempt
            val authenticatedAt = authenticatedAtNanos.getAndSet(0L)
            val livedMs =
                authenticatedAt
                    .takeIf { it > 0L }
                    ?.let { started -> (nowNanos() - started).coerceAtLeast(0L) / NANOS_PER_MILLISECOND }
                    ?: 0L
            val failures =
                if (livedMs >= STABLE_ACCOUNT_SESSION_MS) {
                    // Stability clears old debt; this loss is then the first failure of a new recovery series.
                    shortSessionFailures.set(1L)
                    1L
                } else {
                    shortSessionFailures.updateAndGet { count -> (count + 1L).coerceAtMost(MAX_RECOVERY_BACKOFF_EXPONENT + 1L) }
                }
            record("content_session_retired generation=$generation livedMs=$livedMs retryDebt=$failures")
            if (!releaseSessionIfCurrent(current)) return@launchAttempt
            publish(
                SteamSessionPhase.RESTORABLE,
                messages.restoreFailed,
                accountName = accountName,
                hasStoredSession = true,
            )
            scheduleStoredRecovery(failures)
        }
    }

    private fun requestOnDemandRecovery(fromScheduledPlan: Boolean = false, explicitReconnect: Boolean = false) {
        if (mutableSession.value.phase == SteamSessionPhase.WAITING_FOR_CODE ||
            mutableSession.value.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
        ) return
        val readiness = networkReadiness.state.value
        // StateFlow updates synchronously, while its collector is scheduled. Reconcile that exact snapshot here
        // before consuming it so a restore request cannot race ahead of the generation/epoch fence.
        onNetworkReadinessChanged(readiness)
        if (!fromScheduledPlan && !explicitReconnect && readiness.isReady && consumedReadyGeneration.get() == readiness.generation) {
            record("recovery_ready_generation_joined generation=${readiness.generation}")
            return
        }
        restoreWanted.set(true)
        if (explicitReconnect) explicitRecoveryWanted.set(true)
        if (fromScheduledPlan) scheduledRecoveryWanted.set(true)
        // Ordinary signals join the current not-before plan. Only that plan may spend its retry at the deadline.
        if (!fromScheduledPlan && recoveryJob.get() != null) {
            record("recovery_backoff_joined")
            return
        }
        admitRecoveryIfReady(networkReadiness.state.value)
    }

    @Synchronized
    private fun onNetworkReadinessChanged(next: SteamSessionNetworkReadiness) {
        val previous = lastNetworkReadiness.getAndSet(next)
        currentNetworkGeneration.set(next.generation)
        if (next == previous) return
        val egressChanged = adoptEgressIdentity(next.egressIdentity)
        if (egressChanged) egressReopenOwed.set(true)
        // A semantic egress change fences exactly like a new network: any attempt, route probe or route
        // result that started against the previous egress is no longer evidence about this one.
        if (next.generation != previous.generation || !next.isReady || egressChanged) {
            networkEpoch.incrementAndGet()
            recoveryJob.getAndSet(null)?.cancel()
            scheduledRecoveryWanted.set(false)
            if (next.generation != previous.generation) recoveryPlansThisNetwork.set(0L)
            synchronized(attemptLock) {
                if (runningAttemptIntent.get() == AttemptIntent.RECOVERY) attemptJob.get()?.cancel()
            }
        }
        if (!next.isReady) return
        if (egressReopenOwed.get()) {
            // The baseline names the egress requests use right now, but it is not where a session may be
            // replaced: on a network whose decision is the bridge, reopening on it would cost a sign-in
            // and a doomed attempt on a route that is about to change. The decision re-enters here.
            if (next.egressProvisional) return
            if (!egressReopenOwed.compareAndSet(true, false)) return
            if (activeSession.get() != null && activeSessionEgress.get() == lastEgressIdentity.get()) {
                // A provisional egress moved and the decision then named the one the session already
                // has. Nothing to replace, so nothing is signed in again.
                record("session_reopen_already_applied")
                return
            }
            requestEgressReopen(next)
            return
        }
        admitRecoveryIfReady(next)
    }

    /**
     * Adopts the identity a newer readiness snapshot carries, and says whether traffic actually moved.
     *
     * Only a change between two known egresses counts. The one case where a previously unknown identity
     * is movement is a live session opened while no plan named an egress: that session has to move to the
     * first one the app decides, or it keeps serving from a route the plan no longer describes. Called
     * under the instance lock, so this value and [lastNetworkReadiness] advance together.
     */
    private fun adoptEgressIdentity(next: String?): Boolean {
        if (next == null) return false
        val previous = lastEgressIdentity.getAndSet(next)
        if (previous == null) return activeSession.get() != null
        return previous != next
    }

    /**
     * Asks the one attempt gate to replace the signed-in session with one the newest plan describes.
     *
     * The plan is already published when this runs, so the route prepared below reads it and cannot use
     * the egress that just went away. It may only re-establish a session the user still has: a user
     * sign-out, a session without a stored credential and an interactive sign-in in progress all keep the
     * automatic path out, which is what stops a network change from signing somebody back in.
     */
    private fun requestEgressReopen(readiness: SteamSessionNetworkReadiness) {
        if (!readiness.isReady || logoutInProgress.get()) return
        if (signOutRequested.get()) {
            record("session_reopen_skipped signed_out")
            return
        }
        if (!mutableSession.value.hasStoredSession) {
            record("session_reopen_skipped no_stored_session")
            return
        }
        val phase = mutableSession.value.phase
        if (phase == SteamSessionPhase.WAITING_FOR_CODE ||
            phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
        ) {
            record("session_reopen_skipped interactive_sign_in")
            return
        }
        // An egress that oscillates would otherwise re-authenticate on every flip. It shares the one
        // per-network plan budget with scheduled recovery, and that budget resets on a new network: a
        // user's own proxy edits land as a new generation, while a decision that keeps changing its mind
        // on one network is bounded by the same seven attempts every other recovery path gets.
        val planNumber = recoveryPlansThisNetwork.incrementAndGet()
        if (planNumber > MAX_RECOVERY_PLANS_PER_NETWORK) {
            record("session_reopen_budget_exhausted plans=$planNumber")
            return
        }
        restoreWanted.set(true)
        // The ready generation this session consumed is the one being replaced, so the reopen must not
        // join it: it is a replacement of that session, not a second request for it.
        scheduledRecoveryWanted.set(true)
        admitRecoveryIfReady(readiness, reopenForEgress = true)
    }

    private fun admitRecoveryIfReady(
        readiness: SteamSessionNetworkReadiness,
        reopenForEgress: Boolean = false,
    ) {
        if (!readiness.isReady || !restoreWanted.get() || logoutInProgress.get()) return
        // A manual request waiting for readiness still honors an existing not-before plan.
        if (explicitRecoveryWanted.get() && recoveryJob.get() != null && !reopenForEgress) return
        val scheduled = scheduledRecoveryWanted.getAndSet(false)
        val explicit = explicitRecoveryWanted.getAndSet(false)
        if (explicit) {
            consumedReadyGeneration.set(readiness.generation)
        } else if (!scheduled) {
            while (true) {
                val consumed = consumedReadyGeneration.get()
                if (consumed == readiness.generation) {
                    record("recovery_ready_generation_joined generation=${readiness.generation}")
                    return
                }
                if (consumedReadyGeneration.compareAndSet(consumed, readiness.generation)) break
            }
        }
        val generation = readiness.generation
        val epoch = networkEpoch.get()
        val lease = RecoveryLease(generation, epoch, reopenForEgress)
        launchAttempt {
            activeRecoveryLease.set(lease)
            try {
                ensureRecoveryLease(generation, epoch)
                if (mutableSession.value.phase == SteamSessionPhase.WAITING_FOR_CODE ||
                    mutableSession.value.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
                ) return@launchAttempt
                recoverOnReadyNetwork(generation, epoch)
            } finally {
                activeRecoveryLease.compareAndSet(lease, null)
            }
        }
    }

    private suspend fun recoverOnReadyNetwork(
        generation: Long,
        epoch: Long,
    ) {
        val current =
            activeSession.get()?.takeIf { session ->
                runCatching { session.state() }.getOrNull() == SteamContentSdkSessionState.AUTHENTICATED
            }
        if (current == null) {
            restoreStoredSession(generation, epoch)
            return
        }
        if (activeRecoveryLease.get()?.reopenForEgress == true) {
            // The published plan moved, so this connection holds a route that no longer exists even if its
            // socket still answers a maintenance call. Replacing it is the whole point of the attempt.
            reopenAccountSession(current, generation, epoch)
            return
        }
        val prepared = platform.prepareLiveRoute()
        ensureRecoveryLease(generation, epoch)
        // A healthy published session remains authoritative while Steam is temporarily unreachable. No SDK
        // operation is started until the bounded live route probe succeeds.
        if (prepared == null) {
            scheduleNetworkReadinessRetry()
            return
        }
        val maintained = runMaintenanceStep(current).getOrNull() == SteamContentSdkSessionState.AUTHENTICATED
        ensureRecoveryLease(generation, epoch)
        if (maintained) {
            restoreWanted.set(false)
            return
        }
        if (releaseSessionIfCurrent(current)) {
            publish(
                SteamSessionPhase.RESTORABLE,
                messages.restoreFailed,
                hasStoredSession = true,
            )
        }
        restoreStoredSession(generation, epoch, prepared)
    }

    /**
     * Replaces the live session with one opened on the plan that is published now.
     *
     * The order is the contract, and it is deliberate even when the new egress turns out to be unusable:
     * first the attempt is published, so every plane sees a sign-in in flight and no new work starts on
     * the old session; then that session is closed, which also withdraws it from the authenticated-session
     * registry; only then is a route prepared and the stored credential replayed. Keeping the old session
     * instead, and closing it only if the new route probes successfully, is what the previous round's
     * fencing did - and it leaves a live connection whose route the published plan no longer names, which
     * is exactly the stale-route service this attempt exists to stop. A new egress that cannot be built
     * therefore costs the session for as long as the egress is unusable, and the bounded recovery plan
     * brings it back when a representable egress returns.
     *
     * Cancelling this attempt - a newer egress, a user sign-in, a sign-out - leaves nothing behind that
     * could publish over the replacement, because the publication and the close are both fenced by the
     * recovery lease and by [releaseSessionIfCurrent].
     */
    private suspend fun reopenAccountSession(
        current: SteamContentSdkPlatformSession,
        generation: Long,
        epoch: Long,
    ) {
        publish(SteamSessionPhase.SIGNING_IN, messages.restoringSession, hasStoredSession = true)
        record("session_reopen_egress kind=${egressKindForRecord()}")
        // Resolve the new route while the old session is still the live one: a route that cannot be built
        // is then a reported failure instead of an exception escaping after SIGNING_IN was published.
        val prepared = prepareRouteForReopen()
        if (!releaseSessionIfCurrent(current)) {
            // A newer session replaced this one while the plan was being applied; it was opened against the
            // current plan already, so closing it here would discard the better session.
            record("session_reopen_superseded")
            return
        }
        if (prepared == null) {
            // No representable egress exists, so nothing may be replayed. The session is not kept: its
            // route is the one the plan just stopped naming, and keeping it is what leaves a live
            // connection on an egress nothing else in the app can use.
            publish(SteamSessionPhase.RESTORABLE, messages.restoreFailed, hasStoredSession = true)
            scheduleNetworkReadinessRetry()
            return
        }
        restoreStoredSession(generation, epoch, prepared)
    }

    /**
     * The new egress's route, or null when it cannot be built: never an exception that ends the attempt.
     *
     * The route builder's own failure modes are named rather than caught broadly: a directory read that
     * fails, or a route contract the current plan cannot satisfy, is a reportable "no route" - and anything
     * else stays a real defect instead of being folded into one.
     */
    private suspend fun prepareRouteForReopen(): SteamContentSdkPreparedRoute? =
        try {
            platform.prepareLiveRoute()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            record("session_reopen_route_failure", error)
            null
        } catch (error: IllegalArgumentException) {
            record("session_reopen_route_failure", error)
            null
        } catch (error: IllegalStateException) {
            record("session_reopen_route_failure", error)
            null
        }

    /** The kind half of the egress identity. A proxy address is never written to a diagnostic. */
    private fun egressKindForRecord(): String =
        lastEgressIdentity.get()?.substringBefore(':') ?: "undecided"

    private fun ensureRecoveryLease(
        generation: Long,
        epoch: Long,
    ) {
        if (generation != currentNetworkGeneration.get()) throw staleRecoveryCancellation()
        if (epoch != networkEpoch.get()) throw staleRecoveryCancellation()
        if (!networkReadiness.state.value.isReady) throw staleRecoveryCancellation()
        if (!restoreWanted.get()) throw staleRecoveryCancellation()
        if (logoutInProgress.get()) throw staleRecoveryCancellation()
    }

    private fun staleRecoveryCancellation(): CancellationException =
        CancellationException("Steam recovery readiness changed")

    private fun ensureCurrentRecoveryPublication() {
        val lease = activeRecoveryLease.get() ?: return
        ensureRecoveryLease(lease.generation, lease.epoch)
    }

    private fun scheduleNetworkReadinessRetry() {
        scheduleStoredRecovery(failures = recoveryPlansThisNetwork.get() + 1L)
    }

    private fun scheduleStoredRecovery(failures: Long, minimumDelayMs: Long = 0L) {
        if (logoutInProgress.get() || !mutableSession.value.hasStoredSession) return
        if (recoveryJob.get() != null) {
            record("session_recovery_coalesced")
            return
        }
        val planNumber = recoveryPlansThisNetwork.incrementAndGet()
        if (planNumber > MAX_RECOVERY_PLANS_PER_NETWORK) {
            record("session_recovery_budget_exhausted plans=$planNumber")
            return
        }
        val retryDebt = failures.coerceAtLeast(1L)
        val delayMs = maxOf(steamAccountRecoveryDelayMs(retryDebt), minimumDelayMs)
        lateinit var scheduled: Job
        scheduled =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                if (!recoveryJob.compareAndSet(scheduled, null)) return@launch
                if (logoutInProgress.get()) return@launch
                record("session_recovery_started retryDebt=$retryDebt")
                requestOnDemandRecovery(fromScheduledPlan = true)
            }
        beforeRecoveryPlanPublish()
        if (logoutInProgress.get()) {
            scheduled.cancel()
            return
        }
        if (recoveryJob.compareAndSet(null, scheduled)) {
            if (logoutInProgress.get()) {
                recoveryJob.compareAndSet(scheduled, null)
                scheduled.cancel()
                return
            }
            record("session_recovery_scheduled delayMs=$delayMs retryDebt=$retryDebt")
            scheduled.start()
        } else {
            scheduled.cancel()
            record("session_recovery_coalesced")
        }
    }

    override fun restorePersistedSession() = requestOnDemandRecovery()

    override fun reconnectPersistedSession() {
        if (signOutRequested.get() || logoutInProgress.get() || !mutableSession.value.hasStoredSession) return
        val phase = mutableSession.value.phase
        if (phase != SteamSessionPhase.RESTORABLE && phase != SteamSessionPhase.FAILED) return
        if (attemptInProgress.get() && runningAttemptIntent.get() == AttemptIntent.USER) return
        publish(
            SteamSessionPhase.SIGNING_IN,
            messages.restoringSession,
            hasStoredSession = true,
        )
        requestOnDemandRecovery(explicitReconnect = true)
    }

    override fun login(
        accountName: String,
        password: String,
    ) {
        val trimmed = accountName.trim()
        if (trimmed.isEmpty() || password.isBlank()) {
            publish(
                SteamSessionPhase.FAILED,
                messages.enterCredentials,
                accountName = trimmed.takeIf { it.isNotEmpty() },
            )
            return
        }
        // Invalidate the automatic-recovery lease before cancellation, so even a late route result cannot
        // publish over the explicit account the user is signing in with.
        synchronized(attemptLock) {
            restoreWanted.set(false)
            scheduledRecoveryWanted.set(false)
            explicitRecoveryWanted.set(false)
            networkEpoch.incrementAndGet()
            recoveryJob.getAndSet(null)?.cancel()
            if (runningAttemptIntent.get() == AttemptIntent.RECOVERY) {
                activeSession.get()?.let(::releaseSessionIfCurrent)
            }
        }
        // A sign-in the user asked for takes precedence over whatever attempt is running: it supersedes it
        // rather than waiting behind it, because waiting means a tap that does nothing for up to five minutes.
        // It is also the only thing that reverses an explicit sign-out for the automatic path.
        signOutRequested.set(false)
        launchAttempt(intent = AttemptIntent.USER, supersedes = true) { logon(trimmed, password) }
    }

    override fun submitSteamGuardCode(code: String) {
        val answerText = code.trim()
        // Claiming the pending challenge atomically keeps a second submission from answering twice.
        val challenge = pendingChallenge.get()
        if (answerText.isEmpty() || challenge == null || logoutInProgress.get()) {
            publish(SteamSessionPhase.FAILED, messages.noGuardRequest)
            return
        }
        // The answer runs as an attempt like every other flow. It used to run in a coroutine of its own, which
        // no sign-out cancelled and which could release the session a newer attempt had since opened; a typed
        // code is also a user action, so it supersedes whatever attempt collided with it.
        launchAttempt(intent = AttemptIntent.USER, supersedes = true) {
            val session = activeSession.get()
            if (session == null || session !== challenge.session) {
                // The challenge belongs to a session that is no longer the live one: answering it would either
                // fail against a session that never asked, or be answered into a session nobody is looking at.
                record("guard_answer_stale")
                // A failure is only published when there is still a sign-in to report it against: a queued
                // answer that arrives after a sign-out must not overwrite the phase the sign-out published.
                if (!logoutInProgress.get()) publish(SteamSessionPhase.FAILED, messages.noGuardRequest)
                return@launchAttempt
            }
            val answer = answerText.toByteArray(Charsets.UTF_8)
            val accepted =
                try {
                    withSession { session.respondToChallenge(challenge.id, answer) }
                    true
                } catch (cancelled: CancellationException) {
                    // A cancellation is not a refused answer: reporting it as one would publish a failure over
                    // whatever the cancelling flow is doing.
                    throw cancelled
                } catch (error: Throwable) {
                    record("guard_answer_failure", error)
                    false
                } finally {
                    answer.fill(0)
                }
            if (activeSession.get() !== session) {
                // A newer attempt replaced the session while the answer was in flight; its outcome is not this
                // one's to publish, and releasing here would close a session this answer does not own.
                record("guard_answer_superseded")
                return@launchAttempt
            }
            if (!accepted) {
                publish(
                    SteamSessionPhase.WAITING_FOR_CODE,
                    messages.authRejected,
                    verificationMethod = challenge.kind.verificationMethod(),
                )
                return@launchAttempt
            }
            publish(SteamSessionPhase.SIGNING_IN, messages.codeSubmitted)
            driveToOutcome(
                session = session,
                restore = false,
                accountName = mutableSession.value.accountName,
                hasStoredSession = mutableSession.value.hasStoredSession,
            )
        }
    }

    /**
     * The SDK does not let a host choose the confirmation method.
     *
     * kSteam exposes an in-memory retry with a code prompt; the SDK decides which challenge Steam presents
     * and refuses an answer to a device-confirmation challenge, so a device confirmation cannot be swapped
     * for a typed code here. When a code challenge is already pending this re-publishes it, and when there is
     * no sign-in at all it reports the same "no active sign-in" failure kSteam does.
     */
    override fun useManualSteamGuardFallback() {
        val pending = pendingChallenge.get()
        if (pending != null) {
            publish(
                steamContentSdkChallengePhase(pending.kind),
                challengeMessage(pending.kind),
                verificationMethod = pending.kind.verificationMethod(),
            )
            return
        }
        if (mutableSession.value.phase != SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION) {
            publish(SteamSessionPhase.FAILED, messages.noManualLogin)
            return
        }
        val session = activeSession.get()
        val generation = session?.let { runCatching { it.authorizationGeneration() }.getOrNull() }
        if (session == null || generation == null || logoutInProgress.get()) {
            publish(SteamSessionPhase.FAILED, messages.noManualLogin)
            return
        }
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                // Both calls belong to the exact session generation that displayed this confirmation page.
                val choices = withSession { session.guardChoices() }
                val code =
                    when {
                        choices and SteamContentSdkGuardChoice.DEVICE_CODE_BIT != 0L -> SteamContentSdkGuardChoice.DEVICE_CODE
                        choices and SteamContentSdkGuardChoice.EMAIL_CODE_BIT != 0L -> SteamContentSdkGuardChoice.EMAIL_CODE
                        else -> null
                    }
                if (
                    activeSession.get() !== session ||
                    runCatching { session.authorizationGeneration() }.getOrNull() != generation ||
                    logoutInProgress.get() ||
                    mutableSession.value.phase != SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
                ) {
                    record("manual_guard_fallback_stale")
                    return@launch
                }
                if (code == null) {
                    record("manual_guard_fallback_unavailable choices=$choices")
                    publish(SteamSessionPhase.FAILED, messages.noManualLogin)
                    return@launch
                }
                record("manual_guard_fallback choice=$code")
                withSession { session.chooseGuard(code) }
            }
        manualGuardFallbackJob.getAndSet(job)?.cancel()
        job.invokeOnCompletion { manualGuardFallbackJob.compareAndSet(job, null) }
        job.start()
    }

    override fun logout() {
        // Before anything else: from here on no automatic path may replay the stored credential, even if
        // the store cannot be cleared and still holds it.
        signOutRequested.set(true)
        restoreWanted.set(false)
        scheduledRecoveryWanted.set(false)
        explicitRecoveryWanted.set(false)
        egressReopenOwed.set(false)
        networkEpoch.incrementAndGet()
        val job =
            synchronized(attemptLock) {
                if (!logoutInProgress.compareAndSet(false, true)) return
                pendingAttempt.getAndUpdate { pending -> pending?.takeIf { it.intent == AttemptIntent.USER } }
                attemptJob.getAndSet(null)
            }
        manualGuardFallbackJob.getAndSet(null)?.cancel()
        recoveryJob.getAndSet(null)?.cancel()
        scope.launch {
            try {
                job?.cancelAndJoin()
                // The cancelled attempt may have raced far enough to publish a delayed plan. Fence it again
                // after join, while logoutInProgress still prevents any replacement from being installed.
                recoveryJob.getAndSet(null)?.cancel()
                // The maintenance loop must stop before the session it drives is torn down.
                maintenanceJob.getAndSet(null)?.job?.cancel()
                // A pending recovery retry is a standing plan to sign this account back in, which is the
                // opposite of what this call is doing. It matters most in the failing case below: when the
                // store cannot be cleared the credential is still there, so a retry left running would read
                // it and sign the user back in after they had signed out.
                val session = activeSession.getAndSet(null)
                if (session != null) {
                    withSession { runCatching { session.logout() } }
                    runCatching { session.close() }
                }
                pendingChallenge.set(null)
                authenticatedAtNanos.set(0L)
                shortSessionFailures.set(0L)
                // The claim the UI shows is about the store, so it is the store's answer that is published:
                // telling the user the saved sign-in is gone when it could not be removed is what let a
                // "signed out" app sign itself back in on the next launch.
                val cleared = clearStoredCredential()
                publish(
                    SteamSessionPhase.SIGNED_OUT,
                    if (cleared) messages.signedOut else messages.signOutIncomplete,
                    hasStoredSession = !cleared,
                )
            } finally {
                attemptInProgress.set(false)
                logoutInProgress.set(false)
                drainPendingAttempt()
            }
        }
    }

    override fun onAppForegrounded() {
        if (mutableSession.value.phase == SteamSessionPhase.WAITING_FOR_CODE ||
            mutableSession.value.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
        ) return
        if (appWasBackgrounded.getAndSet(false)) {
            // A later, real foreground return may re-check the same default network generation once. Duplicate
            // ON_START and Shell requests in the same foreground cycle still join the consumed generation.
            consumedReadyGeneration.set(NO_NETWORK_GENERATION)
        }
        requestOnDemandRecovery()
    }

    override fun onAppBackgrounded() {
        appWasBackgrounded.set(true)
    }

    override fun isAttemptInFlight(): Boolean =
        // The running attempt and the one queued behind it, which are the two states in which the SDK's one
        // session slot is already spoken for. A scheduled recovery retry is deliberately not counted: it holds
        // no slot while it waits, and a download that waited for it would wait for a timer rather than a session.
        attemptInProgress.get() || pendingAttempt.get() != null

    /** Releases the live SDK session. The interface has no shutdown hook, so this is the explicit close path. */
    internal fun close() {
        attemptJob.getAndSet(null)?.cancel()
        manualGuardFallbackJob.getAndSet(null)?.cancel()
        recoveryJob.getAndSet(null)?.cancel()
        // A scheduled recovery retry is a live plan to reopen a session, so it does not outlive the shutdown
        // that closed one. It is cancelled before the gate is opened so a retry already inside
        // restorePersistedSession is torn down with the rest.
        // The maintenance loop drives the session this is about to close, so it stops first - the same order
        // releaseSession uses. Leaving it running leaves it ticking against a closed session for up to one
        // interval, which is the leak this path had.
        maintenanceJob.getAndSet(null)?.job?.cancel()
        // Nothing is left to hand the gate to on the shutdown path, so it is opened here rather than by the
        // cancelled attempt's completion handler (which no longer owns it).
        attemptInProgress.set(false)
        pendingAttempt.set(null)
        val session = activeSession.getAndSet(null)
        if (session != null) {
            runCatching { session.close() }
        }
        pendingChallenge.set(null)
    }

    // ------------------------------------------------------------------
    // Flows
    // ------------------------------------------------------------------

    /**
     * Starts [block] as the one running attempt, queueing it when another attempt already holds the gate.
     *
     * The gate is what keeps one SDK session from being driven by two flows at once, and it stays. What
     * changed is that a request arriving under it is no longer discarded: it is kept as the pending attempt
     * and runs when the current one finishes, and a request the user made herself ([supersedes]) also ends
     * the attempt it collided with instead of waiting for its deadline.
     */
    private fun launchAttempt(
        intent: AttemptIntent = AttemptIntent.RECOVERY,
        supersedes: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (intent == AttemptIntent.USER) manualGuardFallbackJob.getAndSet(null)?.cancel()
        val pending = PendingAttempt(intent, block)
        synchronized(attemptLock) {
            if (logoutInProgress.get()) {
                if (intent == AttemptIntent.USER) {
                    // Explicit user sign-in survives teardown; background/registry recovery never does.
                    pendingAttempt.set(pending)
                    record("attempt_held_by_logout")
                } else {
                    record("recovery_ignored_during_logout")
                }
                return
            }
            if (attemptInProgress.compareAndSet(false, true)) {
                startAttempt(intent, block)
                return
            }
            if (intent == AttemptIntent.RECOVERY) {
                val running = attemptJob.get()
                if (runningAttemptIntent.get() == AttemptIntent.RECOVERY && running?.isCancelled == true) {
                    // A new network generation arrived while the old recovery is releasing the gate. Preserve
                    // exactly this newest recovery; otherwise its already-consumed ready edge is lost.
                    pendingAttempt.set(pending)
                    record("recovery_queued_after_network_change")
                } else {
                    // Timer, foreground, maintenance and business signals describe the same desired state.
                    record("recovery_coalesced")
                }
                return
            }
            pendingAttempt.set(pending)
            if (supersedes) {
                record("attempt_superseded")
                // Cancelled rather than cleared from [attemptJob]: the completion handler below is the single place
                // the gate opens, so a superseded attempt still hands it to the request that replaced it.
                attemptJob.get()?.cancel()
            } else {
                record("attempt_queued")
            }
        }
    }

    private fun startAttempt(
        intent: AttemptIntent,
        block: suspend () -> Unit,
    ) {
        attemptCredentialGeneration.set(NO_CREDENTIAL_GENERATION)
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        runningAttemptIntent.set(intent)
        attemptJob.set(job)
        // The gate opens here and nowhere else: this runs whether the attempt finished, threw, was cancelled
        // mid-flight, or was cancelled before its body ever ran. A release written as the body's own `finally`
        // silently misses that last case, and the gate would then stay closed for good.
        job.invokeOnCompletion {
            if (attemptJob.compareAndSet(job, null)) {
                runningAttemptIntent.set(null)
                attemptInProgress.set(false)
                drainPendingAttempt()
            }
        }
        job.start()
    }

    /** Runs the request that arrived while the gate was held, if there still is one. */
    private fun drainPendingAttempt() {
        val next = pendingAttempt.getAndSet(null) ?: return
        if (logoutInProgress.get()) {
            pendingAttempt.set(next)
            return
        }
        if (attemptInProgress.compareAndSet(false, true)) startAttempt(next.intent, next.block) else pendingAttempt.set(next)
    }

    private suspend fun restoreStoredSession(
        networkGeneration: Long,
        epoch: Long,
        preparedRoute: SteamContentSdkPreparedRoute? = null,
    ) {
        // The read result is kept in one value so the one load-time diagnostic below and the branch both read
        // the same store result: the store is still read exactly once per attempt.
        attemptCredentialGeneration.set(credentialStore.generation())
        val credential = credentialStore.read()
        recordCredentialLoad(credential)
        when (credential) {
            SteamCredentialReadResult.Missing -> {
                restoreWanted.set(false)
                pendingChallenge.set(null)
                publish(SteamSessionPhase.SIGNED_OUT, message = null, hasStoredSession = false)
            }

            is SteamCredentialReadResult.Unreadable -> {
                record("credential_read_failure", credential.cause)
                publish(
                    SteamSessionPhase.FAILED,
                    messages.sessionStorageUnavailable,
                    hasStoredSession = true,
                )
            }

            is SteamCredentialReadResult.Value -> {
                when (val result = admission()) {
                    Admission.Allowed -> {
                        val prepared = preparedRoute ?: platform.prepareLiveRoute()
                        ensureRecoveryLease(networkGeneration, epoch)
                        if (prepared == null) {
                            publish(
                                SteamSessionPhase.RESTORABLE,
                                messages.restoreFailed,
                                accountName = credential.credential.accountName,
                                hasStoredSession = true,
                            )
                            scheduleNetworkReadinessRetry()
                        } else {
                            restore(credential.credential, prepared, networkGeneration, epoch)
                        }
                    }
                    is Admission.Limited -> {
                        publish(SteamSessionPhase.RESTORABLE, messages.rateLimited,
                            accountName = credential.credential.accountName, hasStoredSession = true)
                        scheduleStoredRecovery(1L, result.waitMs)
                    }
                    Admission.StorageUnavailable -> {
                        publish(SteamSessionPhase.RESTORABLE, messages.sessionStorageUnavailable,
                            accountName = credential.credential.accountName, hasStoredSession = true)
                        scheduleStoredRecovery(1L, MAX_RECOVERY_BACKOFF_MS)
                    }
                }
            }
        }
    }

    private suspend fun restore(
        stored: PersistedSteamCredential,
        prepared: SteamContentSdkPreparedRoute,
        networkGeneration: Long,
        epoch: Long,
    ) {
        restoreOnce(
            stored = stored,
            prepared = prepared,
            refreshLive = false,
            networkGeneration = networkGeneration,
            epoch = epoch,
        )
        if (lastAttemptFailure.get() != SteamContentSdkSessionFailure.NETWORK) return
        // The transport class is the one attempt failure that can mean the CM endpoint set the platform
        // cached is the stale input: the SDK treats a host-supplied list as authoritative and never
        // questions it, so nothing else would ever refresh it. One retry asks for a list read from the
        // directory and re-presents the same stored credential; a second failure is reported as it is,
        // and kSteam is never consulted.
        record("restore_retry_live_candidates")
        restoreOnce(
            stored = stored,
            prepared = null,
            refreshLive = true,
            networkGeneration = networkGeneration,
            epoch = epoch,
        )
        if (lastAttemptFailure.get() == SteamContentSdkSessionFailure.NETWORK) {
            val failures =
                shortSessionFailures.updateAndGet { count ->
                    (count + 1L).coerceAtMost(MAX_RECOVERY_BACKOFF_EXPONENT + 1L)
                }
            scheduleStoredRecovery(failures)
        }
    }

    private suspend fun restoreOnce(
        stored: PersistedSteamCredential,
        prepared: SteamContentSdkPreparedRoute?,
        refreshLive: Boolean,
        networkGeneration: Long,
        epoch: Long,
    ) {
        // Every attempt announces itself, which `logonOnPlatformRoute` already did and this path did not. The
        // device read is the reason: the first attempt published FAILED while the live-candidate retry was
        // still connecting, so for six seconds the app told every other plane "nobody is signing in". A
        // download that asked for a session inside that window opened its own route over the retry that was
        // working, the retry then failed, and the app sat signed out for 7m36s. A retry that is running is
        // signed in as far as any reader is concerned, and the phase has to say so.
        publish(
            SteamSessionPhase.SIGNING_IN,
            messages.restoringSession,
            accountName = stored.accountName,
            hasStoredSession = true,
        )
        // The class belongs to one attempt: a previous attempt's transport failure must not decide this
        // one's outcome, which is what let a successful live retry still be read as a transport failure.
        lastAttemptFailure.set(SteamContentSdkSessionFailure.NONE)
        val session = openSession(refreshLive, prepared, networkGeneration, epoch)
        if (session == null) {
            publish(
                SteamSessionPhase.RESTORABLE,
                messages.unavailable,
                accountName = stored.accountName,
                hasStoredSession = true,
            )
            scheduleNetworkReadinessRetry()
            return
        }
        if (!session.supportsRestore()) {
            releaseSession()
            publish(
                SteamSessionPhase.FAILED,
                messages.restoreFailed,
                accountName = stored.accountName,
                hasStoredSession = true,
            )
            return
        }
        val token = stored.refreshToken.toByteArray(Charsets.UTF_8)
        try {
            session.restore(stored.accountName, token)
        } catch (cancelled: CancellationException) {
            releaseSessionIfCurrent(session)
            throw cancelled
        } catch (error: Throwable) {
            record("restore_command_failure", error)
            releaseSession()
            publish(
                SteamSessionPhase.FAILED,
                messages.restoreFailed,
                accountName = stored.accountName,
                hasStoredSession = true,
            )
            return
        } finally {
            token.fill(0)
        }
        try {
            driveToOutcome(
                session = session,
                restore = true,
                accountName = stored.accountName,
                hasStoredSession = true,
            )
        } catch (cancelled: CancellationException) {
            releaseSessionIfCurrent(session)
            throw cancelled
        }
    }

    private suspend fun logon(
        accountName: String,
        password: String,
    ) {
        when (val result = admission()) {
            Admission.Allowed -> Unit
            is Admission.Limited -> {
                publish(SteamSessionPhase.FAILED, messages.rateLimited)
                scheduleStoredRecovery(1L, result.waitMs)
                return
            }
            Admission.StorageUnavailable -> {
                publish(SteamSessionPhase.FAILED, messages.sessionStorageUnavailable)
                scheduleStoredRecovery(1L, MAX_RECOVERY_BACKOFF_MS)
                return
            }
        }
        // At most one extra open per user-initiated logon: a transport failure asks once for an endpoint set
        // read live instead of the platform's cached one, which is the only input a failed candidate pass can
        // mean was stale. A second transport failure, or any other failure class, reports as it is - no loop,
        // and kSteam is never consulted. Writing it as one retry rather than a loop is what keeps a second
        // transport failure from starting a third attempt.
        val done = logonOnPlatformRoute(accountName, password, retryLiveCandidates = false)
        if (done || lastAttemptFailure.get() != SteamContentSdkSessionFailure.NETWORK) return
        record("logon_retry_live_candidates")
        logonOnPlatformRoute(accountName, password, retryLiveCandidates = true)
    }

    /**
     * Runs one logon attempt on the platform route.
     *
     * Returns whether this attempt reached a terminal outcome, which is every failure class except the
     * transport one: [lastAttemptFailure] then says whether the caller is allowed its single live-candidate
     * retry. The caller's password is passed straight through to the one begin call, converted to bytes only
     * for that call and zeroed directly afterwards; it is never retained, copied elsewhere, or logged.
     */
    private suspend fun logonOnPlatformRoute(
        accountName: String,
        password: String,
        retryLiveCandidates: Boolean,
    ): Boolean {
        // The class belongs to one attempt, so a previous attempt cannot make this one look retryable.
        lastAttemptFailure.set(SteamContentSdkSessionFailure.NONE)
        publish(SteamSessionPhase.SIGNING_IN, messages.connecting, accountName = accountName, hasStoredSession = false)
        val session = openSession(refreshLive = retryLiveCandidates)
        if (session == null) {
            publish(SteamSessionPhase.FAILED, messages.unavailable, accountName = accountName)
            return true
        }
        if (!session.supportsAccountLogon()) {
            releaseSession()
            publish(SteamSessionPhase.FAILED, messages.authRejected, accountName = accountName)
            return true
        }
        publish(SteamSessionPhase.SIGNING_IN, messages.checkingLoginMethod, accountName = accountName)
        val secret = password.toByteArray(Charsets.UTF_8)
        try {
            session.beginAccountLogon(accountName, secret)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            record("logon_command_failure", error)
            releaseSession()
            publish(SteamSessionPhase.FAILED, messages.authRejected, accountName = accountName)
            return true
        } finally {
            secret.fill(0)
        }
        driveToOutcome(session = session, restore = false, accountName = accountName, hasStoredSession = false)
        return lastAttemptFailure.get() != SteamContentSdkSessionFailure.NETWORK
    }

    private suspend fun openSession(
        refreshLive: Boolean = false,
        prepared: SteamContentSdkPreparedRoute? = null,
        recoveryGeneration: Long? = null,
        recoveryEpoch: Long? = null,
    ): SteamContentSdkPlatformSession? {
        val opened =
            when {
                prepared != null -> platform.openPrepared(prepared)
                refreshLive -> platform.openWithLiveCandidates()
                else -> platform.open()
            }
        if (opened == null) {
            record("sdk_unavailable")
            return null
        }
        if (recoveryGeneration != null && recoveryEpoch != null) {
            try {
                ensureRecoveryLease(recoveryGeneration, recoveryEpoch)
            } catch (cancelled: CancellationException) {
                runCatching { opened.close() }
                throw cancelled
            }
        }
        val previous = activeSession.getAndSet(opened)
        if (previous != null && previous !== opened) {
            runCatching { previous.close() }
        }
        // The egress this session was opened on is the reference the reopen compares against, so a session
        // opened before any decision existed still moves to the first decided egress.
        synchronized(this) {
            val identity = networkReadiness.state.value.egressIdentity
            lastEgressIdentity.set(identity)
            activeSessionEgress.set(identity)
        }
        return opened
    }

    private fun releaseSession() {
        maintenanceJob.getAndSet(null)?.job?.cancel()
        pendingChallenge.set(null)
        val session = activeSession.getAndSet(null) ?: return
        runCatching { session.close() }
    }

    /** Retires only the session whose result is being handled, never a newer replacement. */
    private fun releaseSessionIfCurrent(expected: SteamContentSdkPlatformSession): Boolean {
        if (!activeSession.compareAndSet(expected, null)) return false
        while (true) {
            val loop = maintenanceJob.get() ?: break
            if (loop.session !== expected) break
            if (maintenanceJob.compareAndSet(loop, null)) {
                loop.job.cancel()
                break
            }
        }
        while (true) {
            val challenge = pendingChallenge.get() ?: break
            if (challenge.session !== expected) break
            if (pendingChallenge.compareAndSet(challenge, null)) break
        }
        runCatching { expected.close() }
        return true
    }

    /**
     * One maintenance step, its optional heartbeat drain, and the state they left behind.
     *
     * Shared by the scheduled loop and the foreground check. The foreground one exists because a link that
     * died while the app was in the background is otherwise not noticed for up to a whole interval - and the
     * first screen to load in that window is the one that shows the user an error for a connection the app
     * was about to repair on its own.
     *
     * The state read is the one call here that a tick cannot afford to lose: an exception from it used to end
     * the loop with nothing in the log, so it is returned as a failure rather than thrown.
     */
    private suspend fun runMaintenanceStep(
        session: SteamContentSdkPlatformSession,
    ): Result<SteamContentSdkSessionState> =
        runCatching {
            // Decide and write first. A passive drain is needed after a heartbeat so its reply/server pushes are
            // consumed, but repeatedly reading a quiet CM socket on every host tick has proven destructive on
            // the Android route: the second idle drain reported transport failure on an otherwise usable link.
            val step = runCatching { session.maintain(autoReconnect = false) }
            val pumpDetail =
                if (step.getOrNull()?.step == SteamContentSdkMaintenanceStep.HEARTBEAT_SENT) {
                    runCatching { session.pump() }
                        .fold({ it.outcome.name.lowercase() }, ::maintenanceFailure)
                } else {
                    "skipped"
                }
            record(
                "session_maintenance",
                detail =
                    "pump=$pumpDetail" +
                        " step=" +
                        step.fold({ it.step.name.lowercase() }, ::maintenanceFailure) +
                        " error=" +
                        (sdkFailureDetail(session)?.let { "sdk" } ?: "none"),
            )
            session.state()
        }

    /**
     * Starts the one loop that keeps an established connection alive and notices when it is gone.
     *
     * The SDK runs no background loop of its own by design - ADR 0025 keeps session scheduling host-owned -
     * so a host that never drives maintenance leaves an idle connection to be closed by the peer and never
     * learns that one was. Until this loop existed the app published `phase_signed_in` and then stopped
     * looking, which is why a dropped CM left every Workshop read answering `temporarily_unavailable` until
     * the process was restarted.
     *
     * One tick asks the SDK what is due. A sent heartbeat is followed by one bounded drain; an idle decision
     * does not repeatedly read a quiet socket. Business operations drain their own replies, while heartbeat
     * ticks consume their reply and any queued server pushes. A tick uses detection-only maintenance; when it
     * finds the session no longer authenticated it reports the exact generation and stops. The Backend retires
     * that generation and owns the one delayed replacement plan, while foreground and request signals join its
     * not-before deadline or coalesce through the same attempt gate.
     */
    private fun reportSessionUnavailable(session: SteamContentSdkPlatformSession) {
        val accountName = mutableSession.value.accountName ?: return
        val generation = session.authorizationGeneration() ?: return
        if (!authenticatedSessions.reportUnavailable(accountName, generation)) {
            retireAccountSession(accountName, generation)
        }
    }

    private fun startMaintenance(session: SteamContentSdkPlatformSession) {
        if (maintenanceIntervalMs <= 0L) return
        val job =
            scope.launch {
                // Every stop is named. A maintenance loop that simply stops looks exactly like a session with
                // nothing wrong with it - no tick, no line - and one device reading was precisely that: three
                // minutes of silence from a live process. So the reason is recorded rather than inferred.
                try {
                    while (isActive) {
                        delay(maintenanceIntervalMs)
                        if (activeSession.get() !== session) {
                            record("session_maintenance_stopped replaced")
                            return@launch
                        }
                        if (logoutInProgress.get()) {
                            record("session_maintenance_stopped logout")
                            return@launch
                        }
                        val tick = runMaintenanceStep(session)
                        val state = tick.getOrNull()
                        if (state == null) {
                            record("session_maintenance_failed", error = tick.exceptionOrNull())
                            reportSessionUnavailable(session)
                            return@launch
                        }
                        if (state == SteamContentSdkSessionState.AUTHENTICATED) continue
                        record("session_lost request_recovery_required")
                        reportSessionUnavailable(session)
                        // Maintenance only detects loss. The report below reaches the sole Backend owner, which
                        // retires this exact generation and schedules the bounded replacement attempt.
                        return@launch
                    }
                } catch (error: CancellationException) {
                    // A cancellation is the one stop with no other trace of its own, so it is named too.
                    record("session_maintenance_stopped cancelled")
                    throw error
                }
            }
        maintenanceJob.getAndSet(MaintenanceLoop(session, job))?.job?.cancel()
    }

    private suspend fun driveToOutcome(
        session: SteamContentSdkPlatformSession,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ) {
        val finished =
            withTimeoutOrNull(SESSION_ATTEMPT_TIMEOUT_MS) {
                pollUntilOutcome(session, restore, accountName, hasStoredSession)
            } == true
        if (!finished) {
            record("attempt_timeout")
            releaseSession()
            publish(
                if (restore) SteamSessionPhase.RESTORABLE else SteamSessionPhase.FAILED,
                if (restore) messages.restoreFailed else messages.loginTimeout,
                accountName = accountName,
                hasStoredSession = hasStoredSession,
            )
        }
    }

    /** Polls until a terminal phase is published. Returns true once this attempt needs no further polling. */
    private suspend fun pollUntilOutcome(
        session: SteamContentSdkPlatformSession,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean {
        while (true) {
            val event = withSession { session.pollEvent(SESSION_POLL_TIMEOUT_MS) }
            if (event == null) {
                if (handleIdleState(session, restore, accountName, hasStoredSession)) return true
                continue
            }
            if (dispatchEvent(event, session, restore, accountName, hasStoredSession)) return true
        }
    }

    /** Reads the provider's own state when the queue is empty; true means this attempt is over. */
    private suspend fun handleIdleState(
        session: SteamContentSdkPlatformSession,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean =
        when (withSession { session.state() }) {
            SteamContentSdkSessionState.AUTHENTICATED -> {
                publishSignedIn(accountName, hasStoredSession, stage = restoreStage(restore))
                true
            }

            SteamContentSdkSessionState.CLOSED,
            SteamContentSdkSessionState.UNKNOWN,
            -> {
                // A session that already reported a failure class does not get a second, later class: the
                // transport class is what the caller reads to decide its one live retry, so a close after it
                // must not overwrite it with a generic internal failure.
                if (lastAttemptFailure.get() == SteamContentSdkSessionFailure.NONE) {
                    failAttempt(restore, accountName, hasStoredSession)
                }
                true
            }

            else -> false
        }

    private suspend fun dispatchEvent(
        event: SteamContentSdkSessionEvent,
        session: SteamContentSdkPlatformSession,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean =
        when (event) {
            is SteamContentSdkSessionEvent.StateChanged ->
                handleStateChanged(event.state, restore, accountName, hasStoredSession)

            is SteamContentSdkSessionEvent.AuthenticationChallenge ->
                handleChallenge(session, event.kind, accountName, hasStoredSession)

            SteamContentSdkSessionEvent.PendingFailed -> {
                if (!republishRejectedChallenge(session, accountName, hasStoredSession)) {
                    releaseSession()
                    publish(
                        SteamSessionPhase.FAILED,
                        messages.authRejected,
                        accountName = accountName,
                        hasStoredSession = hasStoredSession,
                    )
                }
                true
            }

            is SteamContentSdkSessionEvent.LoggedOut -> {
                handleLoggedOut(event.revoked, accountName)
                releaseSession()
                true
            }

            is SteamContentSdkSessionEvent.CommandFailed ->
                if (
                    event.failure == SteamContentSdkSessionFailure.AUTHENTICATION_FAILED &&
                    !isRemoteRateLimit(sdkFailureDetail(session)) &&
                    republishRejectedChallenge(session, accountName, hasStoredSession)
                ) {
                    true
                } else if (handleCommandFailure(event.failure, session, restore, accountName, hasStoredSession)) {
                    releaseSession()
                    true
                } else {
                    false
                }

            SteamContentSdkSessionEvent.Heartbeat,
            SteamContentSdkSessionEvent.Unknown,
            -> false
        }

    private fun republishRejectedChallenge(
        session: SteamContentSdkPlatformSession,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean {
        val pending = pendingChallenge.get()?.takeIf { it.session === session } ?: return false
        publish(
            SteamSessionPhase.WAITING_FOR_CODE,
            messages.authRejected,
            accountName = accountName,
            hasStoredSession = hasStoredSession,
            verificationMethod = pending.kind.verificationMethod(),
        )
        return true
    }

    private fun handleStateChanged(
        state: SteamContentSdkSessionState,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean =
        when (state) {
            SteamContentSdkSessionState.AUTHENTICATED -> {
                publishSignedIn(accountName, hasStoredSession, stage = restoreStage(restore))
                true
            }

            SteamContentSdkSessionState.CLOSED,
            SteamContentSdkSessionState.UNKNOWN,
            -> {
                // A session that already reported a failure class does not get a second, later class: the
                // transport class is what the caller reads to decide its one live retry, so a close after it
                // must not overwrite it with a generic internal failure.
                if (lastAttemptFailure.get() == SteamContentSdkSessionFailure.NONE) {
                    failAttempt(restore, accountName, hasStoredSession)
                }
                true
            }

            else -> false
        }

    private suspend fun handleChallenge(
        session: SteamContentSdkPlatformSession,
        kind: SteamContentSdkChallengeKind,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean =
        when (kind) {
            SteamContentSdkChallengeKind.EMAIL,
            SteamContentSdkChallengeKind.MOBILE,
            -> {
                val id = withSession { session.challengeId() }
                if (id == null) {
                    failChallenge(accountName, hasStoredSession)
                } else {
                    pendingChallenge.set(PendingChallenge(kind, id, session))
                    publish(
                        SteamSessionPhase.WAITING_FOR_CODE,
                        challengeMessage(kind),
                        accountName = accountName,
                        hasStoredSession = hasStoredSession,
                        verificationMethod = kind.verificationMethod(),
                    )
                }
                true
            }

            SteamContentSdkChallengeKind.DEVICE_CONFIRMATION,
            SteamContentSdkChallengeKind.QR,
            -> {
                publish(
                    SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                    messages.confirmMobile,
                    accountName = accountName,
                    hasStoredSession = hasStoredSession,
                    verificationMethod = SteamVerificationMethod.DEVICE_CONFIRMATION,
                )
                false
            }

            SteamContentSdkChallengeKind.NONE -> false

            SteamContentSdkChallengeKind.UNKNOWN -> {
                failChallenge(accountName, hasStoredSession)
                true
            }
        }

    private fun challengeMessage(kind: SteamContentSdkChallengeKind): String =
        when (kind) {
            SteamContentSdkChallengeKind.EMAIL -> messages.enterEmailCode
            SteamContentSdkChallengeKind.MOBILE -> messages.enterMobileCode
            else -> messages.challengeUnsupported
        }

    private fun SteamContentSdkChallengeKind.verificationMethod(): SteamVerificationMethod? =
        when (this) {
            SteamContentSdkChallengeKind.EMAIL -> SteamVerificationMethod.EMAIL_CODE
            SteamContentSdkChallengeKind.MOBILE -> SteamVerificationMethod.MOBILE_AUTHENTICATOR_CODE
            SteamContentSdkChallengeKind.DEVICE_CONFIRMATION -> SteamVerificationMethod.DEVICE_CONFIRMATION
            else -> null
        }

    private fun failAttempt(
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ) {
        releaseSession()
        publishAttemptFailure(restore, accountName, hasStoredSession, SteamContentSdkSessionFailure.INTERNAL)
    }

    private fun failChallenge(
        accountName: String?,
        hasStoredSession: Boolean,
    ) {
        releaseSession()
        publish(
            SteamSessionPhase.FAILED,
            messages.challengeUnsupported,
            accountName = accountName,
            hasStoredSession = hasStoredSession,
        )
    }

    private fun publishSignedIn(
        accountName: String?,
        hasStoredSession: Boolean,
        stage: String,
    ) {
        val authenticatedSession =
            synchronized(attemptLock) {
                ensureCurrentRecoveryPublication()
                // USER intent invalidation takes this same lock, so no old recovery can enter the registry after
                // the user has replaced it, even when native poll returned between cancellation checks.
                if (logoutInProgress.get()) {
                    record("auth_edge_after_logout")
                    releaseSession()
                    return
                }
                val current = activeSession.get()
                if (current == null || !current.publishAuthenticated()) {
                    record("authenticated_registry_publish_refused")
                    current?.let(::releaseSessionIfCurrent)
                    publish(
                        if (stage == "restore") SteamSessionPhase.RESTORABLE else SteamSessionPhase.FAILED,
                        if (stage == "restore") messages.restoreFailed else messages.unavailable,
                        accountName = accountName,
                        hasStoredSession = hasStoredSession,
                    )
                    return
                }
                current
            }
        pendingChallenge.set(null)
        recoveryJob.getAndSet(null)?.cancel()
        authenticatedAtNanos.set(nowNanos())
        // The authentication edge is the one moment the app can keep a session across a restart, so the token
        // the SDK retained is stored here on every path that reaches this state - logon, a Guard answer, and a
        // restore alike. The store write happens only when the SDK's retained token differs from the stored
        // one, so a session that is already healthy does not rewrite the encrypted store on every restore.
        val persisted = persistRetainedToken(accountName, stage)
        // Account collections need only the authenticated edge, not the optional persona/avatar projection.
        // Publish readiness first so Library can load immediately; enrich the header later without holding the
        // signed-in transition behind another Steam request.
        publish(
            SteamSessionPhase.SIGNED_IN,
            messages.signedIn,
            accountName = accountName,
            hasStoredSession = hasStoredSession || persisted,
            isConnected = true,
        )
        restoreWanted.set(false)
        scheduledRecoveryWanted.set(false)
        startMaintenance(authenticatedSession)
        scope.launch {
            delay(ACCOUNT_PROFILE_DEFER_MILLIS)
            if (activeSession.get() !== authenticatedSession) return@launch
            val profile =
                withTimeoutOrNull(ACCOUNT_PROFILE_TIMEOUT_MILLIS) {
                    withSession { authenticatedSession.accountProfile() }
                }
            record("account_profile persona=${profile?.personaName != null} avatar=${profile?.avatarUrl != null}")
            if (activeSession.get() !== authenticatedSession || profile == null) return@launch
            val current = mutableSession.value
            if (current.phase == SteamSessionPhase.SIGNED_IN) {
                mutableSession.value =
                    current.copy(
                        personaName = profile.personaName ?: current.personaName,
                        avatarUrl = profile.avatarUrl ?: current.avatarUrl,
                    )
            }
        }
    }

    /**
     * Writes the token the SDK retained into the app's encrypted store.
     *
     * The SDK owns the session and holds the only copy of the token; the copy taken here is zeroed immediately,
     * and nothing in this method logs the value - the one diagnostic records presence and a coarse size bucket
     * only. A result the SDK does not offer, or one that is empty, never overwrites what is stored: only a
     * non-empty token that differs from the stored one is written. Returns whether the store holds a session
     * for this account after the call.
     */
    private fun persistRetainedToken(
        accountName: String?,
        stage: String,
    ): Boolean {
        val session = activeSession.get()
        val token = session?.let { runCatching { it.refreshToken() }.getOrNull() }
        val bucket = tokenLengthClass(token?.size ?: 0)
        try {
            val name = accountName?.takeIf { it.isNotBlank() }
            val absent = name == null || token == null || token.isEmpty()
            val persisted = if (absent) false else writeRetainedToken(name, token)
            record("credential_persisted persisted=$persisted stage=$stage length_class=$bucket")
            return persisted
        } finally {
            token?.fill(0)
        }
    }

    /**
     * Writes [token] unless the store already holds the same one, and reports what a read-back returns.
     *
     * The compare is against the stored credential, not the one this attempt presented, so a rotated token is
     * written and an unchanged one leaves the store untouched. A store that cannot be read is not treated as
     * empty - the compare is skipped and the token is written, which is the recovery path for a store whose
     * read failed. The read-back is what would make a store that saves and loads a *different* token visible:
     * the bytes are compared here and only the outcome is recorded, never either value.
     */
    private fun writeRetainedToken(
        accountName: String,
        token: ByteArray,
    ): Boolean =
        try {
            val stored = readStoredToken()
            val unchanged =
                try {
                    stored != null && stored.contentEquals(token)
                } finally {
                    stored?.fill(0)
                }
            if (!unchanged) {
                // The compare runs once more inside the store, under its lock, so a clear that landed between
                // this read and this write cannot be reverted by the write.
                credentialStore.saveIfChanged(
                    PersistedSteamCredential(
                        accountName = accountName,
                        refreshToken = token.toString(Charsets.UTF_8),
                    ),
                )
            }
            val readBack = readStoredToken()
            val restoredExactly =
                try {
                    readBack != null && readBack.contentEquals(token)
                } finally {
                    readBack?.fill(0)
                }
            record("credential_roundtrip match=$restoredExactly unchanged=$unchanged")
            restoredExactly
        } catch (error: Throwable) {
            record("token_persist_failure", error)
            false
        }

    /**
     * Reads the stored token as bytes, or null when the store holds none this app can read.
     *
     * The caller owns the returned copy and zeroes it: it is a second plaintext copy of the same secret, and
     * neither it nor a formatted form of it is ever logged.
     */
    private fun readStoredToken(): ByteArray? =
        (credentialStore.read() as? SteamCredentialReadResult.Value)
            ?.credential
            ?.refreshToken
            ?.toByteArray(Charsets.UTF_8)

    private fun handleLoggedOut(
        revoked: Boolean,
        accountName: String?,
    ) {
        pendingChallenge.set(null)
        if (revoked) {
            recoveryJob.getAndSet(null)?.cancel()
            // The service revoked the credential, so this is the one path where clearing it is not a claim
            // but the point: what the UI is told about the store is what the store actually did.
            val cleared = clearStoredCredential()
            publish(
                SteamSessionPhase.EXPIRED,
                messages.credentialsExpired,
                accountName = accountName,
                hasStoredSession = !cleared,
            )
        } else {
            // The service ended the session without revoking the credential, so this is not a sign-out of the
            // store: the token is still on disk, and claiming otherwise contradicted the sign-out wording and
            // the next launch, which would restore it. The phase says the session is over; the flag says what
            // is stored.
            publish(
                SteamSessionPhase.SIGNED_OUT,
                messages.signedOut,
                accountName = accountName,
                hasStoredSession = true,
            )
        }
    }

    private fun handleCommandFailure(
        failure: SteamContentSdkSessionFailure,
        session: SteamContentSdkPlatformSession,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean {
        if (failure == SteamContentSdkSessionFailure.NONE) return false
        return publishAttemptFailure(
            restore,
            accountName,
            hasStoredSession,
            failure,
            sdkFailureDetail(session),
        )
    }

    /**
     * The SDK's own description of the failure behind [session], or null when it reports none.
     *
     * The value is the SDK's bounded, sanitised diagnostic, so it is passed on as it stands; a read that fails
     * on a closed session yields null rather than a second failure. It is what lets a device reading name the
     * step and the remote result code behind the class instead of only the class.
     */
    private fun sdkFailureDetail(session: SteamContentSdkPlatformSession): String? =
        runCatching { session.lastError() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Publishes the outcome for an attempt failure and returns whether this attempt is over.
     *
     * Every classified failure ends the attempt. A session that has reported one outcome, including the
     * transport class, will not report a second one for the same attempt, and polling it afterwards is what
     * kept the live-candidate retry from ever being reached. The transport class is still the retry-eligible
     * one, but that is read from [lastAttemptFailure] by the caller, which starts the retry as a new attempt;
     * this return value only says that the attempt that produced the failure is finished.
     */
    private fun publishAttemptFailure(
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
        failure: SteamContentSdkSessionFailure,
        sdkDetail: String? = null,
    ): Boolean {
        val rateLimited = failure == SteamContentSdkSessionFailure.RATE_LIMITED || isRemoteRateLimit(sdkDetail)
        lastAttemptFailure.set(if (rateLimited) SteamContentSdkSessionFailure.RATE_LIMITED else failure)
        if (rateLimited) {
            rememberRateLimit()
            recordClassifiedFailure(failure, restore, sdkDetail)
            val stored = hasStoredSession || credentialStore.read() !is SteamCredentialReadResult.Missing
            publish(
                if (stored) SteamSessionPhase.RESTORABLE else SteamSessionPhase.FAILED,
                messages.rateLimited,
                accountName = accountName,
                hasStoredSession = stored,
            )
            scheduleStoredRecovery(1L, if (rateLimitMarkerRequired) MAX_RECOVERY_BACKOFF_MS else ACCOUNT_RATE_LIMIT_COOLDOWN_MS)
            return true
        }
        recordClassifiedFailure(failure, restore, sdkDetail)
        // A restore the service refused is a dead stored credential, not a transport problem: the same bytes
        // would be refused again, so it is cleared and reported as expired instead of being replayed on every
        // start and every library refresh. Three things have to hold, and the third is what a device reading
        // taught: the failure class must be the credential one, it must be a restore (a refused logon is a
        // wrong password, not a reason to delete what is already stored), and the SDK's own detail must say
        // the service answered - a route failure wears the same class and must not cost the account.
        val refusedStoredCredential =
            restore &&
                hasStoredSession &&
                failure == SteamContentSdkSessionFailure.AUTHENTICATION_FAILED &&
                serviceRefusedCredential(sdkDetail)
        when {
            steamContentSdkFailurePhase(failure) == SteamSessionPhase.EXPIRED || refusedStoredCredential -> {
                recoveryJob.getAndSet(null)?.cancel()
                val cleared = clearStoredCredential(attemptCredentialGuard())
                record("credential_cleared cleared=$cleared refused=$refusedStoredCredential")
                publish(
                    SteamSessionPhase.EXPIRED,
                    messages.credentialsExpired,
                    accountName = accountName,
                    // A clear that failed leaves the credential on disk, and saying otherwise is exactly the
                    // claim that made a refused token look handled while it was still being replayed.
                    hasStoredSession = !cleared,
                )
            }

            steamContentSdkFailurePhase(failure) == SteamSessionPhase.SIGNED_OUT ->
                publish(
                    SteamSessionPhase.SIGNED_OUT,
                    messages.signedOut,
                    accountName = accountName,
                    hasStoredSession = hasStoredSession,
                )

            else ->
                publish(
                    if (restore && failure == SteamContentSdkSessionFailure.NETWORK) {
                        SteamSessionPhase.RESTORABLE
                    } else {
                        SteamSessionPhase.FAILED
                    },
                    if (failure == SteamContentSdkSessionFailure.NETWORK) {
                        if (restore) messages.restoreFailed else messages.networkFailed
                    } else {
                        if (restore) messages.restoreFailed else messages.authRejected
                    },
                    accountName = accountName,
                    hasStoredSession = hasStoredSession,
                )
        }
        return true
    }

    // ------------------------------------------------------------------
    // State and diagnostics
    // ------------------------------------------------------------------

    private fun publish(
        phase: SteamSessionPhase,
        message: String?,
        accountName: String? = null,
        requiresCode: Boolean = phase == SteamSessionPhase.WAITING_FOR_CODE,
        awaitingDeviceConfirmation: Boolean = phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
        verificationMethod: SteamVerificationMethod? = null,
        hasStoredSession: Boolean? = null,
        isConnected: Boolean = false,
        personaName: String? = null,
        avatarUrl: String? = null,
    ) {
        ensureCurrentRecoveryPublication()
        val previous = mutableSession.value
        mutableSession.value =
            SteamSessionState(
                phase = phase,
                accountName = accountName ?: previous.accountName,
                // The profile is read once, on the authenticated edge; every later phase carries it forward so
                // a state published for a challenge or a failure does not blank the account's own name.
                personaName = personaName ?: previous.personaName,
                avatarUrl = avatarUrl ?: previous.avatarUrl,
                message = message,
                requiresCode = requiresCode,
                awaitingDeviceConfirmation = awaitingDeviceConfirmation,
                verificationMethod = verificationMethod,
                hasStoredSession = hasStoredSession ?: previous.hasStoredSession,
                isConnected = isConnected,
            )
        record("phase_${phase.name.lowercase()}")
    }

    private fun clearStoredCredential(guard: Long? = null): Boolean =
        try {
            if (guard == null) {
                credentialStore.clear()
                true
            } else {
                // The guarded form refuses when a newer credential was written since the guard was taken, so
                // a decision about one stored credential can never delete a later one.
                credentialStore.clearIfUnchanged(guard)
            }
        } catch (error: Throwable) {
            // Never swallowed: the caller publishes a claim about the store, and a clear that failed makes
            // that claim false - the token is still there and the next launch would sign the user back in.
            record("credential_clear_failure", error)
            false
        }

    /** The generation the running attempt read its stored credential at, or null when it never read one. */
    private fun attemptCredentialGuard(): Long? =
        attemptCredentialGeneration.get().takeIf { it != NO_CREDENTIAL_GENERATION }

    /**
     * Records one stage line.
     *
     * [detail] carries a diagnostic the SDK already produced and sanitised; it gets its own attribute so the
     * stage value stays a short token and nothing that reads the stage has to parse the sentence around it.
     * The same line also goes to logcat, because a device reading is taken from there: everything that reaches
     * this method is already redacted - a stage token, an exception's class name, or a sentence the SDK
     * sanitised - so the copy adds no material the diagnostic store does not already hold. The log write is
     * wrapped for the same reason as before: a local JVM test runs against the Android stub library, where
     * `Log` is not implemented, and a diagnostic that cannot be written must never change the outcome.
     */
    private fun record(
        stage: String,
        error: Throwable? = null,
        detail: String? = null,
    ) {
        runCatching {
            val suffix =
                buildString {
                    error?.let { append(" error=").append(it.javaClass.simpleName) }
                    detail?.let { append(" sdk=").append(it) }
                }
            Log.i(TAG, if (suffix.isEmpty()) stage else stage + suffix)
        }
        scope.launch {
            runCatching {
                diagnostics.record(
                    DiagnosticEvent(
                        source = "steam-session",
                        level = DiagnosticLevel.INFO,
                        message = "Steam session stage: $stage",
                        attributes =
                            buildMap {
                                put("backend", "steam-content-sdk")
                                put("stage", stage)
                                error?.let { put("error", it.javaClass.simpleName) }
                                detail?.let { put("sdk_detail", it) }
                            },
                    ),
                )
            }
        }
    }

    private suspend fun <T> withSession(block: () -> T): T = sessionMutex.withLock(action = block)

    /**
     * Writes the one line that says how an attempt failure was classified.
     *
     * The class ordinal and the stage are the whole app-side content: no account name, token, endpoint, or error
     * text. The SDK's own diagnostic is passed as the detail when the platform can hand it over, because it
     * names the step and the remote result code the class was derived from.
     */
    private fun recordClassifiedFailure(
        failure: SteamContentSdkSessionFailure,
        restore: Boolean,
        sdkDetail: String?,
    ) {
        val stage = restoreStage(restore)
        record("attempt failure class=${failure.ordinal} stage=$stage", detail = sdkDetail)
    }

    /**
     * Records what the credential store held before an attempt used it.
     *
     * Presence and a coarse size bucket are the whole content: no token bytes, no account name, and no exact
     * length. It is what makes the next device reading decisive about whether a refused restore read a stored
     * token at all, instead of leaving the failure to be inferred from the phase alone. The stored value is
     * already a string in memory, so the bucket is taken from its character count without decoding a copy.
     */
    private fun recordCredentialLoad(result: SteamCredentialReadResult) {
        val stored = (result as? SteamCredentialReadResult.Value)?.credential?.refreshToken
        val present = stored != null
        val nonEmpty = stored?.isNotBlank() == true
        record(
            "credential_load present=$present non_empty=$nonEmpty " +
                "length_class=${tokenLengthClass(stored?.length ?: 0)} stage=restore",
        )
    }

    /**
     * Names why a maintenance call failed.
     *
     * A device reading that only says `pump=failed` cannot tell a link the peer closed from a session handle
     * that was already gone, and the two need different answers. The message is the SDK's own bounded text, so
     * it is passed on with control characters removed and nothing else added.
     */
    private fun maintenanceFailure(error: Throwable): String {
        val detail =
            error.message
                ?.filter { !it.isISOControl() }
                ?.trim()
                ?.take(MAINTENANCE_REASON_LENGTH)
                ?.takeIf { it.isNotEmpty() }
        return "failed:" + (detail ?: error.javaClass.simpleName)
    }

    /** The stage name the device reading uses, from the same flag the failure line and the store compare use. */
    private fun restoreStage(restore: Boolean): String = if (restore) "restore" else "logon"

    /** A coarse size bucket for a credential, so a line can say a token arrived without fingerprinting it. */
    private fun tokenLengthClass(length: Int): String =
        when {
            length <= 0 -> "empty"
            length < SHORT_TOKEN_LENGTH -> "short"
            length < LONG_TOKEN_LENGTH -> "typical"
            else -> "long"
        }

    private companion object {
        const val TAG = "WallHubSteamSession"
        const val SESSION_POLL_TIMEOUT_MS = 250
        const val SESSION_ATTEMPT_TIMEOUT_MS = 5 * 60_000L
        const val MAX_RECOVERY_PLANS_PER_NETWORK = 7L
        const val ACCOUNT_PROFILE_DEFER_MILLIS = 2_000L
        const val ACCOUNT_PROFILE_TIMEOUT_MILLIS = 5_000L
        const val SHORT_TOKEN_LENGTH = 32
        const val LONG_TOKEN_LENGTH = 256

        /** "This attempt never read a stored credential", distinct from generation 0, which is a real one. */
        const val NO_CREDENTIAL_GENERATION = -1L
        const val NO_NETWORK_GENERATION = Long.MIN_VALUE

        /** How much of a maintenance failure's own text a device reading keeps. */
        const val MAINTENANCE_REASON_LENGTH = 160

        /** How much of the SDK's rebuild refusal is kept; the bridge already bounds and redacts its own text. */
    }
}
