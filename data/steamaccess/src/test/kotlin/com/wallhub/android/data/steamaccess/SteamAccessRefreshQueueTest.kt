package com.wallhub.android.data.steamaccess

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAccessRefreshQueueTest {
    @Test
    fun `refresh action does not run on the request caller thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val actionThread = CompletableDeferred<Thread>()
        try {
            val queue = SteamAccessRefreshQueue(scope) {
                actionThread.complete(Thread.currentThread())
            }

            queue.request()

            assertNotEquals(callerThread, withTimeout(5_000) { actionThread.await() })
        } finally {
            scope.cancel()
            dispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
