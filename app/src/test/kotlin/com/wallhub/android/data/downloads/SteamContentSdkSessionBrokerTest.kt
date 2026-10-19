package com.wallhub.android.data.downloads

import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamContentSdkSessionBrokerTest {
    @Test
    fun `thin adapter owns no cached generation or reconnect state`() = runTest {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val broker =
            SteamContentSdkSessionBroker {
                opens.incrementAndGet()
                fakeSession(closes)
            }

        val first = broker.acquire()
        val second = broker.acquire()
        assertEquals(2, opens.get())
        assertEquals(1L, first.generation)
        assertEquals(1L, second.generation)

        first.reportSessionLoss()
        first.reportSessionLoss()
        first.close()
        first.close()
        second.close()
        assertEquals(2, closes.get())
    }

    @Test
    fun `replacement borrow waits strictly after rejected backend generation`() = runTest {
        val observedFences = mutableListOf<Long?>()
        val reportedLosses = mutableListOf<Long>()
        val closes = AtomicInteger()
        val broker =
            SteamContentSdkSessionBroker(
                testBorrow = { afterGeneration ->
                    observedFences += afterGeneration
                    fakeSession(closes) to if (afterGeneration == null) 7L else 8L
                },
                reportLoss = reportedLosses::add,
            )

        val failed = broker.acquire()
        failed.reportSessionLoss()
        val replacement = broker.acquire(afterGeneration = failed.generation)

        assertEquals(listOf(null, 7L), observedFences)
        assertEquals(listOf(7L), reportedLosses)
        assertEquals(8L, replacement.generation)
        failed.close()
        replacement.close()
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
