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
            workshopSessionAccessForSort(WorkshopSort.FRIENDS_FAVORITES),
        )
        assertEquals(
            WorkshopSessionAccess.AUTHENTICATED_ACCOUNT,
            workshopSessionAccessForSort(WorkshopSort.FRIENDS_CREATED),
        )
        assertEquals(
            WorkshopSessionAccess.PUBLIC,
            workshopSessionAccessForSort(WorkshopSort.TRENDING),
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
