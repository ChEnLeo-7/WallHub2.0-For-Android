package com.wallhub.android.data.downloads

import android.os.SystemClock
import com.wallhub.android.core.model.DownloadLiveActivity
import com.wallhub.android.core.model.DownloadTask
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class DownloadLiveReading(
    val activity: DownloadLiveActivity,
    val networkBytesPerSecond: Long?,
    val networkBytes: Long,
    val lastNetworkActivityAt: Long?,
)

/** Process-local transfer telemetry. Durable task state remains exclusively in Room. */
@Singleton
internal class DownloadLiveTelemetry internal constructor(
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
    private val waitMs: suspend (Long) -> Unit,
) {
        @Inject
        constructor() : this(
            nowMs = SystemClock::elapsedRealtime,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            waitMs = { delay(it) },
        )

        private val mutableReadings = MutableStateFlow<Map<String, DownloadLiveReading>>(emptyMap())
        val readings: StateFlow<Map<String, DownloadLiveReading>> = mutableReadings.asStateFlow()

        private val expirationLock = Any()
        private var expirationJob: Job? = null
        private var expirationGeneration = 0L

        fun accept(
            taskId: String,
            progress: SteamDownloadProgress,
        ) {
            val now = nowMs()
            mutableReadings.update { current ->
                val previous = current[taskId]
                val reading =
                    when (progress.phase) {
                        SteamDownloadPhase.PREPARING_DOWNLOAD ->
                            DownloadLiveReading(DownloadLiveActivity.PREPARING, null, progress.networkBytes, null)
                        SteamDownloadPhase.VERIFYING ->
                            DownloadLiveReading(DownloadLiveActivity.VERIFYING, null, progress.networkBytes, null)
                        SteamDownloadPhase.DOWNLOADING -> {
                            // A lower counter belongs to a new transfer/dependency epoch. The reset
                            // snapshot establishes its baseline; only a later increase is activity.
                            val counterReset =
                                previous != null && progress.networkBytes < previous.networkBytes
                            val advanced =
                                !counterReset &&
                                    progress.networkBytes > (previous?.networkBytes ?: 0L)
                            val lastActivityAt =
                                when {
                                    counterReset || !progress.firstContentReceived -> null
                                    advanced -> now
                                    else -> previous?.lastNetworkActivityAt
                                }
                            val fresh =
                                lastActivityAt != null &&
                                    now - lastActivityAt < NETWORK_ACTIVITY_STALE_AFTER_MS
                            DownloadLiveReading(
                                activity =
                                    if (progress.firstContentReceived) {
                                        DownloadLiveActivity.RECEIVING
                                    } else {
                                        DownloadLiveActivity.PREPARING
                                    },
                                networkBytesPerSecond =
                                    progress.networkBytesPerSecond
                                        .takeIf { fresh && it > 0L },
                                networkBytes = progress.networkBytes,
                                lastNetworkActivityAt = lastActivityAt,
                            )
                        }
                        SteamDownloadPhase.CONNECTING,
                        SteamDownloadPhase.AUTHENTICATING,
                        SteamDownloadPhase.RESOLVING,
                        -> return@update current - taskId
                    }
                current + (taskId to reading)
            }
            scheduleExpiration()
        }

        fun clear(taskId: String) {
            mutableReadings.update { it - taskId }
            scheduleExpiration()
        }

        private fun scheduleExpiration() {
            synchronized(expirationLock) {
                expirationGeneration += 1L
                val generation = expirationGeneration
                expirationJob?.cancel()
                val nextActivityAt =
                    mutableReadings.value.values
                        .asSequence()
                        .filter {
                            it.activity == DownloadLiveActivity.RECEIVING &&
                                it.networkBytesPerSecond != null
                        }
                        .mapNotNull { it.lastNetworkActivityAt }
                        .minOrNull()
                expirationJob =
                    nextActivityAt?.let { activityAt ->
                        scope.launch {
                            waitMs((activityAt + NETWORK_ACTIVITY_STALE_AFTER_MS - nowMs()).coerceAtLeast(0L))
                            synchronized(expirationLock) {
                                if (generation != expirationGeneration) return@launch
                            }
                            expireStaleReadings()
                            scheduleExpiration()
                        }
                    }
            }
        }

        private fun expireStaleReadings() {
            val now = nowMs()
            mutableReadings.update { current ->
                current.mapValues { (_, reading) ->
                    if (
                        reading.activity == DownloadLiveActivity.RECEIVING &&
                        reading.networkBytesPerSecond != null &&
                        reading.lastNetworkActivityAt != null &&
                        now - reading.lastNetworkActivityAt >= NETWORK_ACTIVITY_STALE_AFTER_MS
                    ) {
                        reading.copy(networkBytesPerSecond = null)
                    } else {
                        reading
                    }
                }
            }
        }

        fun current(taskId: String): DownloadLiveReading? = mutableReadings.value[taskId]

        fun enrich(task: DownloadTask): DownloadTask {
            val reading = mutableReadings.value[task.id]
            return task.copy(
                liveActivity = reading?.activity,
                liveNetworkBytesPerSecond = reading?.networkBytesPerSecond,
                bytesPerSecond = 0L,
            )
        }

        private companion object {
            const val NETWORK_ACTIVITY_STALE_AFTER_MS = 2_000L
        }
    }
