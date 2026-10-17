package com.wallhub.android.data.steam

import com.wallhub.android.core.model.CmPublicDetailResult
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import io.steamcontent.sdk.WorkshopDetailsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCmDetailOutcomeMapperTest {
    @Test
    fun `typed CM statuses distinguish available missing and unavailable`() {
        val detail = WorkshopDetail(WorkshopSummary(id = 42L, title = "item", author = "author"))
        val available = mapCmDetailOutcome(WorkshopDetailsStatus.OK, null, detail)
        assertSame(detail, (available as CmPublicDetailResult.Available).detail)
        assertEquals(CmPublicDetailResult.NotFound, mapCmDetailOutcome(WorkshopDetailsStatus.NOT_FOUND, 9, null))
        val unavailable = mapCmDetailOutcome(WorkshopDetailsStatus.TEMPORARILY_UNAVAILABLE, 3, null)
        assertTrue(unavailable is CmPublicDetailResult.Unavailable)
        assertEquals("CM status=5 service=3", (unavailable as CmPublicDetailResult.Unavailable).reason)
    }
}
