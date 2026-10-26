package com.wallhub.android.data.downloads

import android.util.Log
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One app playback session over the SDK's bounded rolling range stream. */
internal class SteamContentSdkVideoStreamSession(
    private val sessionLease: SteamContentSdkSessionBroker.Lease,
    private val stream: SteamContentSdkRangeStream,
    override val title: String,
    override val fileName: String,
    override val contentLength: Long,
    private val telemetryLog: StreamTelemetryLog = AndroidStreamTelemetryLog,
    private val clockMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) : WorkshopVideoStreamSession {
    private val closed = AtomicBoolean(false)
    private val pendingRebuffer = AtomicBoolean(false)
    private val pendingSeek = AtomicBoolean(false)
    private val latestDemand = AtomicReference<WorkshopVideoReadDemand?>(null)
    private val demandSignal = Channel<Unit>(Channel.CONFLATED)
    private val demandScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val telemetrySampler = StreamTelemetrySampler(clockMs)

    override val currentCdnProvider: com.wallhub.android.core.model.WorkshopVideoCdnProvider?
        get() = stream.cdnProvider()

    override val currentCdnHost: String?
        get() = stream.currentCdnHost()

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
                        sampleTelemetry(
                            requested = requested,
                            snapshot = snapshot,
                            force = seek || rebuffered,
                            event = if (seek) "seek" else if (rebuffered) "rebuffer" else "demand",
                        )
                    }
                    .onFailure { error ->
                        sampleTelemetry(requested, null, force = true, event = "failure")
                        if (telemetryLog.isWarnEnabled()) {
                            telemetryLog.warn(
                                "area=sdk_stream event=demand_failed kind=${requested.kind.name.lowercase()} " +
                                    "failure=${error.javaClass.simpleName.take(48)}",
                            )
                        }
                    }
            }
        }
    }

    override suspend fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int = withContext(Dispatchers.IO) { readAtBlocking(position, destination, destinationOffset, length) }

    override fun readAtBlocking(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int {
        require(position >= 0L) { "Invalid SDK video read position" }
        require(length >= 0) { "Invalid SDK video read length" }
        require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
            "Invalid SDK video read destination"
        }
        if (length == 0 || position >= contentLength) return 0
        return stream.readAt(
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
        sampleTelemetry(latestDemand.get(), null, force = true, event = "close")
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

    private fun sampleTelemetry(
        requested: WorkshopVideoReadDemand?,
        snapshot: SteamContentSdkBufferSnapshot?,
        force: Boolean,
        event: String,
    ) {
        // The JNI telemetry snapshot and message construction are intentionally behind the platform log gate.
        // Demand remains unsampled; only its detailed diagnostic mirror is rate limited.
        if (!telemetryLog.isDebugEnabled()) return
        val delta = telemetrySampler.sample(force) { stream.telemetry() } ?: return
        telemetryLog.debug(
            buildString {
                append("area=sdk_stream event=").append(event)
                requested?.let {
                    append(" kind=").append(it.kind.name.lowercase())
                    append(" position=").append(it.position)
                    append(" buffered=").append(it.bufferedBytes)
                }
                snapshot?.let {
                    append(" generation=").append(it.generation)
                    append(" target=").append(it.targetBytes)
                    append(" cached=").append(it.cachedBytes)
                }
                append(" fetch_started_delta=").append(delta.fetchStarted)
                append(" fetch_completed_delta=").append(delta.fetchCompleted)
                append(" fetch_failed_delta=").append(delta.fetchFailed)
                append(" deduplicated_delta=").append(delta.deduplicated)
                append(" foreground_waits_delta=").append(delta.foregroundWaits)
                append(" stale_delta=").append(delta.staleCompletions)
                append(" fetch_us_delta=").append(delta.fetchMicros)
                append(" queue_us_delta=").append(delta.queueMicros)
                append(" lock_us_delta=").append(delta.lockMicros)
                append(" decode_us_delta=").append(delta.decodeMicros)
                append(" cache_hits_delta=").append(delta.cacheHits)
                append(" cache_misses_delta=").append(delta.cacheMisses)
                append(" evictions_delta=").append(delta.cacheEvictions)
                append(" foreground_fetch_started_delta=").append(delta.foregroundFetchStarted)
                append(" foreground_fetch_completed_delta=").append(delta.foregroundFetchCompleted)
                append(" foreground_fetch_failed_delta=").append(delta.foregroundFetchFailed)
                append(" foreground_fetch_us_delta=").append(delta.foregroundFetchMicros)
                append(" foreground_decode_us_delta=").append(delta.foregroundDecodeMicros)
            },
        )
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

}

internal interface StreamTelemetryLog {
    fun isDebugEnabled(): Boolean

    fun isWarnEnabled(): Boolean

    fun debug(message: String)

    fun warn(message: String)
}

internal object AndroidStreamTelemetryLog : StreamTelemetryLog {
    override fun isDebugEnabled(): Boolean = Log.isLoggable(TELEMETRY_TAG, Log.DEBUG)

    override fun isWarnEnabled(): Boolean = Log.isLoggable(TELEMETRY_TAG, Log.WARN)

    override fun debug(message: String) {
        Log.d(TELEMETRY_TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TELEMETRY_TAG, message)
    }
}

internal class StreamTelemetrySampler(
    private val clockMs: () -> Long,
    private val intervalMs: Long = TELEMETRY_SAMPLE_INTERVAL_MS,
) {
    private var lastAtMs = Long.MIN_VALUE
    private var previous: SteamContentSdkStreamTelemetry? = null

    @Synchronized
    fun sample(
        force: Boolean = false,
        source: () -> SteamContentSdkStreamTelemetry,
    ): SteamContentSdkStreamTelemetry? {
        val now = clockMs()
        val elapsed = if (lastAtMs == Long.MIN_VALUE) Long.MAX_VALUE else now - lastAtMs
        if (!force && elapsed < intervalMs) return null
        val current = source()
        val delta = current.deltaFrom(previous)
        previous = current
        lastAtMs = now
        return delta
    }
}

private fun SteamContentSdkStreamTelemetry.deltaFrom(previous: SteamContentSdkStreamTelemetry?) =
    if (previous == null) {
        this
    } else {
        SteamContentSdkStreamTelemetry(
            fetchStarted = (fetchStarted - previous.fetchStarted).coerceAtLeast(0L),
            fetchCompleted = (fetchCompleted - previous.fetchCompleted).coerceAtLeast(0L),
            fetchFailed = (fetchFailed - previous.fetchFailed).coerceAtLeast(0L),
            deduplicated = (deduplicated - previous.deduplicated).coerceAtLeast(0L),
            foregroundWaits = (foregroundWaits - previous.foregroundWaits).coerceAtLeast(0L),
            staleCompletions = (staleCompletions - previous.staleCompletions).coerceAtLeast(0L),
            fetchMicros = (fetchMicros - previous.fetchMicros).coerceAtLeast(0L),
            queueMicros = (queueMicros - previous.queueMicros).coerceAtLeast(0L),
            lockMicros = (lockMicros - previous.lockMicros).coerceAtLeast(0L),
            decodeMicros = (decodeMicros - previous.decodeMicros).coerceAtLeast(0L),
            cacheHits = (cacheHits - previous.cacheHits).coerceAtLeast(0L),
            cacheMisses = (cacheMisses - previous.cacheMisses).coerceAtLeast(0L),
            cacheEvictions = (cacheEvictions - previous.cacheEvictions).coerceAtLeast(0L),
            foregroundFetchStarted =
                (foregroundFetchStarted - previous.foregroundFetchStarted).coerceAtLeast(0L),
            foregroundFetchCompleted =
                (foregroundFetchCompleted - previous.foregroundFetchCompleted).coerceAtLeast(0L),
            foregroundFetchFailed =
                (foregroundFetchFailed - previous.foregroundFetchFailed).coerceAtLeast(0L),
            foregroundFetchMicros =
                (foregroundFetchMicros - previous.foregroundFetchMicros).coerceAtLeast(0L),
            foregroundDecodeMicros =
                (foregroundDecodeMicros - previous.foregroundDecodeMicros).coerceAtLeast(0L),
        )
    }

private const val UNKNOWN_VALID_BITRATE_BITS_PER_SECOND = 1L
private const val TELEMETRY_TAG = "WallHubStreamTelemetry"
private const val TELEMETRY_SAMPLE_INTERVAL_MS = 1_000L
