package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamSessionPublicationTest {
    @Test
    fun `current usable promoted session can publish signed in`() {
        assertTrue(
            matchesCurrentUsableSession(
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedSessionId = 42,
                activeSessionId = 42,
                sessionUsable = true,
            ),
        )
    }

    @Test
    fun `disconnected session cannot publish signed in`() {
        assertFalse(
            matchesCurrentUsableSession(
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedSessionId = 42,
                activeSessionId = 42,
                sessionUsable = false,
            ),
        )
    }

    @Test
    fun `superseded or replaced session cannot publish signed in`() {
        assertFalse(
            matchesCurrentUsableSession(
                expectedGeneration = 7,
                currentGeneration = 8,
                expectedSessionId = 42,
                activeSessionId = 42,
                sessionUsable = true,
            ),
        )
        assertFalse(
            matchesCurrentUsableSession(
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedSessionId = 42,
                activeSessionId = 43,
                sessionUsable = true,
            ),
        )
    }
}
