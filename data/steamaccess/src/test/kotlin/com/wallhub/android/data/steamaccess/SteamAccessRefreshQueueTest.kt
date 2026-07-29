package com.wallhub.android.data.steamaccess

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `queue continues after one refresh action fails`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val attempts = AtomicInteger()
        val secondAttempt = CompletableDeferred<Unit>()
        try {
            val queue = SteamAccessRefreshQueue(scope) {
                if (attempts.incrementAndGet() == 1) error("expected")
                secondAttempt.complete(Unit)
            }

            queue.request()
            withTimeout(5_000) {
                while (attempts.get() < 1) kotlinx.coroutines.yield()
            }
            queue.request()

            withTimeout(5_000) { secondAttempt.await() }
            assertTrue(attempts.get() >= 2)
        } finally {
            scope.cancel()
            dispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

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
