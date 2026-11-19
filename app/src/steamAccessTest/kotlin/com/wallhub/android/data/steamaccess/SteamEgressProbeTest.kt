package com.wallhub.android.data.steamaccess

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The system proxy is read once, for the Steam targets, and its four outcomes are preserved.
 * Collapsing "ambiguous" or "unreadable" into "direct" is the silent-bypass defect this pins down.
 */
class SteamEgressProbeTest {
    private val targets =
        listOf(
            URI("https://api.steampowered.com/"),
            URI("https://steamcommunity.com/"),
            URI("https://cdn.steamcontent.com/"),
        )

    private fun selector(vararg answers: List<Proxy>) =
        object : ProxySelector() {
            private var index = 0

            override fun select(uri: URI?): List<Proxy> = answers[minOf(index++, answers.lastIndex)]

            override fun connectFailed(
                uri: URI?,
                sa: SocketAddress?,
                ioe: IOException?,
            ) = Unit
        }

    private fun http(host: String = "10.0.0.5", port: Int = 8080) =
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    @Test
    fun `direct answers are absent rather than an unusable proxy`() {
        val resolved = resolveSteamSystemProxy(selector(listOf(Proxy.NO_PROXY)), targets)

        assertEquals(SteamSystemProxy.Absent, resolved)
    }

