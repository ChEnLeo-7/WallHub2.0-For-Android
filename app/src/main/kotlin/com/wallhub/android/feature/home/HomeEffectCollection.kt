package com.wallhub.android.feature.home

import com.wallhub.android.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** A queue confirmation must not hold up navigation; a newer confirmation replaces the previous one. */
internal suspend fun Flow<HomeEffect>.collectHomeEffects(
    showDownloadConfirmation: suspend (HomeEffect.ShowMessage) -> Unit,
    handleEffect: suspend (HomeEffect) -> Unit,
) = coroutineScope {
    var confirmationJob: Job? = null
    collect { effect ->
        if (effect is HomeEffect.ShowMessage && effect.messageRes == R.string.home_added_to_download_queue) {
            confirmationJob?.cancel()
            confirmationJob = launch { showDownloadConfirmation(effect) }
        } else {
            handleEffect(effect)
        }
    }
}
