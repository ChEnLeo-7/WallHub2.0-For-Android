package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamVerificationMethod
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The cadence of the host-scheduled session maintenance loop.
 *
 * Well under the SDK's own 30 s heartbeat interval, so a due heartbeat is never late by more than one tick,
 * and frequent enough that a connection the peer closed is noticed within seconds rather than when the next
 * read happens to fail.
 */
internal const val STEAM_SESSION_MAINTENANCE_INTERVAL_MS = 5_000L

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
 * A logon or a restore that fails in the transport class - what the SDK reports when its whole candidate
 * pass failed to connect, as "protocol adapter failed" - is retried exactly once against a candidate list
 * read live from the directory. That is the only outcome that can mean the platform's cached endpoint set is
 * the stale input, and one retry is the whole of the policy on either path: there is no retry loop, a second
 * transport failure is reported as it is, and no other failure class is ever retried.
 */
internal class SteamContentSdkSessionBackend(
    private val platform: SteamContentSdkPlatform,
    private val credentialStore: EncryptedSteamCredentialStore,
    private val messages: SteamContentSdkSessionMessages,
    private val diagnostics: DiagnosticRepository,
    private val authenticatedSessions: SteamContentSdkAuthenticatedSessionRegistry =
        SteamContentSdkAuthenticatedSessionRegistry(),
    private val scope: CoroutineScope,
    /**
     * How often an established session is drained and maintained, or zero to leave the schedule to nobody.
     *
     * Scheduling belongs to the host, so the cadence is the host's own input rather than a constant buried in
     * the loop. A JVM test passes zero: a maintenance loop is never idle, so `advanceUntilIdle` would run it
     * forever, and the tests that are about logon and restore have nothing to say about the cadence.
     */
    private val maintenanceIntervalMs: Long = STEAM_SESSION_MAINTENANCE_INTERVAL_MS,
) : SteamSessionRepository {
    private val mutableSession = MutableStateFlow(SteamSessionState())
    override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

    private val attemptInProgress = AtomicBoolean(false)
    private val logoutInProgress = AtomicBoolean(false)
    /** Keeps the persisted account identity stable while the one bounded background CM replacement runs. */
    private val preserveSignedInDuringRestore = AtomicBoolean(false)
    private val attemptJob = AtomicReference<Job?>(null)
    private val activeSession = AtomicReference<SteamContentSdkPlatformSession?>(null)
    private val pendingChallenge = AtomicReference<PendingChallenge?>(null)

    /**
     * The newest request that arrived while an attempt was already running.
     *
     * Only one is kept, and a later request replaces an earlier one, so the user's last intent is the one
     * that runs. Dropping it instead is what made a tap on sign-in do nothing at all while a background
     * restore held the gate for up to its whole five-minute deadline.
     */
    private val pendingAttempt = AtomicReference<(suspend () -> Unit)?>(null)

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
    private val sessionMutex = Mutex()

    init {
        authenticatedSessions.installRecoveryOwner(
            object : SteamContentSdkAccountSessionRecoveryOwner {
                override fun recoverIfCurrent(
                    accountName: String,
                    generation: Long,
                ) = retireAccountSession(accountName, generation)

                override fun recoverStoredSession() = restorePersistedSession()
            },
        )
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
            record("content_session_retired generation=$generation")
            if (!releaseSessionIfCurrent(current)) return@launchAttempt
            // Match the WebView experience: remembered identity survives, while the expensive account CM is
            // reopened only when a foreground account/content operation asks the sole Backend owner for it.
            publish(
                SteamSessionPhase.SIGNED_IN,
                messages.restoreFailed,
                accountName = accountName,
                hasStoredSession = true,
            )
        }
    }

    override fun restorePersistedSession() {
        // The check is inside the block so it is re-read when the attempt actually runs: a queued restore
        // that was overtaken by a successful sign-in has nothing left to do.
        launchAttempt {
            val authenticated =
                activeSession
                    .get()
                    ?.let { session -> runCatching { session.state() }.getOrNull() }
                    .let { it == SteamContentSdkSessionState.AUTHENTICATED }
            if (!authenticated && mutableSession.value.hasStoredSession) {
                preserveSignedInDuringRestore.set(mutableSession.value.phase == SteamSessionPhase.SIGNED_IN)
                try {
                    restoreStoredSession()
                } finally {
                    preserveSignedInDuringRestore.set(false)
                }
            }
        }
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
        // A sign-in the user asked for takes precedence over whatever attempt is running: it supersedes it
        // rather than waiting behind it, because waiting means a tap that does nothing for up to five minutes.
        launchAttempt(supersedes = true) { logon(trimmed, password) }
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
        launchAttempt(supersedes = true) {
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
        scope.launch {
            // Off the caller's thread: both calls cross into the SDK, and the caller is a ViewModel.
            val session = activeSession.get()
            val choices = session?.guardChoices() ?: 0L
            // Only a confirmation the service allows for this account can be selected: the authenticator's code
            // when the account has one, otherwise the code Steam emailed. The SDK publishes the challenge again
            // as that code, and the poll this attempt is already running picks it up and shows the code field.
            val code =
                when {
                    choices and SteamContentSdkGuardChoice.DEVICE_CODE_BIT != 0L -> SteamContentSdkGuardChoice.DEVICE_CODE
                    choices and SteamContentSdkGuardChoice.EMAIL_CODE_BIT != 0L -> SteamContentSdkGuardChoice.EMAIL_CODE
                    else -> null
                }
            if (session == null || code == null) {
                // Nothing to switch to, so this account has no code to type for this logon.
                record("manual_guard_fallback_unavailable choices=$choices")
                publish(SteamSessionPhase.FAILED, messages.noManualLogin)
                return@launch
            }
            record("manual_guard_fallback choice=$code")
            runCatching { session.chooseGuard(code) }
                .onFailure { record("manual_guard_fallback_failed", error = it) }
        }
    }

    override fun logout() {
        if (!logoutInProgress.compareAndSet(false, true)) return
        val job = attemptJob.getAndSet(null)
        scope.launch {
            try {
                job?.cancelAndJoin()
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
        val session = activeSession.get()
        if (session == null || mutableSession.value.phase != SteamSessionPhase.SIGNED_IN) {
            restorePersistedSession()
            return
        }
        scope.launch {
            // The connection is checked before anything is allowed to read through it, so a link that died
            // while the app was in the background is repaired now rather than up to an interval later.
            runMaintenanceStep(session)
            val state = withSession { runCatching { session.state() }.getOrNull() } ?: return@launch
            when (steamContentSdkSessionPhase(state)) {
                SteamSessionPhase.SIGNED_IN -> Unit
                SteamSessionPhase.FAILED,
                SteamSessionPhase.SIGNED_OUT,
                -> {
                    // The connection is gone but a stored credential may still be perfectly good, so this is
                    // the restorable state, not a sign-out: publishing SIGNED_OUT here claimed the user had
                    // left while their credential sat in the store, and the sign-out also closed the engine
                    // every Workshop read in the app goes through. RESTORABLE is the phase the library's
                    // restore affordance and its automatic retry already key on.
                    if (releaseSessionIfCurrent(session)) {
                        publish(
                            SteamSessionPhase.RESTORABLE,
                            messages.restoreFailed,
                            hasStoredSession = true,
                        )
                        restorePersistedSession()
                    } else {
                        record("foreground_session_stale")
                    }
                }

                else -> Unit
            }
        }
    }

    override fun isAttemptInFlight(): Boolean =
        // The running attempt and the one queued behind it, which are the two states in which the SDK's one
        // session slot is already spoken for. A scheduled recovery retry is deliberately not counted: it holds
        // no slot while it waits, and a download that waited for it would wait for a timer rather than a session.
        attemptInProgress.get() || pendingAttempt.get() != null

    /** Releases the live SDK session. The interface has no shutdown hook, so this is the explicit close path. */
    internal fun close() {
        attemptJob.getAndSet(null)?.cancel()
        // A scheduled recovery retry is a live plan to reopen a session, so it does not outlive the shutdown
        // that closed one: without this it would fire up to eight minutes later against a backend that is gone
        // and open the very thing close() just released. It is cancelled before the gate is opened so a retry
        // already inside restorePersistedSession is torn down with the rest.
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
        supersedes: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (logoutInProgress.get()) {
            // A sign-in that arrived during a sign-out runs after the teardown, which is what the user asked
            // for; it is not run inside the teardown, where the session it needs is being closed.
            pendingAttempt.set(block)
            record("attempt_held_by_logout")
            return
        }
        if (attemptInProgress.compareAndSet(false, true)) {
            startAttempt(block)
            return
        }
        pendingAttempt.set(block)
        if (supersedes) {
            record("attempt_superseded")
            // Cancelled rather than cleared from [attemptJob]: the completion handler below is the single place
            // the gate opens, so a superseded attempt still hands it to the request that replaced it.
            attemptJob.get()?.cancel()
        } else {
            record("attempt_queued")
        }
    }

    private fun startAttempt(block: suspend () -> Unit) {
        attemptCredentialGeneration.set(NO_CREDENTIAL_GENERATION)
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        attemptJob.set(job)
        // The gate opens here and nowhere else: this runs whether the attempt finished, threw, was cancelled
        // mid-flight, or was cancelled before its body ever ran. A release written as the body's own `finally`
        // silently misses that last case, and the gate would then stay closed for good.
        job.invokeOnCompletion {
            if (attemptJob.compareAndSet(job, null)) {
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
        if (attemptInProgress.compareAndSet(false, true)) startAttempt(next) else pendingAttempt.set(next)
    }

    private suspend fun restoreStoredSession() {
        // The read result is kept in one value so the one load-time diagnostic below and the branch both read
        // the same store result: the store is still read exactly once per attempt.
        attemptCredentialGeneration.set(credentialStore.generation())
        val credential = credentialStore.read()
        recordCredentialLoad(credential)
        when (credential) {
            SteamCredentialReadResult.Missing -> {
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

            is SteamCredentialReadResult.Value -> restore(credential.credential)
        }
    }

    private suspend fun restore(stored: PersistedSteamCredential) {
        restoreOnce(stored, refreshLive = false)
        if (lastAttemptFailure.get() != SteamContentSdkSessionFailure.NETWORK) return
        // The transport class is the one attempt failure that can mean the CM endpoint set the platform
        // cached is the stale input: the SDK treats a host-supplied list as authoritative and never
        // questions it, so nothing else would ever refresh it. One retry asks for a list read from the
        // directory and re-presents the same stored credential; a second failure is reported as it is,
        // and kSteam is never consulted.
        record("restore_retry_live_candidates")
        restoreOnce(stored, refreshLive = true)
        // A second transport failure is final for this foreground recovery. The user may retry explicitly;
        // no background reconnect loop competes with public anonymous reads or another foreground attempt.
    }

    private suspend fun restoreOnce(
        stored: PersistedSteamCredential,
        refreshLive: Boolean,
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
        val session = openSession(refreshLive)
        if (session == null) {
            publish(
                SteamSessionPhase.FAILED,
                messages.unavailable,
                accountName = stored.accountName,
                hasStoredSession = true,
            )
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
        driveToOutcome(
            session = session,
            restore = true,
            accountName = stored.accountName,
            hasStoredSession = true,
        )
    }

    private suspend fun logon(
        accountName: String,
        password: String,
    ) {
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

    private suspend fun openSession(refreshLive: Boolean = false): SteamContentSdkPlatformSession? {
        val opened =
            try {
                if (refreshLive) platform.openWithLiveCandidates() else platform.open()
            } catch (error: Throwable) {
                record("platform_open_failure", error, error.message?.take(240))
                return null
            }
        if (opened == null) {
            record("sdk_unavailable")
            return null
        }
        val previous = activeSession.getAndSet(opened)
        if (previous != null && previous !== opened) {
            runCatching { previous.close() }
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
     * One drain, one maintenance step, and the state they left behind.
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
            val drained = runCatching { session.pump() }
            val step = runCatching { session.maintain(autoReconnect = true) }
            record(
                "session_maintenance",
                detail =
                    "pump=" +
                        drained.fold({ it.outcome.name.lowercase() }, ::maintenanceFailure) +
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
     * One tick is one drain and one maintenance step: the drain reads whatever the peer already sent, and a
     * socket nobody reads is one the peer closes, while the step writes the heartbeat that is due. A tick
     * that finds the session no longer authenticated reports the loss and stops. The next foreground request
     * owns the single reconnect attempt through SteamSessionRequestRecovery, so maintenance and request handling
     * can never race as two independent recovery authorities.
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
                        // Connection recovery has one owner: the next foreground SDK request retries once
                        // through SteamSessionRequestRecovery. The background maintenance loop only detects
                        // loss; it never starts a competing reconnect attempt.
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
                SteamSessionPhase.FAILED,
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
        // A signed-in session ends whatever outage the retries were riding out.
        if (logoutInProgress.get()) {
            // The session this edge belongs to is being torn down. A Guard answer runs in its own coroutine
            // rather than under the attempt gate, so it can reach here after logout has already published
            // SIGNED_OUT; publishing SIGNED_IN over that would claim a session that no longer exists.
            record("auth_edge_after_logout")
            releaseSession()
            return
        }
        pendingChallenge.set(null)
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
        )
        val authenticatedSession = activeSession.get()
        authenticatedSession?.let(::startMaintenance)
        if (authenticatedSession != null) {
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
            true
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
        lastAttemptFailure.set(failure)
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
                    SteamSessionPhase.FAILED,
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
        personaName: String? = null,
        avatarUrl: String? = null,
    ) {
        val previous = mutableSession.value
        val projectedPhase =
            if (
                preserveSignedInDuringRestore.get() &&
                phase in
                    setOf(
                        SteamSessionPhase.RESTORABLE,
                        SteamSessionPhase.SIGNING_IN,
                        SteamSessionPhase.FAILED,
                    )
            ) {
                SteamSessionPhase.SIGNED_IN
            } else {
                phase
            }
        mutableSession.value =
            SteamSessionState(
                phase = projectedPhase,
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
            )
        record("phase_${projectedPhase.name.lowercase()}")
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
     * Schedules one retry of a restore that failed for a reason that can clear.
     *
     * Bounded on purpose: a network that is really down should be reported to the user rather than hammered, so
     * the attempts are counted, spaced out, and dropped as soon as an attempt signs in.
     */
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
        const val ACCOUNT_PROFILE_DEFER_MILLIS = 2_000L
        const val ACCOUNT_PROFILE_TIMEOUT_MILLIS = 5_000L
        const val SHORT_TOKEN_LENGTH = 32
        const val LONG_TOKEN_LENGTH = 256

        /** "This attempt never read a stored credential", distinct from generation 0, which is a real one. */
        const val NO_CREDENTIAL_GENERATION = -1L

        /** How much of a maintenance failure's own text a device reading keeps. */
        const val MAINTENANCE_REASON_LENGTH = 160

        /** How much of the SDK's rebuild refusal is kept; the bridge already bounds and redacts its own text. */
    }
}
