package com.wallhub.android.data.downloads

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal data class SteamAdaptiveBufferDecision(
    val targetDurationMs: Long,
    val targetBytes: Long,
    val requiredBytesPerSecond: Double,
    val safeThroughputBytesPerSecond: Double,
    val bandwidthLimited: Boolean,
)

internal data class SteamInitialPrefetchPlan(
    val first: SteamStreamByteRange?,
    val tail: SteamStreamByteRange?,
    val initial: SteamStreamByteRange?,
)

/** Mirrors Webview's range-observed, throughput-aware buffering policy for the native stream. */
internal class SteamStreamAdaptiveBufferPolicy(
    private val contentLength: Long,
    private val cacheBudgetBytes: Long,
) {
    private val transferRates = ArrayDeque<Double>()
    private val deliveries = ArrayDeque<Delivery>()
    private var deliveryBytes = 0L
    private var measurementStartedAtNanos = 0L
    private var lastTransferSampleAtNanos = 0L
    private var smoothedTransferRate = 0.0
    private var observedPeakFactor = INITIAL_PEAK_FACTOR
    private var lastAnchor: BufferAnchor? = null
    private var bandwidthPressureSamples = 0

    @Synchronized
    fun recordTransfer(
        deliveredBytes: Int,
        elapsedNanos: Long,
        completedAtNanos: Long = System.nanoTime(),
    ) {
        if (deliveredBytes <= 0 || elapsedNanos <= 0L) return
        if (measurementStartedAtNanos == 0L) {
            measurementStartedAtNanos = (completedAtNanos - elapsedNanos).coerceAtLeast(1L)
        }
        deliveries.addLast(Delivery(completedAtNanos, deliveredBytes))
        deliveryBytes += deliveredBytes.toLong()
        val cutoff = completedAtNanos - TRANSFER_WINDOW_NANOS
        while (deliveries.firstOrNull()?.completedAtNanos?.let { it < cutoff } == true) {
            deliveryBytes -= deliveries.removeFirst().bytes.toLong()
        }
        if (
            lastTransferSampleAtNanos > 0L &&
            completedAtNanos - lastTransferSampleAtNanos < TRANSFER_SAMPLE_INTERVAL_NANOS
        ) {
            return
        }
        val windowStart = max(measurementStartedAtNanos, cutoff)
        val windowNanos = (completedAtNanos - windowStart).coerceAtLeast(TRANSFER_SAMPLE_INTERVAL_NANOS)
        val rate = deliveryBytes.toDouble() * NANOS_PER_SECOND / windowNanos.toDouble()
        if (!rate.isFinite() || rate <= 0.0) return
        lastTransferSampleAtNanos = completedAtNanos
        if (transferRates.size >= MAX_TRANSFER_SAMPLES) transferRates.removeFirst()
        transferRates.addLast(rate)
        smoothedTransferRate =
            if (smoothedTransferRate <= 0.0) {
                rate
            } else {
                smoothedTransferRate * (1.0 - TRANSFER_SAMPLE_WEIGHT) + rate * TRANSFER_SAMPLE_WEIGHT
            }
    }

    @Synchronized
    fun evaluate(
        readPosition: Long,
        playbackPositionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
        playbackSpeed: Float,
    ): SteamAdaptiveBufferDecision {
        val speed = playbackSpeed.takeIf(Float::isFinite)?.coerceIn(0.25f, 4f)?.toDouble() ?: 1.0
        val averageBytesPerSecond =
            if (contentLength > 0L && durationMs > 0L) {
                contentLength.toDouble() * MILLIS_PER_SECOND / durationMs.toDouble()
            } else {
                0.0
            }
        val requiredBytesPerSecond = updateObservedPeak(readPosition, bufferedPositionMs, averageBytesPerSecond) * speed
        val consumptionBytesPerSecond = averageBytesPerSecond * speed
        val safeThroughput = safeThroughput()
        val throughputRatio =
            if (consumptionBytesPerSecond > 0.0 && safeThroughput > 0.0) {
                safeThroughput / consumptionBytesPerSecond
            } else {
                0.0
            }
        val requestedTargetSeconds = targetSecondsForThroughput(throughputRatio)
        val usableCacheBytes = (cacheBudgetBytes * CACHE_BUDGET_FACTOR).toLong().coerceAtLeast(1L)
        val cacheSeconds =
            if (consumptionBytesPerSecond > 0.0) usableCacheBytes / consumptionBytesPerSecond else requestedTargetSeconds
        val targetSeconds = min(requestedTargetSeconds, cacheSeconds).coerceAtLeast(MIN_TARGET_SECONDS)
        val targetBytes =
            if (consumptionBytesPerSecond > 0.0) {
                ceil(targetSeconds * consumptionBytesPerSecond).toLong().coerceIn(1L, usableCacheBytes)
            } else {
                min(INITIAL_BUFFER_BYTES, usableCacheBytes)
            }

        updateBandwidthPressure(consumptionBytesPerSecond, safeThroughput)
        val remainingDurationMs = (durationMs - playbackPositionMs).coerceAtLeast(0L)
        return SteamAdaptiveBufferDecision(
            targetDurationMs = min((targetSeconds * MILLIS_PER_SECOND).toLong(), remainingDurationMs),
            targetBytes = targetBytes,
            requiredBytesPerSecond = requiredBytesPerSecond,
            safeThroughputBytesPerSecond = safeThroughput,
            bandwidthLimited = bandwidthPressureSamples >= MIN_PRESSURE_SAMPLES,
        )
    }

    private fun updateObservedPeak(
        readPosition: Long,
        bufferedPositionMs: Long,
        averageBytesPerSecond: Double,
    ): Double {
        if (averageBytesPerSecond <= 0.0) return 0.0
        val previous = lastAnchor
        if (previous == null || readPosition < previous.byte || bufferedPositionMs < previous.mediaTimeMs) {
            lastAnchor = BufferAnchor(readPosition, bufferedPositionMs)
        } else if (readPosition > previous.byte && bufferedPositionMs > previous.mediaTimeMs) {
            val observedRate =
                (readPosition - previous.byte).toDouble() * MILLIS_PER_SECOND /
                    (bufferedPositionMs - previous.mediaTimeMs).toDouble()
            val factor = (observedRate / averageBytesPerSecond).coerceIn(MIN_PEAK_FACTOR, MAX_PEAK_FACTOR)
            observedPeakFactor = observedPeakFactor * 0.7 + factor * 0.3
            lastAnchor = BufferAnchor(readPosition, bufferedPositionMs)
        }
        return averageBytesPerSecond * observedPeakFactor.coerceIn(MIN_PEAK_FACTOR, MAX_PEAK_FACTOR)
    }

    private fun updateBandwidthPressure(
        consumptionBytesPerSecond: Double,
        safeThroughputBytesPerSecond: Double,
    ) {
        if (transferRates.size < MIN_PRESSURE_SAMPLES || consumptionBytesPerSecond <= 0.0) return
        when {
            safeThroughputBytesPerSecond < consumptionBytesPerSecond -> bandwidthPressureSamples += 1
            safeThroughputBytesPerSecond >= consumptionBytesPerSecond * BANDWIDTH_RECOVERY_FACTOR -> {
                bandwidthPressureSamples = 0
            }
        }
    }

    private fun safeThroughput(): Double {
        if (transferRates.isEmpty()) return smoothedTransferRate * THROUGHPUT_SAFETY_FACTOR
        val sorted = transferRates.sorted()
        val p25 = sorted[((sorted.size - 1) * 0.25).toInt()]
        val conservative = if (smoothedTransferRate > 0.0) min(smoothedTransferRate, p25) else p25
        return conservative * THROUGHPUT_SAFETY_FACTOR
    }

    private fun targetSecondsForThroughput(throughputRatio: Double): Double =
        when {
            throughputRatio >= 1.5 -> 12.0
            throughputRatio >= 1.0 -> 20.0
            else -> 30.0
        }

    private data class BufferAnchor(
        val byte: Long,
        val mediaTimeMs: Long,
    )

    private data class Delivery(
        val completedAtNanos: Long,
        val bytes: Int,
    )

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MILLIS_PER_SECOND = 1_000.0
        const val THROUGHPUT_SAFETY_FACTOR = 0.85
        const val BANDWIDTH_RECOVERY_FACTOR = 1.1
        const val INITIAL_PEAK_FACTOR = 1.35
        const val MIN_PEAK_FACTOR = 1.1
        const val MAX_PEAK_FACTOR = 2.0
        const val CACHE_BUDGET_FACTOR = 0.85
        const val MIN_TARGET_SECONDS = 4.0
        const val MAX_TRANSFER_SAMPLES = 20
        const val MIN_PRESSURE_SAMPLES = 3
        const val TRANSFER_SAMPLE_WEIGHT = 0.25
        const val TRANSFER_WINDOW_NANOS = 5_000_000_000L
        const val TRANSFER_SAMPLE_INTERVAL_NANOS = 500_000_000L
        const val INITIAL_BUFFER_BYTES = 16L * 1024L * 1024L
    }
}

