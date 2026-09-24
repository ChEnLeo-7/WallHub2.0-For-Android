package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamSessionPhase
import io.steamcontent.sdk.ChallengeField
import io.steamcontent.sdk.ChallengeKind
import io.steamcontent.sdk.SessionCapabilityBits
import io.steamcontent.sdk.SessionEventKind
import io.steamcontent.sdk.SessionFailure
import io.steamcontent.sdk.SessionState
import io.steamcontent.sdk.SteamSession
import java.io.Closeable

/**
 * The SDK session states this app understands.
 *
 * The SDK reports a bare ordinal; this app owns a named value so no ordinal crosses a boundary untyped.
 * [UNKNOWN] is the total mapping's value for an ordinal this build does not know.
 */
internal enum class SteamContentSdkSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,
    AUTHENTICATED,
    RECONNECTING,
    CLOSED,
    UNKNOWN,
}

/** The SDK challenge kinds this app understands, with [UNKNOWN] for a kind this build does not know. */
internal enum class SteamContentSdkChallengeKind {
    NONE,
    EMAIL,
    MOBILE,
    DEVICE_CONFIRMATION,
    QR,
    UNKNOWN,
}

/** The SDK session failure ordinals this app understands, with [UNKNOWN] for one it does not know. */
internal enum class SteamContentSdkSessionFailure {
    NONE,
    INVALID_INPUT,
    AUTHENTICATION_FAILED,
    NETWORK,
    UNSUPPORTED,
    SESSION_EXPIRED,
    CANCELLED,
    INTERNAL,
    UNKNOWN,
}

/**
 * One session event with its SDK ordinal already resolved.
 *
 * The transport decision a heartbeat carries is deliberately not kept: this app only drains heartbeats,
 * so carrying that ordinal further would be an untranslated SDK value with no reader.
 */
internal sealed interface SteamContentSdkSessionEvent {
    data class StateChanged(
        val state: SteamContentSdkSessionState,
    ) : SteamContentSdkSessionEvent

    data class AuthenticationChallenge(
        val kind: SteamContentSdkChallengeKind,
    ) : SteamContentSdkSessionEvent

    data object PendingFailed : SteamContentSdkSessionEvent

    data class LoggedOut(
        val revoked: Boolean,
    ) : SteamContentSdkSessionEvent

    data class CommandFailed(
        val failure: SteamContentSdkSessionFailure,
    ) : SteamContentSdkSessionEvent

    data object Heartbeat : SteamContentSdkSessionEvent

    data object Unknown : SteamContentSdkSessionEvent
}

/** Maps the SDK's state ordinal onto the app's named state. Total; an unknown ordinal is [SteamContentSdkSessionState.UNKNOWN]. */
internal fun steamContentSdkSessionState(ordinal: Int): SteamContentSdkSessionState =
    when (ordinal) {
        SessionState.DISCONNECTED -> SteamContentSdkSessionState.DISCONNECTED
        SessionState.CONNECTING -> SteamContentSdkSessionState.CONNECTING
        SessionState.CONNECTED -> SteamContentSdkSessionState.CONNECTED
        SessionState.AUTHENTICATING -> SteamContentSdkSessionState.AUTHENTICATING
        SessionState.AUTHENTICATED -> SteamContentSdkSessionState.AUTHENTICATED
        SessionState.RECONNECTING -> SteamContentSdkSessionState.RECONNECTING
        SessionState.CLOSED -> SteamContentSdkSessionState.CLOSED
        else -> SteamContentSdkSessionState.UNKNOWN
    }

