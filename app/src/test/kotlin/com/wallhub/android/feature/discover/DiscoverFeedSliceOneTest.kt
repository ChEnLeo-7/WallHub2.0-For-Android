package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DiscoverFeedbackRepository
import com.wallhub.android.core.model.DiscoverFeedbackWeight
import com.wallhub.android.core.model.DiscoverRailFeedback
import com.wallhub.android.core.model.DiscoverSavedQuery
import com.wallhub.android.core.model.DiscoverSavedQueryRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverFeedSliceOneTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `blocked metadata does not block the first static rail`() = runTest {
        val loader = RecordingLoader(specs = listOf(spec(1)), blockMetadata = true)
        val viewModel = viewModel(loader)

        viewModel.setVisible(true)
        runCurrent()

        assertEquals(DiscoverRailLoadState.READY, viewModel.state.value.rails.single().loadState)
        assertTrue(!loader.metadataGate.isCompleted)
        loader.metadataGate.complete(Unit)
    }

    @Test
    fun `cached curator generation replaces generic bootstrap`() = runTest {
        val cached = spec(9).copy(source = DiscoverSpecSource.CURATOR_MANIFEST)
        val loader = RecordingLoader(specs = listOf(spec(1)), cachedSpecs = listOf(cached))
        val viewModel = viewModel(loader)

        viewModel.setVisible(true)
        runCurrent()

        assertTrue(loader.staticGenerations.isEmpty())
        assertEquals(listOf(cached.id), viewModel.state.value.rails.map { it.spec.id })
        assertEquals(DiscoverMetadataSource.PERSISTED_CACHE, viewModel.state.value.metadataSource)
    }

    @Test
    fun `one feed worker keeps maximum active row loads at one`() = runTest {
        val loader = RecordingLoader(specs = listOf(spec(1), spec(2), spec(3)))
        val viewModel = viewModel(loader)

        viewModel.setVisible(true)
        runCurrent()

        assertEquals(1, loader.maxActiveLoads)
        assertEquals(3, loader.loadedRailIds.size)
    }

    @Test
    fun `child resume does not create a new generation`() = runTest {
        val loader = RecordingLoader(specs = listOf(spec(1)))
        val viewModel = viewModel(loader)
        viewModel.setVisible(true)
        runCurrent()
        val generation = viewModel.state.value.generation

        viewModel.setVisible(false)
        viewModel.setVisible(true)
        runCurrent()

        assertEquals(generation, viewModel.state.value.generation)
        assertEquals(1, loader.staticGenerations.size)
    }

    @Test
    fun `cancelling a loading rail queues it for resume`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val loader = RecordingLoader(specs = listOf(spec(1)), loadGate = loadGate)
        val viewModel = viewModel(loader)
        viewModel.setVisible(true)
        runCurrent()

        assertEquals(DiscoverRailLoadState.LOADING, viewModel.state.value.rails.single().loadState)
        viewModel.setVisible(false)
        runCurrent()

        assertEquals(DiscoverRailLoadState.QUEUED, viewModel.state.value.rails.single().loadState)
        loadGate.complete(Unit)
    }

    @Test
    fun `disliking active rail cancels it and starts replacement`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val loader = RecordingLoader(specs = listOf(spec(1), spec(2)), loadGate = loadGate)
        val viewModel = viewModel(loader)
        viewModel.setVisible(true)
        runCurrent()

        viewModel.toggleDislike(spec(1).id)
        runCurrent()

        assertTrue(spec(1).id in loader.cancelledRailIds)
        assertEquals(spec(2).id, viewModel.state.value.rails.single().spec.id)
        assertEquals(DiscoverRailLoadState.LOADING, viewModel.state.value.rails.single().loadState)
        loadGate.complete(Unit)
    }

    @Test
    fun `explicit refresh retains committed rows until replacement is ready`() = runTest {
        val secondStatic = CompletableDeferred<Unit>()
        val loader = RecordingLoader(specs = listOf(spec(1)), staticGateAfterFirst = secondStatic)
        val viewModel = viewModel(loader)
        viewModel.setVisible(true)
        runCurrent()
        val committed = viewModel.state.value.rails.single()

        viewModel.refresh()
        runCurrent()

        assertEquals(listOf(committed), viewModel.state.value.rails)
        assertTrue(viewModel.state.value.isRefreshing)
        secondStatic.complete(Unit)
        runCurrent()
        assertTrue(!viewModel.state.value.isRefreshing)
    }

    private fun viewModel(loader: DiscoverFeedLoader) =
        DiscoverViewModel(loader, FakeFeedbackRepository, FakeSavedQueryRepository, FakeSettingsRepository)

    private class RecordingLoader(
        private val specs: List<DiscoverRailSpec>,
        private val cachedSpecs: List<DiscoverRailSpec> = emptyList(),
        blockMetadata: Boolean = false,
        private val staticGateAfterFirst: CompletableDeferred<Unit>? = null,
        private val loadGate: CompletableDeferred<Unit>? = null,
    ) : DiscoverFeedLoader {
        val metadataGate = CompletableDeferred<Unit>().also { if (!blockMetadata) it.complete(Unit) }
        val staticGenerations = mutableListOf<Long>()
        val loadedRailIds = mutableListOf<String>()
        val cancelledRailIds = mutableListOf<String>()
        private var activeLoads = 0
        var maxActiveLoads = 0
            private set

        override suspend fun prepareStaticGeneration(generation: Long): DiscoverGeneration {
            staticGenerations += generation
            if (staticGenerations.size > 1) staticGateAfterFirst?.await()
            return DiscoverGeneration(generation, DiscoverMetadataSource.GENERIC_FALLBACK, "static", specs)
        }

        override suspend fun prepareCachedMetadataGeneration(generation: Long): DiscoverGeneration? =
            cachedSpecs.takeIf(List<DiscoverRailSpec>::isNotEmpty)?.let {
                DiscoverGeneration(generation, DiscoverMetadataSource.PERSISTED_CACHE, "cached", it)
            }

        override suspend fun prepareMetadataGeneration(generation: Long): DiscoverGeneration {
            metadataGate.await()
            return DiscoverGeneration(generation, DiscoverMetadataSource.NETWORK, "network", emptyList())
        }

        override suspend fun loadRail(
            spec: DiscoverRailSpec,
            generation: Long,
            excludedIds: Set<Long>,
        ): DiscoverRailLoadResult {
            activeLoads += 1
            maxActiveLoads = maxOf(maxActiveLoads, activeLoads)
            loadedRailIds += spec.id
            try {
                loadGate?.await()
            } catch (cancelled: CancellationException) {
                cancelledRailIds += spec.id
                throw cancelled
            } finally {
                activeLoads -= 1
            }
            return DiscoverRailLoadResult(
                items = listOf(WorkshopSummary(spec.id.hashCode().toLong(), spec.id, "author", previewUrl = "preview", type = WorkshopType.SCENE)),
                resolvedTitle = null,
                metrics = DiscoverRailMetrics(attempts = 1, resultCount = 1),
            )
        }
    }

    private object FakeFeedbackRepository : DiscoverFeedbackRepository {
        override val feedback: Flow<Map<String, DiscoverRailFeedback>> = MutableStateFlow(emptyMap())
        override suspend fun feedbackFor(railName: String) = DiscoverRailFeedback(railName)
        override suspend fun setLiked(railName: String, liked: Boolean) = Unit
        override suspend fun setDisliked(railName: String, disliked: Boolean) = Unit
        override suspend fun setFavorited(railName: String, favorited: Boolean) = Unit
        override suspend fun weightFor(railName: String) = DiscoverFeedbackWeight()
        override suspend fun weightsFor(railNames: Collection<String>) = railNames.associateWith { DiscoverFeedbackWeight() }
        override suspend fun clearFeedback(railName: String) = Unit
        override suspend fun clearAllFeedback() = Unit
    }

    private object FakeSavedQueryRepository : DiscoverSavedQueryRepository {
        override val queries: Flow<List<DiscoverSavedQuery>> = MutableStateFlow(emptyList())
        override suspend fun upsert(query: DiscoverSavedQuery) = Unit
        override suspend fun remove(queryId: String) = Unit
    }

    private object FakeSettingsRepository : SettingsRepository {
        override val preferences: Flow<AppPreferences> = MutableStateFlow(AppPreferences())
        override suspend fun setTheme(theme: com.wallhub.android.core.model.ThemePreference) = Unit
        override suspend fun setAccent(accent: com.wallhub.android.core.model.AccentPreference, customColor: String?) = Unit
        override suspend fun setHomePreferences(pageSize: Int, columns: Int, multiSelect: Boolean, cardAction: com.wallhub.android.core.model.HomeCardAction, matureContentEnabled: Boolean) = Unit
        override suspend fun setDownloadPreferences(maxConcurrentDownloads: Int, chunkDownloadConcurrency: Int, proxyUrl: String, mediaCacheLimitMb: Int) = Unit
        override suspend fun setOutputDirectory(treeUri: String, label: String) = Unit
        override suspend fun clearOutputDirectory() = Unit
    }

    private fun spec(index: Int) = DiscoverRailSpec(
        id = "static:rail-$index",
        category = DiscoverCategory.WALLPAPER,
        semantic = "most_recent",
        titleKind = DiscoverTitleKind.RECENT_APPROVED,
        queryPlan = DiscoverQueryPlan("rail-$index", "most_recent", WorkshopBrowseQuery(), fidelity = DiscoverQueryFidelity.EXACT),
        priority = 1f,
        weight = 1,
        sticky = false,
        source = DiscoverSpecSource.GENERIC_FALLBACK,
        drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
    )
}
