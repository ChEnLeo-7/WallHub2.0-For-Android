package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.DownloadCommandDao
import com.wallhub.android.core.database.DownloadCommandEntity
import com.wallhub.android.core.database.DownloadEngineStateEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCommandStoreTest {
    @Test
    fun `engine epochs increase and stale epoch cannot acknowledge`() = runTest {
        val fake = FakeCommandDao()
        val store = DownloadCommandStore(fake.proxy)
        val first = store.acquireEngineEpoch(ownerId = "first", now = 1)
        val second = store.acquireEngineEpoch(ownerId = "second", now = 2)
        assertEquals(1L, first)
        assertEquals(2L, second)

        val command = command()
        fake.pending += command
        assertEquals(listOf(command), store.claimTask("task", second, now = 3))
        assertFalse(store.acknowledge(command, first, 1, "stale", false, now = 4))
        assertTrue(store.acknowledge(command, second, 2, "ok", true, now = 5))
    }

    private fun command() =
        DownloadCommandEntity(
            commandId = "command",
            taskId = "task",
            revision = 1,
            attempt = 0,
            command = "PAUSE",
            createdAt = 1,
        )

    private class FakeCommandDao {
        var engine: DownloadEngineStateEntity? = null
        val pending = mutableListOf<DownloadCommandEntity>()
        var claimedEpoch: Long? = null
        var acknowledged = false

        val proxy =
            Proxy.newProxyInstance(
                DownloadCommandDao::class.java.classLoader,
                arrayOf(DownloadCommandDao::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "engineState" -> engine
                    "writeEngineState" -> {
                        engine = args!![0] as DownloadEngineStateEntity
                        Unit
                    }
                    "acquireEngineEpoch" -> {
                        val next = (engine?.epoch ?: 0) + 1
                        DownloadEngineStateEntity(epoch = next, ownerId = args!![0] as String, acquiredAt = args[1] as Long)
                            .also { engine = it }
                    }
                    "pendingForTask" -> pending.filter { it.taskId == args!![0] }
                    "claim" -> {
                        claimedEpoch = args!![1] as Long
                        1
                    }
                    "acknowledgeAndProject" -> {
                        val epoch = args!![1] as Long
                        if (epoch == claimedEpoch && !acknowledged) {
                            acknowledged = true
                            true
                        } else {
                            false
                        }
                    }
                    else -> error("Unexpected DAO call ${method.name}")
                }
            } as DownloadCommandDao
    }
}
