package com.wallhub.android.data.steamaccess

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SteamAccessDohResolverTest {
    @Test
    fun `query plan gives every authority an IPv4 slot before secondary work`() {
        val plan = SteamAccessDohResolver.queryPlan(
            hostnames = listOf("canonical.example", "alias.example"),
            endpoints = listOf("domestic", "foreign", "backup"),
            includeIpv6 = true,
        )

        assertEquals(
            listOf("canonical.example", "alias.example", "canonical.example", "alias.example"),
            plan.take(4).map { it.hostname },
        )
        assertEquals(listOf("domestic", "domestic", "foreign", "foreign"), plan.take(4).map { it.endpoint })
        assertTrue(plan.take(4).all { it.recordType == 1 })
    }

    @Test
    fun `foreground resolution completes after every authority has one address`() {
        val canonical = InetAddress.getByName("23.1.1.1")
        val alias = InetAddress.getByName("23.2.2.2")

        assertTrue(
            foregroundResolutionComplete(
                hostnames = listOf("canonical.example", "alias.example"),
                answers = mapOf("canonical.example" to listOf(canonical), "alias.example" to listOf(alias)),
            ),
        )
        assertTrue(
            !foregroundResolutionComplete(
                hostnames = listOf("canonical.example", "alias.example"),
                answers = mapOf("canonical.example" to listOf(canonical)),
            ),
        )
    }

    @Test
    fun `wire query encodes one standard question`() {
        val query = SteamAccessDohResolver.buildWireQuery("example.com", 1, 0x1234)

        assertEquals(0x12, query[0].toInt() and 0xff)
        assertEquals(0x34, query[1].toInt() and 0xff)
        assertEquals(1, query[5].toInt() and 0xff)
        assertTrue(query.toList().contains(7.toByte()))
    }

    @Test
    fun `wire parser accepts compressed A answer and verifies transaction id`() {
        val response =
            byteArrayOf(
                0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
                0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
                'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0x03, 'c'.code.toByte(),
                'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01,
                0xc0.toByte(), 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c,
                0x00, 0x04, 1, 2, 3, 4,
            )

        assertEquals(
            listOf("1.2.3.4"),
            SteamAccessDohResolver.parseWireAddresses(response, 1, 0x1234).map(InetAddress::getHostAddress),
        )
        assertTrue(SteamAccessDohResolver.parseWireAddresses(response, 1, 0x9999).isEmpty())
    }

    @Test
    fun `wire parser rejects truncated and error responses`() {
        assertTrue(SteamAccessDohResolver.parseWireAddresses(byteArrayOf(1, 2), 1, 1).isEmpty())
        val servfail = byteArrayOf(0, 1, 0x81.toByte(), 0x82.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(SteamAccessDohResolver.parseWireAddresses(servfail, 1, 1).isEmpty())
    }
}
