package com.wallhub.android.app

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local navigation command seam for external intents delivered to an existing MainActivity. */
internal object AppNavigationCommandBus {
    private val mutableDownloadsRequest = MutableStateFlow(0L)
    val downloadsRequest = mutableDownloadsRequest.asStateFlow()

    fun accept(intent: Intent?) {
        if (intent?.data?.scheme == "wallhub" && intent.data?.host == "downloads") {
            mutableDownloadsRequest.value = mutableDownloadsRequest.value + 1L
        }
    }
}
