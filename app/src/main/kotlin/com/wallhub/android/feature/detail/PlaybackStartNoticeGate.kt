package com.wallhub.android.feature.detail

/** Keeps playback-start notices one-shot for the lifetime of the owning ViewModel. */
internal class PlaybackStartNoticeGate {
    private var consumed = false

    @Synchronized
    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}
