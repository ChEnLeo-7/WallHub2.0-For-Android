package com.wallhub.android.data.settings

import com.wallhub.android.core.model.DEFAULT_HOME_PAGE_SIZE
import com.wallhub.android.core.model.HOME_PAGE_SIZE_OPTIONS
import com.wallhub.android.core.model.normalizedHomePageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePreferenceOptionsTest {
    @Test
    fun `page size options contain only supported values`() {
        assertEquals(listOf(10, 15, 30, 50), HOME_PAGE_SIZE_OPTIONS)
        assertEquals(30, DEFAULT_HOME_PAGE_SIZE)
    }

    @Test
    fun `legacy and out of range page sizes normalize to supported values`() {
        assertEquals(30, 24.normalizedHomePageSize())
        assertEquals(10, 1.normalizedHomePageSize())
        assertEquals(50, 100.normalizedHomePageSize())
    }
}
