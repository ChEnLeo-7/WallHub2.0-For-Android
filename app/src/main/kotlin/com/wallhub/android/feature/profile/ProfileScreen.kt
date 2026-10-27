@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProfileCountState {
    data object Loading : ProfileCountState

    data class Loaded(val count: Int) : ProfileCountState

    data object Unavailable : ProfileCountState

    data object SignInRequired : ProfileCountState
}

data class ProfileCounts(
    val subscriptions: ProfileCountState = ProfileCountState.Loading,
    val favorites: ProfileCountState = ProfileCountState.Loading,
)

internal fun profileCountState(result: Result<Int?>): ProfileCountState =
    result.fold(
        onSuccess = { count -> count?.let(ProfileCountState::Loaded) ?: ProfileCountState.Unavailable },
        onFailure = { ProfileCountState.Unavailable },
    )

internal fun ProfileCounts.loadingUnavailableCounts(): ProfileCounts =
    copy(
        subscriptions = subscriptions.takeUnless { it is ProfileCountState.Unavailable } ?: ProfileCountState.Loading,
        favorites = favorites.takeUnless { it is ProfileCountState.Unavailable } ?: ProfileCountState.Loading,
    )

internal fun profileCountsForSignedOut() =
    ProfileCounts(ProfileCountState.SignInRequired, ProfileCountState.SignInRequired)

