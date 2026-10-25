package com.wallhub.android.feature.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileForegroundLoadPolicyTest {
    @Test
    fun `visible signed-in profile schedules account metadata and collection totals`() {
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
