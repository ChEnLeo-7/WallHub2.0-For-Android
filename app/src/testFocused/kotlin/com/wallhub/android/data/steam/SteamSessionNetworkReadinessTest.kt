package com.wallhub.android.data.steam

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SteamSessionNetworkReadinessTest {
    @Test
    fun `validated capability edge makes one network generation ready`() {
        val owner = SteamSessionNetworkStateOwner<String>()

        owner.onAvailable(
            network = "wifi-a",
            hasInternet = true,
            isValidated = false,
            hasCaptivePortal = false,
        )
        val waiting = owner.state.value
        owner.onCapabilitiesChanged(
            network = "wifi-a",
            hasInternet = true,
            isValidated = true,
            hasCaptivePortal = false,
        )
        val ready = owner.state.value
        owner.onCapabilitiesChanged(
            network = "wifi-a",
            hasInternet = true,
            isValidated = true,
            hasCaptivePortal = false,
        )

        assertFalse(waiting.isReady)
        assertTrue(ready.isReady)
        assertEquals(waiting.generation, ready.generation)
        assertEquals(ready, owner.state.value)
    }

    @Test
    fun `network loss and replacement create new generations`() {
        val owner = SteamSessionNetworkStateOwner<String>()
        owner.onAvailable("wifi-a", hasInternet = true, isValidated = true, hasCaptivePortal = false)
        val first = owner.state.value

        owner.onLost("wifi-a")
        val lost = owner.state.value
        owner.onAvailable("wifi-b", hasInternet = true, isValidated = true, hasCaptivePortal = false)
        val replacement = owner.state.value

        assertTrue(first.isReady)
        assertFalse(lost.isReady)
        assertTrue(replacement.isReady)
        assertTrue(lost.generation > first.generation)
        assertTrue(replacement.generation > lost.generation)
    }
}
