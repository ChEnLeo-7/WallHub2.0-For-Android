package com.wallhub.android.data.steamaccess

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamAccessRoutesTest {
    @Test
    fun `community web routes preserve HTTP authority while authenticating each upstream authority`() {
        assertEquals(
            listOf(
                SteamWebUpstreamRule(
                    resolveAuthority = "steamcommunity.com",
                    tlsAuthority = "steamcommunity.com",
                    httpAuthority = "steamcommunity.com",
                ),
                SteamWebUpstreamRule(
                    resolveAuthority = "steamcommunity-a.akamaihd.net",
                    tlsAuthority = "steamcommunity-a.akamaihd.net",
                    httpAuthority = "steamcommunity.com",
                ),
            ),
            SteamAccessRoutes.webUpstreams("STEAMCOMMUNITY.COM."),
        )
        assertTrue(SteamAccessRoutes.webUpstreams("evil.steamcommunity.com").isEmpty())
    }

    @Test
    fun `Workshop image routes retain original Host and authenticate the image upstream`() {
        assertEquals(
            listOf(
                SteamWebUpstreamRule(
                    resolveAuthority = "images.steamusercontent.com",
                    tlsAuthority = "images.steamusercontent.com",
                    httpAuthority = "images.steamusercontent.com",
                ),
                SteamWebUpstreamRule(
                    resolveAuthority = "steamuserimages-a.akamaihd.net",
                    tlsAuthority = "steamuserimages-a.akamaihd.net",
                    httpAuthority = "images.steamusercontent.com",
                ),
            ),
            SteamAccessRoutes.webUpstreams("IMAGES.STEAMUSERCONTENT.COM."),
        )
    }

    @Test
    fun `gateway supports exact core services and CM subdomains only`() {
        assertTrue(SteamAccessRoutes.supports("steamcommunity.com"))
        assertTrue(SteamAccessRoutes.supports("www.steamcommunity.com"))
        assertTrue(SteamAccessRoutes.supports("api.steampowered.com"))
        assertTrue(SteamAccessRoutes.supports("community.steam-api.com"))
        assertTrue(SteamAccessRoutes.supports("images.steamusercontent.com"))
        assertFalse(SteamAccessRoutes.supports("images.steamusercontent.com.evil.example"))
        assertFalse(SteamAccessRoutes.supports("evil.steamcommunity.com"))
        assertFalse(SteamAccessRoutes.supports("steamuserimages-a.akamaihd.net"))
        assertFalse(SteamAccessRoutes.supports("cache1.steamcontent.com"))
        assertTrue(SteamAccessRoutes.supports("cmp1-sea1.steamserver.net"))
        assertFalse(SteamAccessRoutes.supports("steamserver.net"))
        assertFalse(SteamAccessRoutes.supports(".steamserver.net"))
        assertFalse(SteamAccessRoutes.supports("evilsteamserver.net"))
    }

    @Test
    fun `hosts parser accepts valid steam ipv4 and ipv6 entries only`() {
        val result =
            SteamHostsParser.parse(
                """
                23.44.248.222 steamcommunity.com unrelated.example
                2600:1406:3a00::17d5:2a65 api.steampowered.com
                invalid community.steam-api.com
                """.trimIndent(),
            )

        assertEquals(listOf("23.44.248.222"), result.getValue("steamcommunity.com").mapNotNull { it.hostAddress })
        assertEquals(1, result.getValue("api.steampowered.com").size)
        assertFalse("unrelated.example" in result)
    }

    @Test
    fun `loopback connect parser preserves auth and rejects non connect traffic`() {
        val request =
            parseSteamConnectRequest(
                "CONNECT steamcommunity.com:443 HTTP/1.1\r\n" +
                    "Proxy-Authorization: Basic test-token\r\n" +
                    "X-Steam-Content-TLS-Server-Name: cdn.example\r\n\r\n",
            )

        assertEquals("steamcommunity.com", request.host)
        assertEquals(443, request.port)
        assertEquals("Basic test-token", request.authorization)
        assertEquals("cdn.example", request.tlsServerName)
        assertFailsWith<java.io.IOException> {
            parseSteamConnectRequest("GET https://steamcommunity.com HTTP/1.1\r\n\r\n")
        }
        assertEquals(
            27020,
            parseSteamConnectRequest("CONNECT cmp1-sgp1.steamserver.net:27020 HTTP/1.1\r\n\r\n").port,
        )
        assertFailsWith<java.io.IOException> {
            parseSteamConnectRequest("CONNECT cmp1-sgp1.steamserver.net:0 HTTP/1.1\r\n\r\n")
        }
        assertFailsWith<java.io.IOException> {
            parseSteamConnectRequest("CONNECT cmp1-sgp1.steamserver.net:65536 HTTP/1.1\r\n\r\n")
        }
    }

    @Test
    fun `authenticated CDN tunnel accepts directory hostnames on HTTPS only`() {
        assertEquals("st.dl.eccdnx.com", SteamDomainPolicy.requireAuthenticatedCdnEndpoint("ST.DL.ECCDNX.COM.", 443))
        assertFailsWith<IllegalArgumentException> { SteamDomainPolicy.requireAuthenticatedCdnEndpoint("127.0.0.1", 443) }
        assertFailsWith<IllegalArgumentException> { SteamDomainPolicy.requireAuthenticatedCdnEndpoint("metadata", 443) }
        assertFailsWith<IllegalArgumentException> { SteamDomainPolicy.requireAuthenticatedCdnEndpoint("cdn.example", 80) }
    }

    @Test
    fun `authenticated CDN tunnel rejects local addresses but accepts VPN fake IP`() {
        assertTrue(isAuthenticatedCdnAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isAuthenticatedCdnAddress(InetAddress.getByName("198.18.4.168")))
        listOf(
            "127.0.0.1", "10.0.0.1", "169.254.1.1", "192.168.1.1", "100.64.0.1",
            "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.1.1",
        ).forEach { address ->
            assertFalse(isAuthenticatedCdnAddress(InetAddress.getByName(address)), address)
        }
    }

    @Test
    fun `private route policy rejects lookalike hosts and normalizes exact hosts`() {
        assertEquals("steamcommunity.com", SteamDomainPolicy.requireSupported("STEAMCOMMUNITY.COM."))
        assertEquals(
            "cmp1-sea1.steamserver.net",
            SteamDomainPolicy.requireSupported("CMP1-SEA1.STEAMSERVER.NET."),
        )
        assertEquals("/cmsocket/", SteamDomainPolicy.probePath("cmp1-sea1.steamserver.net"))
        assertFailsWith<IllegalArgumentException> {
            SteamDomainPolicy.requireSupported("evil.steamcommunity.com")
        }
        assertTrue(SteamDomainPolicy.supportsEndpoint("cmp1-sgp1.steamserver.net", 27017))
        assertTrue(SteamDomainPolicy.supportsEndpoint("cmp1-sgp1.steamserver.net", 27050))
        assertFalse(SteamDomainPolicy.supportsEndpoint("cmp1-sgp1.steamserver.net", 27016))
        assertFalse(SteamDomainPolicy.supportsEndpoint("cmp1-sgp1.steamserver.net", 27051))
        assertFalse(SteamDomainPolicy.supportsEndpoint("steamcommunity.com", 27020))
    }

    @Test
    fun `system proxy selection is preserved outside the loopback bridge`() {
        val expected = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 7890))
        val selector =
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(expected)

                override fun connectFailed(
                    uri: URI?,
                    sa: SocketAddress?,
                    ioe: IOException?,
                ) = Unit
            }

        assertEquals(listOf(expected), selector.safeSelect(URI("https://cmp1-sea1.steamserver.net")))
        assertEquals(listOf(Proxy.NO_PROXY), null.safeSelect(URI("https://cmp1-sea1.steamserver.net")))
    }

    @Test
    fun `steam bridge recognizes websocket TLS scheme`() {
        assertTrue(isSteamSecureScheme("https"))
        assertTrue(isSteamSecureScheme("WSS"))
        assertFalse(isSteamSecureScheme("http"))
        assertFalse(isSteamSecureScheme("ws"))
    }

    @Test
    fun `cm login connections use enhanced routing and prefer ipv4 addresses`() {
        assertTrue(SteamDomainPolicy.accelerates("cmp3-hkg1.steamserver.net"))
        assertTrue(SteamDomainPolicy.accelerates("cmp1-sgp1.steamserver.net"))
        assertTrue(SteamDomainPolicy.accelerates("steamcommunity.com"))
        assertTrue(SteamDomainPolicy.accelerates("api.steampowered.com"))
        assertFalse(SteamDomainPolicy.accelerates("evil.steamcommunity.com"))

        val ipv4Primary = InetAddress.getByName("103.28.54.102") as Inet4Address
        val ipv4Secondary = InetAddress.getByName("155.133.248.51") as Inet4Address
        val ipv6 = InetAddress.getByName("240e:900:0:1600::37")
        assertEquals(
            listOf<InetAddress>(ipv4Primary, ipv4Secondary),
            preferIpv4Addresses(listOf(ipv6, ipv4Primary, ipv6, ipv4Secondary)),
        )
        assertEquals(listOf<InetAddress>(ipv6), preferIpv4Addresses(listOf(ipv6)))
        assertTrue(preferIpv4Addresses(emptyList()).isEmpty())
    }

    @Test
    fun `system DNS candidates are used only when automatic anti blocking is disabled`() {
        val system = listOf(InetAddress.getByName("104.244.43.208"))
        var lookups = 0
        val lookup = {
            lookups++
            system
        }

        assertTrue(systemDnsCandidates(steamAccessEnabled = true, lookup).isEmpty())
        assertEquals(0, lookups)
        assertEquals(system, systemDnsCandidates(steamAccessEnabled = false, lookup))
        assertEquals(1, lookups)
    }

    @Test
    fun `selected DoH answers cannot be crowded out by four system DNS addresses`() {
        val system = (1..4).map { suffix -> InetAddress.getByName("31.13.94.$suffix") }
        val doh = InetAddress.getByName("23.60.96.245")

        val selected = selectSteamCandidates(system, listOf(doh), emptySet(), emptySet())

        assertEquals(doh.hostAddress, selected.first().hostAddress)
        assertTrue(selected.any { address -> address.hostAddress == doh.hostAddress })
        assertEquals(4, selected.size)
    }

    @Test
    fun `loopback connection admission is bounded without queueing slow clients`() {
        val executor = newBridgeConnectionExecutor()
        val release = CountDownLatch(1)
        try {
            repeat(executor.maximumPoolSize) { executor.execute { release.await() } }
            assertFailsWith<RejectedExecutionException> { executor.execute {} }
            assertEquals(0, executor.queue.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `route cache isolates health by port`() {
        assertEquals(
            "wifi|cmp1-sgp1.steamserver.net|27020",
            steamRouteCacheKey("wifi", "CMP1-SGP1.STEAMSERVER.NET.", 27020),
        )
        assertFalse(
            steamRouteCacheKey("wifi", "cmp1-sgp1.steamserver.net", 443) ==
                steamRouteCacheKey("wifi", "cmp1-sgp1.steamserver.net", 27020),
        )
    }
}
