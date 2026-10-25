package com.wallhub.android.data.downloads

import android.content.Context
import android.content.ContextWrapper
import com.wallhub.android.data.steam.SteamContentSdkEngine
import com.wallhub.android.data.steam.SteamDeviceSeedStore
import com.wallhub.android.data.steam.SteamSessionGeneration
import com.wallhub.android.data.steam.SteamSessionGenerationChangedException
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import sun.misc.Unsafe
import kotlin.test.assertFailsWith
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
    fun `stale borrowed generation never enters account control plane`() {
        val expected = SteamSessionGeneration(7L)
        val engine =
            SteamContentSdkEngine(
                context = contextWithoutAndroidRuntime(),
                deviceSeed = SteamDeviceSeedStore(File("build/test-generation-seed")),
            )
        val borrowed = borrowedSteamContentSdkSession(engine, expected.value)

        val failures =
            listOf(
                assertFailsWith<SteamSessionGenerationChangedException> {
                    borrowed.resolveWorkshop(itemId = 1L)
                },
                assertFailsWith<SteamSessionGenerationChangedException> {
                    borrowed.startDownload(
                        appId = 1L,
                        depotId = 2L,
                        manifestId = 3L,
                        destination = null,
                        chunkConcurrency = 1,
                    )
                },
                assertFailsWith<SteamSessionGenerationChangedException> {
                    borrowed.manifestFiles(appId = 1L, depotId = 2L, manifestId = 3L)
                },
                assertFailsWith<SteamSessionGenerationChangedException> {
                    borrowed.openRangeStream(
                        appId = 1L,
                        depotId = 2L,
                        filePath = "video.mp4",
                        manifestId = 3L,
                        cacheCapacityBytes = 1L,
                    )
                },
            )

        failures.forEach { failure ->
            assertEquals(expected, failure.expected)
            assertEquals(null, failure.actual)
        }
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

    private fun contextWithoutAndroidRuntime(): Context {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(ContextWrapper::class.java) as Context
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
