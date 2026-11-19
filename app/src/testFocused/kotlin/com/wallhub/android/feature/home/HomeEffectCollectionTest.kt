package com.wallhub.android.feature.home

import com.wallhub.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeEffectCollectionTest {
    @Test
    fun navigationIsHandledWhileConfirmationIsStillVisible() = runTest {
        val effects = Channel<HomeEffect>(Channel.BUFFERED)
        val confirmationStarted = CompletableDeferred<Unit>()
        val dismissConfirmation = CompletableDeferred<Unit>()
        val handled = mutableListOf<HomeEffect>()
        backgroundScope.launch {
            effects.receiveAsFlow().collectHomeEffects(
                showDownloadConfirmation = {
                    confirmationStarted.complete(Unit)
                    dismissConfirmation.await()
                },
                handleEffect = { handled += it },
            )
        }
        effects.send(confirmation())
        runCurrent()
        assertTrue(confirmationStarted.isCompleted)

        effects.send(HomeEffect.OpenDetail(42L))
        runCurrent()

        assertFalse(dismissConfirmation.isCompleted)
        assertEquals(listOf(HomeEffect.OpenDetail(42L)), handled)
    }

    @Test
    fun newerConfirmationReplacesPreviousWithoutReplayingItsAction() = runTest {
        val effects = Channel<HomeEffect>(Channel.BUFFERED)
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val actOnConfirmation = CompletableDeferred<Unit>()
        var queueOpened = 0
        backgroundScope.launch {
            effects.receiveAsFlow().collectHomeEffects(
                showDownloadConfirmation = { effect ->
                    val title = effect.formatArgs.single().toString()
                    started += title
                    try {
                        actOnConfirmation.await()
                        queueOpened += 1
                    } finally {
                        cancelled += title
                    }
                },
                handleEffect = {},
            )
        }
        effects.send(confirmation("first"))
        runCurrent()
        effects.send(confirmation("second"))
        runCurrent()
        assertEquals(listOf("first", "second"), started)
        assertEquals(listOf("first"), cancelled)
        assertEquals(0, queueOpened)

        actOnConfirmation.complete(Unit)
        runCurrent()
        assertEquals(1, queueOpened)
    }

    @Test
    fun leavingEffectHandlerCancelsVisibleConfirmation() = runTest {
        val effects = Channel<HomeEffect>(Channel.BUFFERED)
        var cancelled = false
        val collector = backgroundScope.launch {
            effects.receiveAsFlow().collectHomeEffects(
                showDownloadConfirmation = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
                handleEffect = {},
            )
        }
        effects.send(confirmation())
        runCurrent()
        collector.cancel()
        runCurrent()
        assertTrue(cancelled)
    }

    private fun confirmation(title: String = "wallpaper") =
        HomeEffect.ShowMessage(R.string.home_added_to_download_queue, listOf(title))
}
