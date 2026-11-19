package com.wallhub.android.data.steam

import com.wallhub.android.data.downloads.nextContentSteamLoginId
import kotlin.test.Test
import kotlin.test.assertNotEquals

class SteamLoginIdTest {
    @Test
    fun contentSessionsDoNotReuseThePrimaryOrEachOthersLoginId() {
        val first = nextContentSteamLoginId()
        val second = nextContentSteamLoginId()

        assertNotEquals(PRIMARY_STEAM_LOGIN_ID, first)
        assertNotEquals(PRIMARY_STEAM_LOGIN_ID, second)
        assertNotEquals(first, second)
    }
}
