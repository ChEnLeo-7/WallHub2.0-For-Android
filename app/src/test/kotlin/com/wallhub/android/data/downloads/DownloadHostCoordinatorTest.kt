package com.wallhub.android.data.downloads

import androidx.work.ListenableWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DownloadHostCoordinatorTest {
    @Test
    fun `concurrent wakeups for one task share one owned launch`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val launches = AtomicInteger()

        val first = async {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                entered.complete(Unit)
                release.await()
                ListenableWorker.Result.success()
            }
        }
        entered.await()
        val second = async {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                ListenableWorker.Result.failure()
            }
        }

        release.complete(Unit)
        assertSame(first.await(), second.await())
        assertEquals(1, launches.get())
        assertFalse(coordinator.isActive("task-1"))
    }

    @Test
    fun `a later wakeup launches again after the owned task finishes`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(this)
        val launches = AtomicInteger()

        repeat(2) {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                ListenableWorker.Result.success()
            }
        }

        assertEquals(2, launches.get())
    }
}
