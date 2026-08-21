package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.WorkshopBrowseQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverSamplingTest {
    @Test
    fun `same generation produces stable rail order`() {
        val specs = (1..12).map(::spec)

        val first = rankSpecs(specs, emptyMap(), generation = 42L)
        val second = rankSpecs(specs, emptyMap(), generation = 42L)

        assertEquals(first.map(DiscoverRailSpec::id), second.map(DiscoverRailSpec::id))
    }

    @Test
    fun `positive feedback raises a rail ahead of an equal peer`() {
        val specs = listOf(spec(1), spec(2))

        val ranked = rankSpecs(specs, mapOf("rail-2" to 10f), generation = 1L)

        assertEquals("rail-2", ranked.first().id)
    }

    @Test
    fun `retention keeps sticky rails and only the newest 25 ordinary rails`() {
        val sticky = DiscoverRailState(spec(0).copy(sticky = true))
        val ordinary = (1..30).map { DiscoverRailState(spec(it)) }

        val retained = retainBoundedRails(listOf(sticky) + ordinary)

        assertEquals(26, retained.size)
        assertTrue(sticky in retained)
        assertEquals((6..30).map { "rail-$it" }, retained.filterNot { it.spec.sticky }.map { it.spec.id })
    }

    @Test
    fun `expired generation result is ignored`() {
        val state = DiscoverFeedState(generation = 8L, rails = listOf(DiscoverRailState(spec(1))))

        val updated = state.updateRailIfCurrent(expectedGeneration = 7L, railId = "rail-1") {
            it.copy(loadState = DiscoverRailLoadState.READY)
        }

        assertEquals(state, updated)
    }

    @Test
    fun `one rail failure leaves sibling rail untouched`() {
        val first = DiscoverRailState(spec(1), loadState = DiscoverRailLoadState.LOADING)
        val sibling = DiscoverRailState(spec(2), loadState = DiscoverRailLoadState.READY)
        val state = DiscoverFeedState(generation = 3L, rails = listOf(first, sibling))

        val updated = state.updateRailIfCurrent(expectedGeneration = 3L, railId = first.spec.id) {
            it.copy(loadState = DiscoverRailLoadState.FAILED_FINAL, error = "failed")
        }

        assertEquals(DiscoverRailLoadState.FAILED_FINAL, updated.rails.first().loadState)
        assertEquals(sibling, updated.rails.last())
    }

    private fun spec(index: Int) =
        DiscoverRailSpec(
            id = "rail-$index",
            category = if (index % 2 == 0) DiscoverCategory.KEYWORD else DiscoverCategory.CREATOR,
            semantic = "top_rated",
            titleKind = DiscoverTitleKind.KEYWORD,
            titleArgument = "topic-$index",
            queryPlan =
                DiscoverQueryPlan(
                    descriptorId = "rail-$index",
                    semantic = "top_rated",
                    query = WorkshopBrowseQuery(),
                    fidelity = DiscoverQueryFidelity.EXACT,
                ),
            priority = 1f,
            weight = 1,
            sticky = false,
            source = DiscoverSpecSource.OFFICIAL_METADATA,
            drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
        )
}
