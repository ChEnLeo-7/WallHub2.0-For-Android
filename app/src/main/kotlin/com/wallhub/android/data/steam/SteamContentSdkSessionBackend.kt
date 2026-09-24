package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
 * the stale input, and one retry is the whole of the policy on either path: there is no loop, a second
 * transport failure is reported as it is, and no other failure class is ever retried.
 */
internal class SteamContentSdkSessionBackend(
    private val platform: SteamContentSdkPlatform,
    private val credentialStore: EncryptedSteamCredentialStore,
    private val messages: SteamContentSdkSessionMessages,
    private val diagnostics: DiagnosticRepository,
    private val scope: CoroutineScope,
) : SteamSessionRepository {
    private val mutableSession = MutableStateFlow(SteamSessionState())
    override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

    private val attemptInProgress = AtomicBoolean(false)
    private val logoutInProgress = AtomicBoolean(false)
    private val attemptJob = AtomicReference<Job?>(null)
    private val activeSession = AtomicReference<SteamContentSdkPlatformSession?>(null)
    private val pendingChallenge = AtomicReference<PendingChallenge?>(null)

    /** The failure class of the attempt that just finished, so a transport failure earns one retry. */
    private val lastAttemptFailure = AtomicReference(SteamContentSdkSessionFailure.NONE)
    private val sessionMutex = Mutex()

    private class PendingChallenge(
        val kind: SteamContentSdkChallengeKind,
        val id: ByteArray,
    )

    // ------------------------------------------------------------------
    // Session transitions
    // ------------------------------------------------------------------

    override fun restorePersistedSession() {
        if (mutableSession.value.phase == SteamSessionPhase.SIGNED_IN) return
        launchAttempt { restoreStoredSession() }
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
        launchAttempt { logon(trimmed, password) }
    }

    override fun submitSteamGuardCode(code: String) {
        val answerText = code.trim()
        // Claiming the pending challenge atomically keeps a second submission from answering twice. This
        // runs outside the attempt guard because the attempt that published the code prompt has already
        // stopped polling by then.
        val challenge = pendingChallenge.getAndSet(null)
        if (answerText.isEmpty() || challenge == null || logoutInProgress.get()) {
            publish(SteamSessionPhase.FAILED, messages.noGuardRequest)
            return
        }
        scope.launch {
            val session = activeSession.get()
            if (session == null) {
                publish(SteamSessionPhase.FAILED, messages.noGuardRequest)
                return@launch
            }
            val answer = answerText.toByteArray(Charsets.UTF_8)
            val accepted =
                try {
                    withSession { session.respondToChallenge(challenge.id, answer) }
                    true
                } catch (error: Throwable) {
                    record("guard_answer_failure", error)
                    false
                } finally {
                    answer.fill(0)
                }
            if (!accepted) {
                releaseSession()
                publish(SteamSessionPhase.FAILED, messages.authRejected)
                return@launch
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
        when {
            pending != null -> publish(steamContentSdkChallengePhase(pending.kind), messages.enterGuardCode)
            mutableSession.value.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION ->
                record("manual_guard_fallback_unsupported")
            else -> publish(SteamSessionPhase.FAILED, messages.noManualLogin)
        }
    }

    override fun logout() {
        if (!logoutInProgress.compareAndSet(false, true)) return
        val job = attemptJob.getAndSet(null)
        scope.launch {
            try {
                job?.cancelAndJoin()
                val session = activeSession.getAndSet(null)
                if (session != null) {
                    withSession { runCatching { session.logout() } }
                    runCatching { session.close() }
                }
                pendingChallenge.set(null)
                clearStoredCredential()
                publish(SteamSessionPhase.SIGNED_OUT, messages.signedOut, hasStoredSession = false)
            } finally {
                attemptInProgress.set(false)
                logoutInProgress.set(false)
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
            val state = withSession { runCatching { session.state() }.getOrNull() } ?: return@launch
            when (steamContentSdkSessionPhase(state)) {
                SteamSessionPhase.SIGNED_IN -> Unit
                SteamSessionPhase.FAILED,
                SteamSessionPhase.SIGNED_OUT,
                -> {
                    releaseSession()
                    publish(SteamSessionPhase.SIGNED_OUT, messages.signedOut, hasStoredSession = true)
                }
                else -> Unit
            }
        }
    }

    /** Releases the live SDK session. The interface has no shutdown hook, so this is the explicit close path. */
    internal fun close() {
        attemptJob.getAndSet(null)?.cancel()
        val session = activeSession.getAndSet(null)
        if (session != null) {
            runCatching { session.close() }
        }
        pendingChallenge.set(null)
    }

    // ------------------------------------------------------------------
    // Flows
    // ------------------------------------------------------------------

    private fun launchAttempt(block: suspend () -> Unit) {
        if (logoutInProgress.get()) return
        if (!attemptInProgress.compareAndSet(false, true)) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    attemptInProgress.set(false)
                    attemptJob.set(null)
                }
            }
        attemptJob.set(job)
        job.start()
    }

    private suspend fun restoreStoredSession() {
        when (val credential = credentialStore.read()) {
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

            is SteamCredentialReadResult.Value -> {
                val stored = credential.credential
                publish(
                    SteamSessionPhase.SIGNING_IN,
                    messages.restoringSession,
                    accountName = stored.accountName,
                    hasStoredSession = true,
                )
                restore(stored)
            }
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
    }

    private suspend fun restoreOnce(
        stored: PersistedSteamCredential,
        refreshLive: Boolean,
    ) {
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
        publish(SteamSessionPhase.SIGNING_IN, messages.requestingLogin, accountName = accountName)
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
                record("platform_open_failure", error)
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
        pendingChallenge.set(null)
        val session = activeSession.getAndSet(null) ?: return
        runCatching { session.close() }
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
                publishSignedIn(accountName, hasStoredSession)
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
                releaseSession()
                publish(
                    SteamSessionPhase.FAILED,
                    messages.authRejected,
                    accountName = accountName,
                    hasStoredSession = hasStoredSession,
                )
                true
            }

            is SteamContentSdkSessionEvent.LoggedOut -> {
                handleLoggedOut(event.revoked, accountName)
                releaseSession()
                true
            }

            is SteamContentSdkSessionEvent.CommandFailed ->
                if (handleCommandFailure(event.failure, restore, accountName, hasStoredSession)) {
                    releaseSession()
                    true
                } else {
                    false
                }

            SteamContentSdkSessionEvent.Heartbeat,
            SteamContentSdkSessionEvent.Unknown,
            -> false
        }

    private fun handleStateChanged(
        state: SteamContentSdkSessionState,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean =
        when (state) {
            SteamContentSdkSessionState.AUTHENTICATED -> {
                publishSignedIn(accountName, hasStoredSession)
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
                    pendingChallenge.set(PendingChallenge(kind, id))
                    publish(
                        SteamSessionPhase.WAITING_FOR_CODE,
                        messages.enterGuardCode,
                        accountName = accountName,
                        hasStoredSession = hasStoredSession,
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
                )
                false
            }

            SteamContentSdkChallengeKind.NONE -> false

            SteamContentSdkChallengeKind.UNKNOWN -> {
                failChallenge(accountName, hasStoredSession)
                true
            }
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
    ) {
        pendingChallenge.set(null)
        // The authentication edge is the one moment the app can keep a session across a restart, so the token
        // the SDK retained is stored here, once, whichever path reached this state. A failure is reported as
        // "not stored" rather than hidden, because a session the user believes is saved and is not would come
        // back as an unexplained password prompt.
        val stored = persistRetainedToken(accountName)
        publish(
            SteamSessionPhase.SIGNED_IN,
            messages.signedIn,
            accountName = accountName,
            hasStoredSession = hasStoredSession || stored,
        )
    }

    /**
     * Writes the token the SDK retained into the app's encrypted store.
     *
     * The SDK owns the session and holds the only copy of the token; the copy taken here is zeroed immediately,
     * and nothing in this method logs the value. Returns whether the store now holds a session.
     */
    private fun persistRetainedToken(accountName: String?): Boolean {
        val name = accountName?.takeIf { it.isNotBlank() } ?: return false
        val session = activeSession.get() ?: return false
        val token = runCatching { session.refreshToken() }.getOrNull() ?: return false
        return try {
            if (token.isEmpty()) {
                false
            } else {
                credentialStore.save(
                    PersistedSteamCredential(
                        accountName = name,
                        refreshToken = token.toString(Charsets.UTF_8),
                    ),
                )
                true
            }
        } catch (error: Throwable) {
            record("token_persist_failure", error)
            false
        } finally {
            token.fill(0)
        }
    }

    private fun handleLoggedOut(
        revoked: Boolean,
        accountName: String?,
    ) {
        pendingChallenge.set(null)
        if (revoked) {
            clearStoredCredential()
            publish(
                SteamSessionPhase.EXPIRED,
                messages.credentialsExpired,
                accountName = accountName,
                hasStoredSession = false,
            )
        } else {
            publish(
                SteamSessionPhase.SIGNED_OUT,
                messages.signedOut,
                accountName = accountName,
                hasStoredSession = false,
            )
        }
    }

    private fun handleCommandFailure(
        failure: SteamContentSdkSessionFailure,
        restore: Boolean,
        accountName: String?,
        hasStoredSession: Boolean,
    ): Boolean {
        if (failure == SteamContentSdkSessionFailure.NONE) return false
        return publishAttemptFailure(restore, accountName, hasStoredSession, failure)
    }

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
    ): Boolean {
        lastAttemptFailure.set(failure)
        recordClassifiedFailure(failure, restore)
        when (steamContentSdkFailurePhase(failure)) {
            SteamSessionPhase.EXPIRED -> {
                clearStoredCredential()
                publish(
                    SteamSessionPhase.EXPIRED,
                    messages.credentialsExpired,
                    accountName = accountName,
                    hasStoredSession = false,
                )
            }

            SteamSessionPhase.SIGNED_OUT ->
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
        hasStoredSession: Boolean? = null,
    ) {
        val previous = mutableSession.value
        mutableSession.value =
            SteamSessionState(
                phase = phase,
                accountName = accountName ?: previous.accountName,
                personaName = previous.personaName,
                avatarUrl = previous.avatarUrl,
                message = message,
                requiresCode = requiresCode,
                awaitingDeviceConfirmation = awaitingDeviceConfirmation,
                hasStoredSession = hasStoredSession ?: previous.hasStoredSession,
            )
        record("phase_${phase.name.lowercase()}")
    }

    private fun clearStoredCredential() {
        runCatching { credentialStore.clear() }
    }

    private fun record(
        stage: String,
        error: Throwable? = null,
    ) {
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
                            },
                    ),
                )
            }
        }
    }

    private suspend fun <T> withSession(block: () -> T): T = sessionMutex.withLock(action = block)

    /**
     * Writes the one device-side line that says how an attempt failure was classified.
     *
     * The class ordinal and the stage are the whole content: no account name, token, endpoint, or error text.
     * A local JVM test runs against the Android stub library, where a Log call is not implemented, so a
     * diagnostic that cannot be written must never change the session outcome.
     */
    private fun recordClassifiedFailure(
        failure: SteamContentSdkSessionFailure,
        restore: Boolean,
    ) {
        runCatching {
            Log.i(TAG, "attempt failure class=${failure.ordinal} stage=${if (restore) "restore" else "logon"}")
        }
    }

    private companion object {
        const val TAG = "WallHubSteamSession"
        const val SESSION_POLL_TIMEOUT_MS = 250
        const val SESSION_ATTEMPT_TIMEOUT_MS = 5 * 60_000L
    }
}
