package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.ThemePreference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHeavyWorkGovernorTest {
    @Test
    fun chunkMemoryBudgetDoesNotOverrideConfiguredTaskLimit() {
        assertEquals(4, safeDownloadLimit(configuredLimit = 4, maxHeapBytes = 192L * 1024L * 1024L))
        assertEquals(8, safeDownloadLimit(configuredLimit = 8, maxHeapBytes = 512L * 1024L * 1024L))
        assertEquals(1, safeDownloadLimit(configuredLimit = 0, maxHeapBytes = 192L * 1024L * 1024L))
        assertEquals(24, safeChunkConcurrency(configuredConcurrency = 24, maxHeapBytes = 192L * 1024L * 1024L))
        assertEquals(24, safeChunkConcurrency(configuredConcurrency = 24, maxHeapBytes = 512L * 1024L * 1024L))
    }

    /// The offered range is the window the SDK is actually given: one chunk at a time has to be selectable, and
    /// the ceiling has to stay inside the SDK's own client pool so no setting is silently reduced.
    @Test
    fun theChunkConcurrencyRangeIsTheSdkOne() {
        assertEquals(1, safeChunkConcurrency(configuredConcurrency = 1, maxHeapBytes = 512L * 1024L * 1024L))
        assertEquals(1, safeChunkConcurrency(configuredConcurrency = 0, maxHeapBytes = 512L * 1024L * 1024L))
        assertEquals(48, safeChunkConcurrency(configuredConcurrency = 96, maxHeapBytes = 512L * 1024L * 1024L))
    }

    @Test
    fun queuedControlWithdrawsBeforeTakingADownloadSlot() = runTest {
        val governor = DownloadConcurrencyGovernor()
        val firstRelease = CompletableDeferred<Unit>()
        val withdrawnBlockEntered = CompletableDeferred<Unit>()
        val successorEntered = CompletableDeferred<Unit>()
        val first =
            async(start = CoroutineStart.UNDISPATCHED) {
                governor.withSlot("first", 0, 1) { firstRelease.await() }
            }
        val withdrawn =
            async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    governor.withSlot(
                        taskId = "withdrawn",
                        priority = 1,
                        limit = 1,
                        whileWaiting = { false },
                    ) { withdrawnBlockEntered.complete(Unit) }
                }.exceptionOrNull()
            }
        val successor =
            async(start = CoroutineStart.UNDISPATCHED) {
                governor.withSlot("successor", 2, 1) { successorEntered.complete(Unit) }
            }

        advanceTimeBy(251)
        runCurrent()
        assertTrue(withdrawn.await() is DownloadAdmissionWithdrawnException)
        assertTrue(!withdrawnBlockEntered.isCompleted)
        firstRelease.complete(Unit)
        successorEntered.await()

        first.await()
        successor.await()
    }

    @Test
    fun productionAdmissionWaitsForThePersistedLimitBeforeUsingASlot() = runTest {
        val releasePersistedPreferences = CompletableDeferred<Unit>()
        val repository =
            TestSettingsRepository(
                flow {
                    releasePersistedPreferences.await()
                    emit(AppPreferences(maxConcurrentDownloads = 2))
                },
            )
        val governor = DownloadConcurrencyGovernor(repository)
        val entered = CompletableDeferred<Unit>()
        val admission =
            async(start = CoroutineStart.UNDISPATCHED) {
                governor.withSlot("waiting-for-settings", 0, 1) { entered.complete(Unit) }
            }

        runCurrent()
        assertTrue(!entered.isCompleted)

        releasePersistedPreferences.complete(Unit)
        entered.await()
        admission.await()
    }

    @Test
    fun increasingTheGlobalLimitImmediatelyReleasesQueuedWorkInPriorityOrder() = runTest {
        val governor = DownloadConcurrencyGovernor()
        val activeRelease = CompletableDeferred<Unit>()
        val lowerPriorityEntered = CompletableDeferred<Unit>()
        val higherPriorityEntered = CompletableDeferred<Unit>()
        val higherPriorityRelease = CompletableDeferred<Unit>()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("active", 0, 1) { activeRelease.await() }
        }
        val lowerPriority = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("lower", 20, 1) { lowerPriorityEntered.complete(Unit) }
        }
        val higherPriority = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("higher", 10, 1) {
                higherPriorityEntered.complete(Unit)
                higherPriorityRelease.await()
            }
        }

        governor.updateLimit(2)
        higherPriorityEntered.await()
        assertTrue(!lowerPriorityEntered.isCompleted)

        activeRelease.complete(Unit)
        lowerPriorityEntered.await()
        higherPriorityRelease.complete(Unit)
        active.await()
        higherPriority.await()
        lowerPriority.await()
    }

    @Test
    fun decreasingTheGlobalLimitDoesNotPreemptActiveWork() = runTest {
        val governor = DownloadConcurrencyGovernor(initialLimit = 2)
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val queuedEntered = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("first", 0, 2) { firstRelease.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("second", 1, 2) { secondRelease.await() }
        }

        governor.updateLimit(1)
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("queued", 2, 1) { queuedEntered.complete(Unit) }
        }
        firstRelease.complete(Unit)
        runCurrent()
        assertTrue(!queuedEntered.isCompleted)
        secondRelease.complete(Unit)
        queuedEntered.await()

        first.await()
        second.await()
        queued.await()
    }

    @Test
    fun cancelledQueuedWorkDoesNotConsumeAReleasedSlot() = runTest {
        val governor = DownloadConcurrencyGovernor()
        val activeRelease = CompletableDeferred<Unit>()
        val successorEntered = CompletableDeferred<Unit>()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("active", 0, 1) { activeRelease.await() }
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("cancelled", 1, 1) { error("cancelled waiter entered") }
        }
        val successor = async(start = CoroutineStart.UNDISPATCHED) {
            governor.withSlot("successor", 2, 1) { successorEntered.complete(Unit) }
        }

        cancelled.cancelAndJoin()
        activeRelease.complete(Unit)
        successorEntered.await()
        active.await()
        successor.await()
    }

    @Test
    fun conversionRunsAlongsideDownloadsButRemainsSerial() =
        runTest {
            val governor = DownloadConcurrencyGovernor(initialLimit = 4)
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

    private class TestSettingsRepository(
        override val preferences: Flow<AppPreferences>,
    ) : SettingsRepository {
        override suspend fun setTheme(theme: ThemePreference) = Unit

        override suspend fun setAccent(
            accent: AccentPreference,
            customColor: String?,
        ) = Unit

        override suspend fun setHomePreferences(
            pageSize: Int,
            columns: Int,
            multiSelect: Boolean,
            cardAction: HomeCardAction,
            matureContentEnabled: Boolean,
        ) = Unit

        override suspend fun setDownloadPreferences(
            maxConcurrentDownloads: Int,
            chunkDownloadConcurrency: Int,
            proxyUrl: String,
            mediaCacheLimitMb: Int,
        ) = Unit

        override suspend fun setOutputDirectory(
            treeUri: String,
            label: String,
        ) = Unit

        override suspend fun clearOutputDirectory() = Unit
    }
}
