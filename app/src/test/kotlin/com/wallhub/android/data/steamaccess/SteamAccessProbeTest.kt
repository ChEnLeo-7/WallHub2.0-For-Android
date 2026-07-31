package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals

class SteamAccessProbeTest {
    @Test
    fun `successful probes sort before faster failures`() {
        val failed = SteamProbeResult(java.net.InetAddress.getByName("192.0.2.1"), false, 1)
        val successful = SteamProbeResult(java.net.InetAddress.getByName("192.0.2.2"), true, 100)

        val sorted =
            listOf(failed, successful)
                .sortedWith(compareByDescending<SteamProbeResult> { it.successful }.thenBy { it.elapsedMs })

        assertEquals(successful, sorted.first())
    }
}
