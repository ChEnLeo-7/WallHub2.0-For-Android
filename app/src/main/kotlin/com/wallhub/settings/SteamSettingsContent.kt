@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS
import com.wallhub.android.core.model.STEAM_ACCESS_DOH_ENDPOINT_LIMIT
import com.wallhub.android.core.model.SteamAccessPhase
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.normalizeSteamAccessDohEndpoint
import org.uwuaosp.compose.settingslib.PreferenceGroupSpacer
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow
import org.uwuaosp.compose.settingslib.CustomPreferenceRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff

@Composable
internal fun SteamSettingsContent(
    session: SteamSessionState,
    steamAccessEnabled: Boolean,
    steamAccessState: SteamAccessState,
    steamAccessDohEndpoints: List<String>,
    steamAccessDisabledDohEndpoints: Set<String>,
    steamWorkshopDataSource: SteamWorkshopDataSource,
    onSteamAccessEnabledChange: (Boolean) -> Unit,
    onSteamAccessDohEndpointsChange: (List<String>, Set<String>) -> Unit,
    onSteamWorkshopDataSourceChange: (SteamWorkshopDataSource) -> Unit,
    onRefreshSteamAccess: () -> Unit,
    savedApiKey: String,
    apiKey: String,
    onApiKeyChanged: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onOpenApiKeyPage: () -> Unit,
    onLoginSteam: (String, String) -> Unit,
    onSubmitSteamGuardCode: (String) -> Unit,
    onUseManualSteamGuardFallback: () -> Unit,
    onRestoreSteamSession: () -> Unit,
    onLogoutSteam: () -> Unit,
) {
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    val apiKeyChanged = apiKey != savedApiKey

    SettingsSection(
        title = stringResource(R.string.settings_steam_service_access_title),
    ) {
        SwitchPreferenceRow(
            title = stringResource(R.string.settings_steam_automatic_anti_blocking),
            summary = steamAccessState.summary(),
            checked = steamAccessEnabled,
            iconContent = { SettingsPreferenceIcon(Icons.Outlined.Language) },
            position = PreferencePosition.Single,
            onCheckedChange = onSteamAccessEnabledChange,
        )
        PreferenceGroupSpacer()
        SteamAccessDohEndpointsSetting(
            endpoints = steamAccessDohEndpoints,
            disabledEndpoints = steamAccessDisabledDohEndpoints,
            endpointStatuses = steamAccessState.dohEndpointStatuses,
            onSave = onSteamAccessDohEndpointsChange,
        )
        SettingsActionArea {
            FilledTonalButton(
                onClick = onRefreshSteamAccess,
                enabled = steamAccessEnabled && steamAccessState.phase != SteamAccessPhase.RESOLVING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                )
                Text(
                    text = stringResource(R.string.settings_action_check_routes_again),
                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                )
            }
        }
    }

    SettingsSectionSurface(
        modifier = Modifier.padding(vertical = WallHubSpacing.xs),
    ) {
        SteamAccountCard(
            session = session,
            onLogin = onLoginSteam,
            onSubmitCode = onSubmitSteamGuardCode,
            onUseManualCodeFallback = onUseManualSteamGuardFallback,
            onRestore = onRestoreSteamSession,
            onLogout = onLogoutSteam,
            containerColor = Color.Transparent,
        )
    }

    SettingsSection(
        title = stringResource(R.string.settings_workshop_data_source_title),
    ) {
        SettingChoiceRow(
            title = stringResource(R.string.settings_data_source),
            selectedValue = steamWorkshopDataSource,
            values = SteamWorkshopDataSource.entries,
            label = { source ->
                when (source) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> stringResource(R.string.settings_steam_community_html)
                    SteamWorkshopDataSource.WEB_API -> stringResource(R.string.settings_steam_web_api)
                    SteamWorkshopDataSource.CM_WEBSOCKET -> stringResource(R.string.settings_steam_cm_websocket)
                }
            },
            onSelected = onSteamWorkshopDataSourceChange,
            icon = Icons.Outlined.Language,
            position =
                if (steamWorkshopDataSource == SteamWorkshopDataSource.WEB_API) {
                    PreferencePosition.Top
                } else {
                    PreferencePosition.Single
                },
        )
        if (steamWorkshopDataSource == SteamWorkshopDataSource.WEB_API) {
            PreferenceGroupSpacer()
            CustomPreferenceRow(position = PreferencePosition.Bottom) {
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = WallHubSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.settings_steam_api_key),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SettingsFilledTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChanged,
                        placeholder = { Text(stringResource(R.string.settings_steam_api_key)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation =
                            if (apiKeyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector =
                                        if (apiKeyVisible) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                    contentDescription =
                                        if (apiKeyVisible) {
                                            stringResource(R.string.settings_action_hide_api_key)
                                        } else {
                                            stringResource(R.string.settings_action_show_api_key)
                                        },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = onOpenApiKeyPage,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                        )
                        Text(
                            text = stringResource(R.string.settings_action_get_steam_api_key),
                            modifier = Modifier.padding(start = WallHubSpacing.xs),
                        )
                    }
                    Button(
                        onClick = onSaveApiKey,
                        enabled = apiKeyChanged,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (apiKey.isBlank() && savedApiKey.isNotBlank()) {
                                stringResource(R.string.settings_action_clear_steam_api_key)
                            } else {
                                stringResource(R.string.settings_action_save_steam_api_key)
                            },
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_workshop_data_source_account_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SteamAccessDohEndpointsSetting(
    endpoints: List<String>,
    disabledEndpoints: Set<String>,
    endpointStatuses: List<com.wallhub.android.core.model.SteamDohEndpointStatus> = emptyList(),
    onSave: (List<String>, Set<String>) -> Unit,
) {
    val invalidEndpointError = stringResource(R.string.settings_error_invalid_doh_url)
    val duplicateEndpointError = stringResource(R.string.settings_error_duplicate_doh_url)
    val endpointLimitError =
        stringResource(R.string.settings_error_doh_url_limit, STEAM_ACCESS_DOH_ENDPOINT_LIMIT)
    val focusManager = LocalFocusManager.current
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardVisible by rememberSaveable { mutableStateOf(false) }
    var draftEndpoints by rememberSaveable { mutableStateOf(endpoints) }
    var draftDisabledEndpoints by rememberSaveable { mutableStateOf(disabledEndpoints) }
    var endpointText by rememberSaveable { mutableStateOf("") }
    var endpointError by rememberSaveable { mutableStateOf<String?>(null) }
    val enabledCount = endpoints.count { endpoint -> endpoint !in disabledEndpoints }
    val draftChanged = draftEndpoints != endpoints || draftDisabledEndpoints != disabledEndpoints
    val hasUnsavedWork = draftChanged || endpointText.isNotBlank()

    fun openEditor() {
        draftEndpoints = endpoints
        draftDisabledEndpoints = disabledEndpoints
        endpointText = ""
        endpointError = null
        confirmDiscardVisible = false
        editorVisible = true
    }

    fun requestClose() {
        focusManager.clearFocus()
        if (hasUnsavedWork) {
            confirmDiscardVisible = true
        } else {
            editorVisible = false
        }
    }

    fun addEndpoint() {
        val normalized = normalizeSteamAccessDohEndpoint(endpointText)
        endpointError =
            when {
                normalized == null ->
                    invalidEndpointError

                normalized in draftEndpoints ->
                    duplicateEndpointError

                draftEndpoints.size >= STEAM_ACCESS_DOH_ENDPOINT_LIMIT ->
                    endpointLimitError

                else -> null
            }
        if (endpointError == null && normalized != null) {
            draftEndpoints = draftEndpoints + normalized
            draftDisabledEndpoints = draftDisabledEndpoints - normalized
            endpointText = ""
            focusManager.clearFocus()
        }
    }

    PreferenceRow(
        title = stringResource(R.string.settings_doh_routes),
        summary = stringResource(R.string.settings_doh_enabled_summary, enabledCount, endpoints.size),
        position = PreferencePosition.Single,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.Tune) },
        onClick = ::openEditor,
    )

    if (editorVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = ::requestClose,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = WallHubSpacing.none,
            sheetMaxWidth = WallHubSizeTokens.modalContentMaxWidth,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .widthIn(max = 760.dp)
                            .heightIn(max = maxHeight * 0.92f)
                            .imePadding()
                            .navigationBarsPadding(),
                ) {
                    SteamAccessDohEditor(
                        modifier = Modifier.fillMaxSize(),
                        endpoints = draftEndpoints,
                        disabledEndpoints = draftDisabledEndpoints,
                        endpointStatuses = endpointStatuses,
                        endpointText = endpointText,
                        endpointError = endpointError,
                        hasChanges = draftChanged,
                        onEndpointTextChange = { value ->
                            endpointText = value
                            endpointError = null
                        },
                        onAddEndpoint = ::addEndpoint,
                        onReorder = { reordered -> draftEndpoints = reordered },
                        onEnabledChange = { endpoint, enabled ->
                            draftDisabledEndpoints =
                                if (enabled) {
                                    draftDisabledEndpoints - endpoint
                                } else {
                                    draftDisabledEndpoints + endpoint
                                }
                        },
                        onDelete = { endpoint ->
                            draftEndpoints = draftEndpoints - endpoint
                            draftDisabledEndpoints = draftDisabledEndpoints - endpoint
                        },
                        onRestoreDefaults = {
                            draftEndpoints = DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS
                            draftDisabledEndpoints = emptySet()
                            endpointText = ""
                            endpointError = null
                            focusManager.clearFocus()
                        },
                        onCancel = ::requestClose,
                        onSave = {
                            focusManager.clearFocus()
                            onSave(draftEndpoints, draftDisabledEndpoints)
                            editorVisible = false
                        },
                    )
                }
            }
        }
    }

    if (confirmDiscardVisible) {
        AlertDialog(
            onDismissRequest = { confirmDiscardVisible = false },
            title = { Text(stringResource(R.string.settings_discard_changes_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_discard_doh_changes_description),
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscardVisible = false }) {
                    Text(stringResource(R.string.settings_action_keep_editing))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscardVisible = false
                        editorVisible = false
                        endpointText = ""
                        endpointError = null
                    },
                ) {
                    Text(stringResource(R.string.settings_action_discard))
                }
            },
        )
    }
}

