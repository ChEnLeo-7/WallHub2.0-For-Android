@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.R
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.AppUpdateRepository
import com.wallhub.android.core.model.DiagnosticExportRepository
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val launcherIconController: LauncherIconController,
        private val steamSessionRepository: SteamSessionRepository,
        private val diagnosticExportRepository: DiagnosticExportRepository,
        private val steamAccessRepository: SteamAccessRepository,
        private val appUpdateRepository: AppUpdateRepository,
    ) : ViewModel() {
        private val mutableDiagnosticExportState = MutableStateFlow(DiagnosticExportUiState())
        private val mutableAppUpdateState =
            MutableStateFlow(
                AppUpdateUiState(installed = appUpdateRepository.installedAppInfo),
            )
        private var appUpdateJob: Job? = null
        private val effectChannel = Channel<SettingsEffect>(capacity = Channel.BUFFERED)

        val preferences: StateFlow<AppPreferences> =
            settingsRepository.preferences.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AppPreferences(),
            )

        val session: StateFlow<SteamSessionState> =
            steamSessionRepository.session.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SteamSessionState(),
            )

        val diagnosticExportState: StateFlow<DiagnosticExportUiState> =
            mutableDiagnosticExportState.asStateFlow()

        val appUpdateState: StateFlow<AppUpdateUiState> = mutableAppUpdateState.asStateFlow()

        val steamAccessState: StateFlow<SteamAccessState> = steamAccessRepository.state
        val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

        fun onAction(action: SettingsAction) {
            action.toEffect()?.let(effectChannel::trySend) ?: handleStateAction(action)
        }

        private fun handleStateAction(action: SettingsAction) {
            if (handleAppearanceAndHomeAction(action)) return
            if (handleDownloadAction(action)) return
            if (handleSteamAction(action)) return
            handleMaintenanceAction(action)
        }

        private fun handleAppearanceAndHomeAction(action: SettingsAction): Boolean {
            when (action) {
                is SettingsAction.ThemeChanged -> setTheme(action.theme)
                is SettingsAction.AccentChanged -> setAccent(action.accent, action.customColor)
                is SettingsAction.SystemMonetEnabledChanged -> setSystemMonetEnabled(action.enabled)
                is SettingsAction.ThemedLauncherIconEnabledChanged -> setThemedLauncherIconEnabled(action.enabled)
                is SettingsAction.HomePreferencesChanged ->
                    setHomePreferences(
                        pageSize = action.pageSize,
                        columns = action.columns,
                        multiSelect = action.multiSelect,
                        cardAction = action.cardAction,
                        matureContentEnabled = action.matureContentEnabled,
                    )
                is SettingsAction.HomePaginationModeChanged -> setHomePaginationMode(action.mode)
                is SettingsAction.HomeSearchFabChanged -> setHomeSearchFab(action.enabled)
                else -> return false
            }
            return true
        }

        private fun handleDownloadAction(action: SettingsAction): Boolean {
            when (action) {
                is SettingsAction.DownloadPreferencesChanged ->
                    setDownloadPreferences(
                        maxConcurrentDownloads = action.maxConcurrentDownloads,
                        chunkDownloadConcurrency = action.chunkDownloadConcurrency,
                        proxyUrl = action.proxyUrl,
                        mediaCacheLimitMb = action.mediaCacheLimitMb,
                    )
                is SettingsAction.DownloadProxyEnabledChanged -> setDownloadProxyEnabled(action.enabled)
                is SettingsAction.OnlineStreamCacheLimitChanged -> setOnlineStreamCacheLimitMb(action.limitMb)
                is SettingsAction.OutputDirectorySelected -> setOutputDirectory(action.treeUri, action.label)
                SettingsAction.ClearOutputDirectory -> clearOutputDirectory()
                else -> return false
            }
            return true
        }

        private fun handleSteamAction(action: SettingsAction): Boolean {
            when (action) {
                is SettingsAction.SteamApiKeyChanged -> setSteamApiKey(action.apiKey)
                is SettingsAction.SteamWorkshopDataSourceChanged -> setSteamWorkshopDataSource(action.source)
                is SettingsAction.OnlineChunkPlaybackEnabledChanged -> setOnlineChunkPlaybackEnabled(action.enabled)
                is SettingsAction.SteamAccessEnabledChanged -> setSteamAccessEnabled(action.enabled)
                is SettingsAction.SteamAccessDohEndpointsChanged ->
                    setSteamAccessDohEndpoints(action.endpoints, action.disabledEndpoints)
                SettingsAction.RefreshSteamAccess -> refreshSteamAccess()
                SettingsAction.LogoutSteam -> logoutSteam()
                else -> return false
            }
            return true
        }

        private fun handleMaintenanceAction(action: SettingsAction) {
            when (action) {
                SettingsAction.CheckForAppUpdate -> checkForAppUpdate()
                SettingsAction.DownloadLatestRelease -> downloadLatestRelease()
                SettingsAction.CancelAppUpdateDownload -> cancelAppUpdateDownload()
                is SettingsAction.DiagnosticDocumentSelected -> exportDiagnostics(action.destinationUri)
                is SettingsAction.SystemActionFailed -> reportSystemActionFailure(action.message)
                is SettingsAction.InstallerFailed -> reportInstallerError(action.message)
                SettingsAction.RestartSetupWizard -> restartSetupWizard()
                else -> Unit
            }
        }

        private fun reportSystemActionFailure(message: String) {
            effectChannel.trySend(SettingsEffect.ShowMessage(message))
        }

        private fun setTheme(theme: ThemePreference) {
            viewModelScope.launch { settingsRepository.setTheme(theme) }
        }

        private fun setAccent(
            accent: AccentPreference,
            customColor: String? = null,
        ) {
            viewModelScope.launch {
                settingsRepository.setSystemMonetEnabled(false)
                settingsRepository.setAccent(accent, customColor)
            }
        }

        private fun setSystemMonetEnabled(enabled: Boolean) {
            viewModelScope.launch {
                if (!enabled && preferences.value.accent == AccentPreference.MONET) {
                    settingsRepository.setAccent(AccentPreference.DEFAULT)
                }
                settingsRepository.setSystemMonetEnabled(enabled)
            }
        }

        private fun setThemedLauncherIconEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.setThemedLauncherIconEnabled(enabled)
                runCatching { launcherIconController.setThemedIconEnabled(enabled) }
            }
        }

        private fun setHomePreferences(
            pageSize: Int,
            columns: Int,
            multiSelect: Boolean,
            cardAction: HomeCardAction,
            matureContentEnabled: Boolean,
        ) {
            viewModelScope.launch {
                settingsRepository.setHomePreferences(
                    pageSize = pageSize,
                    columns = columns,
                    multiSelect = multiSelect,
                    cardAction = cardAction,
                    matureContentEnabled = matureContentEnabled,
                )
            }
        }

        private fun setHomePaginationMode(mode: HomePaginationMode) {
            viewModelScope.launch { settingsRepository.setHomePaginationMode(mode) }
        }

        private fun setHomeSearchFab(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setHomeSearchFab(enabled) }
        }

        private fun setDownloadPreferences(
            maxConcurrentDownloads: Int,
            chunkDownloadConcurrency: Int,
            proxyUrl: String,
            mediaCacheLimitMb: Int,
        ) {
            viewModelScope.launch {
                settingsRepository.setDownloadPreferences(
                    maxConcurrentDownloads = maxConcurrentDownloads,
                    chunkDownloadConcurrency = chunkDownloadConcurrency,
                    proxyUrl = proxyUrl,
                    mediaCacheLimitMb = mediaCacheLimitMb,
                )
            }
        }

        private fun setOnlineStreamCacheLimitMb(limitMb: Int) {
            viewModelScope.launch { settingsRepository.setOnlineStreamCacheLimitMb(limitMb) }
        }

        private fun setDownloadProxyEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setDownloadProxyEnabled(enabled) }
        }

        private fun setSteamAccessEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setSteamAccessEnabled(enabled) }
        }

        private fun setSteamAccessDohEndpoints(
            endpoints: List<String>,
            disabledEndpoints: Set<String>,
        ) {
            viewModelScope.launch {
                settingsRepository.setSteamAccessDohEndpoints(endpoints, disabledEndpoints)
            }
        }

        private fun refreshSteamAccess() {
            steamAccessRepository.refresh()
        }

        private fun setSteamApiKey(apiKey: String) {
            viewModelScope.launch { settingsRepository.setSteamApiKey(apiKey) }
        }

        private fun setSteamWorkshopDataSource(source: SteamWorkshopDataSource) {
            viewModelScope.launch { settingsRepository.setSteamWorkshopDataSource(source) }
        }

        private fun setOnlineChunkPlaybackEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setOnlineChunkPlaybackEnabled(enabled) }
        }

        private fun setOutputDirectory(
            treeUri: String,
            label: String,
        ) {
            viewModelScope.launch { settingsRepository.setOutputDirectory(treeUri, label) }
        }

        private fun clearOutputDirectory() {
            viewModelScope.launch { settingsRepository.clearOutputDirectory() }
        }

        private fun logoutSteam() {
            steamSessionRepository.logout()
        }

        private fun restartSetupWizard() {
            viewModelScope.launch { settingsRepository.setSetupWizardCompleted(false) }
        }

        private fun checkForAppUpdate() {
            if (appUpdateJob != null) return
            mutableAppUpdateState.value =
                mutableAppUpdateState.value.copy(
                    phase = AppUpdatePhase.CHECKING,
                    release = null,
                    downloadedApkPath = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    message = null,
                )
            appUpdateJob =
                viewModelScope.launch {
                    try {
                        val release = appUpdateRepository.latestRelease()
                        mutableAppUpdateState.value =
                            mutableAppUpdateState.value.copy(
                                phase = if (release.isNewer) AppUpdatePhase.AVAILABLE else AppUpdatePhase.UP_TO_DATE,
                                release = release,
                                downloadedBytes = 0L,
                                totalBytes = release.assetSizeBytes,
                                message = null,
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        mutableAppUpdateState.value =
                            mutableAppUpdateState.value.copy(
                                phase = AppUpdatePhase.FAILED,
                                message = context.getString(R.string.settings_error_check_github_release),
                            )
                    } finally {
                        appUpdateJob = null
                    }
                }
        }

        private fun downloadLatestRelease() {
            val release = mutableAppUpdateState.value.release ?: return
            if (appUpdateJob != null) return
            mutableAppUpdateState.value =
                mutableAppUpdateState.value.copy(
                    phase = AppUpdatePhase.DOWNLOADING,
                    downloadedApkPath = null,
                    downloadedBytes = 0L,
                    totalBytes = release.assetSizeBytes,
                    message = null,
                )
            appUpdateJob =
                viewModelScope.launch {
                    try {
                        val path =
                            appUpdateRepository.downloadRelease(release) { downloaded, total ->
                                if (mutableAppUpdateState.value.phase == AppUpdatePhase.DOWNLOADING) {
                                    mutableAppUpdateState.value =
                                        mutableAppUpdateState.value.copy(
                                            downloadedBytes = downloaded,
                                            totalBytes = total,
                                        )
                                }
                            }
                        mutableAppUpdateState.value =
                            mutableAppUpdateState.value.copy(
                                phase = AppUpdatePhase.DOWNLOADED,
                                downloadedApkPath = path,
                                downloadedBytes = release.assetSizeBytes,
                                totalBytes = release.assetSizeBytes,
                                message = null,
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        mutableAppUpdateState.value =
                            mutableAppUpdateState.value.copy(
                                phase = AppUpdatePhase.FAILED,
                                downloadedApkPath = null,
                                message = context.getString(R.string.settings_error_download_release_apk),
                            )
                    } finally {
                        appUpdateJob = null
                    }
                }
        }

        private fun cancelAppUpdateDownload() {
            val current = mutableAppUpdateState.value
            val release = current.release ?: return
            if (current.phase != AppUpdatePhase.DOWNLOADING) return
            appUpdateRepository.cancelDownload()
            appUpdateJob?.cancel()
            mutableAppUpdateState.value =
                current.copy(
                    phase = if (release.isNewer) AppUpdatePhase.AVAILABLE else AppUpdatePhase.UP_TO_DATE,
                    downloadedApkPath = null,
                    downloadedBytes = 0L,
                    totalBytes = release.assetSizeBytes,
                    message = null,
                )
        }

        override fun onCleared() {
            appUpdateRepository.cancelDownload()
            super.onCleared()
        }

        private fun reportInstallerError(message: String) {
            mutableAppUpdateState.value =
                mutableAppUpdateState.value.copy(
                    phase = AppUpdatePhase.FAILED,
                    message = message,
                )
        }

        private fun exportDiagnostics(destinationUri: String) {
            mutableDiagnosticExportState.value = DiagnosticExportUiState(isExporting = true)
            viewModelScope.launch {
                runCatching {
                    diagnosticExportRepository.exportTo(destinationUri)
                }.onSuccess {
                    mutableDiagnosticExportState.value =
                        DiagnosticExportUiState(
                            message = context.getString(R.string.settings_diagnostic_exported),
                        )
                }.onFailure { error ->
                    mutableDiagnosticExportState.value =
                        DiagnosticExportUiState(
                            message = context.getString(R.string.settings_error_diagnostic_export, error.javaClass.simpleName),
                            isFailure = true,
                        )
                }
            }
        }
    }
