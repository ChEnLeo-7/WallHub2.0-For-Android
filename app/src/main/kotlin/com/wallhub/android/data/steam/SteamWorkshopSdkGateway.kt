package com.wallhub.android.data.steam

import android.util.Log
import io.steamcontent.sdk.SessionState
import kotlinx.coroutines.delay

/**
 * The single deep module through which every Workshop SDK read and write crosses.
 *
 * It owns native-handle serialization, priority scheduling and the one allowed reconnect attempt. Public and
 * account operations share this instance, so extracting their implementations cannot accidentally create
 * competing lanes or recovery authorities.
 */
internal class SteamWorkshopSdkGateway(
    private val engine: SteamContentSdkEngine,
    private val accountSessions: SteamContentSdkAuthenticatedSessionRegistry? = null,
) {
    private val reads =
        SteamWorkshopReadCoordinator(
            maxConcurrent = 1,
            onTiming = { timing ->
                Log.i(
                    TAG,
                    "read kind=${timing.kind} priority=${timing.priority} outcome=${timing.outcome} " +
                        "queueMs=${timing.queueMillis} runMs=${timing.runMillis}",
                )
            },
        )
    private val recovery =
        SteamSessionRequestRecovery(
            if (accountSessions == null) {
                AnonymousRecoverableSteamSession(engine)
            } else {
                AccountRecoverableSteamSession(engine, accountSessions)
            },
        )
    private val writes = SteamWorkshopWriteCoordinator(reads, recovery)

    suspend fun <T> read(
        key: String,
        kind: WorkshopReadKind,
        priority: WorkshopReadPriority,
        operation: () -> T,
    ): T {
        val generation = engine.currentSessionGeneration()?.toString() ?: "none"
        return reads.read("session:$generation:$key", kind, priority) {
            recovery.execute(operation)
        }
    }

    suspend fun <T> write(
        kind: WorkshopReadKind,
        operation: () -> T,
    ): T = writes.write(kind, operation)

    fun signedInAccountId(): Long? =
        runCatching { engine.currentSession()?.accountId() }.getOrNull()?.takeIf { it > 0L }

    suspend fun awaitUsableSession() {
        if (runCatching { engine.state() == SessionState.AUTHENTICATED }.getOrDefault(false)) return
        repeat(PUBLIC_SESSION_READY_ATTEMPTS) {
            delay(SESSION_READY_DELAY_MS)
            if (runCatching { engine.state() == SessionState.AUTHENTICATED }.getOrDefault(false)) return
        }
    }

    suspend fun awaitSignedInSession() {
        val sessions = accountSessions
        if (sessions == null) {
            if (signedInAccountId() != null) return
            repeat(SESSION_READY_ATTEMPTS) {
                delay(SESSION_READY_DELAY_MS)
                if (signedInAccountId() != null) return
            }
            error("No signed-in Steam account session is available")
        }
        val snapshot =
            sessions.awaitCurrentUsable(timeoutMs = ACCOUNT_SESSION_RECOVERY_TIMEOUT_MS)
                ?: error("No signed-in Steam account session is available")
        check(snapshot.engine === engine) { "The authenticated Steam engine changed during account recovery" }
        check(engine.currentSessionGeneration() == snapshot.generation) {
            "The authenticated Steam session generation changed during account recovery"
        }
        check(signedInAccountId() != null) { "No signed-in Steam account is available" }
    }

    private class AccountRecoverableSteamSession(
        private val engine: SteamContentSdkEngine,
        private val sessions: SteamContentSdkAuthenticatedSessionRegistry,
    ) : RecoverableSteamSession {
        override fun generation(): SteamSessionGeneration? =
            sessions.currentUsable()
                ?.takeIf { it.engine === engine }
                ?.generation
                ?.let(::SteamSessionGeneration)

        override fun isUsable(generation: SteamSessionGeneration): Boolean =
            sessions.currentUsable()?.let { it.engine === engine && it.generation == generation.value } == true

        override fun <T> executeIfCurrent(
            expected: SteamSessionGeneration,
            operation: () -> T,
        ): T = engine.executeIfSessionGeneration(expected.value, operation)

        override suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean {
            val failed = sessions.currentUsable()?.takeIf { it.engine === engine } ?: return false
            if (failed.generation != expected.value) return isUsable(SteamSessionGeneration(failed.generation))
            if (!sessions.reportUnavailable(failed.accountName, failed.generation)) return false
            return sessions.awaitCurrentUsable(
                afterGeneration = failed.generation,
                timeoutMs = ACCOUNT_SESSION_RECOVERY_TIMEOUT_MS,
            )?.engine === engine
        }
    }

    private class AnonymousRecoverableSteamSession(
        private val engine: SteamContentSdkEngine,
    ) : RecoverableSteamSession {
        override fun generation(): SteamSessionGeneration? =
            engine.currentSessionGeneration()?.let(::SteamSessionGeneration)

        override fun isUsable(generation: SteamSessionGeneration): Boolean =
            engine.currentSessionGeneration() == generation.value && engine.state() == SessionState.AUTHENTICATED

        override fun <T> executeIfCurrent(
            expected: SteamSessionGeneration,
            operation: () -> T,
        ): T = engine.executeIfSessionGeneration(expected.value, operation)

        override suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean =
            engine.reconnectSessionIfCurrent(expected.value)
    }

    private companion object {
        const val TAG = "WallHubSdkWorkshop"
        const val SESSION_READY_ATTEMPTS = 20
        const val PUBLIC_SESSION_READY_ATTEMPTS = 32
        const val SESSION_READY_DELAY_MS = 250L
        const val ACCOUNT_SESSION_RECOVERY_TIMEOUT_MS = 75_000L
    }
}
