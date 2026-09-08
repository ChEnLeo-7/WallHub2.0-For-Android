package com.wallhub.android.data.downloads

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCdnTransportFallbackTest {
    @Test
    fun authRejectionsAndTlsFailuresAreFallbackEligible() {
        assertTrue(SteamCdnTransportFallback.isInsecureFallbackEligible(SteamCdnHttpException("401", 401)))
        assertTrue(SteamCdnTransportFallback.isInsecureFallbackEligible(SteamCdnHttpException("403", 403)))
        assertTrue(
            SteamCdnTransportFallback.isInsecureFallbackEligible(
                SSLHandshakeException("Unable to parse TLS packet header"),
            ),
        )
        assertTrue(
            SteamCdnTransportFallback.isInsecureFallbackEligible(
                IOException("connect timed out").initCause(
                    SSLHandshakeException("handshake failure"),
                ),
            ),
        )
        assertTrue(SteamCdnTransportFallback.isInsecureFallbackEligible(IOException("socket reset")))
    }

    @Test
    fun protocolErrorsWithoutAuthOrTlsStayOnHttps() {
        assertFalse(SteamCdnTransportFallback.isInsecureFallbackEligible(SteamCdnHttpException("500", 500)))
        assertFalse(
            SteamCdnTransportFallback.isInsecureFallbackEligible(IllegalStateException("length mismatch")),
        )
    }

    @Test
    fun markedHostsPreferInsecureTransport() {
        SteamCdnTransportFallback.markInsecure("broken-edge.example.test", null, " ")
        assertTrue(SteamCdnTransportFallback.prefersInsecure("broken-edge.example.test"))
        assertFalse(SteamCdnTransportFallback.prefersInsecure("healthy-edge.example.test"))
        assertFalse(SteamCdnTransportFallback.prefersInsecure(null))
    }

    @Test
    fun fallbackErrorSummaryCarriesStatusCodes() {
        val error = SteamCdnHttpException("Steam CDN returned 401 for the depot manifest", 401)
        assertEquals(401, error.code)
    }
}
