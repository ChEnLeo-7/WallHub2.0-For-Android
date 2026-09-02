package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.enums.EClientPersonaStateFlag
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SteamPersonaProfileTest {
    @Test
    fun `profile request includes player name and avatar presence`() {
        val flags = EClientPersonaStateFlag.from(steamProfileRequestFlags())

        assertEquals(
            EnumSet.of(
                EClientPersonaStateFlag.PlayerName,
                EClientPersonaStateFlag.Presence,
            ),
            flags,
        )
    }

    @Test
    fun `name-only callback preserves cached avatar`() {
        val profile =
            mergeSteamProfile(
                current = SteamProfile(displayName = "Old name", avatarUrl = "https://example.com/avatar.jpg"),
                callbackDisplayName = "Current name",
                callbackAvatarUrl = null,
                statusFlags = setOf(EClientPersonaStateFlag.PlayerName),
            )

        assertNotNull(profile)
        assertEquals("Current name", profile.displayName)
        assertEquals("https://example.com/avatar.jpg", profile.avatarUrl)
    }

    @Test
    fun `presence callback enriches cached profile without a repeated name`() {
        val profile =
            mergeSteamProfile(
                current = SteamProfile(displayName = "Current name"),
                callbackDisplayName = "",
                callbackAvatarUrl = "https://example.com/avatar.jpg",
                statusFlags = setOf(EClientPersonaStateFlag.Presence),
            )

        assertNotNull(profile)
        assertEquals("Current name", profile.displayName)
        assertEquals("https://example.com/avatar.jpg", profile.avatarUrl)
    }
}
