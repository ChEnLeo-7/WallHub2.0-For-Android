package com.wallhub.android.data.steam

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamReadCacheTest {
    @Test
    fun `fresh value expires into a bounded stale fallback`() = runTest {
        var now = 0L
        val cache = SteamReadCache<String, String>(freshMillis = 100, staleMillis = 300) { now }
        cache.put("friends", "page")

        now = 99
        assertEquals("page", cache.getFresh("friends"))
        now = 100
        assertNull(cache.getFresh("friends"))
        assertEquals("page", cache.getStale("friends"))
        now = 300
        assertNull(cache.getStale("friends"))
    }

    @Test
    fun `least recently used entry is evicted`() = runTest {
        val cache = SteamReadCache<String, String>(maxEntries = 2, freshMillis = 100, staleMillis = 200) { 0L }
        cache.put("a", "A")
        cache.put("b", "B")
        assertEquals("A", cache.getFresh("a"))
        cache.put("c", "C")

        assertNull(cache.getStale("b"))
        assertEquals("A", cache.getFresh("a"))
        assertEquals("C", cache.getFresh("c"))
    }

    @Test
    fun `invalidate removes account scoped data`() = runTest {
        val cache = SteamReadCache<String, String>(freshMillis = 100, staleMillis = 200) { 0L }
        cache.put("subscription", "page")
        cache.invalidateAll()
        assertNull(cache.getStale("subscription"))
    }
}