internal fun steamInitialPrefetchPlan(contentLength: Long): SteamInitialPrefetchPlan {
    if (contentLength <= 0L) return SteamInitialPrefetchPlan(null, null, null)
    val first = steamStreamRange(contentLength, 0L, min(STREAM_FIRST_RANGE_BYTES, contentLength))
    val initialStart = first?.let { it.endInclusive + 1L } ?: 0L
    val initial =
        if (initialStart < min(contentLength, STREAM_INITIAL_BUFFER_BYTES)) {
            steamStreamRange(contentLength, initialStart, STREAM_INITIAL_BUFFER_BYTES - initialStart)
        } else {
            null
        }
    val tailLength = min(STREAM_TAIL_METADATA_BYTES, contentLength)
    val tail =
        if (tailLength > 0L && contentLength > tailLength) {
            steamStreamRange(contentLength, contentLength - tailLength, tailLength)
        } else {
            null
        }
    return SteamInitialPrefetchPlan(first, tail, initial)
}

internal const val STREAM_FIRST_RANGE_BYTES = 2L * 1024L * 1024L
internal const val STREAM_TAIL_METADATA_BYTES = 8L * 1024L * 1024L
internal const val STREAM_INITIAL_BUFFER_BYTES = 16L * 1024L * 1024L
