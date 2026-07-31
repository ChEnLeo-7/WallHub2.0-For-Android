package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse

class DownloadConcurrencyGovernorTest {
    @Test
    fun `hands a released slot to exactly one waiting download`() =
        runTest {
            val governor = DownloadConcurrencyGovernor()
            val firstRelease = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            val thirdEntered = CompletableDeferred<Unit>()
            val thirdRelease = CompletableDeferred<Unit>()

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot(taskId = "first", priority = 0, limit = 1) { firstRelease.await() }
                }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot(taskId = "second", priority = 1, limit = 1) {
                        secondEntered.complete(Unit)
                        secondRelease.await()
                    }
                }

            firstRelease.complete(Unit)
            secondEntered.await()

            val third =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot(taskId = "third", priority = 2, limit = 1) {
                        thirdEntered.complete(Unit)
                        thirdRelease.await()
                    }
                }

            assertFalse(thirdEntered.isCompleted)

            secondRelease.complete(Unit)
            thirdEntered.await()
            thirdRelease.complete(Unit)
            first.await()
            second.await()
            third.await()
        }

    @Test
    fun `updated queue order controls which waiting download starts next`() =
        runTest {
            val governor = DownloadConcurrencyGovernor()
            val activeRelease = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            val thirdEntered = CompletableDeferred<Unit>()
            val thirdRelease = CompletableDeferred<Unit>()

            val active =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot(taskId = "active", priority = 0, limit = 1) {
                        activeRelease.await()
                    }
                }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot(taskId = "second", priority = 1, limit = 1) {
                        secondEntered.complete(Unit)
                        secondRelease.await()
                    }
                }
            val third =
                async(start = CoroutineStart.UNDISPATCHED) {
                    governor.withSlot(taskId = "third", priority = 2, limit = 1) {
                        thirdEntered.complete(Unit)
                        thirdRelease.await()
                    }
                }

            governor.updatePriorities(listOf("active", "third", "second"))
            activeRelease.complete(Unit)
            thirdEntered.await()
            assertFalse(secondEntered.isCompleted)

            thirdRelease.complete(Unit)
            secondEntered.await()
            secondRelease.complete(Unit)
            active.await()
            second.await()
            third.await()
        }
}
