package com.wallhub.android.feature.library

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import com.wallhub.android.R
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopCollectionChange
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.AccountWorkshopSort
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.feature.home.HomeFilterSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshRaceTest {
    @Test
    fun `enter cold loads subscriptions and favorites exactly once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()

                assertEquals(
                    listOf(AccountWorkshopCollection.SUBSCRIPTIONS, AccountWorkshopCollection.FAVORITES),
                    fixture.repository.requests.map { it.query.collection }.sortedBy { it.ordinal },
                )
                assertEquals(listOf(1, 1), fixture.repository.requests.map { it.query.page })
                assertEquals(listOf(true, true), fixture.repository.requests.map { it.refresh })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `tab selection shows prefetched data without another request`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.repository.requests.first { it.query.collection == AccountWorkshopCollection.SUBSCRIPTIONS }
                    .response.complete(page(1, 11L, hasNextPage = true))
                fixture.repository.requests.first { it.query.collection == AccountWorkshopCollection.FAVORITES }
                    .response.complete(page(1, 21L, hasNextPage = false))
                runCurrent()
                val requestCount = fixture.repository.requests.size

                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                runCurrent()
                assertEquals(listOf(21L), fixture.viewModel.uiState.value.items.map { it.id })
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.SUBSCRIPTIONS))
                runCurrent()

                assertEquals(listOf(11L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(requestCount, fixture.repository.requests.size)
                assertEquals(true, fixture.viewModel.uiState.value.hasNextPage)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `refresh replaces only the selected collection and rejects its late request`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.repository.requests.first { it.query.collection == AccountWorkshopCollection.SUBSCRIPTIONS }
                    .response.complete(page(1, 11L, hasNextPage = true))
                fixture.repository.requests.first { it.query.collection == AccountWorkshopCollection.FAVORITES }
                    .response.complete(page(1, 21L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                val oldFavoritesPage = fixture.repository.requests.last()

                fixture.viewModel.onAction(LibraryAction.Refresh)
                runCurrent()
                val refresh = fixture.repository.requests.last()
                assertEquals(AccountWorkshopCollection.FAVORITES, refresh.query.collection)
                assertEquals(4, fixture.repository.requests.size)
                refresh.response.complete(page(1, 201L, hasNextPage = false))
                runCurrent()
                oldFavoritesPage.response.complete(page(2, 22L, hasNextPage = false))
                runCurrent()

                assertEquals(listOf(201L), fixture.viewModel.uiState.value.items.map { it.id })
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.SUBSCRIPTIONS))
                runCurrent()
                assertEquals(listOf(11L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(true, fixture.viewModel.uiState.value.hasNextPage)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `sort only filters reload both first pages and reject late prior sort results`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                val oldFavorites = fixture.repository.requests.single { it.query.collection == AccountWorkshopCollection.FAVORITES }
                fixture.completeNext(page(1, 11L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                val oldSubscriptionsPage = fixture.repository.requests.last()
                val beforeSort = fixture.viewModel.uiState.value
                val requestCount = fixture.repository.requests.size

                fixture.viewModel.onAction(
                    LibraryAction.ApplyFilters(
                        selection = beforeSort.currentFilterSelection(),
                        exactPhrase = beforeSort.exactPhrase,
                        sort = AccountWorkshopSort.TITLE_ASC,
                    ),
                )
                runCurrent()
                val reloads = fixture.repository.requests.drop(requestCount)
                assertEquals(2, reloads.size)
                assertEquals(
                    setOf(AccountWorkshopCollection.SUBSCRIPTIONS, AccountWorkshopCollection.FAVORITES),
                    reloads.map { it.query.collection }.toSet(),
                )
                assertEquals(listOf(1, 1), reloads.map { it.query.page })
                assertEquals(listOf(true, true), reloads.map { it.refresh })
                val subscriptions = reloads.single { it.query.collection == AccountWorkshopCollection.SUBSCRIPTIONS }
                val favorites = reloads.single { it.query.collection == AccountWorkshopCollection.FAVORITES }
                assertEquals(AccountWorkshopSort.TITLE_ASC, subscriptions.query.sort)
                assertEquals(AccountWorkshopSort.UPDATED_DESC, favorites.query.sort)
                assertEquals(beforeSort.currentFilterSelection(), fixture.viewModel.uiState.value.currentFilterSelection())

                subscriptions.response.complete(page(1, 101L, hasNextPage = true))
                favorites.response.complete(page(1, 201L, hasNextPage = false))
                runCurrent()
                oldSubscriptionsPage.response.complete(page(2, 12L, hasNextPage = false))
                oldFavorites.response.complete(page(1, 21L, hasNextPage = true))
                runCurrent()

                val state = fixture.viewModel.uiState.value
                assertEquals(listOf(101L), state.items.map { it.id })
                assertEquals(1, state.currentPage)
                assertEquals(2, state.nextPage)
                assertEquals(true, state.hasNextPage)
                assertEquals(AccountWorkshopSort.TITLE_ASC, state.sortFor())
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                runCurrent()
                assertEquals(listOf(201L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(false, fixture.viewModel.uiState.value.hasNextPage)
                assertEquals(requestCount + 2, fixture.repository.requests.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `changing favorites sort preserves the subscriptions sort across tab switches`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.repository.requests.forEach { it.response.complete(page(1, hasNextPage = false)) }
                runCurrent()
                val initial = fixture.viewModel.uiState.value
                fixture.viewModel.onAction(
                    LibraryAction.ApplyFilters(
                        selection = initial.currentFilterSelection(),
                        exactPhrase = initial.exactPhrase,
                        sort = AccountWorkshopSort.TITLE_ASC,
                    ),
                )
                runCurrent()
                fixture.repository.requests.filter { !it.response.isCompleted }
                    .forEach { it.response.complete(page(1, hasNextPage = false)) }
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                val requestCount = fixture.repository.requests.size
                val favoritesState = fixture.viewModel.uiState.value
                fixture.viewModel.onAction(
                    LibraryAction.ApplyFilters(
                        selection = favoritesState.currentFilterSelection(),
                        exactPhrase = favoritesState.exactPhrase,
                        sort = AccountWorkshopSort.CREATED_DESC,
                    ),
                )
                runCurrent()
                val reloads = fixture.repository.requests.drop(requestCount)
                assertEquals(2, reloads.size)
                assertEquals(
                    AccountWorkshopSort.TITLE_ASC,
                    reloads.single { it.query.collection == AccountWorkshopCollection.SUBSCRIPTIONS }.query.sort,
                )
                assertEquals(
                    AccountWorkshopSort.CREATED_DESC,
                    reloads.single { it.query.collection == AccountWorkshopCollection.FAVORITES }.query.sort,
                )
                reloads.forEach { it.response.complete(page(1, hasNextPage = false)) }
                runCurrent()

                assertEquals(AccountWorkshopSort.CREATED_DESC, fixture.viewModel.uiState.value.sortFor())
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.SUBSCRIPTIONS))
                runCurrent()
                assertEquals(AccountWorkshopSort.TITLE_ASC, fixture.viewModel.uiState.value.sortFor())
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                runCurrent()
                assertEquals(AccountWorkshopSort.CREATED_DESC, fixture.viewModel.uiState.value.sortFor())
                assertEquals(requestCount + 2, fixture.repository.requests.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `cold first page publishes each wallpaper id once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 1L, 1L, 2L, hasNextPage = false))
                runCurrent()

                assertEquals(listOf(1L, 2L), fixture.viewModel.uiState.value.items.map { it.id })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `late pagination cannot replace a completed refresh generation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 1L, 2L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                fixture.completeNext(page(2, 3L, 4L, hasNextPage = true))
                runCurrent()

                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                val oldPagination = fixture.repository.requests.last()
                fixture.viewModel.onAction(LibraryAction.Refresh)
                runCurrent()
                val refresh = fixture.repository.requests.last()

                refresh.response.complete(page(1, 101L, 102L, hasNextPage = true))
                runCurrent()
                oldPagination.response.complete(page(3, 5L, 6L, hasNextPage = false))
                runCurrent()

                val state = fixture.viewModel.uiState.value
                assertEquals(listOf(101L, 102L), state.items.map { it.id })
                assertEquals(state.items.size, state.items.map { it.id }.distinct().size)
                assertEquals(2, state.nextPage)
                assertEquals(1, state.currentPage)
                assertEquals(true, state.hasNextPage)
                assertFalse(state.isLoadingMore)
                assertFalse(state.isRefreshing)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `later refresh wins when two refreshes return out of order`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 1L, hasNextPage = true))
                runCurrent()

                fixture.viewModel.onAction(LibraryAction.Refresh)
                runCurrent()
                val firstRefresh = fixture.repository.requests.last()
                fixture.viewModel.onAction(LibraryAction.Refresh)
                runCurrent()
                val secondRefresh = fixture.repository.requests.last()

                secondRefresh.response.complete(page(1, 201L, hasNextPage = false))
                runCurrent()
                firstRefresh.response.complete(page(1, 101L, hasNextPage = true))
                runCurrent()

                val state = fixture.viewModel.uiState.value
                assertEquals(listOf(201L), state.items.map { it.id })
                assertEquals(false, state.hasNextPage)
                assertEquals(2, state.nextPage)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `disconnected signed in session never loads a personal collection`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val repository = ControlledAccountWorkshopRepository()
                val sessions = FakeSteamSessionRepository()
                sessions.session.value = sessions.session.value.copy(isConnected = false)
                val viewModel =
                    LibraryViewModel(
                        applicationContext = ContextWrapper(null),
                        steamSessionRepository = sessions,
                        accountWorkshopRepository = repository,
                        workshopRepository = proxy(),
                        downloadTaskRepository = proxy(),
                        settingsRepository = settingsRepository(),
                        savedStateHandle = SavedStateHandle(),
                    )
                runCurrent()
                viewModel.onAction(LibraryAction.Enter)
                runCurrent()

                assertEquals(0, repository.requests.size)
                assertEquals(emptyList(), viewModel.uiState.value.items)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `each collection preserves its pagination state across repeated switches`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.repository.requests.first { it.query.collection == AccountWorkshopCollection.SUBSCRIPTIONS }
                    .response.complete(page(1, 1L, hasNextPage = true))
                fixture.repository.requests.first { it.query.collection == AccountWorkshopCollection.FAVORITES }
                    .response.complete(page(1, 101L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                fixture.repository.requests.last().response.complete(page(2, 2L, hasNextPage = false))
                runCurrent()

                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                fixture.repository.requests.last().response.complete(page(2, 102L, hasNextPage = true))
                runCurrent()
                val requestCount = fixture.repository.requests.size

                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.SUBSCRIPTIONS))
                assertEquals(listOf(1L, 2L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(false, fixture.viewModel.uiState.value.hasNextPage)
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                assertEquals(listOf(101L, 102L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(3, fixture.viewModel.uiState.value.nextPage)
                assertEquals(requestCount, fixture.repository.requests.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `leaving and reentering page starts a new cold generation and rejects the old request`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 1L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                val oldPage = fixture.repository.requests.last()
                val requestsBeforeReentry = fixture.repository.requests.size

                fixture.viewModel.onAction(LibraryAction.Leave)
                fixture.viewModel.onAction(LibraryAction.Enter)
                runCurrent()
                val reentryRequests = fixture.repository.requests.drop(requestsBeforeReentry)
                assertEquals(2, reentryRequests.size)
                assertEquals(
                    setOf(AccountWorkshopCollection.SUBSCRIPTIONS, AccountWorkshopCollection.FAVORITES),
                    reentryRequests.map { it.query.collection }.toSet(),
                )
                val reentry = reentryRequests.single { it.query.collection == AccountWorkshopCollection.SUBSCRIPTIONS }
                oldPage.response.complete(page(2, 2L, hasNextPage = false))
                reentry.response.complete(page(1, 201L, 202L, hasNextPage = true))
                runCurrent()

                val state = fixture.viewModel.uiState.value
                assertEquals(listOf(201L, 202L), state.items.map { it.id })
                assertEquals(2, state.nextPage)
                assertEquals(1, state.currentPage)
                assertEquals(AccountWorkshopCollection.SUBSCRIPTIONS, reentry.query.collection)
                assertEquals(true, reentry.refresh)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `request from previous collection cannot commit after collection switch`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 1L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                val oldSubscriptionsPage = fixture.repository.requests.last()

                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                runCurrent()
                val favorites = fixture.repository.requests.first { it.query.collection == AccountWorkshopCollection.FAVORITES }
                favorites.response.complete(page(1, 301L, 302L, hasNextPage = false))
                runCurrent()
                oldSubscriptionsPage.response.complete(page(2, 2L, hasNextPage = false))
                runCurrent()

                val state = fixture.viewModel.uiState.value
                assertEquals(LibraryCollectionTab.FAVORITES, state.collection)
                assertEquals(listOf(301L, 302L), state.items.map { it.id })
                assertEquals(state.items.size, state.items.map { it.id }.distinct().size)
                assertEquals(AccountWorkshopCollection.FAVORITES, favorites.query.collection)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `unsubscribe removes the stable id only after explicit confirmation and ignores a repeated tap`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 41L, 42L, hasNextPage = true))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.first { it.id == 41L }
                val effect = async { fixture.viewModel.effects.first() }

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()

                assertEquals(1, fixture.repository.mutations.size)
                assertEquals(42L, fixture.repository.mutations.single().accountId)
                assertEquals(41L, fixture.repository.mutations.single().workshopId)
                assertEquals(false, fixture.repository.mutations.single().enabled)
                assertEquals(listOf(41L, 42L), fixture.viewModel.uiState.value.items.map { it.id })

                fixture.repository.completeMutation(
                    WorkshopInteraction(subscriptionState = SubscriptionState.NOT_SUBSCRIBED),
                )
                runCurrent()

                assertEquals(listOf(42L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(R.string.library_unsubscribed, (effect.await() as LibraryEffect.ShowMessage).messageRes)

                fixture.viewModel.onAction(LibraryAction.Leave)
                fixture.viewModel.onAction(LibraryAction.Enter)
                runCurrent()
                val reentry = fixture.repository.requests.last { it.query.collection == AccountWorkshopCollection.SUBSCRIPTIONS }
                assertEquals(1, reentry.query.page)
                assertEquals(true, reentry.refresh)
                reentry.response.complete(page(1, 42L, hasNextPage = false))
                runCurrent()
                assertEquals(listOf(42L), fixture.viewModel.uiState.value.items.map { it.id })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `unfavorite removes the stable id only after explicit confirmation and ignores a repeated tap`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 51L, 52L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                runCurrent()
                fixture.completeNext(page(1, 51L, 52L, hasNextPage = true))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.first { it.id == 51L }
                val effect = async { fixture.viewModel.effects.first() }

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()

                assertEquals(1, fixture.repository.mutations.size)
                assertEquals(AccountWorkshopCollection.FAVORITES, fixture.repository.mutations.single().collection)
                assertEquals(51L, fixture.repository.mutations.single().workshopId)
                assertEquals(listOf(51L, 52L), fixture.viewModel.uiState.value.items.map { it.id })

                fixture.repository.completeMutation(
                    WorkshopInteraction(favoriteState = FavoriteState.NOT_FAVORITED),
                )
                runCurrent()

                assertEquals(listOf(52L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(R.string.library_removed_from_favorites, (effect.await() as LibraryEffect.ShowMessage).messageRes)

                fixture.viewModel.onAction(LibraryAction.Leave)
                fixture.viewModel.onAction(LibraryAction.Enter)
                runCurrent()
                val reentry = fixture.repository.requests.last()
                assertEquals(AccountWorkshopCollection.FAVORITES, reentry.query.collection)
                assertEquals(1, reentry.query.page)
                assertEquals(true, reentry.refresh)
                reentry.response.complete(page(1, 52L, hasNextPage = false))
                runCurrent()
                assertEquals(listOf(52L), fixture.viewModel.uiState.value.items.map { it.id })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `unsubscribe keeps the item and reports failure when the repository does not confirm removal`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 61L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.single()
                val effect = async { fixture.viewModel.effects.first() }

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()
                fixture.repository.completeMutation(
                    WorkshopInteraction(subscriptionState = SubscriptionState.SUBSCRIBED),
                )
                runCurrent()

                assertEquals(listOf(61L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(R.string.library_remove_failed, (effect.await() as LibraryEffect.ShowMessage).messageRes)
                val requestCount = fixture.repository.requests.size
                fixture.repository.emitCollectionChange(AccountWorkshopCollection.SUBSCRIPTIONS, 61L)
                runCurrent()
                assertEquals(requestCount + 1, fixture.repository.requests.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `unfavorite keeps the item and reports failure when the repository request throws`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 71L, hasNextPage = false))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                runCurrent()
                fixture.completeNext(page(1, 71L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.single()
                val effect = async { fixture.viewModel.effects.first() }

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()
                fixture.repository.failMutation()
                runCurrent()

                assertEquals(listOf(71L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(R.string.library_remove_failed, (effect.await() as LibraryEffect.ShowMessage).messageRes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `cancelled unsubscribe keeps the item and preserves coroutine cancellation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 75L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.single()

                val effect = async { fixture.viewModel.effects.first() }
                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()
                fixture.repository.cancelMutation()
                runCurrent()

                assertEquals(listOf(75L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(R.string.library_remove_failed, (effect.await() as LibraryEffect.ShowMessage).messageRes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `request started before confirmed unsubscribe cannot restore the removed stable id`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 81L, 82L, hasNextPage = true))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.LoadNextPage)
                runCurrent()
                val oldPage = fixture.repository.requests.last()
                val item = fixture.viewModel.uiState.value.items.first { it.id == 81L }

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()
                fixture.repository.completeMutation(
                    WorkshopInteraction(subscriptionState = SubscriptionState.NOT_SUBSCRIBED),
                )
                runCurrent()
                oldPage.response.complete(page(2, 81L, 83L, hasNextPage = false))
                runCurrent()

                assertEquals(listOf(82L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(2, fixture.viewModel.uiState.value.nextPage)
                assertEquals(1, fixture.viewModel.uiState.value.currentPage)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `local mutation echo is suppressed even when collected after the repository response`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 91L, 92L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.first { it.id == 91L }
                val requestCount = fixture.repository.requests.size

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()
                fixture.repository.completeMutation(
                    WorkshopInteraction(subscriptionState = SubscriptionState.NOT_SUBSCRIBED),
                    emitChangeBeforeResponse = false,
                )
                runCurrent()
                fixture.repository.emitLastMutationChange()
                runCurrent()

                assertEquals(requestCount, fixture.repository.requests.size)
                assertEquals(listOf(92L), fixture.viewModel.uiState.value.items.map { it.id })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `confirmed unsubscribe does not cancel a favorites load started after the mutation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 101L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.single()

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                runCurrent()
                val favorites = fixture.repository.requests.last()

                fixture.repository.completeMutation(
                    WorkshopInteraction(subscriptionState = SubscriptionState.NOT_SUBSCRIBED),
                )
                runCurrent()

                assertEquals(LibraryCollectionTab.FAVORITES, fixture.viewModel.uiState.value.collection)
                assertEquals(true, fixture.viewModel.uiState.value.isLoading)
                favorites.response.complete(page(1, 201L, hasNextPage = false))
                runCurrent()
                assertEquals(listOf(201L), fixture.viewModel.uiState.value.items.map { it.id })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `unrelated same-collection change is not swallowed by a pending local mutation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 111L, 112L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.first { it.id == 111L }

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                runCurrent()
                fixture.repository.emitCollectionChange(AccountWorkshopCollection.SUBSCRIPTIONS, 999L)
                runCurrent()

                assertEquals(3, fixture.repository.requests.size)
                assertEquals(true, fixture.viewModel.uiState.value.isLoading)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `account switch before coroutine admission sends no unsubscribe`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 119L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.single()

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item))
                fixture.repository.activeAccountIdValue = 84L
                fixture.sessions.session.value = fixture.sessions.session.value.copy(accountName = "other-account")
                runCurrent()

                assertEquals(emptyList(), fixture.repository.mutations)
                assertEquals(emptyList(), fixture.viewModel.uiState.value.items)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `account switch before favorite coroutine admission sends no unfavorite`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.viewModel.onAction(LibraryAction.SelectCollection(LibraryCollectionTab.FAVORITES))
                fixture.completeNext(page(1, 120L, hasNextPage = false))
                runCurrent()
                val item = fixture.viewModel.uiState.value.items.single()

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(item, LibraryCollectionTab.FAVORITES))
                fixture.repository.activeAccountIdValue = 84L
                fixture.sessions.session.value = fixture.sessions.session.value.copy(accountName = "other-account")
                runCurrent()

                assertEquals(emptyList(), fixture.repository.mutations)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `mutation from previous account cannot alter or notify the current account`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                fixture.completeNext(page(1, 121L, hasNextPage = false))
                runCurrent()
                val oldAccountItem = fixture.viewModel.uiState.value.items.single()

                fixture.viewModel.onAction(LibraryAction.RemoveFromCollection(oldAccountItem))
                runCurrent()
                fixture.repository.activeAccountIdValue = 84L
                fixture.sessions.session.value =
                    fixture.sessions.session.value.copy(accountName = "other-account")
                runCurrent()
                fixture.completeNext(page(1, 121L, 122L, hasNextPage = false))
                runCurrent()

                fixture.repository.completeMutation(
                    WorkshopInteraction(subscriptionState = SubscriptionState.NOT_SUBSCRIBED),
                )
                runCurrent()

                assertEquals(listOf(121L, 122L), fixture.viewModel.uiState.value.items.map { it.id })
                assertEquals(null, withTimeoutOrNull(1L) { fixture.viewModel.effects.first() })
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun LibraryUiState.currentFilterSelection(): HomeFilterSelection =
        HomeFilterSelection(
            sort = WorkshopSort.TRENDING,
            days = 30,
            types = selectedTypes,
            ratings = selectedRatings,
            genres = selectedGenres,
            officialTags = selectedOfficialTags,
            excludedOfficialTags = selectedExcludedOfficialTags,
            resolutions = selectedResolutions,
        )

    private suspend fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val repository = ControlledAccountWorkshopRepository()
        val sessions = FakeSteamSessionRepository()
        val viewModel =
            LibraryViewModel(
                applicationContext = ContextWrapper(null),
                steamSessionRepository = sessions,
                accountWorkshopRepository = repository,
                workshopRepository = proxy(),
                downloadTaskRepository = proxy(),
                settingsRepository = settingsRepository(),
                savedStateHandle = SavedStateHandle(),
            )
        runCurrent()
        viewModel.onAction(LibraryAction.Enter)
        runCurrent()
        return Fixture(viewModel, repository, sessions)
    }

    private data class Fixture(
        val viewModel: LibraryViewModel,
        val repository: ControlledAccountWorkshopRepository,
        val sessions: FakeSteamSessionRepository,
    ) {
        fun completeNext(result: WorkshopPage) {
            val activeCollection = viewModel.uiState.value.collection.collection
            repository.requests
                .first { request -> request.query.collection == activeCollection && !request.response.isCompleted }
                .response
                .complete(result)
        }
    }

    private fun page(
        page: Int,
        vararg ids: Long,
        hasNextPage: Boolean,
    ) =
        WorkshopPage(
            items = ids.map { id -> WorkshopSummary(id = id, title = "item-$id", author = "author") },
            page = page,
            hasNextPage = hasNextPage,
        )

    private fun settingsRepository(): SettingsRepository =
        proxy { method ->
            if (method.name == "getPreferences") MutableStateFlow(AppPreferences()) else null
        }

    private inline fun <reified T> proxy(noinline answer: (java.lang.reflect.Method) -> Any? = { null }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> answer(method) } as T
}

