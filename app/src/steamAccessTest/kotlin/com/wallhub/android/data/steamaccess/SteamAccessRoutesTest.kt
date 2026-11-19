package com.wallhub.android.data.steamaccess

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused migration of the still-valid route assertions. The pre-migration file also asserted the
 * removed javasteam configuration surface; those tests were retired with that surface and are not
 * silently re-enabled here.
 */
class SteamAccessRoutesTest {
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
    fun `a throwing selector degrades to direct instead of failing every request`() {
        val selector =
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = throw IllegalStateException("selector exploded")

                override fun connectFailed(
                    uri: URI?,
                    sa: SocketAddress?,
                    ioe: IOException?,
                ) = Unit
            }

        assertEquals(listOf(Proxy.NO_PROXY), selector.safeSelect(URI("https://api.steampowered.com")))
    }

    @Test
    fun `only web and cm authorities are accelerated`() {
        assertTrue(SteamDomainPolicy.accelerates("steamcommunity.com"))
        assertTrue(SteamDomainPolicy.accelerates("api.steampowered.com"))
        assertTrue(SteamDomainPolicy.accelerates("cmp1-sea1.steamserver.net"))
        assertFalse(SteamDomainPolicy.accelerates("cdn.steamcontent.com"))
        assertFalse(SteamDomainPolicy.accelerates("avatars.fastly.steamstatic.com"))
        assertFalse(SteamDomainPolicy.accelerates("github.com"))
    }

    @Test
    fun `a core host is only accelerated on https while cm accepts its steam port range`() {
        assertTrue(SteamDomainPolicy.supportsEndpoint("api.steampowered.com", 443))
        assertFalse(SteamDomainPolicy.supportsEndpoint("api.steampowered.com", 80))
        assertTrue(SteamDomainPolicy.supportsEndpoint("cmp1-sea1.steamserver.net", 27017))
        assertTrue(SteamDomainPolicy.supportsEndpoint("cmp1-sea1.steamserver.net", 443))
        assertFalse(SteamDomainPolicy.supportsEndpoint("cmp1-sea1.steamserver.net", 27016))
        assertFalse(SteamDomainPolicy.supportsEndpoint("steamcommunity.com", 6333))
    }

    @Test
    fun `web routes keep the original authority first and only add allowlisted upstreams`() {
        val routes = SteamAccessRoutes.webUpstreams("steamcommunity.com")
        assertEquals(2, routes.size)
        assertEquals("steamcommunity.com", routes.first().httpAuthority)
        assertEquals("steamcommunity-a.akamaihd.net", routes[1].resolveAuthority)
        assertEquals(listOf("steamcommunity-a.akamaihd.net"), SteamAccessRoutes.aliases("steamcommunity.com"))
        assertTrue(SteamAccessRoutes.webUpstreams("cdn.steamcontent.com").isEmpty())
    }

    @Test
    fun `hosts parsing keeps only supported authorities and literal addresses`() {
        val parsed =
            SteamHostsParser.parse(
                """
                # comment
                203.0.113.7 steamcommunity.com cdn.steamcontent.com
                not-an-ip api.steampowered.com
                198.51.100.9 cmp1-sea1.steamserver.net
                """.trimIndent(),
            )
        assertEquals(setOf("steamcommunity.com", "cmp1-sea1.steamserver.net"), parsed.keys)
    }
}
