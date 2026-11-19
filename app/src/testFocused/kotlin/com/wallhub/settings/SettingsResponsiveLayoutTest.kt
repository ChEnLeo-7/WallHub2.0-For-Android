package com.wallhub.settings

import com.wallhub.android.core.designsystem.WallHubWindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsResponsiveLayoutTest {
    @Test
    fun `expanded settings persists Basic as its initial visible detail`() {
        assertEquals(SettingsCategory.BASIC.name, initialSettingsPage(openSteamSignIn = false, expanded = true))
        assertEquals(null, initialSettingsPage(openSteamSignIn = false, expanded = false))
        assertEquals(SettingsCategory.STEAM.name, initialSettingsPage(openSteamSignIn = true, expanded = true))
    }

    @Test
    fun `only expanded windows keep settings categories beside details`() {
        assertFalse(useExpandedSettingsLayout(WallHubWindowLayout.COMPACT))
        assertFalse(useExpandedSettingsLayout(WallHubWindowLayout.MEDIUM))
        assertTrue(useExpandedSettingsLayout(WallHubWindowLayout.EXPANDED))
    }
}
