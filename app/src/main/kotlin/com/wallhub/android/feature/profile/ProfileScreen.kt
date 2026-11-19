@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wallhub.android.BuildConfig
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.model.AccountWorkshopCounts
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

internal fun profileCountsForSignedOut() =
    ProfileCounts(ProfileCountState.SignInRequired, ProfileCountState.SignInRequired)

internal fun AccountWorkshopCounts.toProfileCounts(session: SteamSessionState): ProfileCounts {
    val accountMatches =
        session.phase == SteamSessionPhase.SIGNED_IN &&
            session.isConnected &&
            !session.accountName.isNullOrBlank() &&
            session.accountName.equals(accountName, ignoreCase = true)
    if (!accountMatches || !isValid) {
        return if (session.phase == SteamSessionPhase.SIGNED_IN) ProfileCounts() else profileCountsForSignedOut()
    }
    if (isLoading) return ProfileCounts()
    return ProfileCounts(
        subscriptions = subscriptions?.let(ProfileCountState::Loaded) ?: ProfileCountState.Unavailable,
        favorites = favorites?.let(ProfileCountState::Loaded) ?: ProfileCountState.Unavailable,
    )
}

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
                accountRepository.collectionCounts.collectLatest { snapshot ->
                    _counts.value = snapshot.toProfileCounts(session.value)
                }
            }
            viewModelScope.launch {
                session.collectLatest { current ->
                    when {
                        current.phase == SteamSessionPhase.SIGNED_IN && current.isConnected -> {
                            _counts.value = accountRepository.collectionCounts.value.toProfileCounts(current)
                            scheduleProfileLoad(current.accountName)
                        }
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
            if (
                visible &&
                session.value.phase == SteamSessionPhase.SIGNED_IN &&
                session.value.isConnected
            ) {
                scheduleProfileLoad(session.value.accountName)
            } else if (!visible) {
                cancelProfileLoad()
            }
        }

        private fun scheduleProfileLoad(accountName: String?) {
            if (!shouldScheduleProfileLoad(isVisible, accountName, loadedAccount) || accountName == loadingAccount) return
            cancelProfileLoad()
            if (!accountName.equals(loadedAccount, ignoreCase = true)) _runtimeHours.value = null
            loadingAccount = accountName
            val generation = profileLoadGeneration
            profileLoadJob =
                viewModelScope.launch {
                    try {
                        delay(PROFILE_LOAD_IDLE_DELAY_MS)
                        val hours =
                            try {
                                playtimeRepository.getAppPlaytime(WALLPAPER_ENGINE_APP_ID)?.totalMinutes?.div(60)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                null
                            }
                        if (profileLoadGeneration == generation) {
                            _runtimeHours.value = hours
                            loadedAccount = accountName
                        }
                    } finally {
                        if (profileLoadGeneration == generation) loadingAccount = null
                    }
                }
        }

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
    val isSignedIn = session.phase == SteamSessionPhase.SIGNED_IN && session.isConnected
    val isRestoring = session.phase == SteamSessionPhase.SIGNING_IN && session.hasStoredSession
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    val profileStyle = wallHubStyle()
    val bettbox = profileStyle.capabilities.bettboxSurfaces
    WallHubPageScaffold(
        title = stringResource(R.string.navigation_profile),
        actions = {
            if (!bettbox) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.management_settings))
                }
            }
        },
    ) { padding ->
        if (bettbox) {
            BettboxProfileContent(
                session = session,
                runtimeHours = runtimeHours,
                counts = counts,
                isSignedIn = isSignedIn,
                enabled = !isRestoring,
                onOpenSettings = onOpenSettings,
                onOpenSubscriptions = onOpenSubscriptions,
                onOpenFavorites = onOpenFavorites,
                onOpenLocal = onOpenLocal,
                onOpenLogin = onOpenLogin,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            ProfileMd3Content(
                session = session,
                runtimeHours = runtimeHours,
                counts = counts,
                isSignedIn = isSignedIn,
                isRestoring = isRestoring,
                onOpenDownloads = onOpenDownloads,
                onOpenLocal = onOpenLocal,
                onOpenSubscriptions = onOpenSubscriptions,
                onOpenFavorites = onOpenFavorites,
                onOpenLogin = onOpenLogin,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
internal fun ProfileMd3Content(
    session: SteamSessionState,
    runtimeHours: Int?,
    counts: ProfileCounts,
    isSignedIn: Boolean,
    isRestoring: Boolean,
    onOpenDownloads: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                AsyncImage(model = session.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(112.dp).clip(style.shapes.card))
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
        item { ProfileEntryCard(stringResource(R.string.profile_downloads), stringResource(R.string.profile_download_hint), onOpenDownloads, Icons.Outlined.Download) }
        item { ProfileEntryCard(stringResource(R.string.profile_local), stringResource(R.string.profile_local_hint), onOpenLocal, Icons.Outlined.FolderOpen) }
    }
}

internal fun useExpandedProfileLayout(windowLayout: WallHubWindowLayout): Boolean =
    windowLayout == WallHubWindowLayout.EXPANDED

@Composable
internal fun BettboxProfileContent(
    session: SteamSessionState,
    runtimeHours: Int?,
    counts: ProfileCounts,
    isSignedIn: Boolean,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = useExpandedProfileLayout(LocalWallHubAdaptiveLayout.current.width)
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = LocalWallHubOverlayBottomClearance.current),
    ) {
        if (expanded) {
            item {
                Row(
                    modifier =
                        Modifier
                            .widthIn(max = 1080.dp)
                            .fillMaxWidth()
                            .padding(horizontal = WallHubSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(0.42f)) {
                        ProfileBettboxIdentity(session, runtimeHours, onOpenLogin)
                        ProfileBettboxStats(
                            counts = counts,
                            isSignedIn = isSignedIn,
                            enabled = enabled,
                            onOpenSubscriptions = onOpenSubscriptions,
                            onOpenFavorites = onOpenFavorites,
                            onOpenLogin = onOpenLogin,
                            modifier = Modifier.padding(top = WallHubSpacing.md),
                        )
                        ProfileVersionLabel()
                    }
                    ProfileBettboxEntries(
                        onOpenLocal = onOpenLocal,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.weight(0.58f).padding(top = 24.dp),
                    )
                }
            }
        } else {
            item { ProfileBettboxIdentity(session, runtimeHours, onOpenLogin) }
            item {
                ProfileBettboxStats(
                    counts = counts,
                    isSignedIn = isSignedIn,
                    enabled = enabled,
                    onOpenSubscriptions = onOpenSubscriptions,
                    onOpenFavorites = onOpenFavorites,
                    onOpenLogin = onOpenLogin,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            item {
                ProfileBettboxEntries(
                    onOpenLocal = onOpenLocal,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item { ProfileVersionLabel() }
        }
    }
}

@Composable
private fun ProfileBettboxIdentity(
    session: SteamSessionState,
    runtimeHours: Int?,
    onOpenLogin: () -> Unit,
) {
    val style = wallHubStyle()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        AsyncImage(
            model = session.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(112.dp).clip(style.shapes.card),
        )
        Text(
            session.personaName ?: session.accountName ?: stringResource(R.string.profile_anonymous),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
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

@Composable
private fun ProfileBettboxEntries(
    onOpenLocal: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.profile_more),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = WallHubSpacing.xs),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
        ) {
            ProfileBettboxEntry(
                title = stringResource(R.string.profile_local),
                subtitle = stringResource(R.string.profile_local_hint),
                icon = Icons.Outlined.FolderOpen,
                onClick = onOpenLocal,
            )
            ProfileBettboxEntry(
                title = stringResource(R.string.management_settings),
                subtitle = stringResource(R.string.profile_settings_hint),
                icon = Icons.Outlined.Settings,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun ProfileVersionLabel() {
    Text(
        text = "WallHub ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun ProfileBettboxStats(
    counts: ProfileCounts,
    isSignedIn: Boolean,
    enabled: Boolean,
    onOpenSubscriptions: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    val bettbox = style.capabilities.bettboxSurfaces
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = style.shapes.card,
        color = style.surfaces.settingsRow,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (bettbox) Modifier.heightIn(min = 84.dp) else Modifier.height(84.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileBettboxStat(
                title = stringResource(R.string.profile_subscriptions),
                state = counts.subscriptions,
                onClick = if (isSignedIn) onOpenSubscriptions else onOpenLogin,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            ProfileBettboxStat(
                title = stringResource(R.string.profile_favorites),
                state = counts.favorites,
                onClick = if (isSignedIn) onOpenFavorites else onOpenLogin,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProfileBettboxStat(
    title: String,
    state: ProfileCountState,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val status =
        when (state) {
            ProfileCountState.Loading -> stringResource(R.string.profile_count_loading)
            is ProfileCountState.Loaded -> stringResource(R.string.profile_count_format, state.count)
            ProfileCountState.Unavailable -> stringResource(R.string.profile_count_unavailable)
            ProfileCountState.SignInRequired -> stringResource(R.string.library_sign_in_required)
        }
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics {
                    stateDescription = status
                    liveRegion = LiveRegionMode.Polite
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(profileCountDisplayValue(state), style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProfileBettboxEntry(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = style.shapes.card,
        colors = CardDefaults.cardColors(containerColor = style.surfaces.settingsRow),
        elevation = CardDefaults.cardElevation(defaultElevation = style.elevations.card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

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

internal fun profileCountDisplayValue(state: ProfileCountState): String =
    (state as? ProfileCountState.Loaded)?.count?.toString() ?: "-"

@Composable
private fun ProfileStatCard(title: String, state: ProfileCountState, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    val status =
        when (state) {
            ProfileCountState.Loading -> stringResource(R.string.profile_count_loading)
            is ProfileCountState.Loaded -> stringResource(R.string.profile_count_format, state.count)
            ProfileCountState.Unavailable -> stringResource(R.string.profile_count_unavailable)
            ProfileCountState.SignInRequired -> stringResource(R.string.library_sign_in_required)
        }
    val value = profileCountDisplayValue(state)
    val style = wallHubStyle()
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
        colors = CardDefaults.cardColors(containerColor = style.surfaces.settingsRow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = style.shapes.card,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 108.dp).padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ProfileEntryCard(title: String, subtitle: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val style = wallHubStyle()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = style.surfaces.settingsRow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = style.shapes.card,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(WallHubSpacing.content),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f).padding(start = if (icon == null) 0.dp else 16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
