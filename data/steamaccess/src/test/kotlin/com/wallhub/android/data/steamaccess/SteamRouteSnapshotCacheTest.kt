package com.wallhub.android.data.steamaccess

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamRouteSnapshotCacheTest {
    private val address = InetAddress.getByName("23.44.248.222")

    @Test
    fun `fresh route is returned without scheduling refresh`() {
        val now = 1_000L
        val cache = SteamRouteSnapshotCache { now }
        cache.publish("wifi|steamcommunity.com", route(freshUntil = 2_000L, staleUntil = 4_000L))

        val lookup = cache.lookup("wifi|steamcommunity.com")

        assertEquals(listOf(address), lookup.route?.addresses)
        assertFalse(lookup.shouldRefresh)
    }

    @Test
    fun `stale route remains usable while refresh is scheduled`() {
        val now = 2_500L
        val cache = SteamRouteSnapshotCache { now }
        cache.publish("wifi|steamcommunity.com", route(freshUntil = 2_000L, staleUntil = 4_000L))

        val lookup = cache.lookup("wifi|steamcommunity.com")

        assertTrue(lookup.route?.accelerated == true)
        assertTrue(lookup.shouldRefresh)
    }

    @Test
    fun `hard expired route is removed before lookup returns`() {
        val now = 4_000L
        val cache = SteamRouteSnapshotCache { now }
        cache.publish("wifi|steamcommunity.com", route(freshUntil = 2_000L, staleUntil = 4_000L))

        val lookup = cache.lookup("wifi|steamcommunity.com")

        assertNull(lookup.route)
        assertTrue(lookup.shouldRefresh)
    }

    @Test
    fun `failed refresh cannot replace a stale usable route`() {
        val cache = SteamRouteSnapshotCache { 2_500L }
        val key = "wifi|steamcommunity.com"
        val healthy = route(freshUntil = 2_000L, staleUntil = 4_000L)
        val failed = SteamCachedRoute(
            available = false,
            accelerated = false,
            addresses = emptyList(),
            freshUntil = 3_000L,
            staleUntil = 3_000L,
        )
        cache.publish(key, healthy)

        assertEquals(healthy, cache.publishKeepingUsable(key, failed))
        assertEquals(healthy, cache.lookup(key).route)
    }

    @Test
    fun `failed candidate removal preserves healthy alternatives`() {
        val second = InetAddress.getByName("23.44.248.223")
        val cache = SteamRouteSnapshotCache { 1_000L }
        cache.publish(
            "wifi|steamcommunity.com",
            route(freshUntil = 2_000L, staleUntil = 4_000L).copy(addresses = listOf(address, second)),
        )

        assertFalse(cache.removeAddress("wifi|steamcommunity.com", address))
        assertEquals(listOf(second), cache.lookup("wifi|steamcommunity.com").route?.addresses)
        assertTrue(cache.removeAddress("wifi|steamcommunity.com", second))
        assertNull(cache.lookup("wifi|steamcommunity.com").route)
    }

    private fun route(freshUntil: Long, staleUntil: Long) = SteamCachedRoute(
        available = true,
        accelerated = true,
        addresses = listOf(address),
        freshUntil = freshUntil,
        staleUntil = staleUntil,
    )
}
