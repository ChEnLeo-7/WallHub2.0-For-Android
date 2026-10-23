@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

data class ProfileCounts(
    val subscriptions: Int? = null,
    val favorites: Int? = null,
)

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
                session.collectLatest { current ->
                    when {
                        current.phase == SteamSessionPhase.SIGNED_IN -> scheduleProfileLoad(current.accountName)
                        current.phase == SteamSessionPhase.SIGNING_IN && current.hasStoredSession -> {
                            cancelProfileLoad()
                            loadedAccount = null
                        }
                        else -> {
                            cancelProfileLoad()
                            loadedAccount = null
                            _counts.value = ProfileCounts()
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

        private fun scheduleProfileLoad(accountName: String?) {
            if (!shouldScheduleProfileLoad(isVisible, accountName, loadedAccount) || accountName == loadingAccount) return
            cancelProfileLoad()
            loadingAccount = accountName
            val generation = profileLoadGeneration
            profileLoadJob =
                viewModelScope.launch {
                    try {
                        // Leave the serialized Steam lane free for an immediate navigation tap. If Profile exits
                        // during this idle window, no count RPC starts; once started, disposal cancels both reads.
                        delay(PROFILE_LOAD_IDLE_DELAY_MS)
                        coroutineScope {
                            val subscriptions = async { readCollectionCount(AccountWorkshopCollection.SUBSCRIPTIONS) }
                            val favorites = async { readCollectionCount(AccountWorkshopCollection.FAVORITES) }
                            val subscriptionCount = subscriptions.await()
                            val favoriteCount = favorites.await()
                            ensureActive()
                            _counts.value = ProfileCounts(subscriptionCount, favoriteCount)
                            loadedAccount = accountName.takeIf {
                                subscriptionCount != null && favoriteCount != null
                            }
                            val hours =
                                runCatching {
                                    playtimeRepository.getAppPlaytime(WALLPAPER_ENGINE_APP_ID)
                                        ?.totalMinutes
                                        ?.div(60)
                                }.getOrNull()
                            ensureActive()
                            _runtimeHours.value = hours
                        }
                    } finally {
                        if (profileLoadGeneration == generation) loadingAccount = null
                    }
                }
        }

        private suspend fun readCollectionCount(collection: AccountWorkshopCollection): Int? =
            runCatching {
                accountRepository.browseCollection(
                    AccountWorkshopQuery(
                        collection,
                        pageSize = 1,
                        resolveTotalCount = true,
                    ),
                ).totalCount ?: 0
            }.getOrNull()

        private fun cancelProfileLoad() {
            profileLoadGeneration += 1L
            profileLoadJob?.cancel()
            profileLoadJob = null
            loadingAccount = null
        }
    }

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
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileStatCard(stringResource(R.string.profile_subscriptions), counts.subscriptions?.toString() ?: "—", onOpenSubscriptions, Modifier.weight(1f))
                    ProfileStatCard(stringResource(R.string.profile_favorites), counts.favorites?.toString() ?: "—", onOpenFavorites, Modifier.weight(1f))
                }
            }
            item { ProfileEntryCard(stringResource(R.string.profile_downloads), stringResource(R.string.profile_download_hint), onOpenDownloads, Icons.Outlined.Download) }
            item { ProfileEntryCard(stringResource(R.string.profile_local), stringResource(R.string.profile_local_hint), onOpenLocal, Icons.Outlined.FolderOpen) }
        }
    }
}

@Composable
private fun ProfileStatCard(title: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier.size(108.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(title, maxLines = 2, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
