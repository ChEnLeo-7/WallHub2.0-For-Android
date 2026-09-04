package com.wallhub.android.data.steamaccess

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamAccessRoutesTest {
    @Test
    fun `gateway supports exact core services and CM subdomains only`() {
        assertTrue(SteamAccessRoutes.supports("steamcommunity.com"))
        assertTrue(SteamAccessRoutes.supports("www.steamcommunity.com"))
        assertTrue(SteamAccessRoutes.supports("api.steampowered.com"))
        assertTrue(SteamAccessRoutes.supports("community.steam-api.com"))
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
                    "Proxy-Authorization: Basic test-token\r\n\r\n",
            )

        assertEquals("steamcommunity.com", request.host)
        assertEquals(443, request.port)
        assertEquals("Basic test-token", request.authorization)
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
    fun `ksteam route prewarming covers secure steam and CM URLs only`() {
        assertTrue(shouldPrewarmSteamUrl("https", "api.steampowered.com", 443))
        assertTrue(shouldPrewarmSteamUrl("wss", "cmp1-sea1.steamserver.net", 27020))
        assertFalse(shouldPrewarmSteamUrl("ws", "cmp1-sea1.steamserver.net", 27020))
        assertFalse(shouldPrewarmSteamUrl("wss", "evil.steamcommunity.com", 27020))
        assertFalse(shouldPrewarmSteamUrl("wss", "cmp1-sea1.steamserver.net", 8080))
    }

    @Test
    fun `steam bridge recognizes websocket TLS scheme`() {
        assertTrue(isSteamSecureScheme("https"))
        assertTrue(isSteamSecureScheme("WSS"))
        assertFalse(isSteamSecureScheme("http"))
        assertFalse(isSteamSecureScheme("ws"))
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