/** Maps the SDK's challenge ordinal onto the app's named kind. Total. */
internal fun steamContentSdkChallengeKind(ordinal: Int): SteamContentSdkChallengeKind =
    when (ordinal) {
        ChallengeKind.NONE -> SteamContentSdkChallengeKind.NONE
        ChallengeKind.EMAIL -> SteamContentSdkChallengeKind.EMAIL
        ChallengeKind.MOBILE -> SteamContentSdkChallengeKind.MOBILE
        ChallengeKind.DEVICE_CONFIRMATION -> SteamContentSdkChallengeKind.DEVICE_CONFIRMATION
        ChallengeKind.QR -> SteamContentSdkChallengeKind.QR
        else -> SteamContentSdkChallengeKind.UNKNOWN
    }

/** Maps the SDK's failure ordinal onto the app's named failure. Total. */
internal fun steamContentSdkSessionFailure(ordinal: Long): SteamContentSdkSessionFailure =
    when (ordinal) {
        SessionFailure.NONE -> SteamContentSdkSessionFailure.NONE
        SessionFailure.INVALID_INPUT -> SteamContentSdkSessionFailure.INVALID_INPUT
        SessionFailure.AUTHENTICATION_FAILED -> SteamContentSdkSessionFailure.AUTHENTICATION_FAILED
        SessionFailure.NETWORK -> SteamContentSdkSessionFailure.NETWORK
        SessionFailure.UNSUPPORTED -> SteamContentSdkSessionFailure.UNSUPPORTED
        SessionFailure.SESSION_EXPIRED -> SteamContentSdkSessionFailure.SESSION_EXPIRED
        SessionFailure.CANCELLED -> SteamContentSdkSessionFailure.CANCELLED
        SessionFailure.INTERNAL -> SteamContentSdkSessionFailure.INTERNAL
        else -> SteamContentSdkSessionFailure.UNKNOWN
    }

/**
 * Resolves one polled event's kind and payload into an app-owned event. Total.
 *
 * Every payload ordinal is read here, so the session backend and everything above it deal only in the
 * named values above; an event kind or payload this build does not know becomes [SteamContentSdkSessionEvent.Unknown].
 */
internal fun steamContentSdkSessionEvent(
    kind: Int,
    value: Long,
): SteamContentSdkSessionEvent =
    when (kind) {
        SessionEventKind.STATE_CHANGED -> SteamContentSdkSessionEvent.StateChanged(steamContentSdkSessionState(value.toInt()))
        SessionEventKind.HEARTBEAT -> SteamContentSdkSessionEvent.Heartbeat
        SessionEventKind.AUTHENTICATION_CHALLENGE ->
            SteamContentSdkSessionEvent.AuthenticationChallenge(steamContentSdkChallengeKind(value.toInt()))
        SessionEventKind.PENDING_FAILED -> SteamContentSdkSessionEvent.PendingFailed
        SessionEventKind.LOGGED_OUT -> SteamContentSdkSessionEvent.LoggedOut(revoked = value != 0L)
        SessionEventKind.COMMAND_FAILED -> SteamContentSdkSessionEvent.CommandFailed(steamContentSdkSessionFailure(value))
        else -> SteamContentSdkSessionEvent.Unknown
    }

/** Maps an SDK lifecycle state onto the phase the app's login UI already renders. */
internal fun steamContentSdkSessionPhase(state: SteamContentSdkSessionState): SteamSessionPhase =
    when (state) {
        SteamContentSdkSessionState.DISCONNECTED -> SteamSessionPhase.SIGNED_OUT
        SteamContentSdkSessionState.AUTHENTICATED -> SteamSessionPhase.SIGNED_IN
        SteamContentSdkSessionState.CONNECTING,
        SteamContentSdkSessionState.CONNECTED,
        SteamContentSdkSessionState.AUTHENTICATING,
        SteamContentSdkSessionState.RECONNECTING,
        -> SteamSessionPhase.SIGNING_IN
        SteamContentSdkSessionState.CLOSED,
        SteamContentSdkSessionState.UNKNOWN,
        -> SteamSessionPhase.FAILED
    }

