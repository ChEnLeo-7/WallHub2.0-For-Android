package com.wallhub.android.data.steam

import android.util.Log
import io.steamcontent.sdk.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single deep module through which every Workshop SDK read and write crosses.
 *
 * It owns native-handle serialization, priority scheduling and the one allowed reconnect attempt. Public and
 * account operations share this instance, so extracting their implementations cannot accidentally create
 * competing lanes or recovery authorities.
 */
internal class SteamWorkshopSdkGateway(
    private val engine: SteamContentSdkEngine,
) {
    private val mutex = Mutex()
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
            object : RecoverableSteamSession {
                override fun generation(): SteamSessionGeneration? =
                    engine.currentSessionGeneration()?.let(::SteamSessionGeneration)

                override fun isUsable(generation: SteamSessionGeneration): Boolean =
                    engine.currentSessionGeneration() == generation.value && engine.state() == SessionState.AUTHENTICATED

                override suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean =
                    engine.reconnectSessionIfCurrent(expected.value)
            },
        )
    private val writes = SteamWorkshopWriteCoordinator(reads, recovery)

    suspend fun <T> read(
        key: String,
        kind: WorkshopReadKind,
        priority: WorkshopReadPriority,
        operation: suspend () -> T,
    ): T {
        val generation = engine.currentSessionGeneration()?.toString() ?: "none"
        return reads.read("session:$generation:$key", kind, priority) {
            recovery.execute { mutex.withLock { operation() } }
        }
    }

    suspend fun <T> write(
        kind: WorkshopReadKind,
        operation: suspend () -> T,
    ): T = writes.write(kind) { mutex.withLock { operation() } }

    fun signedInAccountId(): Long? =
        runCatching { engine.currentSession()?.accountId() }.getOrNull()?.takeIf { it > 0L }

    suspend fun awaitSignedInSession() {
        if (signedInAccountId() != null) return
        repeat(SESSION_READY_ATTEMPTS) {
            delay(SESSION_READY_DELAY_MS)
            if (signedInAccountId() != null) return
        }
    }

    private companion object {
        const val TAG = "WallHubSdkWorkshop"
        const val SESSION_READY_ATTEMPTS = 20
        const val SESSION_READY_DELAY_MS = 250L
    }
}
