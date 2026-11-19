package com.wallhub.prototype.mpkg

import java.util.concurrent.atomic.LongAdder

enum class MpkgPhase(
    val stableId: String,
) {
    PARSE("parse"),
    TEXTURE_DECODE("texture_decode"),
    RGBA_LZ4("rgba_lz4"),
    ETC2("etc2"),
    CHECKPOINT("checkpoint"),
    PACKAGE("package"),
    VERIFY("verify"),
    EXPORT("export"),
}

data class MpkgPhaseTiming(
    val elapsedNanos: Long,
    val invocations: Int,
)

class MpkgTimingSnapshot internal constructor(
    private val phases: Map<MpkgPhase, MpkgPhaseTiming>,
) {
    fun phase(phase: MpkgPhase): MpkgPhaseTiming = phases.getValue(phase)

    fun toPrivacySafeLogFields(): String =
        MpkgPhase.entries.joinToString(separator = " ") { phase ->
            val timing = phase(phase)
            "${phase.stableId}Ms=${timing.elapsedNanos / NANOS_PER_MILLISECOND} " +
                "${phase.stableId}Count=${timing.invocations}"
        }
}

/** One conversion attempt's aggregate timings. It never accepts resource identity or content. */
class MpkgTimingSession(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val elapsedByPhase = MpkgPhase.entries.associateWith { LongAdder() }
    private val invocationsByPhase = MpkgPhase.entries.associateWith { LongAdder() }

    fun <T> measure(
        phase: MpkgPhase,
        block: () -> T,
    ): T {
        val startedAt = nanoTime()
        return try {
            block()
        } finally {
            elapsedByPhase.getValue(phase).add((nanoTime() - startedAt).coerceAtLeast(0L))
            invocationsByPhase.getValue(phase).increment()
        }
    }

    suspend fun <T> measureSuspend(
        phase: MpkgPhase,
        block: suspend () -> T,
    ): T {
        val startedAt = nanoTime()
        return try {
            block()
        } finally {
            elapsedByPhase.getValue(phase).add((nanoTime() - startedAt).coerceAtLeast(0L))
            invocationsByPhase.getValue(phase).increment()
        }
    }

    fun snapshot(): MpkgTimingSnapshot =
        MpkgTimingSnapshot(
            MpkgPhase.entries.associateWith { phase ->
                MpkgPhaseTiming(
                    elapsedNanos = elapsedByPhase.getValue(phase).sum(),
                    invocations = invocationsByPhase.getValue(phase).sum().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            },
        )

    fun <T> withCurrent(block: () -> T): T {
        val previous = current.get()
        current.set(this)
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<MpkgTimingSession?>()

        internal fun <T> measureCurrent(
            phase: MpkgPhase,
            block: () -> T,
        ): T = current.get()?.measure(phase, block) ?: block()
    }
}

internal fun <T> MpkgTimingSession?.measureIfEnabled(
    phase: MpkgPhase,
    block: () -> T,
): T = this?.measure(phase, block) ?: block()

internal fun <T> MpkgTimingSession?.withCurrentIfEnabled(block: () -> T): T =
    this?.withCurrent(block) ?: block()

internal suspend fun <T> MpkgTimingSession?.measureSuspendIfEnabled(
    phase: MpkgPhase,
    block: suspend () -> T,
): T = this?.measureSuspend(phase, block) ?: block()

private const val NANOS_PER_MILLISECOND = 1_000_000L
