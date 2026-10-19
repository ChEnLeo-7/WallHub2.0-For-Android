package com.wallhub.android.data.steam

import io.steamcontent.sdk.SessionState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A usable account-session snapshot published by the one account-session owner. */
internal data class SteamContentSdkAuthenticatedSession(
    val engine: SteamContentSdkEngine,
    val generation: Long,
)

/** The one non-blocking recovery signal content callers may send to the account-session owner. */
internal fun interface SteamContentSdkAccountSessionRecoveryOwner {
    fun recoverIfCurrent(
        accountName: String,
        generation: Long,
    )
}

/**
 * Publishes the one account CM session that is both authenticated and transport-usable.
 *
 * The registry owns no session, reconnect loop, lease count or parallel generation. Every mutation compares the
 * complete publication (account, engine and authorization generation), so an obsolete platform wrapper cannot
 * clear or resurrect a replacement hosted by the same process-wide engine.
 */
@javax.inject.Singleton
internal class SteamContentSdkAuthenticatedSessionRegistry
    @javax.inject.Inject
    constructor() {
        private data class Published(
            val accountKey: String,
            val snapshot: SteamContentSdkAuthenticatedSession,
        )

        private val lock = Any()
        private val published = MutableStateFlow<Published?>(null)
        private var recoveryOwner: SteamContentSdkAccountSessionRecoveryOwner? = null
        private val retiredGenerations = mutableMapOf<String, Long>()

        fun installRecoveryOwner(owner: SteamContentSdkAccountSessionRecoveryOwner) {
            synchronized(lock) { recoveryOwner = owner }
        }

        /** Publishes only a newer authenticated authorization generation. */
        fun publish(
            accountName: String,
            engine: SteamContentSdkEngine,
            expectedGeneration: Long,
        ): Long? {
            val generation = engine.currentSessionGeneration()?.takeIf { it == expectedGeneration } ?: return null
            if (engine.state() != SessionState.AUTHENTICATED) return null
            synchronized(lock) {
                val accountKey = accountName.accountKey()
                if (generation <= (retiredGenerations[accountKey] ?: Long.MIN_VALUE)) return null
                val current = published.value
                if (
                    current != null &&
                    current.accountKey == accountName.accountKey() &&
                    current.snapshot.engine === engine &&
                    current.snapshot.generation > generation
                ) {
                    return null
                }
                published.value =
                    Published(
                        accountKey = accountKey,
                        snapshot = SteamContentSdkAuthenticatedSession(engine, generation),
                    )
            }
            return generation
        }

        /** Clears only the exact publication the closing owner previously published. */
        fun clear(
            engine: SteamContentSdkEngine,
            generation: Long?,
        ) {
            if (generation == null) return
            synchronized(lock) {
                val current = published.value ?: return
                if (current.snapshot.engine === engine && current.snapshot.generation == generation) {
                    published.value = null
                }
            }
        }

        fun currentAuthenticated(): SteamContentSdkEngine? = currentUsable()?.engine

        fun authenticated(accountName: String): SteamContentSdkEngine? = currentUsable(accountName)?.engine

        fun currentUsable(accountName: String? = null): SteamContentSdkAuthenticatedSession? {
            val value = published.value ?: return null
            if (accountName != null && value.accountKey != accountName.accountKey()) return null
            if (isStillUsable(value.snapshot)) return value.snapshot
            clear(value.snapshot.engine, value.snapshot.generation)
            return null
        }

        /** Waits for a usable account session, optionally strictly newer than [afterGeneration]. */
        suspend fun awaitUsable(
            accountName: String,
            afterGeneration: Long? = null,
            timeoutMs: Long,
        ): SteamContentSdkAuthenticatedSession? {
            currentUsable(accountName)?.takeIf { afterGeneration == null || it.generation > afterGeneration }?.let {
                return it
            }
            return withTimeoutOrNull(timeoutMs) {
                published.first { value ->
                    value != null &&
                        value.accountKey == accountName.accountKey() &&
                        (afterGeneration == null || value.snapshot.generation > afterGeneration) &&
                        isStillUsable(value.snapshot)
                }?.snapshot
            }
        }

        /** Retires exactly the failed generation and asks the sole account owner to recover it. */
        fun reportUnavailable(
            accountName: String,
            generation: Long,
        ): Boolean {
            val owner =
                synchronized(lock) {
                    val current = published.value ?: return false
                    if (
                        current.accountKey != accountName.accountKey() ||
                        current.snapshot.generation != generation
                    ) {
                        return false
                    }
                    val installed = recoveryOwner ?: return false
                    retiredGenerations[current.accountKey] = generation
                    published.value = null
                    installed
                }
            owner.recoverIfCurrent(accountName, generation)
            return true
        }

        private fun isStillUsable(snapshot: SteamContentSdkAuthenticatedSession): Boolean =
            runCatching {
                snapshot.engine.currentSessionGeneration() == snapshot.generation &&
                    snapshot.engine.state() == SessionState.AUTHENTICATED
            }.getOrDefault(false)

        private fun String.accountKey(): String = trim().lowercase(Locale.ROOT)
    }