private class ControlledAccountWorkshopRepository : AccountWorkshopRepository {
    override val collectionCounts = MutableStateFlow(com.wallhub.android.core.model.AccountWorkshopCounts())
    data class Request(
        val query: AccountWorkshopQuery,
        val refresh: Boolean,
        val response: CompletableDeferred<WorkshopPage> = CompletableDeferred(),
    )

    data class Mutation(
        val accountId: Long,
        val collection: AccountWorkshopCollection,
        val workshopId: Long,
        val enabled: Boolean,
        val summary: WorkshopSummary?,
        val response: CompletableDeferred<WorkshopInteraction> = CompletableDeferred(),
    )

    val requests = mutableListOf<Request>()
    val mutations = mutableListOf<Mutation>()
    var activeAccountIdValue: Long? = 42L
    private val mutableCollectionChanges = MutableSharedFlow<AccountWorkshopCollectionChange>(extraBufferCapacity = 1)
    override val collectionChanges = mutableCollectionChanges

    override fun activeAccountId(): Long? = activeAccountIdValue

    override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage = request(query, refresh = false)

    override suspend fun refreshCollection(query: AccountWorkshopQuery): WorkshopPage = request(query, refresh = true)

    private suspend fun request(query: AccountWorkshopQuery, refresh: Boolean): WorkshopPage {
        val request = Request(query, refresh)
        requests += request
        return withContext(NonCancellable) { request.response.await() }
    }

