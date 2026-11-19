package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import org.junit.Test
import kotlin.test.assertEquals

class WorkshopDetailSeedMergeTest {
    @Test
    fun `fresh detail keeps precise update time from list seed when missing`() {
        val seed = detail(updatedAt = 1_712_345_678L)
        val fresh = detail(updatedAt = null)

        val merged = mergeFreshDetailWithSeed(fresh, seed)

        assertEquals(seed.updatedAt, merged.updatedAt)
        assertEquals(seed.updatedAt, merged.summary.updatedAt)
    }

    @Test
    fun `fresh precise update time takes precedence over list seed`() {
        val seed = detail(updatedAt = 1_712_345_678L)
        val fresh = detail(updatedAt = 1_812_345_678L)

        val merged = mergeFreshDetailWithSeed(fresh, seed)

        assertEquals(fresh.updatedAt, merged.updatedAt)
        assertEquals(fresh.updatedAt, merged.summary.updatedAt)
    }

    private fun detail(updatedAt: Long?): WorkshopDetail =
        WorkshopDetail(
            summary = WorkshopSummary(id = 42L, title = "Workshop item", author = "Creator", updatedAt = updatedAt),
            updatedAt = updatedAt,
        )
}
