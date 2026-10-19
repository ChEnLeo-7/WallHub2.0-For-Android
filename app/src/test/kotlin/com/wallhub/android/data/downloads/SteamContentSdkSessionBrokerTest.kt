package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
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
            SteamContentSdkSessionBroker(
                SteamContentSdkSessionOpener {
                    opens.incrementAndGet()
                    fakeSession(closes)
                },
            )
        val credential = SteamContentCredential("account", "token")

        val first = broker.acquire(credential)
        val second = broker.acquire(credential)
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
