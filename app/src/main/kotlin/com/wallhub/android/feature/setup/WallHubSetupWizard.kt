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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubColorTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
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
import org.uwuaosp.compose.wizard.WizardActions
import org.uwuaosp.compose.wizard.WizardBrandPage
import org.uwuaosp.compose.wizard.WizardPageConfig
import org.uwuaosp.compose.wizard.WizardPageScaffold
import org.uwuaosp.compose.wizard.wizardActionContentPadding
import org.uwuaosp.compose.wizard.wizardPageTransition
import org.uwuaosp.compose.settingslib.SettingsBackIcon
import org.uwuaosp.compose.settingslib.SettingsToolbarActionButton
import org.uwuaosp.compose.settingslib.rememberSettingsTypography
import javax.inject.Inject
import androidx.compose.material.icons.Icons
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
            steamSessionRepository.restorePersistedSession()
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
                    _networkPreflightResult.value = SetupNetworkPreflightResult.checking()
                    _networkPreflightResult.value =
                        networkPreflight.run(SetupNetworkPreflightInput(false)) { snapshot ->
                            _networkPreflightResult.value = snapshot
                        }
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
            networkPreparationJob?.cancel()
            networkPreparationJob = viewModelScope.launch {
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
    val accessibilityEnabled =
        remember(context) {
            (context.getSystemService(AccessibilityManager::class.java))?.isEnabled == true
        }
    var page by rememberSaveable(stateSaver = WallHubSetupPageSaver) {
        mutableStateOf(WallHubSetupPage.Welcome)
    }
    var continueAfterDirectorySelection by rememberSaveable { mutableStateOf(false) }
    var storageError by rememberSaveable { mutableStateOf<String?>(null) }
    val legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                storageError = null
                page = WallHubSetupPage.Steam
            } else {
                storageError = context.getString(R.string.setup_storage_legacy_permission_denied)
            }
        }
    val outputDirectoryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                    .onSuccess {
                        val label =
                            treeUri.lastPathSegment
                                ?.substringAfterLast(':')
                                ?.ifBlank { null }
                                ?: context.getString(R.string.settings_default_export_directory)
                        viewModel.saveOutputDirectory(treeUri.toString(), label)
                        storageError = null
                        if (continueAfterDirectorySelection) page = WallHubSetupPage.Steam
                    }.onFailure {
                        storageError = context.getString(R.string.setup_storage_authorization_failed)
                    }
            } else if (continueAfterDirectorySelection) {
                storageError = context.getString(R.string.setup_storage_authorization_required)
            }
            continueAfterDirectorySelection = false
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
    MaterialTheme(typography = rememberSettingsTypography()) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                wizardPageTransition(
                    WallHubSetupPage.entries.indexOf(targetState) >=
                        WallHubSetupPage.entries.indexOf(initialState),
                )
            },
            label = "WallHubSetupPage",
        ) { target ->
            when (target) {
                WallHubSetupPage.Welcome -> WelcomePage()
                WallHubSetupPage.Storage ->
                    StoragePage(
                        preferences = preferences,
                        errorMessage = storageError,
                        onSelectDirectory = {
                            outputDirectoryLauncher.launch(defaultDownloadsTreeUri())
                        },
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
        WizardActions(
            visible =
                page != WallHubSetupPage.Network &&
                    page != WallHubSetupPage.Warmup &&
                    page != WallHubSetupPage.Connectivity,
            expanded = page == WallHubSetupPage.Welcome || page == WallHubSetupPage.Finish,
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
                            preferences.outputTreeUri.isNullOrBlank() -> {
                                continueAfterDirectorySelection = true
                                outputDirectoryLauncher.launch(defaultDownloadsTreeUri())
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
        if (page != WallHubSetupPage.Welcome) {
            SettingsToolbarActionButton(
                imageVector = SettingsBackIcon,
                contentDescription = stringResource(R.string.settings_action_back),
                onClick = navigateBack,
                iconSize = 16.dp,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 15.dp),
            )
        }
    }
    }
}

@Composable
private fun WelcomePage() {
    val bottomPadding = wizardActionContentPadding(
        visible = true,
        expanded = true,
        showPrimary = true,
        showSecondary = false,
    )
    WizardBrandPage(
        title = stringResource(R.string.setup_welcome_title),
        subtitle = stringResource(R.string.setup_welcome_description),
        bottomContentPadding = bottomPadding,
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
private fun StoragePage(
    preferences: AppPreferences,
    errorMessage: String?,
    onSelectDirectory: () -> Unit,
) {
    val bottomPadding = wizardActionContentPadding(
        visible = true,
        expanded = false,
        showPrimary = true,
        showSecondary = false,
    )
    WizardPageScaffold(
        config = WizardPageConfig(
            title = stringResource(R.string.setup_storage_title),
            description = stringResource(R.string.setup_storage_description),
        ),
        headerIcon = { WizardIcon(Icons.Outlined.FolderOpen) },
        bottomContentPadding = bottomPadding,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
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
        Button(
            onClick = onSelectDirectory,
            modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.md).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Text(
                stringResource(R.string.setup_storage_choose_directory),
                modifier = Modifier.padding(start = WallHubSpacing.xs),
            )
        }
        Text(
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                stringResource(R.string.setup_storage_legacy_permission_detail)
            } else {
                stringResource(R.string.setup_storage_saf_detail)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.sm),
        )
        errorMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.sm),
            )
        }
    }
}

