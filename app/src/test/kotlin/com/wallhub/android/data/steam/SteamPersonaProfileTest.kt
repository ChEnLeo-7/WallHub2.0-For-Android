package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SteamPersonaProfileTest {
    @Test
    fun `avatar hash converts into the steam avatar CDN url`() {
        val hash =
            byteArrayOf(
                0xde.toByte(),
                0xad.toByte(),
                0xbe.toByte(),
                0xef.toByte(),
            )

        val url = hash.toSteamAvatarUrl()

        assertEquals("https://avatars.fastly.steamstatic.com/deadbeef_medium.jpg", url)
    }

    @Test
    fun `blank and zeroed avatar hashes resolve to no url`() {
        assertNull(ByteArray(0).toSteamAvatarUrl())
        assertNull(ByteArray(8).toSteamAvatarUrl())
    }

    @Test
    fun `profile resolution keeps display name and optional avatar`() {
        val profile = SteamProfile(displayName = "Current name", avatarUrl = "https://example.com/avatar.jpg")

        assertNotNull(profile)
        assertEquals("Current name", profile.displayName)
        assertEquals("https://example.com/avatar.jpg", profile.avatarUrl)
    }
}
