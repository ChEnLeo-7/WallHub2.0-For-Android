package com.wallhub.android.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallHubPaginationInputTest {
    @Test
    fun `middle page shows minimum current and maximum pages`() {
        assertEquals(
            listOf(1, 100, 1667),
            buildPaginationItems(currentPage = 100, totalPages = 1667),
        )
    }

    @Test
    fun `current minimum page is not duplicated`() {
        assertEquals(
            listOf(1, 1667),
            buildPaginationItems(currentPage = 1, totalPages = 1667),
        )
    }

    @Test
    fun `current maximum page is not duplicated`() {
        assertEquals(
            listOf(1, 1667),
            buildPaginationItems(currentPage = 1667, totalPages = 1667),
        )
    }

    @Test
    fun `single page is shown once`() {
        assertEquals(listOf(1), buildPaginationItems(currentPage = 1, totalPages = 1))
    }

    @Test
    fun `two-page result only shows both boundaries`() {
        assertEquals(listOf(1, 2), buildPaginationItems(currentPage = 1, totalPages = 2))
        assertEquals(listOf(1, 2), buildPaginationItems(currentPage = 2, totalPages = 2))
    }

    @Test
    fun `page window clamps invalid current page values`() {
        assertEquals(
            listOf(1, 10),
            buildPaginationItems(currentPage = -4, totalPages = 10),
        )
        assertEquals(
            listOf(1, 10),
            buildPaginationItems(currentPage = 99, totalPages = 10),
        )
    }

    @Test
    fun `sanitizes pasted input and removes leading zeroes`() {
        assertEquals("12", sanitizePaginationPageInput("00a1-2"))
        assertEquals("0", sanitizePaginationPageInput("000"))
        assertEquals("", sanitizePaginationPageInput("page"))
        assertEquals("12", sanitizePaginationPageInput("１２12"))
    }

    @Test
    fun `keeps arbitrarily long input visible for validation`() {
        assertEquals("999", sanitizePaginationPageInput("999"))
        assertEquals("21474836479", sanitizePaginationPageInput("21474836479"))
        assertEquals(
            "999999999999999999999",
            sanitizePaginationPageInput("999999999999999999999"),
        )
        assertNull(resolvePaginationPageInput("21474836479"))
    }

    @Test
    fun `resolves pages beyond the known total`() {
        assertEquals(1, resolvePaginationPageInput("1"))
        assertEquals(24, resolvePaginationPageInput("24"))
        assertEquals(999, resolvePaginationPageInput("999"))
        assertEquals(Int.MAX_VALUE, resolvePaginationPageInput(Int.MAX_VALUE.toString()))
    }

    @Test
    fun `rejects empty zero negative and overflowing pages`() {
        assertNull(resolvePaginationPageInput(""))
        assertNull(resolvePaginationPageInput("0"))
        assertNull(resolvePaginationPageInput("-1"))
        assertNull(resolvePaginationPageInput("999999999999999999999"))
    }
}
