package com.wallhub.android.feature.profile

import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileForegroundLoadPolicyTest {
    @Test
    fun `unknown stale total is not displayed as zero`() {
        val stale =
            WorkshopPage(
                items = listOf(WorkshopSummary(1L, "item", "author")),
                page = 1,
                hasNextPage = true,
                totalCount = null,
            )
        val authoritativeEmpty = WorkshopPage(emptyList(), page = 1, hasNextPage = false, totalCount = null)

        assertNull(profileCollectionCount(stale))
        assertEquals(0, profileCollectionCount(authoritativeEmpty))
    }

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
