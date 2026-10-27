package com.wallhub.android.feature.profile

import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun `successful count becomes loaded while failed sibling becomes unavailable`() {
        val subscriptions = profileCountState(Result.success(12))
        val favorites = profileCountState(Result.failure(IllegalStateException("offline")))

        assertEquals(ProfileCountState.Loaded(12), subscriptions)
        assertIs<ProfileCountState.Unavailable>(favorites)
    }

    @Test
    fun `count retry keeps a successful sibling while retrying unavailable count`() {
        val previous =
            ProfileCounts(
                subscriptions = ProfileCountState.Loaded(12),
                favorites = ProfileCountState.Unavailable,
            )

        assertEquals(
            ProfileCounts(
                subscriptions = ProfileCountState.Loaded(12),
                favorites = ProfileCountState.Loading,
            ),
            previous.loadingUnavailableCounts(),
        )
    }

    @Test
    fun `signed out count state explicitly requires sign in`() {
        assertEquals(
            ProfileCounts(
                subscriptions = ProfileCountState.SignInRequired,
                favorites = ProfileCountState.SignInRequired,
            ),
            profileCountsForSignedOut(),
        )
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
