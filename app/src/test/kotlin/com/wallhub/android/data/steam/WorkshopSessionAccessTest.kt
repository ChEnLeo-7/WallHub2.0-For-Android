package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class WorkshopSessionAccessTest {
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
