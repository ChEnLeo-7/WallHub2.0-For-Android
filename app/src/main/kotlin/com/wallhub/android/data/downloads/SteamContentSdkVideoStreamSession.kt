package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.WorkshopVideoBufferState
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import java.math.BigInteger
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
    private val latestDemand = AtomicReference<TimelineDemand?>(null)
    private val demandSignal = Channel<Unit>(Channel.CONFLATED)
    private val demandScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutablePlaybackBufferState = MutableStateFlow(WorkshopVideoBufferState())

    override val playbackBufferState: StateFlow<WorkshopVideoBufferState> = mutablePlaybackBufferState

    init {
        demandScope.launch {
            for (ignored in demandSignal) {
                val timeline = latestDemand.getAndSet(null) ?: continue
                if (closed.get()) continue
                val rebuffered = pendingRebuffer.get()
                val demand = timeline.toSdkDemand(rebuffered)
                runCatching { stream.updatePlaybackDemand(demand) }
                    .onSuccess { snapshot ->
                        if (rebuffered) pendingRebuffer.compareAndSet(true, false)
                        if (!closed.get()) mutablePlaybackBufferState.value = timeline.project(snapshot, demand)
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

    override fun updatePlaybackDemand(
        playbackSpeed: Float,
        playbackPositionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
    ) {
        if (closed.get() || contentLength <= 0L || durationMs <= 0L || !playbackSpeed.isFinite()) return
        val timeline =
            TimelineDemand(
                playbackSpeed = playbackSpeed.coerceAtLeast(0f),
                playbackPositionMs = playbackPositionMs.coerceIn(0L, durationMs),
                bufferedPositionMs = bufferedPositionMs.coerceIn(0L, durationMs),
                durationMs = durationMs,
            )
        latestDemand.set(timeline)
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

    private inner class TimelineDemand(
        val playbackSpeed: Float,
        val playbackPositionMs: Long,
        val bufferedPositionMs: Long,
        val durationMs: Long,
    ) {
        fun toSdkDemand(rebuffered: Boolean): SteamContentSdkPlaybackDemand {
            val position = timelineToBytes(playbackPositionMs, durationMs, contentLength)
            val bufferedEnd = timelineToBytes(maxOf(playbackPositionMs, bufferedPositionMs), durationMs, contentLength)
            val bitrate = bytesToBitsPerSecond(contentLength, durationMs)
            return SteamContentSdkPlaybackDemand(
                position = position,
                bufferedBytes = (bufferedEnd - position).coerceAtLeast(0L),
                // Zero explicitly means unknown network throughput; media bitrate is consumption, not a
                // transport measurement. Rebuffer feedback still grows the adaptive target conservatively.
                throughputBytesPerSecond = 0L,
                bitrateBitsPerSecond = bitrate,
                rebuffered = rebuffered,
            )
        }

        fun project(
            snapshot: SteamContentSdkBufferSnapshot,
            demand: SteamContentSdkPlaybackDemand,
        ): WorkshopVideoBufferState {
            // Media3's contiguous buffered end is authoritative. The SDK snapshot describes its bounded target
            // and cache occupancy, not a promise that every cached byte is contiguous from the playhead.
            val availableBytes = demand.bufferedBytes.coerceAtMost(contentLength - demand.position)
            val bufferedEnd = saturatingAdd(demand.position, availableBytes).coerceAtMost(contentLength)
            val targetDuration = bytesToTimeline(snapshot.targetBytes.coerceAtMost(contentLength), contentLength, durationMs)
            val availableDuration = bytesToTimeline(availableBytes, contentLength, durationMs)
            return WorkshopVideoBufferState(
                bufferedPositionMs = bytesToTimeline(bufferedEnd, contentLength, durationMs),
                availableDurationMs = availableDuration,
                targetDurationMs = targetDuration,
                targetReached = !snapshot.cancelled && availableBytes >= snapshot.targetBytes,
                bandwidthLimited = demand.throughputBytesPerSecond != 0L &&
                    demand.throughputBytesPerSecond <= Long.MAX_VALUE / 8L &&
                    demand.throughputBytesPerSecond * 8L < demand.bitrateBitsPerSecond,
            )
        }
    }
}

private fun timelineToBytes(valueMs: Long, durationMs: Long, contentLength: Long): Long =
    scaleRatio(valueMs, contentLength, durationMs).coerceIn(0L, contentLength)

private fun bytesToTimeline(bytes: Long, contentLength: Long, durationMs: Long): Long =
    if (contentLength <= 0L) 0L else scaleRatio(bytes, durationMs, contentLength).coerceIn(0L, durationMs)

private fun bytesToBitsPerSecond(contentLength: Long, durationMs: Long): Long =
    scaleRatio(contentLength, 8_000L, durationMs).coerceAtLeast(1L)

/** Exact integer scaling without overflowing intermediate products. */
private fun scaleRatio(value: Long, multiplier: Long, divisor: Long): Long {
    if (value <= 0L || multiplier <= 0L || divisor <= 0L) return 0L
    return BigInteger.valueOf(value)
        .multiply(BigInteger.valueOf(multiplier))
        .divide(BigInteger.valueOf(divisor))
        .min(BigInteger.valueOf(Long.MAX_VALUE))
        .toLong()
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
