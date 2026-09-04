package com.wallhub.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCdnHttpsTest {
    @Test
    fun optionalCdnRouteUsesSecureFallback() {
        val url =
            buildSteamCdnCommand(
                server = CdnServer("cdn.example.test", "cdn.example.test", 80, false),
                command = "depot/1/manifest/2/5",
                query = null,
            )

        assertEquals("https", url.scheme)
        assertEquals(443, url.port)
    }

    @Test
    fun cdnRequestUsesVirtualHostForItsUrl() {
        val url =
            buildSteamCdnCommand(
                server =
                    CdnServer(
                        host = "cdn-identity.example.test",
                        vHost = "cdn.example.test",
                        port = 443,
                        https = true,
                    ),
                command = "depot/1/manifest/2/5",
                query = "token=abc",
            )

        assertEquals("https", url.scheme)
        assertEquals("cdn.example.test", url.host)
        assertEquals("/depot/1/manifest/2/5", url.encodedPath)
        assertEquals("token=abc", url.query)
    }

    @Test
    fun cdnProxyUsesOriginHostAndPathInProxyTemplate() {
        val url =
            buildSteamCdnCommand(
                server = CdnServer("origin.example.test", "origin-vhost.example.test", 443, true),
                command = "depot/1/chunk/abcd",
                query = "auth=xyz",
                proxyServer =
                    CdnServer(
                        host = "proxy.example.test",
                        vHost = "proxy-vhost.example.test",
                        port = 443,
                        https = true,
                        useAsProxy = true,
                        proxyRequestPathTemplate = "/proxy/%host%%path%",
                    ),
            )

        assertEquals("https", url.scheme)
        assertEquals("proxy-vhost.example.test", url.host)
        assertEquals("/proxy/origin-vhost.example.test/depot/1/chunk/abcd", url.encodedPath)
        assertEquals("auth=xyz", url.query)
    }

    @Test
    fun cdnAuthUsesServerHostBeforeVirtualHost() {
        assertEquals(
            "cdn-identity.example.test",
            resolveCdnAuthHost("cdn-identity.example.test", "cdn.example.test"),
        )
        assertEquals(
            "cdn.example.test",
            resolveCdnAuthHost(null, "cdn.example.test"),
        )
    }
}
