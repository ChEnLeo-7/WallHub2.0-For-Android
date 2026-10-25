package com.wallhub.android.feature.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileForegroundLoadPolicyTest {
    @Test
    fun `signed-in profile schedules its non-collection metadata only while visible`() {
        assertTrue(
            shouldScheduleProfileLoad(
                isVisible = true,
                accountName = "account",
                loadedAccount = null,
            ),
        )
        assertFalse(
            shouldScheduleProfileLoad(
                isVisible = false,
                accountName = "account",
                loadedAccount = null,
            ),
        )
    }

    @Test
    fun `already loaded account is not scheduled again`() {
        assertFalse(
            shouldScheduleProfileLoad(
                isVisible = true,
                accountName = "Account",
                loadedAccount = "account",
            ),
        )
    }
}
