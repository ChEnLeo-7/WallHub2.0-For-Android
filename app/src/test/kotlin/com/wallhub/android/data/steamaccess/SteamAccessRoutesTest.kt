package com.wallhub.android.data.steamaccess

import com.wallhub.android.data.steam.OkHttpSteamWebSocketConnection
import com.wallhub.android.data.steam.SteamWebSocketServerListProvider
import com.wallhub.android.data.steam.createSteamConfiguration
import com.wallhub.android.data.steam.createSteamDirectoryClient
import com.wallhub.android.data.steam.steamWebSocketUrl
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.ServerRecord
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.EnumSet
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

    @Test
    fun `configuration uses application okhttp client for CM websockets`() {
        val configuration =
            createSteamConfiguration(
                directoryClient = createSteamDirectoryClient(),
                serverListProvider = SteamWebSocketServerListProvider(),
            )

        val connection =
            configuration.connectionFactory.createConnection(
                configuration,
                EnumSet.of(ProtocolTypes.WEB_SOCKET),
            )

        assertTrue(connection is OkHttpSteamWebSocketConnection)
    }

    @Test
    fun `CM websocket URL keeps discovered host port and path`() {
        val endpoint = InetSocketAddress.createUnresolved("cmp1-sea1.steamserver.net", 443)

        assertEquals(
            "https://cmp1-sea1.steamserver.net/cmsocket/",
            steamWebSocketUrl(endpoint).toString(),
        )
    }

    @Test
    fun `server cache keeps websocket endpoints only`() {
        val provider = SteamWebSocketServerListProvider()
        provider.updateServerList(
            listOf(
                ServerRecord.createWebSocketServer("cmp1-sea1.steamserver.net:443"),
                ServerRecord.createServer("127.0.0.1", 27017, ProtocolTypes.TCP),
            ),
        )

        assertEquals(1, provider.fetchServerList().size)
        assertEquals("cmp1-sea1.steamserver.net", provider.fetchServerList().single().host)
    }
}