@Composable
internal fun SteamAccessDohEditor(
    endpoints: List<String>,
    disabledEndpoints: Set<String>,
    endpointStatuses: List<com.wallhub.android.core.model.SteamDohEndpointStatus>,
    endpointText: String,
    endpointError: String?,
    hasChanges: Boolean,
    onEndpointTextChange: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addDialogVisible by rememberSaveable { mutableStateOf(false) }
    val statusByEndpoint = endpointStatuses.associateBy { status -> status.endpoint }
    val selectedCount = endpointStatuses.count { status -> status.selected }
    val enabledCount = endpoints.count { endpoint -> endpoint !in disabledEndpoints }
    val regions =
        listOf(
            com.wallhub.android.core.model.SteamDohRegion.DOMESTIC,
            com.wallhub.android.core.model.SteamDohRegion.FOREIGN,
            com.wallhub.android.core.model.SteamDohRegion.CUSTOM,
        )

    fun moveWithinRegion(endpoint: String, delta: Int) {
        val region = com.wallhub.android.core.model.SteamDohCatalog.endpointFor(endpoint).region
        val peers = endpoints.filter { candidate ->
            com.wallhub.android.core.model.SteamDohCatalog.endpointFor(candidate).region == region
        }
        val currentPeerIndex = peers.indexOf(endpoint)
        val targetPeerIndex = currentPeerIndex + delta
        if (currentPeerIndex < 0 || targetPeerIndex !in peers.indices) return
        val target = peers[targetPeerIndex]
        val currentIndex = endpoints.indexOf(endpoint)
        val targetIndex = endpoints.indexOf(target)
        onReorder(
            endpoints.toMutableList().apply {
                this[currentIndex] = target
                this[targetIndex] = endpoint
            },
        )
    }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            item(key = "doh-header") {
                Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_doh_routes),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = stringResource(R.string.settings_doh_enabled_count, enabledCount, endpoints.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hasChanges) {
                            Text(
                                text = stringResource(R.string.settings_doh_unsaved),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_doh_auto_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "doh-auto-selection") {
                WallHubSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.padding(WallHubSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_doh_auto_selection),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text =
                                    if (endpointStatuses.isEmpty()) {
                                        stringResource(R.string.settings_doh_waiting_for_benchmark)
                                    } else {
                                        stringResource(R.string.settings_doh_selected_count, selectedCount)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            regions.forEach { region ->
                val groupEndpoints = endpoints.filter { endpoint ->
                    com.wallhub.android.core.model.SteamDohCatalog.endpointFor(endpoint).region == region
                }
                if (groupEndpoints.isNotEmpty() || region == com.wallhub.android.core.model.SteamDohRegion.CUSTOM) {
                    item(key = "header-$region") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text =
                                    when (region) {
                                        com.wallhub.android.core.model.SteamDohRegion.DOMESTIC -> stringResource(R.string.settings_doh_group_domestic)
                                        com.wallhub.android.core.model.SteamDohRegion.FOREIGN -> stringResource(R.string.settings_doh_group_foreign)
                                        com.wallhub.android.core.model.SteamDohRegion.CUSTOM -> stringResource(R.string.settings_doh_group_custom)
                                    },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = groupEndpoints.size.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (region == com.wallhub.android.core.model.SteamDohRegion.CUSTOM) {
                                IconButton(onClick = { addDialogVisible = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = stringResource(R.string.settings_add_url),
                                    )
                                }
                            }
                        }
                    }
                    if (groupEndpoints.isNotEmpty()) {
                        item(key = "group-$region") {
                            WallHubSurfaceCard(
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Column {
                                    groupEndpoints.forEachIndexed { index, endpoint ->
                                        val catalogEndpoint = com.wallhub.android.core.model.SteamDohCatalog.endpointFor(endpoint)
                                        val status = statusByEndpoint[endpoint]
                                        SteamAccessDohEndpointItem(
                                            endpoint = endpoint,
                                            displayName = catalogEndpoint.provider,
                                            enabled = endpoint !in disabledEndpoints,
                                            selected = status?.selected == true,
                                            measured = status != null,
                                            available = status?.available == true,
                                            latencyMs = status?.latencyMs,
                                            canDelete = catalogEndpoint.region == com.wallhub.android.core.model.SteamDohRegion.CUSTOM,
                                            canMoveUp = index > 0,
                                            canMoveDown = index < groupEndpoints.lastIndex,
                                            onEnabledChange = { enabled -> onEnabledChange(endpoint, enabled) },
                                            onDelete = { onDelete(endpoint) },
                                            onMoveUp = { moveWithinRegion(endpoint, -1) },
                                            onMoveDown = { moveWithinRegion(endpoint, 1) },
                                        )
                                        if (index < groupEndpoints.lastIndex) {
                                            androidx.compose.material3.HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = WallHubSpacing.none,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onRestoreDefaults,
                    enabled = endpoints != DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS || disabledEndpoints.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.settings_action_reset))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = hasChanges,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_action_save_changes))
                }
            }
        }
    }

    if (addDialogVisible) {
        AlertDialog(
            onDismissRequest = { addDialogVisible = false },
            title = { Text(stringResource(R.string.settings_add_url)) },
            text = {
                SettingsFilledTextField(
                    value = endpointText,
                    onValueChange = onEndpointTextChange,
                    label = { Text(stringResource(R.string.settings_https_doh_url)) },
                    placeholder = { Text("https://dns.example/dns-query") },
                    supportingText = {
                        Text(endpointError ?: stringResource(R.string.settings_new_doh_url_description))
                    },
                    isError = endpointError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (endpointText.isNotBlank()) onAddEndpoint() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { addDialogVisible = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val canAdd =
                            normalizeSteamAccessDohEndpoint(endpointText)?.let { normalized ->
                                normalized !in endpoints && endpoints.size < STEAM_ACCESS_DOH_ENDPOINT_LIMIT
                            } == true
                        onAddEndpoint()
                        if (canAdd) addDialogVisible = false
                    },
                    enabled = endpointText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.settings_action_add))
                }
            },
        )
    }
}

