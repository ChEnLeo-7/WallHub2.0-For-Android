package com.wallhub.android.feature.detail

import androidx.compose.runtime.saveable.Saver
import androidx.media3.common.PlaybackException
import com.wallhub.android.core.model.WorkshopVideoStreamFailureException

enum class StreamPlaybackPath {
    FULL_SCREEN,
    INLINE,
}

/** One workshop page's deterministic allowance for replacing a failed SDK stream automatically. */
class StreamRecoveryBudget private constructor(
    automaticRetriesUsed: Int,
) {
    var automaticRetriesUsed: Int = automaticRetriesUsed.coerceIn(0, MAX_AUTOMATIC_RETRIES)
        private set

    constructor() : this(0)

    fun tryAcquire(
        path: StreamPlaybackPath,
        playbackError: PlaybackException,
    ): Boolean {
        // Naming the path at this seam ensures both UI paths intentionally use this one policy.
        @Suppress("UNUSED_VARIABLE")
        val recoveryPath = path
        if (playbackError.workshopVideoStreamFailureOrNull() == null) return false
        if (automaticRetriesUsed >= MAX_AUTOMATIC_RETRIES) return false
        automaticRetriesUsed += 1
        return true
    }

    /** Playback success is observable but deliberately does not refill a page's retry budget. */
    fun onFirstFrameRendered() = Unit

    /** Only an explicit user action (or constructing a budget for a new item) refills the budget. */
    fun resetForManualRetry() {
        automaticRetriesUsed = 0
    }

    companion object {
        const val MAX_AUTOMATIC_RETRIES = 2

        fun restore(automaticRetriesUsed: Int): StreamRecoveryBudget = StreamRecoveryBudget(automaticRetriesUsed)
    }
}

internal val StreamRecoveryBudgetSaver =
    Saver<StreamRecoveryBudget, Int>(
        save = { it.automaticRetriesUsed },
        restore = StreamRecoveryBudget::restore,
    )

internal fun PlaybackException.workshopVideoStreamFailureOrNull(): WorkshopVideoStreamFailureException? {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        if (current is WorkshopVideoStreamFailureException) return current
        current = current?.cause
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 8
