package com.wallhub.android.data.downloads

import com.wallhub.android.data.steam.SteamContentSdkAuthenticatedSessionRegistry
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Thin borrowing adapter over the account session's single owner. */
@Singleton
internal class SteamContentSdkSessionBroker private constructor(
    private val borrow: suspend (Long?) -> Borrowed,
) {
    private data class Borrowed(
        val session: SteamContentSdkSession,
        val generation: Long,
        val reportLoss: () -> Unit,
    )

    @Inject
    constructor(sessions: SteamContentSdkAuthenticatedSessionRegistry) : this(
        borrow = { afterGeneration ->
            val snapshot =
                sessions.awaitCurrentUsable(
                    afterGeneration = afterGeneration,
                    timeoutMs = ACCOUNT_SESSION_WAIT_MS,
                ) ?: error("The signed-in Steam account session is not available for content download")
            Borrowed(
                session = borrowedSteamContentSdkSession(snapshot.engine),
                generation = snapshot.generation,
                reportLoss = { sessions.reportUnavailable(snapshot.accountName, snapshot.generation) },
            )
        },
    )

    /** Test adapter only; production has no independent opener or account restore authority. */
    internal constructor(
        testBorrow: suspend (Long?) -> Pair<SteamContentSdkSession, Long>,
        reportLoss: (Long) -> Unit,
    ) : this(
        borrow = { afterGeneration ->
            val (session, generation) = testBorrow(afterGeneration)
            Borrowed(session, generation) { reportLoss(generation) }
        },
    )

    internal constructor(opener: suspend () -> SteamContentSdkSession) : this(
        borrow = {
            Borrowed(
                session = opener(),
                generation = 1L,
                reportLoss = {},
            )
        },
    )

    internal class Lease internal constructor(
        val session: SteamContentSdkSession,
        val generation: Long,
        private val reportLoss: () -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val lossReported = AtomicBoolean(false)

        fun reportSessionLoss() {
            if (!closed.get() && lossReported.compareAndSet(false, true)) reportLoss()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) session.close()
        }
    }

    suspend fun acquire(afterGeneration: Long? = null): Lease {
        val borrowed = borrow(afterGeneration)
        return Lease(borrowed.session, borrowed.generation, borrowed.reportLoss)
    }

    private companion object {
        // A cold mobile restore can spend one Directory timeout plus one live-candidate retry before success.
        const val ACCOUNT_SESSION_WAIT_MS = 30_000L
    }
}
