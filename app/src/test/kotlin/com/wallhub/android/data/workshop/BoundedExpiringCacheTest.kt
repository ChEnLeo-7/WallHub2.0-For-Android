package com.wallhub.android.data.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedExpiringCacheTest {
    @Test
    fun `positive and negative entries expire independently`() {
        var now = 0L
        val cache = BoundedExpiringCache<String, String>(2, positiveTtlMs = 100, negativeTtlMs = 10) { now }
        cache.put("profile", "Ada")
        cache.put("missing", null)

        assertEquals(CacheLookup.Present("Ada"), cache.get("profile"))
        assertEquals(CacheLookup.Negative, cache.get("missing"))

        now = 10
        assertEquals(CacheLookup.Missing, cache.get("missing"))
        assertEquals(CacheLookup.Present("Ada"), cache.get("profile"))
    }

    @Test
    fun `recent access protects entry from capacity eviction`() {
        val cache = BoundedExpiringCache<String, String>(2, positiveTtlMs = 100, negativeTtlMs = 10) { 0L }
        cache.put("first", "1")
        cache.put("second", "2")
        cache.get("first")
        cache.put("third", "3")

        assertEquals(CacheLookup.Missing, cache.get("second"))
        assertEquals(CacheLookup.Present("1"), cache.get("first"))
        assertTrue(cache.size() <= 2)
    }

    @Test
    fun `clear invalidates positive and negative entries`() {
        val cache = BoundedExpiringCache<String, String>(2, positiveTtlMs = 100, negativeTtlMs = 10) { 0L }
        cache.put("profile", "Ada")
        cache.put("missing", null)

        cache.clear()

        assertEquals(CacheLookup.Missing, cache.get("profile"))
        assertEquals(CacheLookup.Missing, cache.get("missing"))
    }
}
