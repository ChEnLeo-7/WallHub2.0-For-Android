package com.wallhub.android.data.steam

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class SteamWorkshopWriteCoordinatorTest {
    @Test
    fun `two writes for the same item are never coalesced`() =
        runTest {
            val recovery = SteamSessionRequestRecovery(FakeRecoverableSession())
            val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val requests = SteamWorkshopReadCoordinator(maxConcurrent = 1, scope = requestScope)
            val writes = SteamWorkshopWriteCoordinator(requests, recovery)
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CountDownLatch(1)
            val calls = mutableListOf<Boolean>()

            val first =
                async(Dispatchers.Default) {
                    writes.write(WorkshopReadKind.SET_SUBSCRIBED) {
                        calls += true
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        true
                    }
                }
            firstEntered.await()
            val second =
                async(Dispatchers.Default) {
                    writes.write(WorkshopReadKind.SET_SUBSCRIBED) {
                        calls += false
                        false
                    }
                }
            releaseFirst.countDown()

            assertEquals(listOf(true, false), listOf(first, second).awaitAll())
            assertEquals(listOf(true, false), calls)
            requestScope.cancel()
        }

    @Test
    fun `recoverable write reconnects and retries on the replacement generation`() =
        runTest {
            val session = FakeRecoverableSession()
            val writes =
                SteamWorkshopWriteCoordinator(
                    SteamWorkshopReadCoordinator(maxConcurrent = 1, scope = backgroundScope),
                    SteamSessionRequestRecovery(session),
                )
            var attempts = 0

            val answer =
                writes.write(WorkshopReadKind.POST_COMMENT) {
                    attempts += 1
                    if (attempts == 1) error("content resolution failed at session_unavailable")
                    "confirmed"
                }

            assertEquals("confirmed", answer)
            assertEquals(2, attempts)
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `terminal write failure is propagated and does not report success`() =
        runTest {
            val writes =
                SteamWorkshopWriteCoordinator(
                    SteamWorkshopReadCoordinator(maxConcurrent = 1, scope = backgroundScope),
                    SteamSessionRequestRecovery(FakeRecoverableSession()),
                )
            val denied = IllegalStateException("access_denied: the service refused the request")

            val thrown =
                assertFailsWith<IllegalStateException> {
                    writes.write<Unit>(WorkshopReadKind.SET_FAVORITED) { throw denied }
                }

            assertEquals(denied.message, thrown.message)
        }

    private class FakeRecoverableSession : RecoverableSteamSession {
        private var current = SteamSessionGeneration(1L)
        var reconnectCalls = 0

        override fun generation(): SteamSessionGeneration = current

        override fun <T> executeIfCurrent(
            expected: SteamSessionGeneration,
            operation: () -> T,
        ): T {
            check(current == expected)
            return operation()
        }

        override suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean {
            check(current == expected)
            reconnectCalls += 1
            current = SteamSessionGeneration(current.value + 1L)
            return true
        }
    }
}
