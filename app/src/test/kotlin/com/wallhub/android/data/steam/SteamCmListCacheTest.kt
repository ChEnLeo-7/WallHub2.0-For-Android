package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.Request

class SteamCmListCacheTest {
    @Test
    fun `stale cm list fallback expires after hard ttl`() {
        assertTrue(cmListCacheStaleUsable(0L))
        assertTrue(cmListCacheStaleUsable(24L * 60L * 60L * 1000L))
        assertTrue(cmListCacheStaleUsable(CM_LIST_CACHE_STALE_TTL_MS - 1))
        assertFalse(cmListCacheStaleUsable(CM_LIST_CACHE_STALE_TTL_MS))
        assertFalse(cmListCacheStaleUsable(-1L))
    }

    @Test
    fun `a failed live request may use bounded cache except on transport retry`() {
        assertTrue(cmCandidatesMayFallBack(0L, refreshLive = false))
        assertTrue(cmCandidatesMayFallBack(CM_LIST_CACHE_STALE_TTL_MS - 1, refreshLive = false))
        assertFalse(cmCandidatesMayFallBack(CM_LIST_CACHE_STALE_TTL_MS, refreshLive = false))
        assertFalse(cmCandidatesMayFallBack(null, refreshLive = false))
        assertFalse(cmCandidatesMayFallBack(0L, refreshLive = true))
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

    @Test
    fun `cm list cache validation rejects gzip garbage and accepts json`() {
        val valid = """{"response":{"serverlist":[{"endpoint":"cmp1-hkg1.steamserver.net:443","legacy_endpoint":""}]}}"""
        assertTrue(looksLikeCmListJson(valid))
        assertFalse(looksLikeCmListJson("""{"response":{}}"""))
        assertFalse(looksLikeCmListJson(""))
        assertFalse(looksLikeCmListJson("binary-gzip-garbage"))
    }
}
