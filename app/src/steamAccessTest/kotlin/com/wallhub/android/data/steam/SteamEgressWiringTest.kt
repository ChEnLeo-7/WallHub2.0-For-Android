package com.wallhub.android.data.steam

import com.wallhub.android.data.steamaccess.DOH_PRODUCTION_INITIAL_ENDPOINTS
import com.wallhub.android.data.steamaccess.DOH_PRODUCTION_MAX_ENDPOINTS
import com.wallhub.android.data.steamaccess.SteamProxyEndpoint
import com.wallhub.android.data.steamaccess.SteamProxyKind
import com.wallhub.android.data.steamaccess.steamDohStages
import com.wallhub.android.data.steamaccess.steamProxyEndpointOrNull
import io.steamcontent.sdk.SessionTransportKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SDK seam and the DoH budget. Both are wiring decisions that used to be re-derived outside the
 * plan: a configured proxy must reach both SDK planes, an unrepresentable one must not become direct,
 * and a production request must not fan out across the whole DoH catalog.
 */
class SteamEgressWiringTest {
    private val bridge = "127.0.0.1:45678"

    @Test
    fun `a planned proxy replaces both sdk planes and drops the bridge trust root`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cmp1-sea1.steamserver.net:443",
                configuredProxyUri = "http://10.0.0.5:8080",
                authenticatedCdnProxy = bridge,
                authenticatedCdnProxyAuthorization = "Basic bridge",
                enhancedCmProxy = bridge,
                enhancedCmProxyAuthorization = "Basic bridge",
                enhancedTrustRootPem = "PEM",
                cmTransport = SessionTransportKind.WEBSOCKET,
            )

        assertEquals("http://10.0.0.5:8080", route.cmProxy)
        assertEquals("http://10.0.0.5:8080", route.cdnProxy)
        assertNull(route.cmProxyAuthorization)
        assertNull(route.cdnProxyAuthorization)
        assertNull(route.extraTrustRootPem)
        assertEquals(SessionTransportKind.WEBSOCKET, route.transport)
    }

    @Test
    fun `a bridged plan gives both sdk planes the bridge and the scoped trust root`() {
        val route =
            sdkPlatformRoute(
                cmCandidates = "cmp1-sea1.steamserver.net:443",
                configuredProxyUri = null,
                authenticatedCdnProxy = bridge,
                authenticatedCdnProxyAuthorization = "Basic bridge",
                enhancedCmProxy = bridge,
                enhancedCmProxyAuthorization = "Basic cm",
                enhancedTrustRootPem = "PEM",
                cmTransport = SessionTransportKind.WEBSOCKET,
            )

        assertEquals(bridge, route.cmProxy)
        assertEquals(bridge, route.cdnProxy)
        assertEquals("Basic cm", route.cmProxyAuthorization)
        assertEquals("Basic bridge", route.cdnProxyAuthorization)
        assertEquals("PEM", route.extraTrustRootPem)
    }

    @Test
    fun `socks keeps its scheme and gains the documented default port`() {
        assertEquals("socks5://127.0.0.1:1080", sdkProxyUri("socks5://127.0.0.1"))
        assertEquals("https://proxy.local:443", sdkProxyUri("https://proxy.local"))
    }

    @Test
    fun `a proxy url with credentials is rejected rather than stripped`() {
        assertFailsWith<IllegalArgumentException> { sdkProxyUri("http://user:secret@proxy.local:8080") }
    }

    @Test
    fun `the user proxy parses into the same endpoint the plan carries`() {
        assertEquals(
            SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080),
            steamProxyEndpointOrNull("http://10.0.0.5:8080"),
        )
        assertEquals(
            SteamProxyEndpoint(SteamProxyKind.SOCKS, "fd00::1", 1080),
            steamProxyEndpointOrNull("socks5://[fd00::1]:1080"),
        )
        assertNull(steamProxyEndpointOrNull(" "))
        assertNull(steamProxyEndpointOrNull("http://user:pass@10.0.0.5:8080"))
        assertNull(steamProxyEndpointOrNull("ftp://10.0.0.5:21"))
    }

    @Test
    fun `production doh resolution starts with two endpoints and never exceeds the budget`() {
        val configured = (1..10).map { "https://doh$it.example/dns-query" }

        val stages = steamDohStages(configured)

        assertEquals(DOH_PRODUCTION_INITIAL_ENDPOINTS, stages.first().size)
        assertEquals(DOH_PRODUCTION_MAX_ENDPOINTS, stages.flatten().size)
        assertEquals(stages.flatten().distinct().size, stages.flatten().size)
        assertTrue(stages.first() == configured.take(2))
    }
}
