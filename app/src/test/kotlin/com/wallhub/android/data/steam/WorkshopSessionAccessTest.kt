package com.wallhub.android.data.steam

import com.wallhub.android.core.model.WorkshopSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class WorkshopSessionAccessTest {
    @Test
    fun `friend sorts require the authenticated account engine`() {
        assertEquals(
            WorkshopSessionAccess.AUTHENTICATED_ACCOUNT,
            workshopSessionAccessForBrowse(WorkshopSort.FRIENDS_FAVORITES, creatorId = null),
        )
        assertEquals(
            WorkshopSessionAccess.AUTHENTICATED_ACCOUNT,
            workshopSessionAccessForBrowse(WorkshopSort.FRIENDS_CREATED, creatorId = null),
        )
        assertEquals(
            WorkshopSessionAccess.PUBLIC,
            workshopSessionAccessForBrowse(WorkshopSort.TRENDING, creatorId = null),
        )
    }

    @Test
    fun `author items require the authenticated account engine regardless of sort`() {
        assertEquals(
            WorkshopSessionAccess.AUTHENTICATED_ACCOUNT,
            workshopSessionAccessForBrowse(WorkshopSort.MOST_RECENT, creatorId = "7656119810"),
        )
    }

    @Test
    fun `public CM reads do not wait for an authenticated account`() =
        runTest {
            var waits = 0

            awaitWorkshopSession(WorkshopSessionAccess.PUBLIC) { waits += 1 }

            assertEquals(0, waits)
        }

    @Test
    fun `account reads still wait for an authenticated account`() =
        runTest {
            var waits = 0

            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT) { waits += 1 }

            assertEquals(1, waits)
        }
}