@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        sessionRepository: SteamSessionRepository,
        private val accountRepository: AccountWorkshopRepository,
        private val playtimeRepository: SteamPlaytimeRepository,
    ) : ViewModel() {
        val session: StateFlow<SteamSessionState> = sessionRepository.session
        private val _counts = MutableStateFlow(ProfileCounts())
        val counts: StateFlow<ProfileCounts> = _counts
        private val _runtimeHours = MutableStateFlow<Int?>(null)
        val runtimeHours: StateFlow<Int?> = _runtimeHours
        private var isVisible = false
        private var loadedAccount: String? = null
        private var loadingAccount: String? = null
        private var profileLoadJob: Job? = null
        private var profileLoadGeneration = 0L

        init {
            viewModelScope.launch {
                accountRepository.collectionChanges.collectLatest {
                    loadedAccount = null
                    if (isVisible && session.value.phase == SteamSessionPhase.SIGNED_IN) {
                        scheduleProfileLoad(session.value.accountName)
                    }
                }
            }
            viewModelScope.launch {
                session.collectLatest { current ->
                    when {
                        current.phase == SteamSessionPhase.SIGNED_IN -> scheduleProfileLoad(current.accountName)
                        current.phase == SteamSessionPhase.SIGNING_IN && current.hasStoredSession -> {
                            cancelProfileLoad()
                            loadedAccount = null
                            _counts.value = ProfileCounts()
                            _runtimeHours.value = null
                        }
                        else -> {
                            cancelProfileLoad()
                            loadedAccount = null
                            _counts.value = profileCountsForSignedOut()
                            _runtimeHours.value = null
                        }
                    }
                }
            }
        }

        fun setVisible(visible: Boolean) {
            isVisible = visible
            if (visible && session.value.phase == SteamSessionPhase.SIGNED_IN) {
                scheduleProfileLoad(session.value.accountName)
            } else if (!visible) {
                cancelProfileLoad()
            }
        }

        fun retryCounts() {
            val accountName = session.value.accountName
            if (!isVisible || session.value.phase != SteamSessionPhase.SIGNED_IN || accountName.isNullOrBlank()) return
            loadedAccount = null
            _counts.value = _counts.value.loadingUnavailableCounts()
            scheduleProfileLoad(accountName, preserveLoadedCounts = true)
        }

        private fun scheduleProfileLoad(accountName: String?, preserveLoadedCounts: Boolean = false) {
            if (!shouldScheduleProfileLoad(isVisible, accountName, loadedAccount) || accountName == loadingAccount) return
            cancelProfileLoad()
            if (!preserveLoadedCounts && !accountName.equals(loadedAccount, ignoreCase = true)) {
                _counts.value = ProfileCounts()
                _runtimeHours.value = null
            }
            loadingAccount = accountName
            val generation = profileLoadGeneration
            profileLoadJob =
                viewModelScope.launch {
                    try {
                        delay(PROFILE_LOAD_IDLE_DELAY_MS)
                        coroutineScope {
                            val currentCounts = _counts.value
                            val subscriptions = async {
                                if (currentCounts.subscriptions is ProfileCountState.Loaded) currentCounts.subscriptions
                                else readCollectionCount(AccountWorkshopCollection.SUBSCRIPTIONS)
                            }
                            val favorites = async {
                                if (currentCounts.favorites is ProfileCountState.Loaded) currentCounts.favorites
                                else readCollectionCount(AccountWorkshopCollection.FAVORITES)
                            }
                            val playtime = async {
                                runCatching {
                                    playtimeRepository.getAppPlaytime(WALLPAPER_ENGINE_APP_ID)?.totalMinutes?.div(60)
                                }.getOrNull()
                            }
                            val subscriptionState = subscriptions.await()
                            val favoriteState = favorites.await()
                            val hours = playtime.await()
                            ensureActive()
                            _counts.value = ProfileCounts(subscriptionState, favoriteState)
                            _runtimeHours.value = hours
                            loadedAccount = accountName
                        }
                    } finally {
                        if (profileLoadGeneration == generation) loadingAccount = null
                    }
                }
        }

        private suspend fun readCollectionCount(collection: AccountWorkshopCollection): ProfileCountState =
            try {
                profileCountState(
                    Result.success(
                        accountRepository.browseCollection(
                            AccountWorkshopQuery(
                                collection = collection,
                                pageSize = 1,
                                resolveTotalCount = true,
                            ),
                        ).let(::profileCollectionCount),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                profileCountState(Result.failure(failure))
            }

        private fun cancelProfileLoad() {
            profileLoadGeneration += 1L
            profileLoadJob?.cancel()
            profileLoadJob = null
            loadingAccount = null
        }
    }

internal fun profileCollectionCount(page: com.wallhub.android.core.model.WorkshopPage): Int? =
    page.totalCount ?: if (page.items.isEmpty() && !page.hasNextPage) 0 else null

internal fun shouldScheduleProfileLoad(
    isVisible: Boolean,
    accountName: String?,
    loadedAccount: String?,
): Boolean =
    isVisible &&
        !accountName.isNullOrBlank() &&
        !accountName.equals(loadedAccount, ignoreCase = true)

private const val PROFILE_LOAD_IDLE_DELAY_MS = 300L
private const val WALLPAPER_ENGINE_APP_ID = 431960

@Composable
fun ProfileRoute(
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenLogin: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val runtimeHours by viewModel.runtimeHours.collectAsStateWithLifecycle()
    val isSignedIn = session.phase == SteamSessionPhase.SIGNED_IN
    val isRestoring = session.phase == SteamSessionPhase.SIGNING_IN && session.hasStoredSession
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    WallHubPageScaffold(title = stringResource(R.string.navigation_profile), actions = {
        IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.management_settings)) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    AsyncImage(model = session.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(112.dp).clip(MaterialTheme.shapes.extraLarge))
                    Text(session.personaName ?: session.accountName ?: stringResource(R.string.profile_anonymous), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                    if (
                        session.phase == SteamSessionPhase.SIGNED_IN ||
                        (session.phase == SteamSessionPhase.SIGNING_IN && session.hasStoredSession)
                    ) {
                        Text(
                            stringResource(
                                R.string.profile_runtime_summary,
                                runtimeHours?.let { stringResource(R.string.profile_hours_format, it) }
                                    ?: stringResource(R.string.profile_runtime_unknown),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (
                        session.phase != SteamSessionPhase.SIGNED_IN &&
                        !(session.phase == SteamSessionPhase.SIGNING_IN && session.hasStoredSession)
                    ) {
                        TextButton(onClick = onOpenLogin) { Text(stringResource(R.string.profile_sign_in)) }
                    }
                }
            }
            item {
                ProfileStats(
                    counts = counts,
                    isSignedIn = isSignedIn,
                    enabled = !isRestoring,
                    onOpenSubscriptions = onOpenSubscriptions,
                    onOpenFavorites = onOpenFavorites,
                    onOpenLogin = onOpenLogin,
                )
            }
            if (isSignedIn && counts.hasUnavailableCount()) {
                item {
                    TextButton(onClick = viewModel::retryCounts) {
                        Text(stringResource(R.string.profile_count_retry))
                    }
                }
            }
            item { ProfileEntryCard(stringResource(R.string.profile_downloads), stringResource(R.string.profile_download_hint), onOpenDownloads, Icons.Outlined.Download) }
            item { ProfileEntryCard(stringResource(R.string.profile_local), stringResource(R.string.profile_local_hint), onOpenLocal, Icons.Outlined.FolderOpen) }
        }
    }
}

private fun ProfileCounts.hasUnavailableCount(): Boolean =
    subscriptions is ProfileCountState.Unavailable || favorites is ProfileCountState.Unavailable

@Composable
private fun ProfileStats(
    counts: ProfileCounts,
    isSignedIn: Boolean,
    enabled: Boolean,
    onOpenSubscriptions: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val oneColumn = maxWidth < 360.dp
        if (oneColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileStatCard(stringResource(R.string.profile_subscriptions), counts.subscriptions, if (isSignedIn) onOpenSubscriptions else onOpenLogin, enabled, Modifier.fillMaxWidth())
                ProfileStatCard(stringResource(R.string.profile_favorites), counts.favorites, if (isSignedIn) onOpenFavorites else onOpenLogin, enabled, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileStatCard(stringResource(R.string.profile_subscriptions), counts.subscriptions, if (isSignedIn) onOpenSubscriptions else onOpenLogin, enabled, Modifier.weight(1f))
                ProfileStatCard(stringResource(R.string.profile_favorites), counts.favorites, if (isSignedIn) onOpenFavorites else onOpenLogin, enabled, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProfileStatCard(title: String, state: ProfileCountState, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    val status =
        when (state) {
            ProfileCountState.Loading -> stringResource(R.string.profile_count_loading)
            is ProfileCountState.Loaded -> stringResource(R.string.profile_count_format, state.count)
            ProfileCountState.Unavailable -> stringResource(R.string.profile_count_unavailable)
            ProfileCountState.SignInRequired -> stringResource(R.string.library_sign_in_required)
        }
    val value =
        when (state) {
            is ProfileCountState.Loaded -> state.count.toString()
            ProfileCountState.SignInRequired -> stringResource(R.string.profile_sign_in)
            else -> status
        }
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .defaultMinSize(minHeight = 108.dp)
                .semantics(mergeDescendants = true) {
                    stateDescription = status
                    liveRegion = LiveRegionMode.Polite
                },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ProfileEntryCard(title: String, subtitle: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f).padding(start = if (icon == null) 0.dp else 16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
