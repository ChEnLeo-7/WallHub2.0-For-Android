package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.Request

class SteamCmListCacheTest {
    @Test
    fun `cm list cache serves fresh entries and falls back for stale ones`() {
        assertEquals(SteamCmListCacheAction.FETCH_LIVE, cmListCacheDecision(null))
        assertEquals(SteamCmListCacheAction.SERVE_CACHED, cmListCacheDecision(0L))
        assertEquals(SteamCmListCacheAction.SERVE_CACHED, cmListCacheDecision(CM_LIST_CACHE_FRESH_TTL_MS - 1))
        assertEquals(
            SteamCmListCacheAction.FETCH_LIVE_WITH_FALLBACK,
            cmListCacheDecision(CM_LIST_CACHE_FRESH_TTL_MS),
        )
        assertEquals(
            SteamCmListCacheAction.FETCH_LIVE_WITH_FALLBACK,
            cmListCacheDecision(CM_LIST_CACHE_STALE_TTL_MS - 1),
        )
        assertEquals(
            SteamCmListCacheAction.FETCH_LIVE_WITH_FALLBACK,
            cmListCacheDecision(CM_LIST_CACHE_STALE_TTL_MS + 1),
        )
    }

    @Test
    fun `stale cm list fallback expires after hard ttl`() {
        assertTrue(cmListCacheStaleUsable(0L))
        assertTrue(cmListCacheStaleUsable(CM_LIST_CACHE_FRESH_TTL_MS))
        assertTrue(cmListCacheStaleUsable(CM_LIST_CACHE_STALE_TTL_MS - 1))
        assertFalse(cmListCacheStaleUsable(CM_LIST_CACHE_STALE_TTL_MS))
        assertFalse(cmListCacheStaleUsable(-1L))
    }

    @Test
    fun `cm list request detection matches api directory endpoint only`() {
        val cmList =
            Request
                .Builder()
                .url("https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?cmtype=websockets")
                .build()
        assertTrue(cmList.isCmListRequest())

        val otherApi =
            Request
                .Builder()
                .url("https://api.steampowered.com/ISteamWebAPIUtil/GetSupportedAPIList/v1/")
                .build()
        assertFalse(otherApi.isCmListRequest())

        val otherHost =
            Request
                .Builder()
                .url("https://evil.example.com/ISteamDirectory/GetCMListForConnect/v1/")
                .build()
        assertFalse(otherHost.isCmListRequest())
    }
}
