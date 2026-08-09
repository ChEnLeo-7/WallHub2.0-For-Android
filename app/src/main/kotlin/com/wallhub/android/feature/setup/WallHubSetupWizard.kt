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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.settings.SETTINGS_CONTENT_MAX_WIDTH
import com.wallhub.settings.SteamAccountCard
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import com.wallhub.settings.SteamAccessDohEndpointsSetting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.uwuaosp.compose.wizard.WizardActions
import org.uwuaosp.compose.wizard.WizardBrandPage
import org.uwuaosp.compose.wizard.WizardPageConfig
import org.uwuaosp.compose.wizard.WizardPageScaffold
import org.uwuaosp.compose.wizard.wizardActionContentPadding
import org.uwuaosp.compose.wizard.wizardPageTransition
import org.uwuaosp.compose.settingslib.SettingsToolbarActionButton
import org.uwuaosp.compose.settingslib.rememberSettingsTypography
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff

private enum class WallHubSetupPage {
    Welcome,
    Steam,
    Network,
    Finish,
}

@HiltViewModel
class WallHubSetupViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val steamSessionRepository: SteamSessionRepository,
    ) : ViewModel() {
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

        fun saveNetwork(
            proxyUrl: String,
            proxyEnabled: Boolean,
            steamAccessEnabled: Boolean,
            dataSource: SteamWorkshopDataSource,
            apiKey: String,
        ) {
            viewModelScope.launch {
                val current = preferences.value
                settingsRepository.setDownloadPreferences(
                    maxConcurrentDownloads = current.maxConcurrentDownloads,
                    chunkDownloadConcurrency = current.chunkDownloadConcurrency,
                    proxyUrl = proxyUrl,
                    mediaCacheLimitMb = current.mediaCacheLimitMb,
                )
                settingsRepository.setDownloadProxyEnabled(proxyEnabled)
                settingsRepository.setSteamAccessEnabled(steamAccessEnabled)
                settingsRepository.setSteamWorkshopDataSource(dataSource)
                settingsRepository.setSteamApiKey(apiKey)
            }
        }

        fun saveDohEndpoints(endpoints: List<String>, disabledEndpoints: Set<String>) {
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
    var page by rememberSaveable { mutableStateOf(WallHubSetupPage.Welcome) }
    val pageIndex = WallHubSetupPage.entries.indexOf(page)
    BackHandler(enabled = pageIndex > 0) {
        page = WallHubSetupPage.entries[pageIndex - 1]
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
                WallHubSetupPage.Steam -> SteamPage(session = session, viewModel = viewModel)
                WallHubSetupPage.Network ->
                    NetworkPage(
                        preferences = preferences,
                        onSaveDohEndpoints = viewModel::saveDohEndpoints,
                        onContinue = { proxyUrl, proxyEnabled, accessEnabled, source, apiKey ->
                            viewModel.saveNetwork(proxyUrl, proxyEnabled, accessEnabled, source, apiKey)
                            page = WallHubSetupPage.Finish
                        },
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
                WallHubSetupPage.Steam, WallHubSetupPage.Network -> stringResource(R.string.setup_action_next)
                WallHubSetupPage.Finish -> stringResource(R.string.setup_action_finish)
            }
        WizardActions(
            visible = page != WallHubSetupPage.Network,
            expanded = page == WallHubSetupPage.Welcome || page == WallHubSetupPage.Finish,
            primaryLabel = primaryLabel,
            onPrimaryClick = {
                when (page) {
                    WallHubSetupPage.Welcome -> page = WallHubSetupPage.Steam
                    WallHubSetupPage.Steam -> page = WallHubSetupPage.Network
                    WallHubSetupPage.Network -> Unit
                    WallHubSetupPage.Finish -> onComplete()
                }
            },
            secondaryLabel = stringResource(R.string.setup_action_skip),
            onSecondaryClick = { page = WallHubSetupPage.Network },
            showPrimary = showPrimary,
            showSecondary = showSkip,
        )
        if (page != WallHubSetupPage.Welcome) {
            SettingsToolbarActionButton(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.settings_action_back),
                onClick = { page = WallHubSetupPage.entries[pageIndex - 1] },
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
            TextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_steam_api_key)) },
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null,
                        )
                    }
                },
            )
        }
        WizardActions(
            visible = true,
            expanded = false,
            primaryLabel = stringResource(R.string.setup_action_next),
            onPrimaryClick = { onContinue(proxyUrl, proxyEnabled, accessEnabled, source, apiKey) },
        )
    }
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
