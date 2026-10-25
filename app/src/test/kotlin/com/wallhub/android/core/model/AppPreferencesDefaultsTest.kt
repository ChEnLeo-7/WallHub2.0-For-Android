package com.wallhub.android.core.model

import kotlin.test.Test
import kotlin.test.assertFalse

class AppPreferencesDefaultsTest {
    @Test
    fun `automatic anti blocking is disabled by default`() {
        assertFalse(AppPreferences().steamAccessEnabled)
    }
}
