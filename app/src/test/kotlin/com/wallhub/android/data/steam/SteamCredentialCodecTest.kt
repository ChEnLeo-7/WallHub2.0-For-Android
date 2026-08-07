package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SteamCredentialCodecTest {
    @Test
    fun `v2 record preserves arbitrary persona metadata`() {
        val credential =
            PersistedSteamCredential(
                accountName = "account",
                refreshToken = "token\u001Fvalue",
                personaName = "昵称 \u001F Persona",
                avatarUrl = "https://avatars.example/avatar.jpg?x=1&y=2",
            )

        assertEquals(credential, decodeSteamCredential(encodeSteamCredential(credential)))
    }

    @Test
    fun `legacy v1 record remains readable`() {
        val separator = "\u001F"

        assertEquals(
            PersistedSteamCredential(accountName = "legacy", refreshToken = "refresh-token"),
            decodeSteamCredential("v1${separator}legacy${separator}refresh-token"),
        )
    }

    @Test
    fun `v2 record preserves missing profile`() {
        val credential = PersistedSteamCredential(accountName = "account", refreshToken = "token")

        assertEquals(credential, decodeSteamCredential(encodeSteamCredential(credential)))
    }

    @Test
    fun `malformed record is rejected`() {
        assertFailsWith<IllegalArgumentException> { decodeSteamCredential("v2\u001Finvalid") }
    }
}
