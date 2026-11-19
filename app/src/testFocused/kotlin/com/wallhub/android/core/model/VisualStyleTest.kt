package com.wallhub.android.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VisualStyleTest {
    @Test
    fun `missing blank and unknown persisted values fall back to Material 3`() {
        assertEquals(VisualStyle.MATERIAL3, VisualStyle.fromStableId(null))
        assertEquals(VisualStyle.MATERIAL3, VisualStyle.fromStableId(""))
        assertEquals(VisualStyle.MATERIAL3, VisualStyle.fromStableId("  "))
        assertEquals(VisualStyle.MATERIAL3, VisualStyle.fromStableId("MATERIAL3"))
        assertEquals(VisualStyle.MATERIAL3, VisualStyle.fromStableId("retired_style"))
    }

    @Test
    fun `stable ids are explicit and do not depend on enum order or display names`() {
        assertEquals("material3", VisualStyle.MATERIAL3.stableId)
        assertEquals("material3_expressive", VisualStyle.MATERIAL3_EXPRESSIVE.stableId)
        assertEquals(VisualStyle.MATERIAL3_EXPRESSIVE, VisualStyle.fromStableId("bettbox_inspired"))
        assertEquals(VisualStyle.MATERIAL3_EXPRESSIVE, VisualStyle.fromStableId("material3_expressive"))
        assertEquals(VisualStyle.MATERIAL3, VisualStyle.fromStableId("classic_wallhub"))
        assertNotEquals(VisualStyle.MATERIAL3.stableId, VisualStyle.MATERIAL3_EXPRESSIVE.stableId)
    }

    @Test
    fun `default is Material 3`() {
        assertEquals(VisualStyle.MATERIAL3, VisualStyle.DEFAULT)
        assertEquals(VisualStyle.MATERIAL3, AppPreferences().visualStyle)
    }

    @Test
    fun `changing visual style leaves theme palette and accent untouched`() {
        val before =
            AppPreferences(
                theme = ThemePreference.DARK,
                accent = AccentPreference.ROSE,
                useSystemMonet = false,
            )

        val after = before.copy(visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE)

        assertEquals(ThemePreference.DARK, after.theme)
        assertEquals(AccentPreference.ROSE, after.accent)
        assertEquals(false, after.useSystemMonet)
        assertEquals(VisualStyle.MATERIAL3_EXPRESSIVE, after.visualStyle)
    }
}

