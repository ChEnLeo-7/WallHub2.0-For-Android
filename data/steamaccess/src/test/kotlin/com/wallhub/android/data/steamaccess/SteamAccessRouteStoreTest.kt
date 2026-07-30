package com.wallhub.android.data.steamaccess

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SteamAccessRouteStoreTest {
    @Test
    fun `real successes outrank repeated failures`() {
        val now = 1_000_000L
        val healthy =
            JSONObject()
                .put("success", 2)
                .put("lastSuccessAt", now)
                .put("consecutiveFailure", 0)
        val failing =
            JSONObject()
                .put("success", 4)
                .put("lastSuccessAt", 0L)
                .put("consecutiveFailure", 3)

        assertTrue(SteamAccessRouteStore.score(healthy, now) > SteamAccessRouteStore.score(failing, now))
    }

    @Test
    fun `lower handshake latency wins when health history is otherwise equal`() {
        val now = 1_000_000L
        val fast =
            JSONObject()
                .put("success", 2)
                .put("lastSuccessAt", now)
                .put("consecutiveFailure", 0)
                .put("latencyEwmaMs", 120L)
        val slow =
            JSONObject()
                .put("success", 2)
                .put("lastSuccessAt", now)
                .put("consecutiveFailure", 0)
                .put("latencyEwmaMs", 2_500L)

        assertTrue(SteamAccessRouteStore.score(fast, now) > SteamAccessRouteStore.score(slow, now))
    }

    @Test
    fun `failure cooldown grows and caps at thirty minutes`() {
        assertEquals(3 * 60_000L, SteamAccessRouteStore.failureCooldown(1))
        assertEquals(10 * 60_000L, SteamAccessRouteStore.failureCooldown(2))
        assertEquals(30 * 60_000L, SteamAccessRouteStore.failureCooldown(3))
        assertEquals(30 * 60_000L, SteamAccessRouteStore.failureCooldown(10))
    }
}
