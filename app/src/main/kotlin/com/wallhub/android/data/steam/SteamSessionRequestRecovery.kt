package com.wallhub.android.data.steam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A stable identity for the SDK session against which a request is issued. */
internal data class SteamSessionGeneration(
    val value: Long,
)

/**
 * The narrow session seam required by [SteamSessionRequestRecovery].
 *
 * Implementations must compare and reconnect atomically: [reconnectIfCurrent] returns true only when the
 * expected generation is still current and reconnect produced a newer usable generation. A false result means
 * that generation can no longer be recovered. No request is replayed by this seam.
 */
internal interface RecoverableSteamSession {
    fun generation(): SteamSessionGeneration?

    /** True only when [generation] is still current and authenticated enough to serve the retry. */
    fun isUsable(generation: SteamSessionGeneration): Boolean = generation() == generation

    suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean
}

internal enum class SteamRequestRecoveryCause {
    REQUEST_REFUSED_BEFORE_SENT,
    SESSION_UNAVAILABLE,
    CONNECTION_FAILED_BEFORE_ANSWER,
}

/**
 * The two local SDK failures for which reconnecting can make the same operation meaningful.
 *
 * The current Kotlin binding exposes operation failures as exceptions whose redacted message contains the Rust
 * error. Keep matching deliberately narrow: remote refusals, bounds errors, and arbitrary transport failures
 * must not become implicit retries here.
 */
internal fun steamRequestRecoveryCause(error: Throwable): SteamRequestRecoveryCause? {
    val message = error.message?.lowercase() ?: return null
    return when {
        "the request was refused before it was sent" in message ->
            SteamRequestRecoveryCause.REQUEST_REFUSED_BEFORE_SENT
        "session_unavailable" in message || "session unavailable" in message ->
            SteamRequestRecoveryCause.SESSION_UNAVAILABLE
        "the connection failed before the service answered" in message ->
            SteamRequestRecoveryCause.CONNECTION_FAILED_BEFORE_ANSWER
        else -> null
    }
}

/**
 * Executes one SDK request with at most one recovery and one retry.
 *
 * Recovery is single-flight per wrapper. Concurrent failures from one generation share the replacement: the
 * first waiter reconnects, while later waiters observe that the generation changed and retry on it. The retry
 * is called directly rather than recursively, so a second recoverable failure is returned to the caller.
 * Cancellation is never classified, delayed, or converted to an unavailable result.
 */
internal class SteamSessionRequestRecovery(
    private val session: RecoverableSteamSession,
    private val classify: (Throwable) -> SteamRequestRecoveryCause? = ::steamRequestRecoveryCause,
) {
    private val reconnectMutex = Mutex()

    suspend fun <T> execute(request: suspend () -> T): T {
        val attemptedGeneration = session.generation()
        try {
            return request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (attemptedGeneration == null || classify(failure) == null) throw failure

            val usable =
                reconnectMutex.withLock {
                    val current = session.generation()
                    when {
                        current == null -> false
                        current != attemptedGeneration -> session.isUsable(current)
                        else -> session.reconnectIfCurrent(attemptedGeneration) &&
                            session.generation()?.let(session::isUsable) == true
                    }
                }
            if (!usable) throw failure
            return request()
        }
    }
}
