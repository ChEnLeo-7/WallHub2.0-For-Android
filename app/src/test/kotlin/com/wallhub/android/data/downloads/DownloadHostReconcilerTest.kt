package com.wallhub.android.data.downloads

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHostReconcilerTest {
    @Test
    fun `a durable command signalled while another task runs is launched before that task completes`() = runTest {
        val signal = ProcessDownloadHostWakeSignal()
        val pending = mutableListOf("task-a")
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val reconciler = reconciler(signal, pending) { taskId ->
            when (taskId) {
                "task-a" -> {
                    aStarted.complete(Unit)
                    releaseA.await()
                }
                "task-b" -> bStarted.complete(Unit)
            }
            pending.remove(taskId)
            DownloadHostRunResult.SUCCESS
        }

        val run = backgroundScope.async { reconciler.reconcile() }
        aStarted.await()
        pending += "task-b"
        signal.signal()

        bStarted.await()
        assertFalse(releaseA.isCompleted)
        releaseA.complete(Unit)
        assertEquals(DownloadHostRunResult.SUCCESS, run.await())
    }

    @Test
    fun `another command for an active task never creates a concurrent launch`() = runTest {
        val signal = ProcessDownloadHostWakeSignal()
        val pending = mutableListOf("task-a")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val activeLaunches = AtomicInteger()
        var maximumActiveLaunches = 0
        val reconciler = reconciler(signal, pending) {
            val active = activeLaunches.incrementAndGet()
            maximumActiveLaunches = maxOf(maximumActiveLaunches, active)
            started.complete(Unit)
            release.await()
            pending.clear()
            activeLaunches.decrementAndGet()
            DownloadHostRunResult.SUCCESS
        }

        val run = backgroundScope.async { reconciler.reconcile() }
        started.await()
        pending += "task-a"
        signal.signal()
        testScheduler.runCurrent()

        assertEquals(1, maximumActiveLaunches)
        release.complete(Unit)
        assertEquals(DownloadHostRunResult.SUCCESS, run.await())
    }

    @Test
    fun `retry from any owned task retains WorkManager retry semantics`() = runTest {
        val signal = ProcessDownloadHostWakeSignal()
        val pending = mutableListOf("task-a")
        val result = reconciler(signal, pending) { DownloadHostRunResult.RETRY }.reconcile()

        assertEquals(DownloadHostRunResult.RETRY, result)
        assertTrue(pending.contains("task-a"))
    }

    private fun reconciler(
        signal: DownloadHostWakeSignal,
        pending: MutableList<String>,
        launch: suspend (String) -> DownloadHostRunResult,
    ) = DownloadHostReconciler(
        pendingTaskIds = { pending.toList() },
        host = object : DownloadHostCoordinator {
            override suspend fun reconcile(
                taskId: String,
                launchOwned: suspend () -> DownloadHostRunResult,
            ): DownloadHostRunResult = launchOwned()
        },
        signal = signal,
        launchTask = launch,
    )
}
