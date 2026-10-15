package com.wallhub.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopSourceCacheSettingsTest {
    @Test
    fun `budget presets include requested source cache sizes`() {
        assertEquals(WorkshopSourceCacheBudgetPreset.GIB_1, workshopSourceCacheBudgetPreset(1))
        assertEquals(WorkshopSourceCacheBudgetPreset.GIB_2, workshopSourceCacheBudgetPreset(2))
        assertEquals(WorkshopSourceCacheBudgetPreset.GIB_5, workshopSourceCacheBudgetPreset(5))
        assertEquals(WorkshopSourceCacheBudgetPreset.GIB_10, workshopSourceCacheBudgetPreset(10))
        assertEquals(WorkshopSourceCacheBudgetPreset.CUSTOM, workshopSourceCacheBudgetPreset(3))
    }

    @Test
    fun `custom budget accepts only positive whole GiB`() {
        assertEquals(7, parsePositiveGiB(" 7 "))
        assertNull(parsePositiveGiB("0"))
        assertNull(parsePositiveGiB("-1"))
        assertNull(parsePositiveGiB("1.5"))
        assertNull(parsePositiveGiB("lots"))
        assertNull(parsePositiveGiB("999999999999999999999"))
    }
}
