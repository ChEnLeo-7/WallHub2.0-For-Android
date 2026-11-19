package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge has to serve the two schemes Steam actually advertises for CDN content.
 *
 * A Workshop manifest is fetched from the `http://` URL Steam's public details return, and the SDK sends
 * that through the loopback proxy as a plain CONNECT to port 80. The bridge only accepted an authenticated
 * TLS tunnel on 443, so it refused the request and the whole content resolution failed before a byte was
 * downloaded. A plain relay is not a weaker TLS path: there is no TLS on port 80 to weaken, the host rule
 * and the public-address rule are unchanged, and only these two ports are accepted at all.
 */
class SteamCdnTunnelTest {
    @Test
    fun `port 443 keeps the authenticated tls tunnel and port 80 is a plain relay`() {
        assertEquals(SteamCdnTunnelMode.AUTHENTICATED_TLS, steamCdnTunnelMode(443))
        assertEquals(SteamCdnTunnelMode.PLAIN_RELAY, steamCdnTunnelMode(80))
    }

    @Test
    fun `every other port is refused rather than tunnelled`() {
        for (port in listOf(0, 22, 81, 4430, 8080, 65535)) {
            assertNull(steamCdnTunnelMode(port), "port $port must not be tunnelled")
        }
    }

    @Test
    fun `the plain relay keeps the same host rule as the tls tunnel`() {
        for (host in CDN_HOSTS) {
            assertEquals(host, SteamDomainPolicy.requireAuthenticatedCdnTunnel(host, 80))
            assertEquals(host, SteamDomainPolicy.requireAuthenticatedCdnTunnel(host, 443))
            assertEquals(host, SteamDomainPolicy.requireAuthenticatedCdnEndpoint(host, 443))
        }
    }

    @Test
    fun `the tls tunnel still refuses port 80 so no plain connection is ever intercepted`() {
        assertFailsWith<IllegalArgumentException> {
            SteamDomainPolicy.requireAuthenticatedCdnEndpoint(CDN_HOSTS.first(), 80)
        }
    }

    @Test
    fun `the plain relay refuses addresses and single labels the cdn rule never owned`() {
        for (host in listOf("127.0.0.1", "::1", "localhost", "", "not a host")) {
            assertFailsWith<IllegalArgumentException>("host '$host' must be refused") {
                SteamDomainPolicy.requireAuthenticatedCdnTunnel(host, 80)
            }
        }
    }

    @Test
    fun `the cdn tunnel rule is shape bounded and the web host rule is a separate allowlist`() {
        // The CDN rule admits any syntactically valid public host the SDK names, which is what lets Steam
        // advertise new CDN families; it is not a licence for the Web planes to follow those hosts.
        assertEquals("example.com", SteamDomainPolicy.requireAuthenticatedCdnTunnel("example.com", 80))
        assertTrue(SteamDomainPolicy.supports("api.steampowered.com"))
        assertTrue(SteamDomainPolicy.accelerates("api.steampowered.com"))
        assertFalse(
            SteamDomainPolicy.supports("example.com"),
            "the Web planes must only accelerate Steam-owned hosts",
        )
        assertFalse(SteamDomainPolicy.supports("cloud-3.steamusercontent.com"))
    }

    @Test
    fun `only the plain http port is added to the authenticated cdn tunnel`() {
        assertTrue(SteamDomainPolicy.requireAuthenticatedCdnTunnel(CDN_HOSTS.first(), 80).isNotBlank())
        assertFailsWith<IllegalArgumentException> {
            SteamDomainPolicy.requireAuthenticatedCdnTunnel(CDN_HOSTS.first(), 8080)
        }
    }

    private companion object {
        val CDN_HOSTS =
            listOf(
                "cloud-3.steamusercontent.com",
                "cdn.steamcontent.com",
                "steamcdn-a.akamaihd.net",
            )
    }
}
