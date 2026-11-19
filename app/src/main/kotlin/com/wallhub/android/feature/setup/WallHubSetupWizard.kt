/*
 * Copyright (C) 2026 UwUniverse
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wallhub.android.feature.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubColorTokens
import com.wallhub.android.core.designsystem.WallHubHorizontalDivider
import com.wallhub.android.core.designsystem.allFilesAccessSettingsIntent
import com.wallhub.android.core.designsystem.canWritePublicDownloadsDirectly
import com.wallhub.android.core.designsystem.hasAllFilesAccess
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessMode
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.data.steamaccess.SteamAccessManager
import com.wallhub.settings.SETTINGS_CONTENT_MAX_WIDTH
import com.wallhub.settings.SteamAccountCard
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import com.wallhub.settings.SteamAccessDohEndpointsSetting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.uwuaosp.compose.wizard.WizardBrandPage
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff

private val PAD_SETUP_RAIL_WIDTH = 280.dp
private val PAD_SETUP_CONTENT_MAX_WIDTH = 1120.dp
private val LocalWallHubSetupPadLayout = staticCompositionLocalOf { false }

internal enum class WallHubSetupPage {
    Welcome,
    Network,
    Warmup,
    Connectivity,
    Storage,
    Steam,
    Finish,
}

internal fun durableSetupPage(page: WallHubSetupPage): WallHubSetupPage =
    when (page) {
        WallHubSetupPage.Warmup, WallHubSetupPage.Connectivity -> WallHubSetupPage.Network
        else -> page
    }

private val WallHubSetupPageSaver =
    Saver<WallHubSetupPage, String>(
        save = { page -> durableSetupPage(page).name },
        restore = { saved -> WallHubSetupPage.valueOf(saved) },
    )

internal fun nextSetupPage(page: WallHubSetupPage): WallHubSetupPage =
    WallHubSetupPage.entries[WallHubSetupPage.entries.indexOf(page) + 1]

internal fun previousSetupPage(page: WallHubSetupPage): WallHubSetupPage =
    when (page) {
        WallHubSetupPage.Warmup, WallHubSetupPage.Connectivity -> WallHubSetupPage.Network
        else -> WallHubSetupPage.entries[WallHubSetupPage.entries.indexOf(page) - 1]
    }

@HiltViewModel
class WallHubSetupViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val steamSessionRepository: SteamSessionRepository,
        private val networkPreflight: SetupNetworkPreflight,
        private val steamAccessManager: SteamAccessManager,
    ) : ViewModel() {
        private val _networkPreflightResult = MutableStateFlow<SetupNetworkPreflightResult?>(null)
        internal val networkPreflightResult: StateFlow<SetupNetworkPreflightResult?> = _networkPreflightResult.asStateFlow()
        private val _antiBlockingWarmup = MutableStateFlow(SetupAntiBlockingWarmupState())
        internal val antiBlockingWarmup: StateFlow<SetupAntiBlockingWarmupState> = _antiBlockingWarmup.asStateFlow()
        private var networkPreparationJob: Job? = null
        private val networkCheck = SetupNetworkCheckCoordinator(viewModelScope)
        private var pendingDohConfiguration: Pair<List<String>, Set<String>>? = null
        private var expectedEnabledDohEndpoints: List<String> = emptyList()
        val preferences: StateFlow<AppPreferences> =
            settingsRepository.preferences.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppPreferences(),
            )
        val session: StateFlow<SteamSessionState> = steamSessionRepository.session

        fun login(accountName: String, password: String) {
            steamSessionRepository.login(accountName, password)
        }

        fun submitCode(code: String) {
            steamSessionRepository.submitSteamGuardCode(code)
        }

        fun useManualCodeFallback() {
            steamSessionRepository.useManualSteamGuardFallback()
        }

        fun restoreSteamSession() {
            steamSessionRepository.reconnectPersistedSession()
        }

        fun logoutSteam() {
            steamSessionRepository.logout()
        }

        fun saveNetworkAndPrepare(
            proxyUrl: String,
            proxyEnabled: Boolean,
            steamAccessEnabled: Boolean,
            dataSource: SteamWorkshopDataSource,
            apiKey: String,
        ) {
            networkPreparationJob?.cancel()
            networkCheck.cancel()
            _networkPreflightResult.value = null
            _antiBlockingWarmup.value =
                if (steamAccessEnabled) {
                    SetupAntiBlockingWarmupState.warming()
                } else {
                    SetupAntiBlockingWarmupState()
                }
            networkPreparationJob = viewModelScope.launch {
                val current = preferences.value
                val dohConfiguration =
                    pendingDohConfiguration
                        ?: (current.steamAccessDohEndpoints to current.steamAccessDisabledDohEndpoints)
                settingsRepository.setSteamAccessDohEndpoints(
                    dohConfiguration.first,
                    dohConfiguration.second,
                )
                expectedEnabledDohEndpoints =
                    dohConfiguration.first.filterNot(dohConfiguration.second::contains)
                settingsRepository.setDownloadPreferences(
                    maxConcurrentDownloads = current.maxConcurrentDownloads,
                    chunkDownloadConcurrency = current.chunkDownloadConcurrency,
                    proxyUrl = proxyUrl,
                    mediaCacheLimitMb = current.mediaCacheLimitMb,
                )
                settingsRepository.setDownloadProxyEnabled(proxyEnabled)
                if (steamAccessEnabled) settingsRepository.setSteamAccessMode(SteamAccessMode.SMART_DOH)
                settingsRepository.setSteamAccessEnabled(steamAccessEnabled)
                settingsRepository.setSteamWorkshopDataSource(dataSource)
                settingsRepository.setSteamApiKey(apiKey)
                if (!steamAccessEnabled) {
                    runNetworkCheck()
                    return@launch
                }
                runWarmup(force = false)
            }
        }

        fun retryWarmup() {
            networkPreparationJob?.cancel()
            _antiBlockingWarmup.value = SetupAntiBlockingWarmupState.warming()
            networkPreparationJob = viewModelScope.launch { runWarmup(force = true) }
        }

        private suspend fun runWarmup(force: Boolean) {
            try {
                steamAccessManager.awaitDohConfiguration(expectedEnabledDohEndpoints)
                _antiBlockingWarmup.value = steamAccessManager.warmUpDohRoutes(force).toSetupState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _antiBlockingWarmup.value =
                    SetupAntiBlockingWarmupState(
                        phase = SetupAntiBlockingWarmupPhase.DEGRADED,
                        reason = (error.message ?: error.javaClass.simpleName).replace(Regex("\\s+"), " ").take(96),
                    )
            }
        }

        fun runNetworkCheck() {
            networkCheck.launch {
                val enabled = preferences.value.steamAccessEnabled
                _networkPreflightResult.value = SetupNetworkPreflightResult.checking()
                _networkPreflightResult.value =
                    networkPreflight.run(SetupNetworkPreflightInput(enabled)) { snapshot ->
                        _networkPreflightResult.value = snapshot
                    }
            }
        }

        fun retryNetworkCheck() = runNetworkCheck()

        fun clearNetworkPreparation() {
            networkPreparationJob?.cancel()
            networkPreparationJob = null
            networkCheck.cancel()
            _antiBlockingWarmup.value = SetupAntiBlockingWarmupState()
            _networkPreflightResult.value = null
        }

        fun saveOutputDirectory(treeUri: String, label: String) {
            viewModelScope.launch { settingsRepository.setOutputDirectory(treeUri, label) }
        }

        fun saveDohEndpoints(endpoints: List<String>, disabledEndpoints: Set<String>) {
            pendingDohConfiguration = endpoints.toList() to disabledEndpoints.toSet()
            viewModelScope.launch {
                settingsRepository.setSteamAccessDohEndpoints(endpoints, disabledEndpoints)
            }
        }
    }

@Composable
fun WallHubSetupWizard(
    onComplete: () -> Unit,
    viewModel: WallHubSetupViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val networkPreflightResult by viewModel.networkPreflightResult.collectAsStateWithLifecycle()
    val antiBlockingWarmup by viewModel.antiBlockingWarmup.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val legacyPermissionDenied = stringResource(R.string.setup_storage_legacy_permission_denied)
    val storageAuthorizationRequired = stringResource(R.string.setup_storage_authorization_required)
    val accessibilityEnabled =
        remember(context) {
            (context.getSystemService(AccessibilityManager::class.java))?.isEnabled == true
        }
    var page by rememberSaveable(stateSaver = WallHubSetupPageSaver) {
        mutableStateOf(WallHubSetupPage.Welcome)
    }
    var storageError by rememberSaveable { mutableStateOf<String?>(null) }
    var hasFileAccess by remember { mutableStateOf(context.canWritePublicDownloadsDirectly()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val granted = context.canWritePublicDownloadsDirectly()
                    hasFileAccess = granted
                    if (granted) storageError = null
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                storageError = null
                hasFileAccess = true
                page = WallHubSetupPage.Steam
            } else {
                storageError = legacyPermissionDenied
            }
        }
    val requestFileAccess = {
        val intent = context.allFilesAccessSettingsIntent()
        if (intent != null) {
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (!started) {
                storageError = storageAuthorizationRequired
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            page = WallHubSetupPage.Steam
        }
    }
    val pageIndex = WallHubSetupPage.entries.indexOf(page)
    val navigateBack = {
        if (
            page == WallHubSetupPage.Steam &&
            session.phase in setOf(
                SteamSessionPhase.SIGNING_IN,
                SteamSessionPhase.WAITING_FOR_CODE,
                SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
            )
        ) {
            viewModel.logoutSteam()
        }
        if (page == WallHubSetupPage.Warmup || page == WallHubSetupPage.Connectivity) {
            viewModel.clearNetworkPreparation()
        }
        page = previousSetupPage(page)
    }
    BackHandler(enabled = pageIndex > 0, onBack = navigateBack)
    LaunchedEffect(page, antiBlockingWarmup.phase) {
        if (
            page == WallHubSetupPage.Warmup &&
            antiBlockingWarmup.phase == SetupAntiBlockingWarmupPhase.READY &&
            !accessibilityEnabled
        ) {
            delay(1_000L)
            viewModel.runNetworkCheck()
            page = WallHubSetupPage.Connectivity
        }
    }
    val padWizard = LocalConfiguration.current.screenWidthDp >= 600
    val wizardPageEnter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val wizardPageExit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    CompositionLocalProvider(
        LocalWallHubSetupPadLayout provides padWizard,
        LocalWallHubWizardNavigateBack provides if (page == WallHubSetupPage.Welcome) null else navigateBack,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (padWizard) {
                PadSetupStageRail(page = page)
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                fadeIn(wizardPageEnter) togetherWith fadeOut(wizardPageExit)
            },
            label = "WallHubSetupPage",
        ) { target ->
            when (target) {
                WallHubSetupPage.Welcome -> WelcomePage()
                WallHubSetupPage.Storage ->
                    StoragePage(
                        preferences = preferences,
                        errorMessage = storageError,
                        hasFileAccess = hasFileAccess,
                        onRequestAccess = { requestFileAccess() },
                    )
                WallHubSetupPage.Steam -> SteamPage(session = session, viewModel = viewModel)
                WallHubSetupPage.Network ->
                    NetworkPage(
                        preferences = preferences,
                        onSaveDohEndpoints = viewModel::saveDohEndpoints,
                        onContinue = { proxyUrl, proxyEnabled, accessEnabled, source, apiKey ->
                            viewModel.saveNetworkAndPrepare(proxyUrl, proxyEnabled, accessEnabled, source, apiKey)
                            page =
                                if (accessEnabled) {
                                    WallHubSetupPage.Warmup
                                } else {
                                    WallHubSetupPage.Connectivity
                                }
                        },
                    )
                WallHubSetupPage.Warmup ->
                    AntiBlockingWarmupPage(
                        state = antiBlockingWarmup,
                        showReadyContinue = accessibilityEnabled,
                        onRetry = viewModel::retryWarmup,
                        onReadyContinue = {
                            viewModel.runNetworkCheck()
                            page = WallHubSetupPage.Connectivity
                        },
                    )
                WallHubSetupPage.Connectivity ->
                    ConnectivityPage(
                        result = networkPreflightResult ?: SetupNetworkPreflightResult.checking(),
                        onRetry = viewModel::retryNetworkCheck,
                        onContinue = { page = WallHubSetupPage.Storage },
                    )
                WallHubSetupPage.Finish -> FinishPage()
            }
        }
            }
        }
        val signedIn = session.phase == SteamSessionPhase.SIGNED_IN
        val showSkip = page == WallHubSetupPage.Steam && !signedIn
        val showPrimary = page != WallHubSetupPage.Steam || signedIn
        val primaryLabel =
            when (page) {
                WallHubSetupPage.Welcome -> stringResource(R.string.setup_action_start)
                WallHubSetupPage.Steam, WallHubSetupPage.Network, WallHubSetupPage.Warmup,
                WallHubSetupPage.Connectivity, WallHubSetupPage.Storage,
                -> stringResource(R.string.setup_action_next)
                WallHubSetupPage.Finish -> stringResource(R.string.setup_action_finish)
            }
        WallHubWizardActions(
            visible =
                page != WallHubSetupPage.Network &&
                    page != WallHubSetupPage.Warmup &&
                    page != WallHubSetupPage.Connectivity,
            modifier = if (padWizard) Modifier.padding(start = PAD_SETUP_RAIL_WIDTH) else Modifier,
            primaryLabel = primaryLabel,
            onPrimaryClick = {
                when (page) {
                    WallHubSetupPage.Welcome -> page = WallHubSetupPage.Network
                    WallHubSetupPage.Storage -> {
                        when {
                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> {
                                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !context.hasAllFilesAccess() -> {
                                requestFileAccess()
                            }
                            else -> page = WallHubSetupPage.Steam
                        }
                    }
                    WallHubSetupPage.Steam -> page = WallHubSetupPage.Finish
                    WallHubSetupPage.Network, WallHubSetupPage.Warmup, WallHubSetupPage.Connectivity -> Unit
                    WallHubSetupPage.Finish -> onComplete()
                }
            },
            secondaryLabel = stringResource(R.string.setup_action_skip),
            onSecondaryClick = {
                if (
                    session.phase in setOf(
                        SteamSessionPhase.SIGNING_IN,
                        SteamSessionPhase.WAITING_FOR_CODE,
                        SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                    )
                ) {
                    viewModel.logoutSteam()
                }
                page = WallHubSetupPage.Finish
            },
            showPrimary = showPrimary,
            showSecondary = showSkip,
        )
    }
    }
}

@Composable
private fun PadSetupStageRail(page: WallHubSetupPage) {
    val currentIndex = WallHubSetupPage.entries.indexOf(page)
    Surface(
        modifier = Modifier.width(PAD_SETUP_RAIL_WIDTH).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = WallHubSpacing.lg, vertical = WallHubSpacing.xl),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            ) {
                Image(
                    painter = painterResource(R.drawable.wallhub_logo),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.setup_pad_rail_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(WallHubSpacing.xl))
            Text(
                text = stringResource(R.string.setup_pad_progress, currentIndex + 1, WallHubSetupPage.entries.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(WallHubSpacing.md))
            WallHubSetupPage.entries.forEachIndexed { index, item ->
                PadSetupStageItem(
                    page = item,
                    selected = item == page,
                    completed = index < currentIndex,
                    compact = item == WallHubSetupPage.Warmup || item == WallHubSetupPage.Connectivity,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.setup_pad_rail_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PadSetupStageItem(
    page: WallHubSetupPage,
    selected: Boolean,
    completed: Boolean,
    compact: Boolean,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = if (compact) WallHubSpacing.md else 0.dp, bottom = WallHubSpacing.xs),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.compact),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Icon(
                imageVector = if (completed) Icons.Outlined.CheckCircle else page.setupIcon(),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 20.dp else 22.dp),
                tint = if (completed || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = page.setupTitle(),
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
            )
        }
    }
}

private fun WallHubSetupPage.setupIcon(): ImageVector =
    when (this) {
        WallHubSetupPage.Welcome -> Icons.Outlined.CheckCircle
        WallHubSetupPage.Network, WallHubSetupPage.Warmup -> Icons.Outlined.Language
        WallHubSetupPage.Connectivity -> Icons.Outlined.Schedule
        WallHubSetupPage.Storage -> Icons.Outlined.FolderOpen
        WallHubSetupPage.Steam -> Icons.Outlined.PersonOutline
        WallHubSetupPage.Finish -> Icons.Outlined.Check
    }

@Composable
private fun WallHubSetupPage.setupTitle(): String =
    when (this) {
        WallHubSetupPage.Welcome -> stringResource(R.string.setup_pad_stage_welcome)
        WallHubSetupPage.Network -> stringResource(R.string.setup_network_title)
        WallHubSetupPage.Warmup -> stringResource(R.string.setup_warmup_title)
        WallHubSetupPage.Connectivity -> stringResource(R.string.setup_connectivity_title)
        WallHubSetupPage.Storage -> stringResource(R.string.setup_storage_title)
        WallHubSetupPage.Steam -> stringResource(R.string.setup_steam_title)
        WallHubSetupPage.Finish -> stringResource(R.string.setup_finish_title)
    }

@Composable
private fun WelcomePage() {
    if (LocalWallHubSetupPadLayout.current) {
        WallHubWizardPage(
            icon = Icons.Outlined.CheckCircle,
            title = stringResource(R.string.setup_welcome_title),
            description = stringResource(R.string.setup_welcome_description),
            scrollable = false,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().widthIn(max = PAD_SETUP_CONTENT_MAX_WIDTH),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.setup_pad_welcome_summary),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(WallHubSpacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                ) {
                    PadSetupOverviewCard(
                        icon = Icons.Outlined.Language,
                        title = stringResource(R.string.setup_network_title),
                        description = stringResource(R.string.setup_pad_welcome_network),
                        modifier = Modifier.weight(1f),
                    )
                    PadSetupOverviewCard(
                        icon = Icons.Outlined.FolderOpen,
                        title = stringResource(R.string.setup_storage_title),
                        description = stringResource(R.string.setup_pad_welcome_storage),
                        modifier = Modifier.weight(1f),
                    )
                    PadSetupOverviewCard(
                        icon = Icons.Outlined.PersonOutline,
                        title = stringResource(R.string.setup_steam_title),
                        description = stringResource(R.string.setup_pad_welcome_steam),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        return
    }
    WizardBrandPage(
        title = stringResource(R.string.setup_welcome_title),
        subtitle = stringResource(R.string.setup_welcome_description),
        bottomContentPadding = 96.dp,
        illustration = {
            Image(
                painter = painterResource(R.drawable.wallhub_logo),
                contentDescription = stringResource(R.string.settings_about_wallhub_logo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(240.dp),
            )
        },
    )
}

@Composable
private fun PadSetupOverviewCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 210.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(WallHubSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StoragePage(
    preferences: AppPreferences,
    errorMessage: String?,
    hasFileAccess: Boolean,
    onRequestAccess: () -> Unit,
) {
    val accessDetail =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> stringResource(R.string.setup_storage_all_files_detail)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> stringResource(R.string.setup_storage_legacy_permission_detail)
            else -> stringResource(R.string.setup_storage_default_detail)
        }
    val accessLabel =
        if (hasFileAccess) {
            stringResource(R.string.setup_storage_access_granted)
        } else {
            stringResource(R.string.setup_storage_grant_access)
        }
    WallHubWizardPage(
        icon = Icons.Outlined.FolderOpen,
        title = stringResource(R.string.setup_storage_title),
        description = stringResource(R.string.setup_storage_description),
    ) {
        if (LocalWallHubSetupPadLayout.current) {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = PAD_SETUP_CONTENT_MAX_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
            ) {
                SetupSurface(modifier = Modifier.weight(1.2f).heightIn(min = 240.dp)) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(WallHubSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(28.dp))
                            }
                        }
                        Text(stringResource(R.string.setup_storage_current_location), style = MaterialTheme.typography.titleMedium)
                        Text(
                            preferences.outputDirectoryLabel ?: stringResource(R.string.settings_default_export_directory),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            if (preferences.outputTreeUri == null) {
                                stringResource(R.string.setup_storage_default_detail)
                            } else {
                                stringResource(R.string.setup_storage_authorized_detail)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(0.9f),
                    verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                ) {
                    SetupSurface {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                        ) {
                            Text(stringResource(R.string.setup_pad_storage_access_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                accessDetail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = onRequestAccess,
                                enabled = !hasFileAccess,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            ) {
                                Icon(
                                    if (hasFileAccess) Icons.Outlined.CheckCircle else Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                )
                                Text(
                                    accessLabel,
                                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                                )
                            }
                        }
                    }
                    errorMessage?.let { message ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(message, modifier = Modifier.padding(WallHubSpacing.md))
                        }
                    }
                }
            }
            return@WallHubWizardPage
        }
        SetupSurface {
            Column(
                modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.md),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                Text(stringResource(R.string.setup_storage_current_location), style = MaterialTheme.typography.labelLarge)
                Text(
                    preferences.outputDirectoryLabel ?: stringResource(R.string.settings_default_export_directory),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (preferences.outputTreeUri == null) {
                        stringResource(R.string.setup_storage_default_detail)
                    } else {
                        stringResource(R.string.setup_storage_authorized_detail)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onRequestAccess,
            enabled = !hasFileAccess,
            modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.md).heightIn(min = 56.dp),
        ) {
            Icon(
                if (hasFileAccess) Icons.Outlined.CheckCircle else Icons.Outlined.FolderOpen,
                contentDescription = null,
            )
            Text(
                accessLabel,
                modifier = Modifier.padding(start = WallHubSpacing.xs),
            )
        }
        Text(
            accessDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.sm),
        )
        errorMessage?.let { message ->
            if (wallHubStyle().capabilities.bettboxSurfaces) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.sm),
                ) {
                    Row(
                        modifier = Modifier.padding(WallHubSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(start = WallHubSpacing.xs),
                        )
                    }
                }
            } else {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.sm),
                )
            }
        }
    }
}

@Composable
private fun SteamPage(session: SteamSessionState, viewModel: WallHubSetupViewModel) {
    SteamPageContent(
        session = session,
        onLogin = viewModel::login,
        onSubmitCode = viewModel::submitCode,
        onUseManualCodeFallback = viewModel::useManualCodeFallback,
        onRestore = viewModel::restoreSteamSession,
        onLogout = viewModel::logoutSteam,
    )
}

@Composable
private fun SteamPageContent(
    session: SteamSessionState,
    onLogin: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onUseManualCodeFallback: () -> Unit,
    onRestore: () -> Unit,
    onLogout: () -> Unit,
) {
    WallHubWizardPage(
        icon = Icons.Outlined.PersonOutline,
        title = stringResource(R.string.setup_steam_title),
        description = stringResource(R.string.setup_steam_description),
    ) {
        if (LocalWallHubSetupPadLayout.current) {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = PAD_SETUP_CONTENT_MAX_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
                verticalAlignment = Alignment.Top,
            ) {
                SetupSurface(modifier = Modifier.weight(0.8f).heightIn(min = 300.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.PersonOutline, contentDescription = null, modifier = Modifier.size(28.dp))
                            }
                        }
                        Text(stringResource(R.string.setup_pad_steam_panel_title), style = MaterialTheme.typography.titleLarge)
                        PadSetupFeatureRow(stringResource(R.string.setup_pad_steam_library))
                        PadSetupFeatureRow(stringResource(R.string.setup_pad_steam_comments))
                        PadSetupFeatureRow(stringResource(R.string.setup_pad_steam_optional))
                    }
                }
                SteamAccountCard(
                    session = session,
                    onLogin = onLogin,
                    onSubmitCode = onSubmitCode,
                    onUseManualCodeFallback = onUseManualCodeFallback,
                    onRestore = onRestore,
                    onLogout = onLogout,
                    modifier = Modifier.weight(1.2f).widthIn(max = 720.dp),
                )
            }
            return@WallHubWizardPage
        }
        SteamAccountCard(
            session = session,
            onLogin = onLogin,
            onSubmitCode = onSubmitCode,
            onUseManualCodeFallback = onUseManualCodeFallback,
            onRestore = onRestore,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = SETTINGS_CONTENT_MAX_WIDTH),
        )
    }
}

@Composable
private fun PadSetupFeatureRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkPage(
    preferences: AppPreferences,
    onSaveDohEndpoints: (List<String>, Set<String>) -> Unit,
    onContinue: (String, Boolean, Boolean, SteamWorkshopDataSource, String) -> Unit,
) {
    var proxyUrl by rememberSaveable(preferences.downloadProxyUrl) { mutableStateOf(preferences.downloadProxyUrl) }
    var proxyEnabled by rememberSaveable(preferences.downloadProxyEnabled) { mutableStateOf(preferences.downloadProxyEnabled) }
    var accessEnabled by rememberSaveable(preferences.steamAccessEnabled) { mutableStateOf(preferences.steamAccessEnabled) }
    var source by rememberSaveable(preferences.steamWorkshopDataSource) { mutableStateOf(preferences.steamWorkshopDataSource) }
    var apiKey by remember(preferences.steamApiKey) { mutableStateOf(preferences.steamApiKey) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        WallHubWizardPage(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.setup_network_title),
            description = stringResource(R.string.setup_network_description),
        ) {
            SetupAdaptiveColumns(
                first = {
            SetupToggle(
                title = stringResource(R.string.settings_use_network_proxy),
                description = stringResource(R.string.settings_use_network_proxy_description),
                checked = proxyEnabled,
                enabled = isSupportedDownloadProxyUrl(proxyUrl),
                onCheckedChange = { proxyEnabled = it },
            )
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = {
                    proxyUrl = it
                    if (!isSupportedDownloadProxyUrl(it)) proxyEnabled = false
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_field_label)) },
            )
            if (wallHubStyle().capabilities.bettboxSurfaces) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SetupToggle(
                        title = stringResource(R.string.settings_steam_automatic_anti_blocking),
                        description = stringResource(R.string.settings_steam_service_access_description),
                        checked = accessEnabled,
                        onCheckedChange = { accessEnabled = it },
                    )
                    SteamAccessDohEndpointsSetting(
                        endpoints = preferences.steamAccessDohEndpoints,
                        disabledEndpoints = preferences.steamAccessDisabledDohEndpoints,
                        onSave = onSaveDohEndpoints,
                    )
                }
            } else {
                SetupToggle(
                    title = stringResource(R.string.settings_steam_automatic_anti_blocking),
                    description = stringResource(R.string.settings_steam_service_access_description),
                    checked = accessEnabled,
                    onCheckedChange = { accessEnabled = it },
                    modifier = Modifier.padding(top = 16.dp),
                )
                SteamAccessDohEndpointsSetting(
                    endpoints = preferences.steamAccessDohEndpoints,
                    disabledEndpoints = preferences.steamAccessDisabledDohEndpoints,
                    onSave = onSaveDohEndpoints,
                )
            }
                },
                second = {
            ExposedDropdownMenuBox(
                expanded = sourceMenuExpanded,
                onExpandedChange = { sourceMenuExpanded = it },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                OutlinedTextField(
                    value = source.label(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_data_source)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sourceMenuExpanded) },
                    modifier =
                        Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                    SteamWorkshopDataSource.entries.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label()) },
                            onClick = { source = item; sourceMenuExpanded = false },
                        )
                    }
                }
            }
            if (source == SteamWorkshopDataSource.WEB_API) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.xs),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_steam_api_key)) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription =
                                    stringResource(
                                        if (apiKeyVisible) {
                                            R.string.settings_action_hide_api_key
                                        } else {
                                            R.string.settings_action_show_api_key
                                        },
                                    ),
                            )
                        }
                    },
                )
            }
                },
            )
        }
        WallHubWizardActions(
            visible = true,
            primaryLabel = stringResource(R.string.setup_action_next),
            primaryEnabled = source != SteamWorkshopDataSource.WEB_API || apiKey.isNotBlank(),
            onPrimaryClick = { onContinue(proxyUrl, proxyEnabled, accessEnabled, source, apiKey) },
        )
    }
}

@Composable
private fun ColumnScope.SetupAdaptiveColumns(
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    if (LocalWallHubSetupPadLayout.current) {
        Row(
            modifier = Modifier.fillMaxWidth().widthIn(max = PAD_SETUP_CONTENT_MAX_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                content = first,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                content = second,
            )
        }
    } else {
        first()
        second()
    }
}

@Composable
private fun AntiBlockingWarmupPage(
    state: SetupAntiBlockingWarmupState,
    showReadyContinue: Boolean,
    onRetry: () -> Unit,
    onReadyContinue: () -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion =
        remember(context) {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }
    val successColor =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            WallHubColorTokens.statusSuccessOnDark
        } else {
            WallHubColorTokens.statusSuccessOnLight
        }
    WallHubWizardPage(
        icon = Icons.Outlined.Language,
        title = stringResource(R.string.setup_warmup_title),
        description = stringResource(R.string.setup_warmup_description),
    ) {
        SetupSurface(
            modifier =
                Modifier
                    .padding(top = WallHubSpacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            classicShape = MaterialTheme.shapes.extraLarge,
            classicTonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
            ) {
                val ready = state.phase == SetupAntiBlockingWarmupPhase.READY
                val readyAlpha =
                    animateFloatAsState(
                        targetValue = if (ready) 1f else 0f,
                        animationSpec = warmupFadeSpec(reduceMotion),
                        label = "anti-blocking-warmup-progress",
                    )
                var showSpinner by remember(reduceMotion) { mutableStateOf(!reduceMotion) }
                LaunchedEffect(ready, reduceMotion) {
                    showSpinner = !reduceMotion
                    if (ready && !reduceMotion) {
                        delay(warmupFadeDurationMillis(reduceMotion) + 50L)
                        showSpinner = false
                    }
                }
                Box(
                    modifier = Modifier.size(128.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (state.phase) {
                        SetupAntiBlockingWarmupPhase.DEGRADED ->
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(72.dp),
                            )
                        else -> {
                            val wizardStyle = wallHubStyle()
                            val warmupHighlight =
                                if (wizardStyle.capabilities.bettboxSurfaces) {
                                    wizardStyle.navigation.selectedContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            Surface(
                                modifier =
                                    Modifier
                                        .size(128.dp)
                                        .graphicsLayer { alpha = 1f - readyAlpha.value },
                                shape = CircleShape,
                                color = warmupHighlight,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (showSpinner) {
                                        ContinuousWarmupProgressIndicator(
                                            modifier = Modifier.size(92.dp),
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Outlined.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(42.dp),
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = successColor,
                                modifier =
                                    Modifier
                                        .size(72.dp)
                                        .graphicsLayer { alpha = readyAlpha.value },
                            )
                        }
                    }
                }
                if (state.phase == SetupAntiBlockingWarmupPhase.DEGRADED) {
                    WarmupCopy(
                        title = stringResource(R.string.setup_warmup_degraded),
                        detail =
                            if (state.reason.isNotBlank()) {
                                state.reason
                            } else {
                                stringResource(
                                    R.string.setup_warmup_degraded_detail,
                                    state.domesticRoutes,
                                    state.foreignRoutes,
                                )
                            },
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        WarmupCopy(
                            title = stringResource(R.string.setup_warmup_working),
                            detail = stringResource(R.string.setup_warmup_working_detail),
                            modifier =
                                Modifier
                                    .graphicsLayer { alpha = 1f - readyAlpha.value }
                                    .then(
                                        if (ready) Modifier.semantics { invisibleToUser() }
                                        else Modifier,
                                    ),
                        )
                        WarmupCopy(
                            title =
                                stringResource(
                                    if (state.bridged && !state.bypassed) {
                                        R.string.setup_warmup_ready_enhanced
                                    } else {
                                        R.string.setup_warmup_ready
                                    },
                                ),
                            detail =
                                when {
                                    state.bridged && !state.bypassed ->
                                        stringResource(R.string.setup_warmup_ready_enhanced_detail)
                                    state.bypassed -> stringResource(R.string.setup_warmup_direct_detail)
                                    else ->
                                        stringResource(
                                            R.string.setup_warmup_ready_detail,
                                            state.domesticRoutes,
                                            state.foreignRoutes,
                                        )
                                },
                            modifier =
                                Modifier
                                    .graphicsLayer { alpha = readyAlpha.value }
                                    .then(
                                        if (ready) Modifier
                                        else Modifier.semantics { invisibleToUser() },
                                    ),
                        )
                    }
                }
                if (state.phase == SetupAntiBlockingWarmupPhase.DEGRADED) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.setup_warmup_retry),
                            modifier = Modifier.padding(start = WallHubSpacing.xs),
                        )
                    }
                }
                if (showReadyContinue) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(WallHubSizeTokens.primaryActionHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        WarmupReadyContinueButton(
                            visible = ready,
                            alpha = { readyAlpha.value },
                            onClick = onReadyContinue,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContinuousWarmupProgressIndicator(modifier: Modifier = Modifier) {
    CircularWavyProgressIndicator(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent,
    )
}

@Composable
private fun WarmupCopy(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarmupReadyContinueButton(
    visible: Boolean,
    alpha: () -> Float,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = visible,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(WallHubSizeTokens.primaryActionHeight)
                .graphicsLayer { this.alpha = alpha() }
                .then(
                    if (visible) Modifier
                    else Modifier.semantics { invisibleToUser() },
                ),
    ) {
        Text(stringResource(R.string.setup_warmup_continue))
    }
}

@Composable
private fun ConnectivityPage(
    result: SetupNetworkPreflightResult,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val showContinue = result.overallStatus.showsContinueAction()
    Box(modifier = Modifier.fillMaxSize()) {
        WallHubWizardPage(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.setup_connectivity_title),
            description = stringResource(R.string.setup_connectivity_description),
        ) {
            SetupNetworkPreflightCard(result, onRetry)
        }
        if (showContinue) {
            WallHubWizardActions(
                visible = true,
                primaryLabel = stringResource(R.string.setup_action_continue),
                onPrimaryClick = onContinue,
            )
        }
    }
}

@Composable
private fun SetupNetworkPreflightCard(
    result: SetupNetworkPreflightResult,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion =
        remember(context) {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }
    val successColor =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            WallHubColorTokens.statusSuccessOnDark
        } else {
            WallHubColorTokens.statusSuccessOnLight
        }
    SetupSurface(
        modifier = Modifier.padding(top = WallHubSpacing.md),
        classicShape = MaterialTheme.shapes.extraLarge,
        classicTonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = result.overallStatus,
                            transitionSpec = { connectivityStatusTransition(reduceMotion) },
                            label = "connectivity-overall-status",
                        ) { status ->
                            ConnectivityOverallStatusIcon(status, successColor)
                        }
                    }
                }
                AnimatedContent(
                    targetState = result.overallStatus,
                    transitionSpec = { connectivityStatusTransition(reduceMotion) },
                    label = "connectivity-overall-title",
                ) { status ->
                    Text(status.label(), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (LocalWallHubSetupPadLayout.current) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    result.items.chunked((result.items.size + 1) / 2).forEach { group ->
                        Column(modifier = Modifier.weight(1f)) {
                            group.forEachIndexed { index, item ->
                                ConnectivityCheckRow(
                                    item = item,
                                    successColor = successColor,
                                    reduceMotion = reduceMotion,
                                )
                                if (index != group.lastIndex) {
                                    WallHubHorizontalDivider(modifier = Modifier.padding(start = 36.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                Column {
                    result.items.forEachIndexed { index, item ->
                        ConnectivityCheckRow(
                            item = item,
                            successColor = successColor,
                            reduceMotion = reduceMotion,
                        )
                        if (index != result.items.lastIndex) {
                            WallHubHorizontalDivider(
                                modifier = Modifier.padding(start = 36.dp),
                            )
                        }
                    }
                }
            }
            if (result.overallStatus.showsRetryAction()) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.setup_network_retry),
                        modifier = Modifier.padding(start = WallHubSpacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectivityCheckRow(
    item: SetupNetworkCheckResult,
    successColor: Color,
    reduceMotion: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = WallHubSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = item.status,
            transitionSpec = { connectivityStatusTransition(reduceMotion) },
            label = "connectivity-row-icon",
        ) { status ->
            ConnectivityCheckStatusIcon(status, successColor)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(item.check.label(), style = MaterialTheme.typography.bodyLarge)
            if (
                item.reason.isNotBlank() &&
                item.status != SetupNetworkCheckStatus.PASSED &&
                item.status != SetupNetworkCheckStatus.CHECKING
            ) {
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedContent(
            targetState = item,
            transitionSpec = { connectivityStatusTransition(reduceMotion) },
            label = "connectivity-row-result",
        ) { animatedItem ->
            Text(
                text = animatedItem.statusLabel(),
                style = MaterialTheme.typography.labelLarge,
                color =
                    when (animatedItem.status) {
                        SetupNetworkCheckStatus.PASSED -> successColor
                        SetupNetworkCheckStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun ConnectivityOverallStatusIcon(
    status: SetupNetworkOverallStatus,
    successColor: Color,
) {
    when (status) {
        SetupNetworkOverallStatus.CHECKING ->
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
            )
        SetupNetworkOverallStatus.PASSED ->
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = successColor,
                modifier = Modifier.size(24.dp),
            )
        SetupNetworkOverallStatus.WARNING ->
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
        SetupNetworkOverallStatus.SKIPPED ->
            Icon(
                imageVector = Icons.Outlined.RemoveCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
    }
}

@Composable
private fun ConnectivityCheckStatusIcon(
    status: SetupNetworkCheckStatus,
    successColor: Color,
) {
    when (status) {
        SetupNetworkCheckStatus.CHECKING ->
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        SetupNetworkCheckStatus.PASSED ->
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = successColor,
                modifier = Modifier.size(22.dp),
            )
        SetupNetworkCheckStatus.FAILED ->
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
        SetupNetworkCheckStatus.SKIPPED ->
            Icon(
                imageVector = Icons.Outlined.RemoveCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
    }
}

private fun warmupFadeDurationMillis(reduceMotion: Boolean): Int = if (reduceMotion) 90 else 200

private fun warmupFadeSpec(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
    tween(
        durationMillis = warmupFadeDurationMillis(reduceMotion),
        easing = LinearEasing,
    )

private fun connectivityStatusTransition(reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) {
        fadeIn(tween(durationMillis = 120)).togetherWith(fadeOut(tween(durationMillis = 90)))
    } else {
        (fadeIn(tween(durationMillis = 200)) +
            scaleIn(initialScale = 0.94f, animationSpec = tween(durationMillis = 200)))
            .togetherWith(
                fadeOut(tween(durationMillis = 120)) +
                    scaleOut(targetScale = 0.98f, animationSpec = tween(durationMillis = 120)),
            )
    }

@Composable
private fun SetupNetworkOverallStatus.label(): String =
    when (this) {
        SetupNetworkOverallStatus.CHECKING -> stringResource(R.string.setup_network_status_checking)
        SetupNetworkOverallStatus.PASSED -> stringResource(R.string.setup_network_status_passed)
        SetupNetworkOverallStatus.WARNING -> stringResource(R.string.setup_network_status_warning)
        SetupNetworkOverallStatus.SKIPPED -> stringResource(R.string.setup_network_status_skipped)
    }

@Composable
private fun SetupNetworkCheck.label(): String =
    when (this) {
        SetupNetworkCheck.DIRECTORY -> stringResource(R.string.setup_network_item_directory)
        SetupNetworkCheck.PUBLIC_DETAILS_JSON -> stringResource(R.string.setup_network_item_public_details)
        SetupNetworkCheck.COMMUNITY_HTML -> stringResource(R.string.setup_network_item_community_html)
        SetupNetworkCheck.WORKSHOP_IMAGE -> stringResource(R.string.setup_network_item_workshop_image)
        SetupNetworkCheck.GITHUB_API -> stringResource(R.string.setup_network_item_github_api)
        SetupNetworkCheck.CURATOR_API -> stringResource(R.string.setup_network_item_curator_api)
    }

@Composable
private fun SetupNetworkCheckResult.statusLabel(): String =
    when (status) {
        SetupNetworkCheckStatus.CHECKING -> stringResource(R.string.setup_network_checking)
        SetupNetworkCheckStatus.PASSED -> requireNotNull(compactLatencyLabel())
        SetupNetworkCheckStatus.FAILED -> stringResource(R.string.setup_network_failed)
        SetupNetworkCheckStatus.SKIPPED -> stringResource(R.string.setup_network_skipped)
    }

@Composable
private fun FinishPage() {
    val wizardStyle = wallHubStyle()
    val finishHighlight =
        if (wizardStyle.capabilities.bettboxSurfaces) {
            wizardStyle.navigation.selectedContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val finishContent =
        if (wizardStyle.capabilities.bettboxSurfaces) {
            wizardStyle.navigation.selectedContent
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
    if (LocalWallHubSetupPadLayout.current) {
        WallHubWizardPage(
            icon = Icons.Outlined.CheckCircle,
            title = stringResource(R.string.setup_finish_title),
            description = stringResource(R.string.setup_finish_description),
            scrollable = false,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = finishHighlight,
                        modifier = Modifier.size(116.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = finishContent,
                                modifier = Modifier.size(60.dp),
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                    ) {
                        Text(
                            text = stringResource(R.string.setup_pad_finish_ready),
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.setup_pad_finish_summary),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    SetupSurface(
                        modifier = Modifier.fillMaxWidth(),
                        classicShape = MaterialTheme.shapes.extraLarge,
                        classicTonalElevation = 1.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = WallHubSpacing.lg)) {
                            PadFinishStatusRow(stringResource(R.string.setup_pad_finish_network))
                            WallHubHorizontalDivider()
                            PadFinishStatusRow(stringResource(R.string.setup_pad_finish_storage))
                            WallHubHorizontalDivider()
                            PadFinishStatusRow(stringResource(R.string.setup_pad_finish_settings))
                        }
                    }
                }
            }
        }
        return
    }
    WizardBrandPage(
        title = stringResource(R.string.setup_finish_title),
        subtitle = stringResource(R.string.setup_finish_description),
        bottomContentPadding = 96.dp,
        illustration = {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = finishHighlight,
                modifier = Modifier.size(180.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = finishContent,
                        modifier = Modifier.size(96.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun PadFinishStatusRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = WallHubSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SetupSurface(
    modifier: Modifier = Modifier,
    classicShape: Shape = MaterialTheme.shapes.large,
    classicTonalElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val style = wallHubStyle()
    val bettbox = style.capabilities.bettboxSurfaces
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = if (bettbox) style.shapes.card else classicShape,
        color = if (bettbox) style.surfaces.content else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (bettbox) 0.dp else classicTonalElevation,
    ) {
        content()
    }
}

@Composable
internal fun WallHubSetupPadPreview(page: WallHubSetupPage) {
    val previewPreferences =
        AppPreferences(
            outputDirectoryLabel = "Download/WallHub",
            outputTreeUri = "content://preview/Download/WallHub",
            steamAccessEnabled = true,
        )
    val previewConnectivity =
        SetupNetworkPreflightResult(
            overallStatus = SetupNetworkOverallStatus.WARNING,
            items =
                SetupNetworkCheck.entries.mapIndexed { index, check ->
                    if (index == 4) {
                        SetupNetworkCheckResult(
                            check = check,
                            status = SetupNetworkCheckStatus.FAILED,
                            reason = "HTTP 404",
                            stage = SetupProbeStage.HTTP,
                        )
                    } else {
                        SetupNetworkCheckResult(
                            check = check,
                            status = SetupNetworkCheckStatus.PASSED,
                            durationMillis = 620L + (index * 137L),
                            stage = SetupProbeStage.PASSED,
                        )
                    }
                },
        )
    CompositionLocalProvider(
        LocalWallHubSetupPadLayout provides true,
        LocalWallHubWizardNavigateBack provides null,
    ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    PadSetupStageRail(page)
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (page) {
                            WallHubSetupPage.Welcome -> WelcomePage()
                            WallHubSetupPage.Network ->
                                NetworkPage(
                                    preferences = previewPreferences,
                                    onSaveDohEndpoints = { _, _ -> },
                                    onContinue = { _, _, _, _, _ -> },
                                )
                            WallHubSetupPage.Warmup ->
                                AntiBlockingWarmupPage(
                                    state =
                                        SetupAntiBlockingWarmupState(
                                            phase = SetupAntiBlockingWarmupPhase.READY,
                                            domesticRoutes = 5,
                                            foreignRoutes = 5,
                                            measuredRoutes = 10,
                                            elapsedMillis = 1_840,
                                            bridged = true,
                                        ),
                                    showReadyContinue = true,
                                    onRetry = {},
                                    onReadyContinue = {},
                                )
                            WallHubSetupPage.Connectivity ->
                                ConnectivityPage(
                                    result = previewConnectivity,
                                    onRetry = {},
                                    onContinue = {},
                                )
                            WallHubSetupPage.Storage ->
                                StoragePage(
                                    preferences = previewPreferences,
                                    errorMessage = null,
                                    hasFileAccess = false,
                                    onRequestAccess = {},
                                )
                            WallHubSetupPage.Steam ->
                                SteamPageContent(
                                    session = SteamSessionState(),
                                    onLogin = { _, _ -> },
                                    onSubmitCode = {},
                                    onUseManualCodeFallback = {},
                                    onRestore = {},
                                    onLogout = {},
                                )
                            WallHubSetupPage.Finish -> FinishPage()
                        }
                    }
                }
                if (page in setOf(WallHubSetupPage.Welcome, WallHubSetupPage.Storage, WallHubSetupPage.Steam, WallHubSetupPage.Finish)) {
                    val steamSignedOut = page == WallHubSetupPage.Steam
                    WallHubWizardActions(
                        visible = true,
                        modifier = Modifier.padding(start = PAD_SETUP_RAIL_WIDTH),
                        primaryLabel =
                            stringResource(
                                when (page) {
                                    WallHubSetupPage.Welcome -> R.string.setup_action_start
                                    WallHubSetupPage.Finish -> R.string.setup_action_finish
                                    else -> R.string.setup_action_next
                                },
                            ),
                        onPrimaryClick = {},
                        secondaryLabel = stringResource(R.string.setup_action_skip),
                        onSecondaryClick = {},
                        showPrimary = !steamSignedOut,
                        showSecondary = steamSignedOut,
                    )
                }
            }
        }
}

@Composable
private fun SetupToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SetupSurface(modifier = modifier) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun SteamWorkshopDataSource.label(): String =
    when (this) {
        SteamWorkshopDataSource.COMMUNITY_HTML -> stringResource(R.string.settings_steam_community_html)
        SteamWorkshopDataSource.WEB_API -> stringResource(R.string.settings_steam_web_api)
        SteamWorkshopDataSource.CM_WEBSOCKET -> stringResource(R.string.settings_steam_cm_websocket)
    }