/**
 * Maps an SDK challenge kind onto the phase the app's login UI renders for it.
 *
 * A code challenge and a device challenge are different phases on purpose: the UI shows a code field for
 * the first and a "confirm on your phone" instruction for the second. A kind this build does not know is
 * a failure rather than a silent guess at one of them.
 */
internal fun steamContentSdkChallengePhase(kind: SteamContentSdkChallengeKind): SteamSessionPhase =
    when (kind) {
        SteamContentSdkChallengeKind.EMAIL,
        SteamContentSdkChallengeKind.MOBILE,
        -> SteamSessionPhase.WAITING_FOR_CODE
        SteamContentSdkChallengeKind.DEVICE_CONFIRMATION,
        SteamContentSdkChallengeKind.QR,
        -> SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
        SteamContentSdkChallengeKind.NONE -> SteamSessionPhase.SIGNING_IN
        SteamContentSdkChallengeKind.UNKNOWN -> SteamSessionPhase.FAILED
    }

/** Maps an SDK session failure onto the phase the app publishes for it. */
internal fun steamContentSdkFailurePhase(failure: SteamContentSdkSessionFailure): SteamSessionPhase =
    when (failure) {
        SteamContentSdkSessionFailure.NONE -> SteamSessionPhase.SIGNING_IN
        SteamContentSdkSessionFailure.SESSION_EXPIRED -> SteamSessionPhase.EXPIRED
        SteamContentSdkSessionFailure.CANCELLED -> SteamSessionPhase.SIGNED_OUT
        SteamContentSdkSessionFailure.INVALID_INPUT,
        SteamContentSdkSessionFailure.AUTHENTICATION_FAILED,
        SteamContentSdkSessionFailure.NETWORK,
        SteamContentSdkSessionFailure.UNSUPPORTED,
        SteamContentSdkSessionFailure.INTERNAL,
        SteamContentSdkSessionFailure.UNKNOWN,
        -> SteamSessionPhase.FAILED
    }

/**
 * The SDK session handle as this app uses it: named state, resolved events, and the logon calls.
 *
 * It exists so the session backend's flow is exercised by a JVM unit test without a device or the native
 * library. Methods take and return only app-owned types or opaque bytes; no SDK ordinal or SDK type crosses it.
 */
internal interface SteamContentSdkPlatformSession : Closeable {
    fun supportsAccountLogon(): Boolean

    fun supportsRestore(): Boolean

    fun state(): SteamContentSdkSessionState

    fun pollEvent(timeoutMs: Int): SteamContentSdkSessionEvent?

    /** The pending challenge identifier, or null when the SDK reports none. An opaque value, never logged. */
    fun challengeId(): ByteArray?

    /**
     * The refresh token the SDK retained after a logon, or null when it retained none.
     *
     * A secret: the caller stores it once and zeroes its copy; nothing here logs it.
     */
    fun refreshToken(): ByteArray?

    fun beginAccountLogon(
        accountName: String,
        password: ByteArray,
    )

    fun restore(
        accountName: String,
        refreshToken: ByteArray,
    )

    fun respondToChallenge(
        challengeId: ByteArray,
        answer: ByteArray,
    )

    fun logout()
}

/**
 * Opens the one SDK session the session backend owns.
 *
 * Returns null when the SDK cannot be used on this device; throws when the platform route is unavailable.
 * Either way the caller fails rather than falling back to another backend.
 */
internal fun interface SteamContentSdkPlatform {
    suspend fun open(): SteamContentSdkPlatformSession?

    /**
     * Opens the session on a CM endpoint set read from the directory rather than the platform's cache.
     *
     * The default keeps every platform that knows no live route - a test double, or a host with no cache -
     * on [open]. Only the single retry after a transport-level restore failure calls this.
     */
    suspend fun openWithLiveCandidates(): SteamContentSdkPlatformSession? = open()
}

/**
 * The platform hook's session as [SteamContentSdkPlatformSession].
 *
 * This is the only class that translates SDK ordinals. It supplies the host route from the same factory the
 * download path uses, begins logon and restore through the hook, and hands polling and the challenge id back
 * as named values. It never retains a password or a Guard answer: each is passed to the hook for one call.
 */
