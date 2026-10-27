package com.wallhub.android.feature.home

import com.wallhub.android.core.model.WorkshopSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeCoverPrefetchPolicyTest {
    @Test
    fun `two column grid prefetches only the next two rows`() {
        val candidates = candidates(lastVisible = 3, columns = 2, listMode = false, budget = HomeCoverPrefetchBudget.UNMETERED)

        assertEquals(listOf(5L, 6L, 7L, 8L), candidates.map(HomeCoverPrefetchCandidate::itemId))
    }

    @Test
    fun `four column grid is capped at eight covers`() {
        val candidates = candidates(lastVisible = 7, columns = 4, listMode = false, budget = HomeCoverPrefetchBudget.UNMETERED)

        assertEquals(8, candidates.size)
        assertEquals(9L..16L, candidates.map(HomeCoverPrefetchCandidate::itemId).let { it.first()..it.last() })
    }

    @Test
    fun `list mode prefetches three covers`() {
        val candidates = candidates(lastVisible = 1, columns = 1, listMode = true, budget = HomeCoverPrefetchBudget.UNMETERED)

        assertEquals(listOf(3L, 4L, 5L), candidates.map(HomeCoverPrefetchCandidate::itemId))
    }

    @Test
    fun `metered network shrinks budget to one row`() {
        val candidates = candidates(lastVisible = 3, columns = 2, listMode = false, budget = HomeCoverPrefetchBudget.METERED)

        assertEquals(listOf(5L, 6L), candidates.map(HomeCoverPrefetchCandidate::itemId))
    }

    @Test
    fun `disabled budget and upward motion do not prefetch`() {
        assertTrue(candidates(3, 2, false, HomeCoverPrefetchBudget.DISABLED).isEmpty())
        assertTrue(
            selectHomeCoverPrefetchCandidates(
                items = items(),
                lastVisibleIndex = 3,
                columns = 2,
                listMode = false,
                movingForward = false,
                budget = HomeCoverPrefetchBudget.UNMETERED,
            ).isEmpty(),
        )
    }

    @Test
    fun `visible and prefetched covers share normalized url`() {
        assertEquals("https://example.test/cover.jpg", normalizeHomeCoverPreviewUrl("  https://example.test/cover.jpg  "))
        assertEquals(null, normalizeHomeCoverPreviewUrl("   "))
    }

    @Test
    fun `selection stays inside loaded window and skips empty or duplicate urls`() {
        val loaded =
            listOf(
                item(1),
                item(2),
                item(3, previewUrl = null),
                item(4, previewUrl = "https://example.test/shared.jpg"),
                item(5, previewUrl = "https://example.test/shared.jpg"),
            )

        val candidates =
            selectHomeCoverPrefetchCandidates(
                items = loaded,
                lastVisibleIndex = 1,
                columns = 2,
                listMode = false,
                movingForward = true,
                budget = HomeCoverPrefetchBudget.UNMETERED,
            )

        assertEquals(listOf(4L), candidates.map(HomeCoverPrefetchCandidate::itemId))
    }

    private fun candidates(
        lastVisible: Int,
        columns: Int,
        listMode: Boolean,
        budget: HomeCoverPrefetchBudget,
    ) =
        selectHomeCoverPrefetchCandidates(
            items = items(),
            lastVisibleIndex = lastVisible,
            columns = columns,
            listMode = listMode,
            movingForward = true,
            budget = budget,
        )

    private fun items() = (1L..20L).map(::item)

    private fun item(id: Long, previewUrl: String? = "https://example.test/$id.jpg") =
        WorkshopSummary(id = id, title = "item-$id", author = "author", previewUrl = previewUrl)
}
