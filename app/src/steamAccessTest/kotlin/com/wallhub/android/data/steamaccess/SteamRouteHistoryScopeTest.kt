package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Route history is learned per egress, not per transport. Before this contract the history key was the
 * network transport ("wifi"), so an IP ranked behind one proxy or DNS server was reused behind another.
 */
class SteamRouteHistoryScopeTest {
    @Test
    fun `two wifi networks with different egress do not share route history`() {
        assertNotEquals(
            steamRouteHistoryKey("wifi|proxy-a|dns-1", "api.steampowered.com", 443),
            steamRouteHistoryKey("wifi|direct|dns-2", "api.steampowered.com", 443),
        )
    }

    @Test
    fun `hostname normalization and port stay part of the identity`() {
        assertEquals(
            steamRouteHistoryKey("vpn|fp", "API.SteamPowered.com.", 443),
            steamRouteHistoryKey("vpn|fp", "api.steampowered.com", 443),
        )
        assertNotEquals(
            steamRouteHistoryKey("vpn|fp", "api.steampowered.com", 443),
            steamRouteHistoryKey("vpn|fp", "api.steampowered.com", 27017),
        )
    }

    @Test
    fun `a proxied and a direct egress never collide`() {
        val proxied = steamRouteHistoryKey("wifi|10.0.0.5:8080", "steamcommunity.com", 443)
        val direct = steamRouteHistoryKey("wifi|direct", "steamcommunity.com", 443)
        assertNotEquals(proxied, direct)
    }
}
