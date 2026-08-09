@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.ThemePreference
import org.uwuaosp.compose.settingslib.SettingsScaffold
import org.uwuaosp.compose.settingslib.rememberSettingsTypography
import org.uwuaosp.compose.settingslib.SettingsCategory as UwuSettingsCategory

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    steamAccessState: SteamAccessState,
    session: SteamSessionState,
    diagnosticExportState: DiagnosticExportUiState,
    appUpdateState: AppUpdateUiState,
    onBack: () -> Unit,
    onAction: (SettingsAction) -> Unit,
) {
    val onThemePreferenceChange: (ThemePreference) -> Unit = { onAction(SettingsAction.ThemeChanged(it)) }
    val onAccentChange: (AccentPreference, String?) -> Unit = { accent, customColor ->
        onAction(SettingsAction.AccentChanged(accent, customColor))
    }
    val onSystemMonetEnabledChange: (Boolean) -> Unit = {
        onAction(SettingsAction.SystemMonetEnabledChanged(it))
    }
    val onThemedLauncherIconEnabledChange: (Boolean) -> Unit = {
        onAction(SettingsAction.ThemedLauncherIconEnabledChanged(it))
    }
    val onHomePreferencesChange: (Int, Int, Boolean, HomeCardAction, Boolean) -> Unit =
        { pageSize, columns, multiSelect, cardAction, matureContentEnabled ->
            onAction(
                SettingsAction.HomePreferencesChanged(
                    pageSize,
                    columns,
                    multiSelect,
                    cardAction,
                    matureContentEnabled,
                ),
            )
        }
    val onHomePaginationModeChange: (HomePaginationMode) -> Unit = {
        onAction(SettingsAction.HomePaginationModeChanged(it))
    }
    val onHomeSearchFabChange: (Boolean) -> Unit = {
        onAction(SettingsAction.HomeSearchFabChanged(it))
    }
    val onDownloadPreferencesChange: (Int, Int, String, Int) -> Unit =
        { maxConcurrentDownloads, chunkDownloadConcurrency, proxyUrl, mediaCacheLimitMb ->
            onAction(
                SettingsAction.DownloadPreferencesChanged(
                    maxConcurrentDownloads,
                    chunkDownloadConcurrency,
                    proxyUrl,
                    mediaCacheLimitMb,
                ),
            )
        }
    val onDownloadProxyEnabledChange: (Boolean) -> Unit = {
        onAction(SettingsAction.DownloadProxyEnabledChanged(it))
    }
    val onOnlineStreamCacheLimitChange: (Int) -> Unit = {
        onAction(SettingsAction.OnlineStreamCacheLimitChanged(it))
    }
    val onSteamApiKeyChange: (String) -> Unit = { onAction(SettingsAction.SteamApiKeyChanged(it)) }
    val onSteamWorkshopDataSourceChange: (SteamWorkshopDataSource) -> Unit = {
        onAction(SettingsAction.SteamWorkshopDataSourceChanged(it))
    }
    val onOnlineChunkPlaybackEnabledChange: (Boolean) -> Unit = {
        onAction(SettingsAction.OnlineChunkPlaybackEnabledChanged(it))
    }
    val onSteamAccessEnabledChange: (Boolean) -> Unit = {
        onAction(SettingsAction.SteamAccessEnabledChanged(it))
    }
    val onSteamAccessDohEndpointsChange: (List<String>, Set<String>) -> Unit = { endpoints, disabledEndpoints ->
        onAction(SettingsAction.SteamAccessDohEndpointsChanged(endpoints, disabledEndpoints))
    }
    val onRefreshSteamAccess: () -> Unit = { onAction(SettingsAction.RefreshSteamAccess) }
    val onLoginSteam: (String, String) -> Unit = { accountName, password ->
        onAction(SettingsAction.LoginSteam(accountName, password))
    }
    val onSubmitSteamGuardCode: (String) -> Unit = { onAction(SettingsAction.SubmitSteamGuardCode(it)) }
    val onUseManualSteamGuardFallback: () -> Unit = { onAction(SettingsAction.UseManualSteamGuardFallback) }
    val onRestoreSteamSession: () -> Unit = { onAction(SettingsAction.RestoreSteamSession) }
    val onLogoutSteam: () -> Unit = { onAction(SettingsAction.LogoutSteam) }
    val onSelectOutputDirectory: () -> Unit = { onAction(SettingsAction.SelectOutputDirectory) }
    val onClearOutputDirectory: () -> Unit = { onAction(SettingsAction.ClearOutputDirectory) }
    val onCheckForAppUpdate: () -> Unit = { onAction(SettingsAction.CheckForAppUpdate) }
    val onDownloadLatestRelease: () -> Unit = { onAction(SettingsAction.DownloadLatestRelease) }
    val onCancelAppUpdateDownload: () -> Unit = { onAction(SettingsAction.CancelAppUpdateDownload) }
    val onInstallDownloadedRelease: (String) -> Unit = {
        onAction(SettingsAction.InstallDownloadedRelease(it))
    }
    val onExportDiagnostics: () -> Unit = { onAction(SettingsAction.ExportDiagnostics) }
    val onRequestNotifications: () -> Unit = { onAction(SettingsAction.RequestNotifications) }
    val onOpenExternalUri: (String, String) -> Unit = { uri, failureMessage ->
        onAction(SettingsAction.OpenExternalUri(uri, failureMessage))
    }
    val onRestartSetupWizard: () -> Unit = { onAction(SettingsAction.RestartSetupWizard) }
    var selectedPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val availableAccents =
        AccentPreference.entries.filter { accent ->
            accent != AccentPreference.MONET
        }
    var customAccentColor by remember(preferences.customAccentColor) {
        mutableStateOf(preferences.customAccentColor)
    }
    var proxyUrl by remember(preferences.downloadProxyUrl) {
        mutableStateOf(preferences.downloadProxyUrl)
    }
    var steamApiKey by remember(preferences.steamApiKey) {
        mutableStateOf(preferences.steamApiKey)
    }

    fun saveHomePreferences(
        pageSize: Int = preferences.homePageSize,
        columns: Int = preferences.homeColumns,
        multiSelect: Boolean = preferences.homeFilterMultiSelect,
        cardAction: HomeCardAction = preferences.homeCardAction,
        matureContentEnabled: Boolean = preferences.matureContentEnabled,
    ) {
        onHomePreferencesChange(pageSize, columns, multiSelect, cardAction, matureContentEnabled)
    }
    BackHandler(enabled = selectedPageName != null) {
        selectedPageName = null
    }
    AnimatedContent(
        targetState = selectedPageName,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState == null) -1 else 1
            val enterOffsetDivisor =
                if (targetState == null) {
                    SETTINGS_PAGE_EXIT_OFFSET_DIVISOR
                } else {
                    SETTINGS_PAGE_ENTER_OFFSET_DIVISOR
                }
            val exitOffsetDivisor =
                if (targetState == null) {
                    SETTINGS_PAGE_ENTER_OFFSET_DIVISOR
                } else {
                    SETTINGS_PAGE_EXIT_OFFSET_DIVISOR
                }
            (
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = SETTINGS_PAGE_ENTER_DURATION_MS,
                            easing = SETTINGS_PAGE_EASING,
                        ),
                ) +
                    slideInHorizontally(
                        initialOffsetX = { width -> direction * width / enterOffsetDivisor },
                        animationSpec =
                            tween(
                                durationMillis = SETTINGS_PAGE_ENTER_DURATION_MS,
                                easing = SETTINGS_PAGE_EASING,
                            ),
                    )
            ) togetherWith (
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = SETTINGS_PAGE_EXIT_DURATION_MS,
                            easing = SETTINGS_PAGE_EASING,
                        ),
                ) +
                    slideOutHorizontally(
                        targetOffsetX = { width -> -direction * width / exitOffsetDivisor },
                        animationSpec =
                            tween(
                                durationMillis = SETTINGS_PAGE_EXIT_DURATION_MS,
                                easing = SETTINGS_PAGE_EASING,
                            ),
                    )
            )
        },
        contentKey = { pageName -> pageName ?: SETTINGS_CATEGORY_INDEX_KEY },
        label = "SettingsCategoryPage",
    ) { displayedPageName ->
        val displayedCategory =
            displayedPageName
                ?.takeUnless { it == SETTINGS_ABOUT_PAGE_KEY }
                ?.let { categoryName -> SettingsCategory.entries.firstOrNull { it.name == categoryName } }
        when {
            displayedPageName == null -> {
                SettingsCategoryIndex(
                    title = stringResource(R.string.settings_title),
                    onBack = onBack,
                    onOpenCategory = { selectedPageName = it.name },
                    onOpenAbout = { selectedPageName = SETTINGS_ABOUT_PAGE_KEY },
                )
            }

            displayedPageName == SETTINGS_ABOUT_PAGE_KEY -> {
                AboutWallHubScreen(
                    installed = appUpdateState.installed,
                    appUpdateState = appUpdateState,
                    onBack = { selectedPageName = null },
                    onCheckForAppUpdate = onCheckForAppUpdate,
                    onDownloadLatestRelease = onDownloadLatestRelease,
                    onCancelAppUpdateDownload = onCancelAppUpdateDownload,
                    onInstallDownloadedRelease = onInstallDownloadedRelease,
                    onOpenExternalUri = onOpenExternalUri,
                    onRestartSetupWizard = onRestartSetupWizard,
                )
            }

            displayedCategory == SettingsCategory.APPEARANCE -> {
                AppearanceSettingsScreen(
                    preferences = preferences,
                    availableAccents = availableAccents,
                    customAccentColor = customAccentColor,
                    onBack = { selectedPageName = null },
                    onCustomAccentColorChanged = { customAccentColor = it },
                    onThemePreferenceChange = onThemePreferenceChange,
                    onAccentChange = onAccentChange,
                    onSystemMonetEnabledChange = onSystemMonetEnabledChange,
                    onThemedLauncherIconEnabledChange = onThemedLauncherIconEnabledChange,
                    onHomePreferencesChange = onHomePreferencesChange,
                    onHomePaginationModeChange = onHomePaginationModeChange,
                    onHomeSearchFabChange = onHomeSearchFabChange,
                )
            }

            displayedCategory != null -> {
                val colorScheme = MaterialTheme.colorScheme
                MaterialTheme(
                    colorScheme =
                        colorScheme.copy(
                            surfaceContainer = colorScheme.surfaceContainerLowest,
                            surfaceBright = colorScheme.surfaceContainerLow,
                        ),
                    typography = rememberSettingsTypography(),
                ) {
                    SettingsScaffold(
                        title = stringResource(displayedCategory.labelRes),
                        showBackButton = true,
                        onNavigateUp = { selectedPageName = null },
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .widthIn(max = SETTINGS_CONTENT_MAX_WIDTH)
                                    .fillMaxWidth(),
                        ) {
                            SettingsCategoryContent(
                                category = displayedCategory,
                                preferences = preferences,
                                steamAccessState = steamAccessState,
                                session = session,
                                diagnosticExportState = diagnosticExportState,
                                proxyUrl = proxyUrl,
                                onProxyUrlChanged = { proxyUrl = it },
                                steamApiKey = steamApiKey,
                                onSteamApiKeyChanged = { steamApiKey = it },
                                onMatureContentEnabledChange = { enabled ->
                                    saveHomePreferences(matureContentEnabled = enabled)
                                },
                                onDownloadPreferencesChange = onDownloadPreferencesChange,
                                onDownloadProxyEnabledChange = onDownloadProxyEnabledChange,
                                onOnlineStreamCacheLimitChange = onOnlineStreamCacheLimitChange,
                                onSteamAccessEnabledChange = onSteamAccessEnabledChange,
                                onSteamAccessDohEndpointsChange = onSteamAccessDohEndpointsChange,
                                onSteamWorkshopDataSourceChange = onSteamWorkshopDataSourceChange,
                                onRefreshSteamAccess = onRefreshSteamAccess,
                                onSaveSteamApiKey = { onSteamApiKeyChange(steamApiKey) },
                                onOpenExternalUri = onOpenExternalUri,
                                onLoginSteam = onLoginSteam,
                                onSubmitSteamGuardCode = onSubmitSteamGuardCode,
                                onUseManualSteamGuardFallback = onUseManualSteamGuardFallback,
                                onRestoreSteamSession = onRestoreSteamSession,
                                onLogoutSteam = onLogoutSteam,
                                onSelectOutputDirectory = onSelectOutputDirectory,
                                onClearOutputDirectory = onClearOutputDirectory,
                                onExportDiagnostics = onExportDiagnostics,
                                onOnlineChunkPlaybackEnabledChange = onOnlineChunkPlaybackEnabledChange,
                                onRequestNotifications = onRequestNotifications,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsCategoryContent(
    category: SettingsCategory,
    preferences: AppPreferences,
    steamAccessState: SteamAccessState,
    session: SteamSessionState,
    diagnosticExportState: DiagnosticExportUiState,
    proxyUrl: String,
    onProxyUrlChanged: (String) -> Unit,
    steamApiKey: String,
    onSteamApiKeyChanged: (String) -> Unit,
    onMatureContentEnabledChange: (Boolean) -> Unit,
    onDownloadPreferencesChange: (Int, Int, String, Int) -> Unit,
    onDownloadProxyEnabledChange: (Boolean) -> Unit,
    onOnlineStreamCacheLimitChange: (Int) -> Unit,
    onSteamAccessEnabledChange: (Boolean) -> Unit,
    onSteamAccessDohEndpointsChange: (List<String>, Set<String>) -> Unit,
    onSteamWorkshopDataSourceChange: (SteamWorkshopDataSource) -> Unit,
    onRefreshSteamAccess: () -> Unit,
    onSaveSteamApiKey: () -> Unit,
    onOpenExternalUri: (String, String) -> Unit,
    onLoginSteam: (String, String) -> Unit,
    onSubmitSteamGuardCode: (String) -> Unit,
    onUseManualSteamGuardFallback: () -> Unit,
    onRestoreSteamSession: () -> Unit,
    onLogoutSteam: () -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onClearOutputDirectory: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onOnlineChunkPlaybackEnabledChange: (Boolean) -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val openSteamApiKeyPageFailure = stringResource(R.string.settings_error_open_steam_api_key_page)
    when (category) {
        SettingsCategory.BASIC ->
            BasicSettingsContent(
                matureContentEnabled = preferences.matureContentEnabled,
                diagnosticExportState = diagnosticExportState,
                onMatureContentEnabledChange = onMatureContentEnabledChange,
                onExportDiagnostics = onExportDiagnostics,
                onRequestNotifications = onRequestNotifications,
            )

        SettingsCategory.DOWNLOAD ->
            DownloadSettingsContent(
                preferences = preferences,
                proxyUrl = proxyUrl,
                onProxyUrlChanged = onProxyUrlChanged,
                onSelectOutputDirectory = onSelectOutputDirectory,
                onClearOutputDirectory = onClearOutputDirectory,
                onDownloadPreferencesChange = onDownloadPreferencesChange,
                onDownloadProxyEnabledChange = onDownloadProxyEnabledChange,
                onOnlineChunkPlaybackEnabledChange = onOnlineChunkPlaybackEnabledChange,
                onOnlineStreamCacheLimitChange = onOnlineStreamCacheLimitChange,
            )

        SettingsCategory.STEAM ->
            SteamSettingsContent(
                session = session,
                steamAccessEnabled = preferences.steamAccessEnabled,
                steamAccessState = steamAccessState,
                steamAccessDohEndpoints = preferences.steamAccessDohEndpoints,
                steamAccessDisabledDohEndpoints = preferences.steamAccessDisabledDohEndpoints,
                steamWorkshopDataSource = preferences.steamWorkshopDataSource,
                onSteamAccessEnabledChange = onSteamAccessEnabledChange,
                onSteamAccessDohEndpointsChange = onSteamAccessDohEndpointsChange,
                onSteamWorkshopDataSourceChange = onSteamWorkshopDataSourceChange,
                onRefreshSteamAccess = onRefreshSteamAccess,
                savedApiKey = preferences.steamApiKey,
                apiKey = steamApiKey,
                onApiKeyChanged = onSteamApiKeyChanged,
                onSaveApiKey = onSaveSteamApiKey,
                onOpenApiKeyPage = {
                    onOpenExternalUri(
                        STEAM_API_KEY_URL,
                        openSteamApiKeyPageFailure,
                    )
                },
                onLoginSteam = onLoginSteam,
                onSubmitSteamGuardCode = onSubmitSteamGuardCode,
                onUseManualSteamGuardFallback = onUseManualSteamGuardFallback,
                onRestoreSteamSession = onRestoreSteamSession,
                onLogoutSteam = onLogoutSteam,
            )

        SettingsCategory.APPEARANCE -> Unit

    }
}

private const val SETTINGS_ABOUT_PAGE_KEY = "about_wallhub"

@Composable
internal fun SettingsSectionSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    WallHubSurfaceCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        content = content,
    )
}

@Composable
internal fun SettingsFilledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = MaterialTheme.shapes.large,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
            ),
    )
}

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        UwuSettingsCategory(title = title)
        Column(content = content)
    }
}
