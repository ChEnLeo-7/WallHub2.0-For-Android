package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionAdmissionCancellationTest {
    private val taskIds = mutableSetOf<String>()

    @After
    fun clearCancellationSignals() {
        taskIds.forEach(FormalWorkshopConversionCancellation::clear)
    }

    @Test
    fun `cancellation while blocked on playback gate returns promptly and reaches settlement`() = runTest {
        val taskId = track("playback-blocked")
        val playbackCoordinator = PlaybackDownloadCoordinator()
        val governor = DownloadConcurrencyGovernor()
        val playback = playbackCoordinator.registerPlayback()
        val conversionEntered = CompletableDeferred<Unit>()
        val admission =
            async(start = CoroutineStart.UNDISPATCHED) {
                ConversionAdmission.await(taskId) {
                    playbackCoordinator.awaitBackgroundWorkAllowed()
                    governor.withConversionSlot { conversionEntered.complete(Unit) }
                }
            }

        runCurrent()
        assertFalse(admission.isCompleted)
        requestCancellation(taskId)

        assertEquals(ConversionAdmission.Cancelled, withTimeout(1_000) { admission.await() })
        assertFalse(conversionEntered.isCompleted)
        assertTrue(FormalWorkshopConversionCancellation.claimCancellation(taskId))
        settleCancellation(taskId)
        playback.close()
    }

    @Test
    fun `cancellation while blocked on conversion slot returns promptly and reaches settlement`() = runTest {
        val taskId = track("conversion-slot-blocked")
        val governor = DownloadConcurrencyGovernor()
        val activeRelease = CompletableDeferred<Unit>()
        val active =
            async(start = CoroutineStart.UNDISPATCHED) {
                governor.withConversionSlot { activeRelease.await() }
            }
        val conversionEntered = CompletableDeferred<Unit>()
        val admission =
            async(start = CoroutineStart.UNDISPATCHED) {
                ConversionAdmission.await(taskId) {
                    governor.withConversionSlot { conversionEntered.complete(Unit) }
                }
            }

        runCurrent()
        assertFalse(admission.isCompleted)
        requestCancellation(taskId)

        assertEquals(ConversionAdmission.Cancelled, withTimeout(1_000) { admission.await() })
        assertFalse(conversionEntered.isCompleted)
        assertTrue(FormalWorkshopConversionCancellation.claimCancellation(taskId))
        settleCancellation(taskId)
        activeRelease.complete(Unit)
        active.await()
    }

    private suspend fun requestCancellation(taskId: String) {
        assertTrue(FormalWorkshopConversionCancellation.beginRequest(taskId))
        FormalWorkshopConversionCancellation.completeRequest(taskId)
    }

    private fun settleCancellation(taskId: String) {
        FormalWorkshopConversionCancellation.clear(taskId)
        assertFalse(FormalWorkshopConversionCancellation.isRequested(taskId))
    }

    private fun track(taskId: String): String = taskId.also(taskIds::add)
}
