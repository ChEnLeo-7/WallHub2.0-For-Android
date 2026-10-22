package com.wallhub.android.data.steamaccess

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SteamRequestRoutingTest {
    @Test fun `cache expires on demand and rejects obsolete generation`() {
        var now = 0L
        val cache = SteamRequestRouteCache { now }
        val addresses = listOf(InetAddress.getByName("8.8.8.8"))
        assertTrue(cache.put("host", 0, addresses))
        assertEquals(addresses, cache.get("host", 0))
        now = 60_000
        assertNull(cache.get("host", 0))
        cache.invalidate()
        assertFalse(cache.put("host", 0, addresses))
        assertNull(cache.get("host", 1))
        assertTrue(cache.put("host", 1, addresses))
        cache.remove("host", 0)
        assertEquals(addresses, cache.get("host", 1))
    }

    @Test fun `cache is bounded and does not cache empty resolution`() {
        val cache = SteamRequestRouteCache { 0 }
        val addresses = listOf(InetAddress.getByName("8.8.8.8"))
        repeat(65) { cache.put("host$it", 0, addresses) }
        assertNull(cache.get("host0", 0))
        assertEquals(addresses, cache.get("host64", 0))
        cache.put("empty", 0, emptyList())
        assertNull(cache.get("empty", 0))
    }

    @Test fun `candidate history cannot inject old seeds and cooling excludes failed answers`() {
        val live = InetAddress.getByName("8.8.8.8")
        assertTrue(selectSteamCandidates(emptyList(), emptyList(), setOf("1.1.1.1"), emptySet()).isEmpty())
        assertTrue(selectSteamCandidates(listOf(live), listOf(live), emptySet(), setOf("8.8.8.8")).isEmpty())
        assertEquals(listOf(live), selectSteamCandidates(listOf(live), listOf(live), emptySet(), emptySet()))
        assertTrue(selectSteamCandidates(listOf(InetAddress.getLoopbackAddress()), emptyList(), emptySet(), emptySet()).isEmpty())
    }

    @Test fun `relay flushes each chunk before waiting for more input`() {
        val destination = ByteArrayOutputStream()
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = error("bulk only")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (reads++ == 0) { bytes[offset] = 42; return 1 }
                assertEquals(listOf<Byte>(42), destination.toByteArray().toList())
                return -1
            }
        }
        assertNull(forwardSteamBytes(source, BufferedOutputStream(destination)))
    }

    @Test fun `relay attributes only input failure not output cancellation or normal EOF`() {
        val failure = IOException("upstream")
        val source = object : InputStream() { override fun read(): Int = throw failure }
        assertSame(failure, forwardSteamBytes(source, ByteArrayOutputStream()))
        val canceled = object : OutputStream() { override fun write(value: Int) { throw IOException("cancel") } }
        assertNull(forwardSteamBytes(ByteArrayInputStream(byteArrayOf(1)), canceled))
        assertNull(forwardSteamBytes(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream()))
        assertSame(failure, forwardSteamBytes(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), onEof = { failure }))
    }

    @Test fun `response observer accepts split status only once and rejects malformed traffic`() {
        var responses = 0
        val observer = SteamResponseStatusObserver { responses++ }
        listOf("HTTP/1.1 2", "00 OK\r", "\nsecret-header: value\r\n").forEach {
            observer.accept(it.toByteArray(), it.length)
        }
        assertTrue(observer.receivedResponse)
        assertEquals(1, responses)
        val malformed = SteamResponseStatusObserver { error("not HTTP") }
        val bytes = "not HTTP\r\n".toByteArray()
        malformed.accept(bytes, bytes.size)
        assertFalse(malformed.receivedResponse)
    }
}
