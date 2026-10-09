package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.WorkshopVideoBufferState
import com.wallhub.android.core.model.WorkshopVideoReadDemand
import com.wallhub.android.core.model.WorkshopVideoReadKind
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One app playback session over the SDK's bounded rolling range stream. */
internal class SteamContentSdkVideoStreamSession(
    private val sessionLease: SteamContentSdkSessionBroker.Lease,
    private val stream: SteamContentSdkRangeStream,
    override val title: String,
    override val fileName: String,
    override val contentLength: Long,
) : WorkshopVideoStreamSession {
    private val closed = AtomicBoolean(false)
    private val pendingRebuffer = AtomicBoolean(false)
    private val pendingSeek = AtomicBoolean(false)
    private val latestDemand = AtomicReference<WorkshopVideoReadDemand?>(null)
    private val demandSignal = Channel<Unit>(Channel.CONFLATED)
    private val demandScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutablePlaybackBufferState = MutableStateFlow(WorkshopVideoBufferState())

    override val playbackBufferState: StateFlow<WorkshopVideoBufferState> = mutablePlaybackBufferState

    init {
        demandScope.launch {
            for (ignored in demandSignal) {
                val requested = latestDemand.getAndSet(null) ?: continue
                if (closed.get()) continue
                val seek = pendingSeek.get() || requested.kind == WorkshopVideoReadKind.SEEK
                if (seek) {
                    // A discontinuity invalidates the previous rolling window. Sequential opens/reads never do.
                    runCatching { stream.cancelPlaybackDemand() }
                }
                val rebuffered = pendingRebuffer.get()
                val demand = requested.toSdkDemand(rebuffered)
                runCatching { stream.updatePlaybackDemand(demand) }
                    .onSuccess { snapshot ->
                        if (rebuffered) pendingRebuffer.compareAndSet(true, false)
                        if (seek) pendingSeek.compareAndSet(true, false)
                        if (!closed.get()) mutablePlaybackBufferState.value = requested.project(snapshot)
                        val telemetry = stream.telemetry()
                        Log.d(
                            TELEMETRY_TAG,
                            "area=sdk_stream event=demand kind=${requested.kind.name.lowercase()} " +
                                "generation=${snapshot.generation} position=${requested.position} " +
                                "buffered=${requested.bufferedBytes} target=${snapshot.targetBytes} " +
                                "cached=${snapshot.cachedBytes} fetch_started=${telemetry.fetchStarted} " +
                                "fetch_completed=${telemetry.fetchCompleted} fetch_failed=${telemetry.fetchFailed} " +
                                "deduplicated=${telemetry.deduplicated} foreground_waits=${telemetry.foregroundWaits} " +
                                "stale=${telemetry.staleCompletions} fetch_us=${telemetry.fetchMicros} " +
                                "queue_us=${telemetry.queueMicros} lock_us=${telemetry.lockMicros} " +
                                "decode_us=${telemetry.decodeMicros} cache_hits=${telemetry.cacheHits} " +
                                "cache_misses=${telemetry.cacheMisses} evictions=${telemetry.cacheEvictions}",
                        )
                    }
                    .onFailure { error ->
                        Log.w(
                            TELEMETRY_TAG,
                            "area=sdk_stream event=demand_failed kind=${requested.kind.name.lowercase()} " +
                                "failure=${error.javaClass.simpleName.take(48)}",
                        )
                    }
            }
        }
    }

    override suspend fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            require(position >= 0L) { "Invalid SDK video read position" }
            require(length >= 0) { "Invalid SDK video read length" }
            require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
                "Invalid SDK video read destination"
            }
            if (length == 0 || position >= contentLength) return@withContext 0
            stream.readAt(
                position = position,
                destination = destination,
                offset = destinationOffset,
                length = minOf(length.toLong(), contentLength - position).toInt(),
            )
        }

    override fun updateReadDemand(demand: WorkshopVideoReadDemand) {
        if (closed.get() || contentLength <= 0L) return
        val position = demand.position.coerceIn(0L, contentLength)
        if (demand.kind == WorkshopVideoReadKind.SEEK) pendingSeek.set(true)
        latestDemand.set(
            demand.copy(
                position = position,
                bufferedBytes = demand.bufferedBytes.coerceIn(0L, contentLength - position),
            ),
        )
        demandSignal.trySend(Unit)
    }

    override fun reportPlaybackRebuffer() {
        if (!closed.get()) pendingRebuffer.set(true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        latestDemand.set(null)
        demandSignal.close()
        demandScope.cancel()
        // The platform account owner may already have invalidated the borrowed native session. Cleanup is
        // idempotent at this app boundary: one stale handle must not crash the main thread or prevent lease release.
        runCatching { stream.cancel() }
        runCatching { stream.cancelPlaybackDemand() }
        runCatching { stream.close() }
        runCatching { sessionLease.close() }
    }

    private fun WorkshopVideoReadDemand.toSdkDemand(rebuffered: Boolean) =
        SteamContentSdkPlaybackDemand(
            position = position,
            bufferedBytes = bufferedBytes,
            // The current AAR interface has no explicit unknown bitrate but requires a positive value.
            // One is the conservative valid sentinel; deriving it from duration would be a lossy estimate.
            throughputBytesPerSecond = 0L,
            bitrateBitsPerSecond = UNKNOWN_VALID_BITRATE_BITS_PER_SECOND,
            rebuffered = rebuffered,
        )

    private fun WorkshopVideoReadDemand.project(snapshot: SteamContentSdkBufferSnapshot): WorkshopVideoBufferState {
        val available = bufferedBytes.coerceAtMost(contentLength - position)
        return WorkshopVideoBufferState(
            bufferedBytes = saturatingAdd(position, available).coerceAtMost(contentLength),
            availableBytes = available,
            targetBytes = snapshot.targetBytes.coerceAtMost(contentLength),
            targetReached = !snapshot.cancelled && available >= snapshot.targetBytes,
            bandwidthLimited = false,
        )
    }
}

private const val UNKNOWN_VALID_BITRATE_BITS_PER_SECOND = 1L
private const val TELEMETRY_TAG = "WallHubStreamTelemetry"

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