private fun defaultDownloadsTreeUri(): Uri =
    DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Download/WallHub",
    )

@Composable
private fun SteamPage(session: SteamSessionState, viewModel: WallHubSetupViewModel) {
    val signedIn = session.phase == SteamSessionPhase.SIGNED_IN
    val bottomPadding = wizardActionContentPadding(
        visible = true,
        expanded = false,
        showPrimary = signedIn,
        showSecondary = !signedIn,
    )
    WizardPageScaffold(
        config = WizardPageConfig(
            title = stringResource(R.string.setup_steam_title),
            description = stringResource(R.string.setup_steam_description),
        ),
        headerIcon = { WizardIcon(Icons.Outlined.PersonOutline) },
        bottomContentPadding = bottomPadding,
    ) {
        SteamAccountCard(
            session = session,
            onLogin = viewModel::login,
            onSubmitCode = viewModel::submitCode,
            onUseManualCodeFallback = viewModel::useManualCodeFallback,
            onRestore = viewModel::restoreSteamSession,
            onLogout = viewModel::logoutSteam,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = SETTINGS_CONTENT_MAX_WIDTH),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkPage(
    preferences: AppPreferences,
    onSaveDohEndpoints: (List<String>, Set<String>) -> Unit,
    onContinue: (String, Boolean, Boolean, SteamWorkshopDataSource, String) -> Unit,
) {
    val bottomPadding = wizardActionContentPadding(
        visible = true,
        expanded = false,
        showPrimary = true,
        showSecondary = false,
    )
    var proxyUrl by rememberSaveable(preferences.downloadProxyUrl) { mutableStateOf(preferences.downloadProxyUrl) }
    var proxyEnabled by rememberSaveable(preferences.downloadProxyEnabled) { mutableStateOf(preferences.downloadProxyEnabled) }
    var accessEnabled by rememberSaveable(preferences.steamAccessEnabled) { mutableStateOf(preferences.steamAccessEnabled) }
    var source by rememberSaveable(preferences.steamWorkshopDataSource) { mutableStateOf(preferences.steamWorkshopDataSource) }
    var apiKey by remember(preferences.steamApiKey) { mutableStateOf(preferences.steamApiKey) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        WizardPageScaffold(
            config = WizardPageConfig(
                title = stringResource(R.string.setup_network_title),
                description = stringResource(R.string.setup_network_description),
            ),
            headerIcon = { WizardIcon(Icons.Outlined.Language) },
            bottomContentPadding = bottomPadding,
        ) {
            SetupToggle(
                title = stringResource(R.string.settings_use_network_proxy),
                description = stringResource(R.string.settings_use_network_proxy_description),
                checked = proxyEnabled,
                enabled = isSupportedDownloadProxyUrl(proxyUrl),
                onCheckedChange = { proxyEnabled = it },
            )
            TextField(
                value = proxyUrl,
                onValueChange = {
                    proxyUrl = it
                    if (!isSupportedDownloadProxyUrl(it)) proxyEnabled = false
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_field_label)) },
            )
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
            ExposedDropdownMenuBox(
                expanded = sourceMenuExpanded,
                onExpandedChange = { sourceMenuExpanded = it },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                TextField(
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
                TextField(
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
        }
        WizardActions(
            visible = true,
            expanded = false,
            primaryLabel = stringResource(R.string.setup_action_next),
            primaryEnabled = source != SteamWorkshopDataSource.WEB_API || apiKey.isNotBlank(),
            onPrimaryClick = { onContinue(proxyUrl, proxyEnabled, accessEnabled, source, apiKey) },
        )
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
    WizardPageScaffold(
        config =
            WizardPageConfig(
                title = stringResource(R.string.setup_warmup_title),
                description = stringResource(R.string.setup_warmup_description),
            ),
        headerIcon = { WizardIcon(Icons.Outlined.Language) },
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = WallHubSpacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
            ) {
                Surface(
                    modifier = Modifier.size(128.dp),
                    shape = CircleShape,
                    color =
                        if (state.phase == SetupAntiBlockingWarmupPhase.READY) {
                            Color.Transparent
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Crossfade(
                            targetState = state.phase,
                            animationSpec =
                                tween(
                                    durationMillis = if (reduceMotion) 90 else 160,
                                    easing = WARMUP_STATUS_EASE_OUT,
                                ),
                            label = "anti-blocking-warmup-status",
                        ) { phase ->
                            when (phase) {
                                SetupAntiBlockingWarmupPhase.IDLE,
                                SetupAntiBlockingWarmupPhase.WARMING,
                                -> Box(contentAlignment = Alignment.Center) {
                                    if (!reduceMotion) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(92.dp),
                                            strokeWidth = 5.dp,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Outlined.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(42.dp),
                                    )
                                }
                                SetupAntiBlockingWarmupPhase.READY ->
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = successColor,
                                        modifier = Modifier.size(72.dp),
                                    )
                                SetupAntiBlockingWarmupPhase.DEGRADED ->
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(72.dp),
                                    )
                            }
                        }
                    }
                }
                Crossfade(
                    targetState = state.phase,
                    animationSpec =
                        tween(
                            durationMillis = if (reduceMotion) 90 else 160,
                            easing = WARMUP_STATUS_EASE_OUT,
                        ),
                    label = "anti-blocking-warmup-copy",
                ) { phase ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                    ) {
                        Text(
                            text =
                                when (phase) {
                                    SetupAntiBlockingWarmupPhase.IDLE,
                                    SetupAntiBlockingWarmupPhase.WARMING,
                                    -> stringResource(R.string.setup_warmup_working)
                                    SetupAntiBlockingWarmupPhase.READY -> stringResource(R.string.setup_warmup_ready)
                                    SetupAntiBlockingWarmupPhase.DEGRADED -> stringResource(R.string.setup_warmup_degraded)
                                },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text =
                                when (phase) {
                                    SetupAntiBlockingWarmupPhase.IDLE,
                                    SetupAntiBlockingWarmupPhase.WARMING,
                                    -> stringResource(R.string.setup_warmup_working_detail)
                                    SetupAntiBlockingWarmupPhase.READY ->
                                        if (state.bypassed) {
                                            stringResource(R.string.setup_warmup_direct_detail)
                                        } else {
                                            stringResource(
                                                R.string.setup_warmup_ready_detail,
                                                state.domesticRoutes,
                                                state.foreignRoutes,
                                            )
                                        }
                                    SetupAntiBlockingWarmupPhase.DEGRADED ->
                                        if (state.reason.isNotBlank()) {
                                            state.reason
                                        } else {
                                            stringResource(
                                                R.string.setup_warmup_degraded_detail,
                                                state.domesticRoutes,
                                                state.foreignRoutes,
                                            )
                                        }
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.phase == SetupAntiBlockingWarmupPhase.DEGRADED) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedVisibility(
                            visible = state.phase == SetupAntiBlockingWarmupPhase.READY,
                            enter = warmupButtonEnterTransition(reduceMotion),
                            exit = warmupButtonExitTransition(reduceMotion),
                        ) {
                            Button(
                                onClick = onReadyContinue,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Text(stringResource(R.string.setup_warmup_continue))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectivityPage(
    result: SetupNetworkPreflightResult,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val showContinue = result.overallStatus.showsContinueAction()
    val bottomPadding =
        wizardActionContentPadding(
            visible = showContinue,
            expanded = false,
            showPrimary = showContinue,
            showSecondary = false,
        )
    Box(modifier = Modifier.fillMaxSize()) {
        WizardPageScaffold(
            config =
                WizardPageConfig(
                    title = stringResource(R.string.setup_connectivity_title),
                    description = stringResource(R.string.setup_connectivity_description),
                ),
            headerIcon = { WizardIcon(Icons.Outlined.Language) },
            bottomContentPadding = bottomPadding,
        ) {
            SetupNetworkPreflightCard(result, onRetry)
        }
        if (showContinue) {
            WizardActions(
                visible = true,
                expanded = false,
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
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.md),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
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
            Column {
                result.items.forEachIndexed { index, item ->
                    ConnectivityCheckRow(
                        item = item,
                        successColor = successColor,
                        reduceMotion = reduceMotion,
                    )
                    if (index != result.items.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 36.dp),
                        )
                    }
                }
            }
            if (result.overallStatus.showsRetryAction()) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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

private val WARMUP_STATUS_EASE_OUT = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

private fun warmupButtonEnterTransition(reduceMotion: Boolean): EnterTransition =
    fadeIn(
        tween(
            durationMillis = if (reduceMotion) 100 else 140,
            delayMillis = if (reduceMotion) 0 else 80,
            easing = WARMUP_STATUS_EASE_OUT,
        ),
    )

private fun warmupButtonExitTransition(reduceMotion: Boolean): ExitTransition =
    fadeOut(
        tween(
            durationMillis = if (reduceMotion) 70 else 90,
            easing = WARMUP_STATUS_EASE_OUT,
        ),
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
        SetupNetworkCheck.CM_TRANSPORT -> stringResource(R.string.setup_network_item_cm_transport)
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
    val bottomPadding = wizardActionContentPadding(
        visible = true,
        expanded = true,
        showPrimary = true,
        showSecondary = false,
    )
    WizardBrandPage(
        title = stringResource(R.string.setup_finish_title),
        subtitle = stringResource(R.string.setup_finish_description),
        bottomContentPadding = bottomPadding,
        illustration = {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(180.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(96.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun WizardIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp),
    )
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
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
