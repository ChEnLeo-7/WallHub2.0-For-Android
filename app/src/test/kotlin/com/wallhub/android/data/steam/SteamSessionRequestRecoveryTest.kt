package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class SteamSessionRequestRecoveryTest {
    @Test
    fun `a request refused before send reconnects and retries once`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session)
            var calls = 0

            val result =
                recovery.execute {
                    calls += 1
                    if (calls == 1) throw IllegalArgumentException("invalid_input: the request was refused before it was sent")
                    "page"
                }

            assertEquals("page", result)
            assertEquals(2, calls)
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `a stale connection failure before service answer reconnects and retries once`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session)
            var calls = 0

            val result =
                recovery.execute {
                    calls += 1
                    if (calls == 1) {
                        error("temporarily_unavailable: the connection failed before the service answered")
                    }
                    "detail"
                }

            assertEquals("detail", result)
            assertEquals(2, calls)
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `a session unavailable failure reconnects and retries once`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session)
            var calls = 0

            val result =
                recovery.execute {
                    calls += 1
                    if (calls == 1) error("content resolution failed at session_unavailable")
                    42
                }

            assertEquals(42, result)
            assertEquals(2, calls)
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `a stale first admission invokes a mutation only on the explicit replacement generation`() =
        runTest {
            val session = FakeRecoverableSession().apply { staleAdmissionsRemaining = 1 }
            val recovery = SteamSessionRequestRecovery(session)
            var mutations = 0

            val result =
                recovery.execute {
                    mutations += 1
                    "confirmed"
                }

            assertEquals("confirmed", result)
            assertEquals(1, mutations)
            assertEquals(listOf(SteamSessionGeneration(2L)), session.admittedGenerations)
            assertEquals(0, session.reconnectCalls)
        }

    @Test
    fun `a stale second admission returns without invoking the mutation`() =
        runTest {
            val session = FakeRecoverableSession().apply { staleAdmissionsRemaining = 2 }
            val recovery = SteamSessionRequestRecovery(session)
            var mutations = 0

            assertFailsWith<SteamSessionGenerationChangedException> {
                recovery.execute {
                    mutations += 1
                    "must not run"
                }
            }

            assertEquals(0, mutations)
            assertEquals(emptyList(), session.admittedGenerations)
            assertEquals(0, session.reconnectCalls)
        }

    @Test
    fun `a second recoverable failure escapes without another reconnect`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session)
            var calls = 0

            assertFailsWith<IllegalStateException> {
                recovery.execute<Unit> {
                    calls += 1
                    error("invalid_input: the request was refused before it was sent")
                }
            }

            assertEquals(2, calls)
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `a failed reconnect preserves the original failure without retrying`() =
        runTest {
            val session = FakeRecoverableSession().apply { reconnectSucceeds = false }
            val recovery = SteamSessionRequestRecovery(session)
            val refused = IllegalStateException("invalid_input: the request was refused before it was sent")
            var calls = 0

            val thrown =
                assertFailsWith<IllegalStateException> {
                    recovery.execute<Unit> {
                        calls += 1
                        throw refused
                    }
                }

            assertSame(refused, thrown)
            assertEquals(1, calls)
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `unrelated failures are not reconnected or retried`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session)
            val denied = IllegalStateException("access_denied: the service refused the request")
            var calls = 0

            val thrown =
                assertFailsWith<IllegalStateException> {
                    recovery.execute<Unit> {
                        calls += 1
                        throw denied
                    }
                }

            assertSame(denied, thrown)
            assertEquals(1, calls)
            assertEquals(0, session.reconnectCalls)
        }

    @Test
    fun `concurrent failures from one generation share one reconnect`() =
        runTest {
            val session = FakeRecoverableSession()
            val reconnectEntered = CompletableDeferred<Unit>()
            val permitReconnect = CompletableDeferred<Unit>()
            session.beforeReconnect = {
                reconnectEntered.complete(Unit)
                permitReconnect.await()
            }
            val recovery = SteamSessionRequestRecovery(session)
            val attempts = IntArray(2)

            val reads =
                List(2) { index ->
                    async {
                        recovery.execute {
                            attempts[index] += 1
                            if (attempts[index] == 1) {
                                error("invalid_input: the request was refused before it was sent")
                            }
                            index
                        }
                    }
                }
            reconnectEntered.await()
            permitReconnect.complete(Unit)

            assertEquals(listOf(0, 1), reads.awaitAll())
            assertEquals(listOf(2, 2), attempts.toList())
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `an authenticated generation replaced before recovery is not reconnected again`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session)
            var calls = 0

            val result =
                recovery.execute {
                    calls += 1
                    if (calls == 1) {
                        session.replaceGeneration(usable = true)
                        error("content resolution failed at session unavailable")
                    }
                    "new generation"
                }

            assertEquals("new generation", result)
            assertEquals(0, session.reconnectCalls)
        }

    @Test
    fun `an unauthenticated replacement generation does not permit a retry`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session)
            val unavailable = IllegalStateException("content resolution failed at session unavailable")
            var calls = 0

            val thrown =
                assertFailsWith<IllegalStateException> {
                    recovery.execute<Unit> {
                        calls += 1
                        session.replaceGeneration(usable = false)
                        throw unavailable
                    }
                }

            assertSame(unavailable, thrown)
            assertEquals(1, calls)
            assertEquals(0, session.reconnectCalls)
        }

    @Test
    fun `a reconnect that does not authenticate does not permit a retry`() =
        runTest {
            val session = FakeRecoverableSession().apply { reconnectIsUsable = false }
            val recovery = SteamSessionRequestRecovery(session)
            val unavailable = IllegalStateException("content resolution failed at session unavailable")
            var calls = 0

            val thrown =
                assertFailsWith<IllegalStateException> {
                    recovery.execute<Unit> {
                        calls += 1
                        throw unavailable
                    }
                }

            assertSame(unavailable, thrown)
            assertEquals(1, calls)
            assertEquals(1, session.reconnectCalls)
        }

    @Test
    fun `cancellation is propagated without reconnect or retry`() =
        runTest {
            val session = FakeRecoverableSession()
            val recovery = SteamSessionRequestRecovery(session) { SteamRequestRecoveryCause.SESSION_UNAVAILABLE }
            val cancelled = CancellationException("caller left")
            var calls = 0

            val thrown =
                assertFailsWith<CancellationException> {
                    recovery.execute<Unit> {
                        calls += 1
                        throw cancelled
                    }
                }

            assertSame(cancelled, thrown)
            assertEquals(1, calls)
            assertEquals(0, session.reconnectCalls)
        }

    private class FakeRecoverableSession : RecoverableSteamSession {
        private var current = SteamSessionGeneration(1L)
        var reconnectCalls = 0
        var reconnectSucceeds = true
        var reconnectIsUsable = true
        var beforeReconnect: suspend () -> Unit = {}
        var staleAdmissionsRemaining = 0
        val admittedGenerations = mutableListOf<SteamSessionGeneration>()
        private var usable = true

        override fun generation(): SteamSessionGeneration = current

        override fun isUsable(generation: SteamSessionGeneration): Boolean = current == generation && usable

        override fun <T> executeIfCurrent(
            expected: SteamSessionGeneration,
            operation: () -> T,
        ): T {
            if (staleAdmissionsRemaining > 0) {
                staleAdmissionsRemaining -= 1
                replaceGeneration()
            }
            if (current != expected) throw SteamSessionGenerationChangedException(expected, current)
            admittedGenerations += expected
            return operation()
        }

        override suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean {
            check(current == expected)
            reconnectCalls += 1
            beforeReconnect()
            if (reconnectSucceeds) {
                current = SteamSessionGeneration(current.value + 1L)
                usable = reconnectIsUsable
            }
            return reconnectSucceeds && usable
        }

        fun replaceGeneration(usable: Boolean = true) {
            current = SteamSessionGeneration(current.value + 1L)
            this.usable = usable
        }
    }
}
