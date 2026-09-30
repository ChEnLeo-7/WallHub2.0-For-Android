package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
