package com.wallhub.android.data.steam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A stable identity for the SDK session against which a request is issued. */
internal data class SteamSessionGeneration(
    val value: Long,
)

/** The expected generation was replaced before its business operation entered native code. */
internal class SteamSessionGenerationChangedException(
    val expected: SteamSessionGeneration,
    val actual: SteamSessionGeneration?,
) : IllegalStateException("The Steam session generation changed before operation admission")

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

    /** Checks [expected] and invokes the synchronous [operation] as one admission step against the owner. */
    fun <T> executeIfCurrent(
        expected: SteamSessionGeneration,
        operation: () -> T,
    ): T

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

    suspend fun <T> execute(request: () -> T): T {
        val attemptedGeneration = session.generation()
        try {
            return if (attemptedGeneration == null) {
                request()
            } else {
                session.executeIfCurrent(attemptedGeneration, request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val staleAdmission = failure is SteamSessionGenerationChangedException
            if (attemptedGeneration == null || (!staleAdmission && classify(failure) == null)) throw failure

            val retryGeneration =
                reconnectMutex.withLock {
                    val current = session.generation()
                    when {
                        current == null -> null
                        current != attemptedGeneration -> current.takeIf(session::isUsable)
                        !session.reconnectIfCurrent(attemptedGeneration) -> null
                        else ->
                            session.generation()
                                ?.takeIf { it != attemptedGeneration }
                                ?.takeIf(session::isUsable)
                    }
                }
            if (retryGeneration == null) throw failure
            return session.executeIfCurrent(retryGeneration, request)
        }
    }
}