    fun completeMutation(
        interaction: WorkshopInteraction,
        emitChangeBeforeResponse: Boolean = true,
    ) {
        val mutation = mutations.last()
        if (emitChangeBeforeResponse) emitMutationChange(mutation)
        mutation.response.complete(interaction)
    }

    fun emitLastMutationChange() {
        emitMutationChange(mutations.last())
    }

    fun emitCollectionChange(
        collection: AccountWorkshopCollection,
        workshopId: Long,
        accountId: Long = requireNotNull(activeAccountIdValue),
    ) {
        mutableCollectionChanges.tryEmit(AccountWorkshopCollectionChange(accountId, collection, workshopId))
    }

    private fun emitMutationChange(mutation: Mutation) {
        emitCollectionChange(mutation.collection, mutation.workshopId, mutation.accountId)
    }

    fun failMutation() {
        mutations.last().response.completeExceptionally(IllegalStateException("controlled mutation failure"))
    }

    fun cancelMutation() {
        mutations.last().response.completeExceptionally(CancellationException("controlled mutation cancellation"))
    }

    override suspend fun resolveAuthorDisplayName(workshopId: Long): String? = null
    override suspend fun getInteraction(workshopId: Long) = WorkshopInteraction()

    override suspend fun setSubscribed(
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction =
        mutate(requireNotNull(activeAccountIdValue), AccountWorkshopCollection.SUBSCRIPTIONS, workshopId, subscribed, summary)

    override suspend fun setSubscribedForAccount(
        expectedAccountId: Long,
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction {
        check(activeAccountIdValue == expectedAccountId) { "The signed-in Steam account changed before the mutation" }
        return mutate(expectedAccountId, AccountWorkshopCollection.SUBSCRIPTIONS, workshopId, subscribed, summary)
    }

    override suspend fun setFavorited(
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction =
        mutate(requireNotNull(activeAccountIdValue), AccountWorkshopCollection.FAVORITES, workshopId, favorited, summary)

    private suspend fun mutate(
        accountId: Long,
        collection: AccountWorkshopCollection,
        workshopId: Long,
        enabled: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction {
        val mutation = Mutation(accountId, collection, workshopId, enabled, summary)
        mutations += mutation
        return mutation.response.await()
    }

    override suspend fun postComment(workshopId: Long, ownerId: String, text: String) = Unit
}

private class FakeSteamSessionRepository : SteamSessionRepository {
    override val session =
        MutableStateFlow(
            SteamSessionState(
                phase = SteamSessionPhase.SIGNED_IN,
                accountName = "account",
                hasStoredSession = true,
                isConnected = true,
            ),
        )

    override fun restorePersistedSession() = Unit
    override fun login(accountName: String, password: String) = Unit
    override fun submitSteamGuardCode(code: String) = Unit
    override fun useManualSteamGuardFallback() = Unit
    override fun logout() = Unit
}
