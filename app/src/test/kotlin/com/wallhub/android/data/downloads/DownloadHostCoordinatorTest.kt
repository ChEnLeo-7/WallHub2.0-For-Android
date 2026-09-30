package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
                DownloadHostRunResult.SUCCESS
            }
        }
        entered.await()
        val second = async {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                DownloadHostRunResult.FAILURE
            }
        }

        release.complete(Unit)
        assertSame(first.await(), second.await())
        assertEquals(1, launches.get())
        assertFalse(coordinator.isActive("task-1"))
    }

    @Test
    fun `cancelling a joiner cannot evict the owned run`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val launches = AtomicInteger()
        val owner = async {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                entered.complete(Unit)
                release.await()
                DownloadHostRunResult.SUCCESS
            }
        }
        entered.await()
        val joiner = async {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                DownloadHostRunResult.FAILURE
            }
        }
        yield()
        joiner.cancelAndJoin()

        val third = async {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                DownloadHostRunResult.FAILURE
            }
        }
        yield()
        assertEquals(1, launches.get())
        assertTrue(coordinator.isActive("task-1"))

        release.complete(Unit)
        assertSame(DownloadHostRunResult.SUCCESS, owner.await())
        assertSame(DownloadHostRunResult.SUCCESS, third.await())
        assertFalse(coordinator.isActive("task-1"))
    }

    @Test
    fun `a later wakeup launches again after the owned task finishes`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(this)
        val launches = AtomicInteger()

        repeat(2) {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                DownloadHostRunResult.SUCCESS
            }
        }

        assertEquals(2, launches.get())
    }
}
