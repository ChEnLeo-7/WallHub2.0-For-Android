package com.wallhub.android.feature.discover

import com.wallhub.android.DiscoverResultsDestination
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopSummary
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    fun `initial discovery batch stops before an expensive focus showcase`() {
        val ordinary = (1..3).map(::spec)
        val creatorShowcase =
            spec(8).copy(id = "focus-creators", category = DiscoverCategory.CREATOR_SHOWCASE)
        val later = (4..7).map(::spec)
        val ranked = deferInitialShowcases(ordinary + creatorShowcase + later)
        val count = initialDiscoverRailCount(ranked)

        assertEquals(5, count)
        assertTrue(ranked.take(count).none { it.category.isFocusShowcase() })
        assertEquals(
            listOf("rail-1", "rail-2", "rail-3", "rail-4", "rail-5", "focus-creators", "rail-6", "rail-7"),
            ranked.map(DiscoverRailSpec::id),
        )
    }

    @Test
    fun `focus showcase resolves a bounded stable oversample`() {
        val children = (1..20).map(::spec)

        val first = sampleFeaturedChildren(children, generation = 42L, railId = "focus")
        val second = sampleFeaturedChildren(children, generation = 42L, railId = "focus")

        assertEquals(8, first.size)
        assertEquals(first.map(DiscoverRailSpec::id), second.map(DiscoverRailSpec::id))
    }

    @Test
    fun `candidate selection preserves Steam order while filtering invalid items`() {
        val rail = spec(1).copy(qualityRules = DiscoverQualityRules(targetItemCount = 3))
        val candidates =
            listOf(
                WorkshopSummary(5L, "five", "author", previewUrl = "preview", type = com.wallhub.android.core.model.WorkshopType.SCENE),
                WorkshopSummary(4L, "excluded", "author", previewUrl = "preview", type = com.wallhub.android.core.model.WorkshopType.SCENE),
                WorkshopSummary(3L, "missing preview", "author", type = com.wallhub.android.core.model.WorkshopType.SCENE),
                WorkshopSummary(2L, "two", "author", previewUrl = "preview", type = com.wallhub.android.core.model.WorkshopType.VIDEO),
                WorkshopSummary(1L, "one", "author", previewUrl = "preview", type = com.wallhub.android.core.model.WorkshopType.WEB),
            )

        val selected = selectDiscoverCandidates(rail, candidates, excludedIds = setOf(4L))

        assertEquals(listOf(5L, 2L, 1L), selected.map(WorkshopSummary::id))
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
    fun `results request fence rejects old generation and destination`() {
        val destination = destination("old")
        val request = DiscoverResultsRequest(4L, destination)

        assertTrue(request.isCurrent(4L, destination))
        assertTrue(!request.isCurrent(3L, destination))
        assertTrue(!request.isCurrent(4L, destination("new")))
    }

    @Test
    fun `discover rail retries only transient failures`() {
        assertTrue(isTransientDiscoverFailure(IOException("connection reset")))
        assertTrue(isTransientDiscoverFailure(IllegalStateException("session unavailable")))
        assertTrue(!isTransientDiscoverFailure(IllegalArgumentException("InvalidParam")))
        assertTrue(!isTransientDiscoverFailure(UnsupportedOperationException("unsupported")))
        assertTrue(!isTransientDiscoverFailure(CancellationException("cancelled")))
    }

    @Test
    fun `static refresh queues static matches but leaves metadata rows ready`() {
        val static = DiscoverRailState(spec(1), loadState = DiscoverRailLoadState.READY)
        val metadataSpec = spec(9).copy(source = DiscoverSpecSource.CURATOR_MANIFEST)
        val metadata = DiscoverRailState(metadataSpec, loadState = DiscoverRailLoadState.READY)

        val rows = rowsForStaticRefresh(listOf(spec(1), spec(2)), listOf(static, metadata), initialCount = 2)

        assertEquals(DiscoverRailLoadState.QUEUED, rows.first().loadState)
        assertEquals(metadata, rows[1])
        assertEquals(listOf("rail-1", "rail-9", "rail-2"), rows.map { it.spec.id })
    }

    @Test
    fun `live generation keeps committed cards visible while queuing their fresh replacement`() {
        val oldSpec = spec(1)
        val cards = listOf(WorkshopSummary(id = 42L, title = "old", author = "author"))
        val previous =
            DiscoverRailState(
                spec = oldSpec,
                loadState = DiscoverRailLoadState.READY,
                items = cards,
            )

        val rows = rowsForDiscoverGeneration(listOf(oldSpec), listOf(previous), preserveCommittedRows = true)

        assertEquals(cards, rows.single().items)
        assertEquals(DiscoverRailLoadState.QUEUED, rows.single().loadState)
    }

    @Test
    fun `live generation keeps previously loaded offscreen rows`() {
        val first = DiscoverRailState(spec(1), loadState = DiscoverRailLoadState.READY)
        val offscreen = DiscoverRailState(spec(9), loadState = DiscoverRailLoadState.READY)

        val rows = rowsForDiscoverGeneration(listOf(spec(1), spec(2)), listOf(first, offscreen), preserveCommittedRows = true)

        assertEquals(listOf("rail-1", "rail-9", "rail-2"), rows.map { it.spec.id })
        assertTrue(rows.all { it.loadState == DiscoverRailLoadState.QUEUED })
    }

    @Test
    fun `initial generation has no retained cards`() {
        val rows = rowsForDiscoverGeneration(listOf(spec(1)), emptyList(), preserveCommittedRows = false)

        assertTrue(rows.single().items.isEmpty())
        assertEquals(DiscoverRailLoadState.QUEUED, rows.single().loadState)
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

    private fun destination(railId: String) =
        DiscoverResultsDestination(
            railId = railId,
            titleKind = DiscoverTitleKind.KEYWORD.name,
            semantic = "top_rated",
        )

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
            source = DiscoverSpecSource.CURATOR_MANIFEST,
            drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
        )
}
