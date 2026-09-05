package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHeavyWorkGovernorTest {
    @Test
    fun chunkMemoryBudgetDoesNotOverrideConfiguredTaskLimit() {
        assertEquals(4, safeDownloadLimit(configuredLimit = 4, maxHeapBytes = 192L * 1024L * 1024L))
        assertEquals(4, safeDownloadLimit(configuredLimit = 4, maxHeapBytes = 512L * 1024L * 1024L))
        assertEquals(1, safeDownloadLimit(configuredLimit = 1, maxHeapBytes = 192L * 1024L * 1024L))
        assertEquals(24, safeChunkConcurrency(configuredConcurrency = 24, maxHeapBytes = 192L * 1024L * 1024L))
        assertEquals(24, safeChunkConcurrency(configuredConcurrency = 24, maxHeapBytes = 512L * 1024L * 1024L))
    }

    @Test
    fun chunkMemoryEstimateIncludesSteamNetworkCopies() {
        val mebibyte = 1024 * 1024

        assertEquals(
            13L * mebibyte,
            estimatedSteamChunkPeakMemoryBytes(
                compressedBytes = mebibyte,
                uncompressedBytes = 2 * mebibyte,
            ),
        )
    }

    @Test
    fun oversizedChunkReservationRunsExclusively() =
        runTest {
            val budget = DownloadMemoryBudget(maxHeapBytes = 192L * 1024L * 1024L)
            val firstEntered = CompletableDeferred<Unit>()
            val firstRelease = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    budget.withPermit(requestedBytes = 64L * 1024L * 1024L) {
                        firstEntered.complete(Unit)
                        firstRelease.await()
                    }
                }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    budget.withPermit(requestedBytes = 1L) { secondEntered.complete(Unit) }
                }

            firstEntered.await()
            assertTrue(!secondEntered.isCompleted)
            firstRelease.complete(Unit)
            first.await()
            second.await()
            assertTrue(secondEntered.isCompleted)
        }

    @Test
    fun conversionRunsAlongsideDownloadsButRemainsSerial() =
        runTest {
            val governor = DownloadConcurrencyGovernor()
            val firstDownloadRelease = CompletableDeferred<Unit>()
            val conversionEntered = CompletableDeferred<Unit>()
            val conversionRelease = CompletableDeferred<Unit>()
            val secondDownloadEntered = CompletableDeferred<Unit>()
            val secondConversionEntered = CompletableDeferred<Unit>()

            val firstDownload =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot("first", 0, 4) { firstDownloadRelease.await() }
                }
            val conversion =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withConversionSlot {
                        conversionEntered.complete(Unit)
                        conversionRelease.await()
                    }
                }
            val secondDownload =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot("second", 1, 4) { secondDownloadEntered.complete(Unit) }
                }
            val secondConversion =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withConversionSlot { secondConversionEntered.complete(Unit) }
                }

            assertTrue(conversionEntered.isCompleted)
            assertTrue(secondDownloadEntered.isCompleted)
            assertTrue(!secondConversionEntered.isCompleted)
            firstDownloadRelease.complete(Unit)
            conversionRelease.complete(Unit)
            secondConversionEntered.await()

            firstDownload.await()
            conversion.await()
            secondDownload.await()
            secondConversion.await()
        }
}
