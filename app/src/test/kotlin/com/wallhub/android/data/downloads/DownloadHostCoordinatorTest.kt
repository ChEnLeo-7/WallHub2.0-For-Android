package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.DownloadCommandDao
import com.wallhub.android.core.database.DownloadCommandEntity
import com.wallhub.android.core.database.DownloadEngineStateEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DownloadHostCoordinatorTest {
    @Test
    fun `concurrent wakeups for one task share one owned launch`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(null, true)
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
    }

    @Test
    fun `cancelling the owning execution window cancels the transfer`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(null, true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val owner =
            async {
                coordinator.reconcile("task-1") {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }

        entered.await()
        owner.cancelAndJoin()

        cancelled.await()
    }

    @Test
    fun `cancelling a joiner cannot evict the owned run`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(null, true)
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

        release.complete(Unit)
        assertSame(DownloadHostRunResult.SUCCESS, owner.await())
        assertSame(DownloadHostRunResult.SUCCESS, third.await())
    }

    @Test
    fun `a command accepted during a run remains pending for the successor wake`() = runTest {
        val commands = mutableListOf(command("run", 1, "RUN"))
        val acknowledged = mutableListOf<String>()
        val dao = commandDao(commands, acknowledged)
        val coordinator = SingleProcessDownloadHostCoordinator(DownloadCommandStore(dao), true)

        coordinator.reconcile("task-1") {
            commands += command("cancel", 2, "CANCEL")
            DownloadHostRunResult.SUCCESS
        }

        assertEquals(listOf("run"), acknowledged)
        assertEquals(listOf("cancel"), commands.filterNot { it.commandId in acknowledged }.map { it.commandId })
    }

    @Test
    fun `a later wakeup launches again after the owned task finishes`() = runTest {
        val coordinator = SingleProcessDownloadHostCoordinator(null, true)
        val launches = AtomicInteger()

        repeat(2) {
            coordinator.reconcile("task-1") {
                launches.incrementAndGet()
                DownloadHostRunResult.SUCCESS
            }
        }

        assertEquals(2, launches.get())
    }

    private fun command(
        id: String,
        revision: Long,
        value: String,
    ) =
        DownloadCommandEntity(
            commandId = id,
            taskId = "task-1",
            revision = revision,
            attempt = 0,
            command = value,
            createdAt = revision,
        )

    @Suppress("UNCHECKED_CAST")
    private fun commandDao(
        commands: MutableList<DownloadCommandEntity>,
        acknowledged: MutableList<String>,
    ): DownloadCommandDao =
        Proxy.newProxyInstance(
            DownloadCommandDao::class.java.classLoader,
            arrayOf(DownloadCommandDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "acquireEngineEpoch" -> DownloadEngineStateEntity(epoch = 1, ownerId = "test", acquiredAt = 1)
                "pendingForTask" -> commands.filterNot { it.commandId in acknowledged }
                "claim" -> 1
                "receiptSequenceBase" -> 0L
                "acknowledgeAndProject" -> {
                    acknowledged += (args!![0] as DownloadCommandEntity).commandId
                    true
                }
                else -> error("Unexpected command DAO call ${method.name}")
            }
        } as DownloadCommandDao
}
