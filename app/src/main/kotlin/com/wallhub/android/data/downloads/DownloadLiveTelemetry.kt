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

        private val telemetryLock = Any()
        private val latestReadings = mutableMapOf<String, DownloadLiveReading>()
        private val lastPublishedAt = mutableMapOf<String, Long>()
        private val expirationLock = Any()
        private var expirationJob: Job? = null
        private var expirationGeneration = 0L

        fun accept(
            taskId: String,
            progress: SteamDownloadProgress,
        ) {
            val now = nowMs()
            synchronized(telemetryLock) {
                val previous = latestReadings[taskId]
                val reading =
                    when (progress.phase) {
                        SteamDownloadPhase.PREPARING_DOWNLOAD ->
                            DownloadLiveReading(DownloadLiveActivity.PREPARING, null, progress.networkBytes, null)
                        SteamDownloadPhase.VERIFYING ->
                            DownloadLiveReading(DownloadLiveActivity.VERIFYING, null, progress.networkBytes, null)
                        SteamDownloadPhase.DOWNLOADING -> {
                            // A lower counter belongs to a new transfer/dependency epoch. The reset
                            // snapshot establishes its baseline; only a later increase is activity.
                            val counterReset = previous != null && progress.networkBytes < previous.networkBytes
                            val advanced = !counterReset && progress.networkBytes > (previous?.networkBytes ?: 0L)
                            val lastActivityAt =
                                when {
                                    counterReset || !progress.firstContentReceived -> null
                                    advanced -> now
                                    else -> previous?.lastNetworkActivityAt
                                }
                            val fresh =
                                lastActivityAt != null && now - lastActivityAt < NETWORK_ACTIVITY_STALE_AFTER_MS
                            DownloadLiveReading(
                                activity =
                                    if (progress.firstContentReceived) {
                                        DownloadLiveActivity.RECEIVING
                                    } else {
                                        DownloadLiveActivity.PREPARING
                                    },
                                networkBytesPerSecond = progress.networkBytesPerSecond.takeIf { fresh && it > 0L },
                                networkBytes = progress.networkBytes,
                                lastNetworkActivityAt = lastActivityAt,
                            )
                        }
                        SteamDownloadPhase.CONNECTING,
                        SteamDownloadPhase.AUTHENTICATING,
                        SteamDownloadPhase.RECOVERING_SESSION,
                        SteamDownloadPhase.RESOLVING,
                        -> {
                            latestReadings.remove(taskId)
                            lastPublishedAt.remove(taskId)
                            mutableReadings.update { it - taskId }
                            return
                        }
                    }
                latestReadings[taskId] = reading
                val published = mutableReadings.value[taskId]
                val immediateTransition =
                    published == null ||
                        published.activity != reading.activity ||
                        (published.networkBytesPerSecond != null && reading.networkBytesPerSecond == null)
                val speedChanged = materiallyDifferent(published?.networkBytesPerSecond, reading.networkBytesPerSecond)
                val rateLimitElapsed = lastPublishedAt[taskId]?.let { now - it >= SPEED_UPDATE_INTERVAL_MS } ?: true
                if (immediateTransition || (speedChanged && rateLimitElapsed)) {
                    mutableReadings.update { it + (taskId to reading) }
                    lastPublishedAt[taskId] = now
                }
            }
            scheduleExpiration()
        }

        fun clear(taskId: String) {
            synchronized(telemetryLock) {
                latestReadings.remove(taskId)
                lastPublishedAt.remove(taskId)
                mutableReadings.update { it - taskId }
            }
            scheduleExpiration()
        }

        private fun scheduleExpiration() {
            synchronized(expirationLock) {
                expirationGeneration += 1L
                val generation = expirationGeneration
                expirationJob?.cancel()
                val nextActivityAt =
                    synchronized(telemetryLock) {
                        latestReadings.values
                            .asSequence()
                            .filter {
                                it.activity == DownloadLiveActivity.RECEIVING &&
                                    it.networkBytesPerSecond != null
                            }
                            .mapNotNull { it.lastNetworkActivityAt }
                            .minOrNull()
                    }
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
            synchronized(telemetryLock) {
                latestReadings.replaceAll { _, reading -> reading.expireSpeedAt(now) }
                mutableReadings.update { current ->
                    current.mapValues { (taskId, published) ->
                        val latest = latestReadings[taskId] ?: published
                        if (latest.networkBytesPerSecond == null) {
                            latest
                        } else {
                            published.copy(
                                activity = latest.activity,
                                networkBytes = latest.networkBytes,
                                lastNetworkActivityAt = latest.lastNetworkActivityAt,
                            )
                        }
                    }
                }
            }
        }

        private fun DownloadLiveReading.expireSpeedAt(now: Long): DownloadLiveReading =
            if (
                activity == DownloadLiveActivity.RECEIVING &&
                networkBytesPerSecond != null &&
                lastNetworkActivityAt != null &&
                now - lastNetworkActivityAt >= NETWORK_ACTIVITY_STALE_AFTER_MS
            ) {
                copy(networkBytesPerSecond = null)
            } else {
                this
            }

        private fun materiallyDifferent(previous: Long?, current: Long?): Boolean {
            if (previous == null || current == null) return previous != current
            val delta = kotlin.math.abs(current - previous)
            return delta >= SPEED_CHANGE_MIN_BYTES_PER_SECOND &&
                delta * 100 >= maxOf(previous, current) * SPEED_CHANGE_MIN_PERCENT
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
            const val SPEED_UPDATE_INTERVAL_MS = 1_000L
            const val SPEED_CHANGE_MIN_BYTES_PER_SECOND = 32L * 1_024L
            const val SPEED_CHANGE_MIN_PERCENT = 10L
        }
    }
