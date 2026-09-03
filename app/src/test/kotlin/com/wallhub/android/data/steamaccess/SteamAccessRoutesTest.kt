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
}
