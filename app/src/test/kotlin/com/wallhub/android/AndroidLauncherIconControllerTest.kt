package com.wallhub.android

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidLauncherIconControllerTest {
    @Test
    fun `themed selection enables themed alias`() {
        assertEquals(
            LauncherIconSelection(
                enabledAlias = "MainActivityThemedIcon",
                disabledAlias = "MainActivityColorIcon",
            ),
            launcherIconSelection(themedIconEnabled = true),
        )
    }

    @Test
    fun `color selection enables color alias`() {
        assertEquals(
            LauncherIconSelection(
                enabledAlias = "MainActivityColorIcon",
                disabledAlias = "MainActivityThemedIcon",
            ),
            launcherIconSelection(themedIconEnabled = false),
        )
    }
}
