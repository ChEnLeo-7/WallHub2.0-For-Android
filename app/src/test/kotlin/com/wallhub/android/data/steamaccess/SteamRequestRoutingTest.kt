package com.wallhub.android.data.steamaccess

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.lang.reflect.Proxy
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SteamRequestRoutingTest {
    @Test
    fun `alias upstream is limited to anonymous public reads with the CONNECT authority`() {
        assertTrue(
            mayUseSteamWebAlias(
                connectAuthority = "steamcommunity.com",
                requestHead =
                    "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\n" +
                        "Host: steamcommunity.com\r\nAccept: text/html\r\nConnection: close\r\n\r\n",
            ),
        )
        assertTrue(
            mayUseSteamWebAlias(
                connectAuthority = "api.steampowered.com",
                requestHead =
                    "POST /ISteamRemoteStorage/GetPublishedFileDetails/v1/ HTTP/1.1\r\n" +
                        "Host: api.steampowered.com\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: 52\r\nConnection: close\r\n\r\n",
            ),
        )
        assertTrue(
            mayUseSteamWebAlias(
                connectAuthority = "images.steamusercontent.com",
                requestHead =
                    "GET /ugc/9540179765161089476/4DE4889B4F31581DD70DA81AC75C79BF6B9927F9/ HTTP/1.1\r\n" +
                        "Host: images.steamusercontent.com\r\n" +
                        "Accept: image/*\r\nConnection: close\r\n\r\n",
            ),
        )
        assertTrue(
            mayUseSteamWebAlias(
                connectAuthority = "images.steamusercontent.com",
                requestHead =
                    "GET /ugc/123/ABC/?imw=1920&imh=1920&ima=fit&letterbox=false HTTP/1.1\r\n" +
                        "Host: images.steamusercontent.com\r\nConnection: close\r\n\r\n",
            ),
        )
        listOf(
            "GET /sharedfiles/filedetails/?id=3799142774&sessionid=secret HTTP/1.1\r\nHost: steamcommunity.com\r\nConnection: close\r\n\r\n",
            "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\nHost: steamcommunity.com\r\n\r\n",
            "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\nHost: steamcommunity.com\r\nCookie: sessionid=secret\r\nConnection: close\r\n\r\n",
            "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\nHost: steamcommunity.com\r\nAuthorization: Bearer secret\r\nConnection: close\r\n\r\n",
            "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\nHost: steamcommunity.com\r\nx-webapi-key: secret\r\nConnection: close\r\n\r\n",
            "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\nHost: steamcommunity.com\r\nHost: api.steampowered.com\r\n\r\n",
            "GET https://api.steampowered.com/path HTTP/1.1\r\nHost: steamcommunity.com\r\n\r\n",
            "POST /sharedfiles/filedetails/ HTTP/1.1\r\nHost: steamcommunity.com\r\nContent-Length: 0\r\n\r\n",
        ).forEach { head ->
            assertFalse(mayUseSteamWebAlias("steamcommunity.com", head), head)
        }
        listOf(
            "GET /ugc/123/ABC/?token=secret HTTP/1.1\r\nHost: images.steamusercontent.com\r\nConnection: close\r\n\r\n",
            "GET /ugc/123/ABC/?imw=1920&imw=640 HTTP/1.1\r\nHost: images.steamusercontent.com\r\nConnection: close\r\n\r\n",
            "GET /profiles/123/avatar.jpg HTTP/1.1\r\nHost: images.steamusercontent.com\r\nConnection: close\r\n\r\n",
            "GET /ugc/123/ABC/ HTTP/1.1\r\nHost: images.steamusercontent.com\r\nCookie: secret=value\r\nConnection: close\r\n\r\n",
            "GET /ugc/123/ABC/ HTTP/1.1\r\nHost: images.steamusercontent.com\r\nAuthorization: Bearer secret\r\nConnection: close\r\n\r\n",
            "GET /ugc/123/ABC/ HTTP/1.1\r\nHost: images.steamusercontent.com\r\nx-webapi-key: secret\r\nConnection: close\r\n\r\n",
        ).forEach { head ->
            assertFalse(mayUseSteamWebAlias("images.steamusercontent.com", head), head)
        }
    }

    @Test
    fun `web target binding preserves TLS authority when original and alias share an address`() {
        val shared = InetAddress.getByName("8.8.8.8")
        val rules = SteamAccessRoutes.webUpstreams("steamcommunity.com")

        val targets =
            bindSteamWebTargets(
                rules = rules,
                addressesByAuthority =
                    mapOf(
                        "steamcommunity.com" to listOf(shared, InetAddress.getLoopbackAddress()),
                        "steamcommunity-a.akamaihd.net" to listOf(shared),
                    ),
            )

        assertEquals(2, targets.size)
        assertEquals(
            listOf("steamcommunity.com", "steamcommunity-a.akamaihd.net"),
            targets.map { it.rule.tlsAuthority },
        )
        assertTrue(targets.all { it.address == shared })
        assertFalse(targets[0].healthKey == targets[1].healthKey)
    }

    @Test
    fun `bridge exposes alias targets only to classified anonymous requests`() {
        val address = InetAddress.getByName("8.8.8.8")
        val targets =
            bindSteamWebTargets(
                SteamAccessRoutes.webUpstreams("steamcommunity.com"),
                mapOf(
                    "steamcommunity.com" to listOf(address),
                    "steamcommunity-a.akamaihd.net" to listOf(address),
                ),
            )
        val anonymousHead =
            "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\n" +
                "Host: steamcommunity.com\r\nConnection: close\r\n\r\n"
        val authenticatedHead =
            "GET /sharedfiles/filedetails/?id=3799142774 HTTP/1.1\r\n" +
                "Host: steamcommunity.com\r\nCookie: sessionid=secret\r\nConnection: close\r\n\r\n"

        assertEquals(2, steamWebTargetsForRequest("steamcommunity.com", anonymousHead, targets).size)
        assertEquals(
            listOf("steamcommunity.com"),
            steamWebTargetsForRequest("steamcommunity.com", authenticatedHead, targets)
                .map { it.rule.tlsAuthority },
        )
    }

    @Test
    fun `visible TLS uses and verifies the selected upstream authority rather than the HTTP authority`() {
        val target =
            SteamWebDialTarget(
                rule =
                    SteamWebUpstreamRule(
                        resolveAuthority = "steamcommunity-a.akamaihd.net",
                        tlsAuthority = "steamcommunity-a.akamaihd.net",
                        httpAuthority = "steamcommunity.com",
                    ),
                address = InetAddress.getByName("8.8.8.8"),
            )
        val parameters = visibleSteamTlsParameters(target)
        assertEquals(
            "steamcommunity-a.akamaihd.net",
            (parameters.serverNames.single() as SNIHostName).asciiName,
        )

        var verifiedAuthority: String? = null
        val verifier = HostnameVerifier { hostname, _ ->
            verifiedAuthority = hostname
            true
        }
        val session =
            Proxy.newProxyInstance(
                SSLSession::class.java.classLoader,
                arrayOf(SSLSession::class.java),
            ) { _, method, _ ->
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            } as SSLSession

        assertTrue(verifySteamTlsPeer(target, verifier, session))
        assertEquals("steamcommunity-a.akamaihd.net", verifiedAuthority)
        assertFalse(verifySteamTlsPeer(target, HostnameVerifier { _, _ -> false }, session))
    }

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

    @Test
    fun `candidate selection preserves success ranking and cooldown stays short`() {
        val first = InetAddress.getByName("8.8.8.8")
        val second = InetAddress.getByName("1.1.1.1")
        assertEquals(
            listOf(second, first),
            selectSteamCandidates(listOf(first, second), emptyList(), linkedSetOf("1.1.1.1", "8.8.8.8"), emptySet()),
        )
        assertEquals(10_000L, SteamAccessRouteStore.failureCooldown(1))
        assertEquals(30_000L, SteamAccessRouteStore.failureCooldown(2))
        assertEquals(60_000L, SteamAccessRouteStore.failureCooldown(100))
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
