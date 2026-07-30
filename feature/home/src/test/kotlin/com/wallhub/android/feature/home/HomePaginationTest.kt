package com.wallhub.android.feature.home

import org.junit.Test
import kotlin.test.assertEquals

class HomePaginationTest {
    @Test
    fun `new query does not inherit total count from previous results`() {
        assertEquals(
            null,
            resolveHomeTotalCount(
                reportedTotalCount = null,
                previousTotalCount = 2_582_380,
                append = false,
            ),
        )
    }

    @Test
    fun `appended page retains known total count when response omits it`() {
        assertEquals(
            330,
            resolveHomeTotalCount(
                reportedTotalCount = null,
                previousTotalCount = 330,
                append = true,
            ),
        )
    }

    @Test
    fun `reported Steam maximum page takes priority over total count`() {
        assertEquals(
            1_000,
            resolveHomeTotalPages(
                reportedTotalPages = 1_000,
                totalCount = 2_840_535,
                pageSize = 30,
                page = 1,
                hasNextPage = true,
            ),
        )
    }

    @Test
    fun `unknown maximum falls back to the next available page`() {
        assertEquals(
            2,
            resolveHomeTotalPages(
                reportedTotalPages = null,
                totalCount = null,
                pageSize = 30,
                page = 1,
                hasNextPage = true,
            ),
        )
    }
}
