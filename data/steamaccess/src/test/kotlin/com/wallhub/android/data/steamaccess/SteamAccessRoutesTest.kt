package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamAccessRoutesTest {
    @Test
    fun `gateway supports exact core services and excludes subdomains and cdn hosts`() {
        assertTrue(SteamAccessRoutes.supports("steamcommunity.com"))
        assertTrue(SteamAccessRoutes.supports("www.steamcommunity.com"))
        assertTrue(SteamAccessRoutes.supports("api.steampowered.com"))
        assertTrue(SteamAccessRoutes.supports("community.steam-api.com"))
        assertFalse(SteamAccessRoutes.supports("evil.steamcommunity.com"))
        assertFalse(SteamAccessRoutes.supports("steamuserimages-a.akamaihd.net"))
        assertFalse(SteamAccessRoutes.supports("cache1.steamcontent.com"))
        assertFalse(SteamAccessRoutes.supports("cmp1-sea1.steamserver.net"))
    }

    @Test
    fun `hosts parser accepts valid steam ipv4 and ipv6 entries only`() {
        val result = SteamHostsParser.parse(
            """
            23.44.248.222 steamcommunity.com unrelated.example
            2600:1406:3a00::17d5:2a65 api.steampowered.com
            invalid community.steam-api.com
            """.trimIndent(),
        )

        assertEquals(listOf("23.44.248.222"), result.getValue("steamcommunity.com").map { it.hostAddress })
        assertEquals(1, result.getValue("api.steampowered.com").size)
        assertFalse("unrelated.example" in result)
    }
}
