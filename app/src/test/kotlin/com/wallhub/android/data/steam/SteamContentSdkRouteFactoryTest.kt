package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SteamContentSdkRouteFactoryTest {
    @Test
    fun `an HTTP proxy URI maps structurally to the SDK authority`() {
        assertEquals("proxy.example:8080", sdkHttpProxyAuthority(" http://proxy.example:8080/ "))
        assertEquals("proxy.example:80", sdkHttpProxyAuthority("http://proxy.example"))
    }

    @Test
    fun `HTTPS and SOCKS proxy URIs are rejected rather than downgraded`() {
        listOf(
            "https://proxy.example:8443",
            "socks://proxy.example:1080",
            "socks5://proxy.example:1080",
        ).forEach { proxyUri ->
            assertFailsWith<IllegalArgumentException>(proxyUri) {
                sdkHttpProxyAuthority(proxyUri)
            }
        }
    }

    @Test
    fun `a malformed or credentialed proxy URI is rejected`() {
        listOf(
            "proxy.example:8080",
            "http://user:password@proxy.example:8080",
            "http://proxy.example:8080/path",
            "http://proxy.example:8080?token=secret",
        ).forEach { proxyUri ->
            assertFailsWith<IllegalArgumentException>(proxyUri) {
                sdkHttpProxyAuthority(proxyUri)
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
    fun `the platform acceleration bridge applies only to CDN traffic`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = null,
                acceleratedCdnProxy = "127.0.0.1:32123",
                acceleratedTrustRootPem = "private-root",
            )

        assertEquals("127.0.0.1:32123", route.cdnProxy)
        assertNull(route.cmProxy)
        assertNull(route.cmProxyAuthorization)
        assertEquals("private-root", route.extraTrustRootPem)
    }

    @Test
    fun `a configured proxy takes precedence over the platform acceleration bridge`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = "http://proxy.example:3128",
                acceleratedCdnProxy = "127.0.0.1:32123",
                acceleratedTrustRootPem = "private-root",
            )

        assertEquals("proxy.example:3128", route.cdnProxy)
        assertEquals("proxy.example:3128", route.cmProxy)
        assertNull(route.extraTrustRootPem)
    }

    @Test
    fun `the supported HTTP proxy applies to both CDN and CM traffic without credentials`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cm1.steamserver.net:443",
                configuredProxyUri = "http://proxy.example:3128",
            )

        assertEquals("proxy.example:3128", route.cdnProxy)
        assertEquals("proxy.example:3128", route.cmProxy)
        assertNull(route.cmProxyAuthorization)
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
}
