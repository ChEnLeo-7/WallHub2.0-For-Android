package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamContentSdkSessionBrokerTest {
    @Test
    fun `overlapping operations share one account session until the last lease closes`() = runTest {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val session = fakeSession(closes)
        val broker = SteamContentSdkSessionBroker { opens.incrementAndGet(); session }

        val first = broker.acquire(SteamContentCredential("Account", "token"))
        val second = broker.acquire(SteamContentCredential("account", "same-token"))

        assertSame(first.session, second.session)
        assertEquals(1, opens.get())
        first.close()
        assertEquals(0, closes.get())
        second.close()
        assertEquals(1, closes.get())
        second.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun `reported generation loss retires epoch and replacement waits for every lease to drain`() = runTest {
        val opens = AtomicInteger()
        val firstCloses = AtomicInteger()
        val secondCloses = AtomicInteger()
        val sessions = listOf(fakeSession(firstCloses), fakeSession(secondCloses))
        val broker = SteamContentSdkSessionBroker { sessions[opens.getAndIncrement()] }
        val credential = SteamContentCredential("account", "token")

        val failed = broker.acquire(credential)
        val overlapping = broker.acquire(credential)
        assertEquals(failed.generation, overlapping.generation)

        failed.reportSessionLoss()
        failed.reportSessionLoss()
        val replacement = async { broker.acquire(credential) }
        failed.close()
        assertEquals(null, withTimeoutOrNull(50) { replacement.await() })
        assertEquals(1, opens.get())

        overlapping.close()
        val fresh = replacement.await()
        assertEquals(2, opens.get())
        assertEquals(1, firstCloses.get())
        assertTrue(fresh.generation > failed.generation)
        fresh.close()
        assertEquals(1, secondCloses.get())
    }

    @Test
    fun `concurrent loss reports coalesce into one replacement epoch`() = runTest {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val broker = SteamContentSdkSessionBroker { opens.incrementAndGet(); fakeSession(closes) }
        val credential = SteamContentCredential("account", "token")
        val first = broker.acquire(credential)
        val second = broker.acquire(credential)

        first.reportSessionLoss()
        second.reportSessionLoss()
        first.close()
        second.close()
        val replacements = listOf(async { broker.acquire(credential) }, async { broker.acquire(credential) })
        val leases = replacements.map { it.await() }

        assertEquals(2, opens.get())
        assertSame(leases[0].session, leases[1].session)
        assertEquals(leases[0].generation, leases[1].generation)
        leases.forEach { it.close() }
    }

    @Test
    fun `cancelling the first waiter does not cancel the shared session open`() = runTest {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val openStarted = CompletableDeferred<Unit>()
        val releaseOpen = CompletableDeferred<Unit>()
        val session = fakeSession(closes)
        val broker =
            SteamContentSdkSessionBroker {
                opens.incrementAndGet()
                openStarted.complete(Unit)
                releaseOpen.await()
                session
            }

        val first = async { broker.acquire(SteamContentCredential("account", "token")) }
        openStarted.await()
        val second = async { broker.acquire(SteamContentCredential("ACCOUNT", "token")) }
        first.cancelAndJoin()
        releaseOpen.complete(Unit)

        val lease = second.await()
        assertSame(session, lease.session)
        assertEquals(1, opens.get())
        lease.close()
        assertEquals(1, closes.get())
    }

    private fun fakeSession(closes: AtomicInteger): SteamContentSdkSession =
        Proxy.newProxyInstance(
            SteamContentSdkSession::class.java.classLoader,
            arrayOf(SteamContentSdkSession::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "close" -> {
                    closes.incrementAndGet()
                    Unit
                }
                "toString" -> "FakeSteamContentSdkSession"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected session call ${method.name}")
            }
        } as SteamContentSdkSession
}
