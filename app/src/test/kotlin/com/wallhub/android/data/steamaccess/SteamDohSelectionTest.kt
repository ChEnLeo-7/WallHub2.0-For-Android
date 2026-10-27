package com.wallhub.android.data.steamaccess

import com.wallhub.android.core.model.SteamDohCatalog
import com.wallhub.android.core.model.SteamDohEndpoint
import com.wallhub.android.core.model.SteamDohProtocol
import com.wallhub.android.core.model.SteamDohRegion
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamDohSelectionTest {
    @Test
    fun `built in catalog has twenty foreign and ten domestic unique providers`() {
        assertEquals(20, SteamDohCatalog.foreign.size)
        assertEquals(10, SteamDohCatalog.domestic.size)
        assertEquals(30, SteamDohCatalog.builtIn.map { it.provider.lowercase() }.distinct().size)
        assertEquals(30, SteamDohCatalog.builtIn.map { it.url }.distinct().size)
    }

    @Test
    fun `selector keeps five fastest from each built in region and viable custom routes`() {
        val domestic = (1..7).map { endpoint("d$it", SteamDohRegion.DOMESTIC) }
        val foreign = (1..7).map { endpoint("f$it", SteamDohRegion.FOREIGN) }
        val custom = endpoint("custom", SteamDohRegion.CUSTOM)
        val endpoints = domestic + foreign + custom
        val results = endpoints.mapIndexed { index, endpoint ->
            val latency = when (endpoint.region) {
                SteamDohRegion.DOMESTIC -> 100L - index
                SteamDohRegion.FOREIGN -> 200L - index
                SteamDohRegion.CUSTOM -> 50L
            }
            SteamDohProbeResult(endpoint, latency, listOf(InetAddress.getByName("1.1.1.1")))
        }

        val selected = selectFastestDohEndpoints(endpoints, results)

        assertEquals(11, selected.size)
        val fastestDomestic = domestic.takeLast(5).asReversed().map { it.url }
        val fastestForeign = foreign.takeLast(5).asReversed().map { it.url }
        assertEquals(
            fastestDomestic.zip(fastestForeign).flatMap { (domesticUrl, foreignUrl) ->
                listOf(domesticUrl, foreignUrl)
            },
            selected.take(10),
        )
        assertEquals(custom.url, selected.last())
    }

    @Test
    fun `fallback is balanced and bounded while background benchmark warms`() {
        val domestic = (1..7).map { endpoint("d$it", SteamDohRegion.DOMESTIC) }
        val foreign = (1..7).map { endpoint("f$it", SteamDohRegion.FOREIGN) }

        val fallback = fallbackDohEndpoints(domestic + foreign)

        assertEquals(10, fallback.size)
        assertEquals(domestic.first().url, fallback[0])
        assertEquals(foreign.first().url, fallback[1])
        assertEquals(domestic[4].url, fallback[8])
        assertEquals(foreign[4].url, fallback[9])
    }

    @Test
    fun `legacy default URLs migrate to replacement built in identities`() {
        assertEquals(
            listOf(
                "https://sm2.doh.pub/dns-query",
                "https://dns.alidns.com/dns-query",
            ),
            SteamDohCatalog.migrateLegacyUrls(
                listOf(
                    "https://1.12.12.12/resolve",
                    "https://doh.pub/resolve",
                    "https://dns.alidns.com/resolve",
                ),
            ),
        )
    }

    @Test
    fun `selector excludes failed probes and uses configured order to break latency ties`() {
        val first = endpoint("first", SteamDohRegion.DOMESTIC)
        val second = endpoint("second", SteamDohRegion.DOMESTIC)
        val failed = endpoint("failed", SteamDohRegion.FOREIGN)
        val address = InetAddress.getByName("1.1.1.1")

        assertEquals(
            listOf(first.url, second.url),
            selectFastestDohEndpoints(
                listOf(first, second, failed),
                listOf(
                    SteamDohProbeResult(first, 10, listOf(address)),
                    SteamDohProbeResult(second, 10, listOf(address)),
                    SteamDohProbeResult(failed, null, emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `production benchmark is attempted once per generation and endpoint fingerprint`() {
        val admission = SteamDohBenchmarkAdmission()

        assertTrue(admission.admit(7, "a\u0000b"))
        assertFalse(admission.admit(7, "a\u0000b"))
        assertTrue(admission.admit(7, "a\u0000c"))
        assertTrue(admission.admit(8, "a\u0000c"))
    }

    @Test
    fun `invalidating routes permits a new benchmark attempt`() {
        val admission = SteamDohBenchmarkAdmission()
        assertTrue(admission.admit(7, "a"))
        assertFalse(admission.admit(7, "a"))

        admission.clear()

        assertTrue(admission.admit(7, "a"))
    }

    private fun endpoint(name: String, region: SteamDohRegion) =
        SteamDohEndpoint(name, "https://$name.example/dns-query", region, SteamDohProtocol.WIRE)
}
