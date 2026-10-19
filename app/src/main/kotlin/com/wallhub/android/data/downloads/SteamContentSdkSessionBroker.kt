package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.data.steam.SteamContentSdkAuthenticatedSessionRegistry
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Thin borrowing adapter over the account session's single owner. */
@Singleton
internal class SteamContentSdkSessionBroker private constructor(
    private val borrow: suspend (SteamContentCredential, Long?) -> Borrowed,
) {
    private data class Borrowed(
        val session: SteamContentSdkSession,
        val generation: Long,
        val reportLoss: () -> Unit,
    )

    @Inject
    constructor(sessions: SteamContentSdkAuthenticatedSessionRegistry) : this(
        borrow = { credential, afterGeneration ->
            val accountName = credential.accountName.trim()
            require(accountName.isNotEmpty()) { "Steam account scope must not be empty" }
            val snapshot =
                sessions.awaitUsable(
                    accountName = accountName,
                    afterGeneration = afterGeneration,
                    timeoutMs = ACCOUNT_SESSION_WAIT_MS,
                ) ?: error("The signed-in Steam account session is not available for content download")
            Borrowed(
                session = borrowedSteamContentSdkSession(snapshot.engine),
                generation = snapshot.generation,
                reportLoss = { sessions.reportUnavailable(accountName, snapshot.generation) },
            )
        },
    )

    /** Test adapter only; production has no independent opener or account restore authority. */
    internal constructor(opener: SteamContentSdkSessionOpener) : this(
        borrow = { credential, _ ->
            Borrowed(
                session = opener.open(credential),
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

    suspend fun acquire(
        credential: SteamContentCredential,
        afterGeneration: Long? = null,
    ): Lease {
        val borrowed = borrow(credential, afterGeneration)
        return Lease(borrowed.session, borrowed.generation, borrowed.reportLoss)
    }

    private companion object {
        const val ACCOUNT_SESSION_WAIT_MS = 15_000L
    }
}