@Composable
internal fun SteamAccessDohEndpointItem(
    endpoint: String,
    displayName: String,
    enabled: Boolean,
    selected: Boolean,
    measured: Boolean,
    available: Boolean,
    latencyMs: Long?,
    canDelete: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = WallHubSizeTokens.listItemMinimumHeight),
    ) {
        androidx.compose.material3.ListItem(
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_doh_selected),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = WallHubSpacing.xs, vertical = WallHubSpacing.xxxs),
                            )
                        }
                    }
                }
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxxs)) {
                    Text(
                        text = endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            when {
                                latencyMs != null && available -> stringResource(R.string.settings_doh_latency, latencyMs)
                                !enabled -> stringResource(R.string.settings_disabled)
                                measured && !available -> stringResource(R.string.settings_doh_unavailable)
                                else -> stringResource(R.string.settings_doh_not_measured)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            when {
                                selected -> MaterialTheme.colorScheme.primary
                                latencyMs != null && available -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = WallHubSpacing.md, end = WallHubSpacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.settings_doh_move_up, displayName))
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.settings_doh_move_down, displayName))
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.settings_action_delete_doh_url, endpoint))
                }
            }
        }
    }
}

@Composable
internal fun SettingsActionArea(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(start = WallHubSpacing.md, end = WallHubSpacing.md, bottom = WallHubSpacing.md),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        content = content,
    )
}

@Composable
internal fun SettingsStatusMessage(
    message: String,
    isFailure: Boolean,
) {
    val containerColor =
        if (isFailure) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        if (isFailure) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(WallHubSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun SettingsNotice(
    title: String,
    message: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(WallHubSpacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
