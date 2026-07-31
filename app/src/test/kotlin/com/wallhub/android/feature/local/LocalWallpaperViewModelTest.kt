package com.wallhub.android.feature.local

import androidx.lifecycle.SavedStateHandle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.LocalWallpaperDeleteResult
import com.wallhub.android.core.model.LocalWallpaperRepository
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LocalWallpaperViewModelTest {
    @Test
    fun `choose directory emits a one-time route effect`() =
        runTest {
            withViewModel { viewModel, effects, _ ->
                viewModel.onAction(LocalWallpaperAction.ChooseDirectory)

                assertIs<LocalWallpaperEffect.ChooseDirectory>(effects.single())
            }
        }

    @Test
    fun `system action failure is emitted as feedback effect`() =
        runTest {
            withViewModel { viewModel, effects, _ ->
                viewModel.onAction(LocalWallpaperAction.SystemActionFailed("failed"))

                assertEquals(
                    "failed",
                    assertIs<LocalWallpaperEffect.ShowMessage>(effects.single()).message,
                )
            }
        }

    @Test
    fun `successful external import callback persists requested state`() =
        runTest {
            withViewModel { viewModel, _, repository ->
                viewModel.onAction(LocalWallpaperAction.ImportLaunched(RESOURCE_ID))
                advanceUntilIdle()

                assertEquals(listOf(RESOURCE_ID), repository.importRequests.map(Pair<String, Long>::first))
            }
        }

    @Test
    fun `local actions update filters selection and compact detail return state`() =
        runTest {
            withViewModel { viewModel, _, _ ->
                viewModel.onAction(LocalWallpaperAction.SearchQueryChanged("rain"))
                viewModel.onAction(
                    LocalWallpaperAction.SelectFormatFilter(LocalWallpaperFormatFilter.MPKG),
                )
                viewModel.onAction(LocalWallpaperAction.SetFavoriteOnly(true))
                viewModel.onAction(LocalWallpaperAction.SelectResource(RESOURCE_ID))

                assertEquals("rain", viewModel.uiState.value.searchQuery)
                assertEquals(LocalWallpaperFormatFilter.MPKG, viewModel.uiState.value.formatFilter)
                assertEquals(true, viewModel.uiState.value.favoriteOnly)
                assertEquals(LocalWallpaperViewMode.DETAIL, viewModel.uiState.value.viewMode)
                assertEquals(RESOURCE_ID, viewModel.uiState.value.selectedResourceId)

                viewModel.onAction(LocalWallpaperAction.SelectResource(null))
                viewModel.onAction(LocalWallpaperAction.ResetFilters)

                assertEquals(LocalWallpaperViewMode.LIST, viewModel.uiState.value.viewMode)
                assertEquals(null, viewModel.uiState.value.selectedResourceId)
                assertEquals(LocalWallpaperFormatFilter.ALL, viewModel.uiState.value.formatFilter)
                assertEquals(false, viewModel.uiState.value.favoriteOnly)
            }
        }

    @Test
    fun `local filters and selection survive view model recreation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val savedStateHandle = SavedStateHandle()
                val first =
                    LocalWallpaperViewModel(
                        FakeLocalWallpaperRepository(),
                        EmptyLocalSettingsRepository,
                        savedStateHandle,
                    )
                first.onAction(LocalWallpaperAction.SearchQueryChanged("rain"))
                first.onAction(LocalWallpaperAction.SelectFormatFilter(LocalWallpaperFormatFilter.MPKG))
                first.onAction(LocalWallpaperAction.SetFavoriteOnly(true))
                first.onAction(LocalWallpaperAction.StartSelection(RESOURCE_ID))
                advanceUntilIdle()

                val restored =
                    LocalWallpaperViewModel(
                        FakeLocalWallpaperRepository(),
                        EmptyLocalSettingsRepository,
                        savedStateHandle,
                    )

                assertEquals("rain", restored.uiState.value.searchQuery)
                assertEquals(LocalWallpaperFormatFilter.MPKG, restored.uiState.value.formatFilter)
                assertEquals(true, restored.uiState.value.favoriteOnly)
                assertEquals(setOf(RESOURCE_ID), restored.uiState.value.selectedResourceIds)
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.withViewModel(
        block: suspend (
            LocalWallpaperViewModel,
            MutableList<LocalWallpaperEffect>,
            FakeLocalWallpaperRepository,
        ) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeLocalWallpaperRepository()
            val viewModel = LocalWallpaperViewModel(repository, EmptyLocalSettingsRepository)
            val effects = mutableListOf<LocalWallpaperEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.effects.collect(effects::add)
            }
            advanceUntilIdle()

            block(viewModel, effects, repository)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val RESOURCE_ID = "local-1"
    }
}

private class FakeLocalWallpaperRepository : LocalWallpaperRepository {
    val importRequests = mutableListOf<Pair<String, Long>>()

    override fun scan(): Flow<LocalWallpaperScanSnapshot> = flowOf(LocalWallpaperScanSnapshot())

    override suspend fun setFavorite(
        resourceId: String,
        favorite: Boolean,
    ) = Unit

    override suspend fun replaceTags(
        resourceId: String,
        tags: Set<String>,
    ) = Unit

    override suspend fun renameTag(
        oldTag: String,
        newTag: String,
    ) = Unit

    override suspend fun deleteTag(tag: String) = Unit

    override suspend fun markImportRequested(
        resourceId: String,
        requestedAt: Long,
    ) {
        importRequests += resourceId to requestedAt
    }

    override suspend fun delete(resource: LocalWallpaperResource): LocalWallpaperDeleteResult =
        LocalWallpaperDeleteResult(deleted = true, message = "deleted")
}

private object EmptyLocalSettingsRepository : SettingsRepository {
    override val preferences: Flow<AppPreferences> = MutableStateFlow(AppPreferences())

    override suspend fun setTheme(theme: ThemePreference) = Unit

    override suspend fun setLanguage(language: AppLanguage) = Unit

    override suspend fun setAccent(
        accent: AccentPreference,
        customColor: String?,
    ) = Unit

    override suspend fun setHomePreferences(
        pageSize: Int,
        columns: Int,
        multiSelect: Boolean,
        cardAction: HomeCardAction,
        matureContentEnabled: Boolean,
    ) = Unit

    override suspend fun setDownloadPreferences(
        maxConcurrentDownloads: Int,
        chunkDownloadConcurrency: Int,
        proxyUrl: String,
        mediaCacheLimitMb: Int,
    ) = Unit

    override suspend fun setOutputDirectory(
        treeUri: String,
        label: String,
    ) = Unit

    override suspend fun clearOutputDirectory() = Unit
}
