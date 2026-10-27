package com.wallhub.android.data.steam

import com.wallhub.android.data.steamaccess.STEAM_SYSTEM_PROXY_TARGETS
import com.wallhub.android.data.steamaccess.resolveSteamSystemProxy
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import io.steamcontent.sdk.SessionTransportKind
import kotlin.test.assertTrue

class SteamContentSdkRouteFactoryTest {
    @Test
    fun `supported proxy URIs retain their scheme and receive scheme defaults`() {
        assertEquals("http://proxy.example:80", sdkProxyUri(" HTTP://Proxy.Example/ "))
        assertEquals("https://proxy.example:443", sdkProxyUri("https://proxy.example"))
        assertEquals("socks://proxy.example:1080", sdkProxyUri("socks://proxy.example/"))
        assertEquals("socks5://proxy.example:1080", sdkProxyUri("socks5://proxy.example"))
        assertEquals("https://proxy.example:8443", sdkProxyUri("https://proxy.example:8443"))
    }

    @Test
    fun `IPv6 proxy URIs stay bracketed and keep explicit or default ports`() {
        assertEquals("http://[2001:db8::1]:8080", sdkProxyUri("http://[2001:DB8::1]:8080/"))
        assertEquals("https://[2001:db8::2]:443", sdkProxyUri("https://[2001:DB8::2]"))
        assertEquals("socks5://[2001:db8::3]:1080", sdkProxyUri("socks5://[2001:DB8::3]"))
    }

    @Test
    fun `a malformed credentialed or decorated proxy URI is rejected`() {
        listOf(
            "proxy.example:8080",
            "ftp://proxy.example:21",
            "http://user:password@proxy.example:8080",
            "https://proxy.example:8443/path",
            "socks://proxy.example:1080?token=secret",
            "socks5://proxy.example:1080#fragment",
        ).forEach { proxyUri ->
            assertFailsWith<IllegalArgumentException>(proxyUri) {
                sdkProxyUri(proxyUri)
            }
        }
    }

    @Test
    fun `no configured proxy leaves both SDK routes direct`() {
        val route = sdkPlatformRoute("cm1.steamserver.net:443", configuredProxyUri = null)

        assertNull(route.cdnProxy)
        assertNull(route.cmProxy)
        assertNull(route.cmProxyAuthorization)
    }

    @Test
    fun `the authenticated platform bridge applies only to CDN traffic`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = null,
                authenticatedCdnProxy = "127.0.0.1:32123",
                authenticatedCdnProxyAuthorization = "Basic secret",
            )

        assertEquals("127.0.0.1:32123", route.cdnProxy)
        assertEquals("Basic secret", route.cdnProxyAuthorization)
        assertNull(route.cmProxy)
        assertNull(route.cmProxyAuthorization)
    }

    @Test
    fun `the internal enhanced route covers both CM and CDN with route-scoped trust`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = null,
                authenticatedCdnProxy = "127.0.0.1:32123",
                authenticatedCdnProxyAuthorization = "Basic cdn",
                enhancedCmProxy = "127.0.0.1:32123",
                enhancedCmProxyAuthorization = "Basic cm",
                enhancedTrustRootPem = "-----BEGIN CERTIFICATE-----\nroute\n-----END CERTIFICATE-----\n",
            )

        assertEquals("127.0.0.1:32123", route.cdnProxy)
        assertEquals("Basic cdn", route.cdnProxyAuthorization)
        assertEquals("127.0.0.1:32123", route.cmProxy)
        assertEquals("Basic cm", route.cmProxyAuthorization)
        assertTrue(route.extraTrustRootPem.orEmpty().contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun `direct account WSS keeps CM off the HTTP bridge while CDN remains enhanced`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cmp1-hkg1.steamserver.net:443",
                configuredProxyUri = null,
                authenticatedCdnProxy = "127.0.0.1:32123",
                authenticatedCdnProxyAuthorization = "Basic cdn",
                enhancedTrustRootPem = "-----BEGIN CERTIFICATE-----\nroute\n-----END CERTIFICATE-----\n",
                cmTransport = SessionTransportKind.WEBSOCKET,
            )

        assertEquals(SessionTransportKind.WEBSOCKET, route.transport)
        assertNull(route.cmProxy)
        assertEquals("127.0.0.1:32123", route.cdnProxy)
        assertTrue(route.extraTrustRootPem.orEmpty().contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun `the configured proxy URI applies intact to both CDN and CM without bridge credentials`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = "socks5://[2001:db8::4]",
                authenticatedCdnProxy = "127.0.0.1:32123",
                authenticatedCdnProxyAuthorization = "Basic cdn",
                enhancedCmProxy = "http://127.0.0.1:32123",
                enhancedCmProxyAuthorization = "Basic cm",
                enhancedTrustRootPem = "-----BEGIN CERTIFICATE-----\nroute\n-----END CERTIFICATE-----\n",
            )

        assertEquals("socks5://[2001:db8::4]:1080", route.cdnProxy)
        assertEquals("socks5://[2001:db8::4]:1080", route.cmProxy)
        assertNull(route.cdnProxyAuthorization)
        assertNull(route.cmProxyAuthorization)
        assertNull(route.extraTrustRootPem)
    }

    @Test
    fun `legacy internal bridge routes remain authority or explicit HTTP URI`() {
        val authorityRoute =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = null,
                authenticatedCdnProxy = "127.0.0.1:32123",
                enhancedCmProxy = "127.0.0.1:32123",
            )
        val uriRoute =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = null,
                authenticatedCdnProxy = "http://127.0.0.1:32123",
                enhancedCmProxy = "http://127.0.0.1:32123",
            )

        assertEquals("127.0.0.1:32123", authorityRoute.cdnProxy)
        assertEquals("127.0.0.1:32123", authorityRoute.cmProxy)
        assertEquals("http://127.0.0.1:32123", uriRoute.cdnProxy)
        assertEquals("http://127.0.0.1:32123", uriRoute.cmProxy)
    }

    @Test
    fun `representable system HTTP proxy applies to both SDK traffic planes`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
        val effective = resolveSteamSystemProxy(proxySelector { listOf(proxy) }, STEAM_SYSTEM_PROXY_TARGETS)

        val route = sdkPlatformRoute("cm1.steamserver.net:443", effective.sdkProxyUri)

        assertTrue(effective.representable)
        assertEquals("http://proxy.example:8080", route.cdnProxy)
        assertEquals("http://proxy.example:8080", route.cmProxy)
        assertNull(route.cdnProxyAuthorization)
        assertNull(route.cmProxyAuthorization)
    }

    @Test
    fun `system proxy bypass is rejected when SDK planes do not share one representable egress`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
        val effective =
            resolveSteamSystemProxy(
                proxySelector { uri -> if (uri.host.startsWith("cm")) listOf(Proxy.NO_PROXY) else listOf(proxy) },
                STEAM_SYSTEM_PROXY_TARGETS,
            )

        assertTrue(!effective.representable)
        assertNull(effective.sdkProxyUri)
    }

    @Test
    fun `connect attempts equal the bounded supplied CM candidate count`() {
        val route =
            sdkPlatformRoute(
                cmCandidates =
                    """
                    cm1.steamserver.net:443
                    cm2.steamserver.net:443
                    cm3.steamserver.net:443
                    cm4.steamserver.net:443
                    """.trimIndent(),
                configuredProxyUri = null,
            )

        assertEquals(4, route.connectAttempts)
    }

    private fun proxySelector(select: (URI) -> List<Proxy>): ProxySelector =
        object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = select(uri)

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
}