internal class SteamContentSdkEnginePlatform(
    private val engine: SteamContentSdkEngine,
    private val routes: SteamContentSdkRouteFactory,
) : SteamContentSdkPlatform {
    override suspend fun open(): SteamContentSdkPlatformSession = openPlatformRoute(refreshLive = false)

    override suspend fun openWithLiveCandidates(): SteamContentSdkPlatformSession =
        openPlatformRoute(refreshLive = true)

    private suspend fun openPlatformRoute(refreshLive: Boolean): SteamContentSdkPlatformSession {
        val route =
            routes.platformRoute(refreshLive = refreshLive)
                ?: error("The platform's Steam route is unavailable; the Steam Content SDK session cannot start")
        val session =
            engine.open(route)
                ?: error("The Steam Content SDK session is unavailable on this device")
        return SteamContentSdkEnginePlatformSession(engine, session)
    }
}

private class SteamContentSdkEnginePlatformSession(
    private val engine: SteamContentSdkEngine,
    private val session: SteamSession,
) : SteamContentSdkPlatformSession {
    /**
     * The account this session was started for, so the authenticated session can be published as one the
     * content path may borrow. Set by the logon and restore that started it, and never logged.
     */
    private var accountName: String? = null

    override fun supportsAccountLogon(): Boolean =
        session.capabilities().supports(SessionCapabilityBits.ACCOUNT_LOGIN)

    override fun supportsRestore(): Boolean =
        session.capabilities().supports(SessionCapabilityBits.RESTORE)

    override fun state(): SteamContentSdkSessionState =
        steamContentSdkSessionState(session.state()).also(::publishWhenAuthenticated)

    override fun pollEvent(timeoutMs: Int): SteamContentSdkSessionEvent? =
        session.pollEvent(timeoutMs)?.let { event ->
            steamContentSdkSessionEvent(event.kind, event.value).also { resolved ->
                if (resolved is SteamContentSdkSessionEvent.StateChanged) {
                    publishWhenAuthenticated(resolved.state)
                }
            }
        }

    /**
     * Publishes this session as borrowable once it authenticates.
     *
     * Publishing on the authentication edge is what keeps the content path from opening a second live
     * session for the same account; the domain name stays in the record, and a lender re-checks the
     * engine's state, so an unauthenticated or closed session is never lent.
     */
    private fun publishWhenAuthenticated(state: SteamContentSdkSessionState) {
        val name = accountName ?: return
        if (state == SteamContentSdkSessionState.AUTHENTICATED) {
            SteamContentSdkLiveSession.publish(name, engine)
        }
    }

    override fun challengeId(): ByteArray? =
        runCatching { session.challengeText(ChallengeField.ID) }.getOrNull()?.takeIf { it.isNotEmpty() }

    override fun refreshToken(): ByteArray? =
        runCatching { session.refreshToken() }.getOrNull()

    override fun beginAccountLogon(
        accountName: String,
        password: ByteArray,
    ) {
        check(engine.logon(accountName, password)) {
            "The Steam Content SDK session cannot begin an account logon"
        }
        this.accountName = accountName
    }

    override fun restore(
        accountName: String,
        refreshToken: ByteArray,
    ) {
        check(engine.restore(accountName, refreshToken)) {
            "The Steam Content SDK session cannot restore a stored account"
        }
        this.accountName = accountName
    }

    override fun respondToChallenge(
        challengeId: ByteArray,
        answer: ByteArray,
    ) {
        check(engine.submitGuardAnswer(challengeId, answer)) {
            "The Steam Content SDK session refused a Guard answer"
        }
    }

    override fun logout() = engine.logout()

    override fun close() {
        SteamContentSdkLiveSession.clear(engine)
        engine.close()
    }
}