    @Test
    fun `a single http proxy is representable`() {
        val resolved = resolveSteamSystemProxy(selector(listOf(http())), targets)

        assertEquals(
            SteamSystemProxy.Present(SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080)),
            resolved,
        )
    }

    @Test
    fun `a socks proxy keeps its kind`() {
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 1080))

        val resolved = resolveSteamSystemProxy(selector(listOf(socks)), targets)

        val present = assertIs<SteamSystemProxy.Present>(resolved)
        assertEquals(SteamProxyKind.SOCKS, present.endpoint.kind)
        assertEquals("socks://127.0.0.1:1080", present.endpoint.uri)
    }

    @Test
    fun `an ipv6 proxy host is bracketed in the sdk uri`() {
        val resolved = resolveSteamSystemProxy(selector(listOf(http(host = "fd00::1"))), targets)

        val present = assertIs<SteamSystemProxy.Present>(resolved)
        assertEquals("http://[fd00::1]:8080", present.endpoint.uri)
    }

    @Test
    fun `an empty selection is ambiguous not direct`() {
        val resolved = resolveSteamSystemProxy(selector(emptyList()), targets)

        assertEquals(SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.EmptySelection), resolved)
    }

    @Test
    fun `several proxies for one target are ambiguous`() {
        val resolved = resolveSteamSystemProxy(selector(listOf(http(), http(port = 8081))), targets)

        assertEquals(SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.MultipleProxies), resolved)
    }

    @Test
    fun `targets that disagree are ambiguous`() {
        val resolved =
            resolveSteamSystemProxy(
                selector(listOf(http()), listOf(http(port = 9090)), listOf(http())),
                targets,
            )

        assertEquals(SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.InconsistentTargets), resolved)
    }

    @Test
    fun `a selector that throws is unreadable not direct`() {
        val throwing =
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = throw IllegalStateException("boom")

                override fun connectFailed(
                    uri: URI?,
                    sa: SocketAddress?,
                    ioe: IOException?,
                ) = Unit
            }

        assertEquals(SteamSystemProxy.Unavailable, resolveSteamSystemProxy(throwing, targets))
        assertEquals(SteamSystemProxy.Unavailable, resolveSteamSystemProxy(null, targets))
    }

    @Test
    fun `proxy authentication failure is its own reason`() {
        assertEquals(
            SteamBlockReason.PROXY_AUTH_REQUIRED,
            classifySteamProbeFailure(status = 407, error = null, viaProxy = true),
        )
    }

    @Test
    fun `rate limiting and server rejection stay distinct`() {
        assertEquals(SteamBlockReason.RATE_LIMITED, classifySteamProbeFailure(429, null, viaProxy = false))
        assertEquals(SteamBlockReason.SERVER_REJECTED, classifySteamProbeFailure(403, null, viaProxy = false))
        assertEquals(SteamBlockReason.SERVER_REJECTED, classifySteamProbeFailure(503, null, viaProxy = true))
    }

    @Test
    fun `tls, dns, timeout and connect failures are classified separately`() {
        assertEquals(
            SteamBlockReason.TLS_FAILURE,
            classifySteamProbeFailure(null, SSLHandshakeException("bad chain"), viaProxy = false),
        )
        assertEquals(
            SteamBlockReason.DNS_FAILURE,
            classifySteamProbeFailure(null, UnknownHostException("api.steampowered.com"), viaProxy = false),
        )
        assertEquals(
            SteamBlockReason.TIMEOUT,
            classifySteamProbeFailure(null, SocketTimeoutException("read timed out"), viaProxy = false),
        )
        assertEquals(
            SteamBlockReason.CONNECT_FAILURE,
            classifySteamProbeFailure(null, ConnectException("connection refused"), viaProxy = false),
        )
    }

    @Test
    fun `a wrapped cause keeps the specific reason`() {
        val wrapped = IOException("handshake failed", SSLHandshakeException("alt name mismatch"))

        assertEquals(SteamBlockReason.TLS_FAILURE, classifySteamProbeFailure(null, wrapped, viaProxy = true))
    }

    /**
     * OkHttp refuses a tunnel whose 407 no authenticator answered with a bare IOException. The exact
     * message is what the real proxy test observes; pinning it here is what keeps an OkHttp upgrade from
     * silently turning a credential problem back into "the line is blocked".
     */
    @Test
    fun `okhttps tunnel refusal over a proxy is proxy authentication`() {
        val refusal = IOException("Failed to authenticate with proxy")

        assertEquals(
            SteamBlockReason.PROXY_AUTH_REQUIRED,
            classifySteamProbeFailure(null, refusal, viaProxy = true),
        )
        assertEquals(
            SteamBlockReason.PROXY_AUTH_REQUIRED,
            classifySteamProbeFailure(null, IOException("wrapped", refusal), viaProxy = true),
        )
        // The same text carries no proxy meaning when the request never went through one.
        assertEquals(
            SteamBlockReason.CONNECT_FAILURE,
            classifySteamProbeFailure(null, refusal, viaProxy = false),
        )
    }

    @Test
    fun `an observed challenge classifies as authentication without any message`() {
        assertEquals(
            SteamBlockReason.PROXY_AUTH_REQUIRED,
            classifySteamProbeFailure(null, IOException("socket closed"), viaProxy = true, proxyAuthChallenged = true),
        )
        // A challenge cannot describe a probe that never used a proxy.
        assertEquals(
            SteamBlockReason.CONNECT_FAILURE,
            classifySteamProbeFailure(null, IOException("socket closed"), viaProxy = false, proxyAuthChallenged = true),
        )
    }

    @Test
    fun `a 407 answered by an origin is a server rejection not a proxy demand`() {
        assertEquals(
            SteamBlockReason.SERVER_REJECTED,
            classifySteamProbeFailure(status = 407, error = null, viaProxy = false),
        )
    }

    @Test
    fun `ordinary transport and server failures are never reported as proxy authentication`() {
        assertEquals(
            SteamBlockReason.CONNECT_FAILURE,
            classifySteamProbeFailure(null, ConnectException("connection refused"), viaProxy = true),
        )
        assertEquals(
            SteamBlockReason.CONNECT_FAILURE,
            classifySteamProbeFailure(null, UnknownHostException("proxy.invalid"), viaProxy = true),
        )
        assertEquals(
            SteamBlockReason.TLS_FAILURE,
            classifySteamProbeFailure(null, SSLHandshakeException("bad chain"), viaProxy = true),
        )
        assertEquals(
            SteamBlockReason.TIMEOUT,
            classifySteamProbeFailure(null, SocketTimeoutException("read timed out"), viaProxy = true),
        )
        assertEquals(SteamBlockReason.SERVER_REJECTED, classifySteamProbeFailure(401, null, viaProxy = true))
        assertEquals(SteamBlockReason.SERVER_REJECTED, classifySteamProbeFailure(403, null, viaProxy = true))
        assertEquals(SteamBlockReason.SERVER_REJECTED, classifySteamProbeFailure(500, null, viaProxy = true))
    }
}
