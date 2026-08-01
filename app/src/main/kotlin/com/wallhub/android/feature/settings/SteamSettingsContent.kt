@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS
import com.wallhub.android.core.model.STEAM_ACCESS_DOH_ENDPOINT_LIMIT
import com.wallhub.android.core.model.SteamAccessPhase
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.normalizeSteamAccessDohEndpoint
import kotlinx.coroutines.launch
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

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
    onOpenSteamLogin: () -> Unit,
    onLogoutSteam: () -> Unit,
) {
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    val apiKeyChanged = apiKey != savedApiKey

    SettingsSection(
        title = stringResource(R.string.settings_steam_account_title),
        supportingText = stringResource(R.string.settings_steam_account_description),
        icon = Icons.Outlined.PersonOutline,
    ) {
        SettingsListItem(
            headlineContent = {
                Text(
                    if (session.phase == SteamSessionPhase.SIGNED_IN) {
                        session.accountName.orEmpty().ifBlank { stringResource(R.string.settings_steam_name) }
                    } else {
                        stringResource(R.string.settings_steam_sign_in_status)
                    },
                )
            },
            supportingContent = {
                Text(session.settingsSummary())
            },
        )
        SettingsActionArea {
            if (session.phase == SteamSessionPhase.SIGNED_IN) {
                OutlinedButton(
                    onClick = onLogoutSteam,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.settings_action_sign_out_steam),
                        modifier = Modifier.padding(start = WallHubSpacing.xs),
                    )
                }
            } else {
                Button(
                    onClick = onOpenSteamLogin,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonOutline,
                        contentDescription = null,
                    )
                    Text(
                        text =
                            when (session.phase) {
                                SteamSessionPhase.RESTORABLE ->
                                    stringResource(R.string.settings_action_restore_steam_sign_in)

                                SteamSessionPhase.SIGNING_IN,
                                SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                                SteamSessionPhase.WAITING_FOR_CODE,
                                -> stringResource(R.string.settings_action_view_sign_in_progress)

                                else -> stringResource(R.string.settings_action_sign_in_steam)
                            },
                        modifier = Modifier.padding(start = WallHubSpacing.xs),
                    )
                }
            }
        }
    }

    SettingsSection(
        title = stringResource(R.string.settings_steam_service_access_title),
        supportingText = stringResource(R.string.settings_steam_service_access_description),
        icon = Icons.Outlined.Language,
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_steam_automatic_anti_blocking),
            supportingText = steamAccessState.summary(),
            checked = steamAccessEnabled,
            onCheckedChange = onSteamAccessEnabledChange,
        )
        SettingsItemDivider()
        SteamAccessDohEndpointsSetting(
            endpoints = steamAccessDohEndpoints,
            disabledEndpoints = steamAccessDisabledDohEndpoints,
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

    SettingsSection(
        title = stringResource(R.string.settings_workshop_data_source_title),
        supportingText = stringResource(R.string.settings_workshop_data_source_description),
        icon = Icons.Outlined.Language,
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
            supportingText =
                when (steamWorkshopDataSource) {
                    SteamWorkshopDataSource.COMMUNITY_HTML ->
                        stringResource(R.string.settings_data_source_community_description)

                    SteamWorkshopDataSource.WEB_API ->
                        stringResource(R.string.settings_data_source_web_api_description)

                    SteamWorkshopDataSource.CM_WEBSOCKET ->
                        stringResource(R.string.settings_data_source_cm_description)
                },
            onSelected = onSteamWorkshopDataSourceChange,
        )
    }

    SettingsSection(
        title = stringResource(R.string.settings_steam_web_api),
        supportingText =
            stringResource(R.string.settings_steam_web_api_description),
        icon = Icons.Outlined.Tune,
    ) {
        Column(
            modifier = Modifier.padding(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            SettingsFilledTextField(
                value = apiKey,
                onValueChange = onApiKeyChanged,
                label = { Text(stringResource(R.string.settings_steam_api_key)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
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
                    imageVector = Icons.Outlined.OpenInNew,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SteamAccessDohEndpointsSetting(
    endpoints: List<String>,
    disabledEndpoints: Set<String>,
    onSave: (List<String>, Set<String>) -> Unit,
) {
    val context = LocalContext.current
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
                    context.getString(R.string.settings_error_invalid_doh_url)

                normalized in draftEndpoints ->
                    context.getString(R.string.settings_error_duplicate_doh_url)

                draftEndpoints.size >= STEAM_ACCESS_DOH_ENDPOINT_LIMIT ->
                    context.getString(R.string.settings_error_doh_url_limit, STEAM_ACCESS_DOH_ENDPOINT_LIMIT)

                else -> null
            }
        if (endpointError == null && normalized != null) {
            draftEndpoints = draftEndpoints + normalized
            draftDisabledEndpoints = draftDisabledEndpoints - normalized
            endpointText = ""
            focusManager.clearFocus()
        }
    }

    SettingsListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = ::openEditor),
        headlineContent = {
            Text(stringResource(R.string.settings_doh_urls))
        },
        supportingContent = {
            Text(
                stringResource(R.string.settings_doh_enabled_summary, enabledCount, endpoints.size),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
            )
        },
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
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var draggedEndpoint by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dragOrder by remember { mutableStateOf(endpoints) }
    val itemExtentPx = with(density) { (STEAM_DOH_ITEM_HEIGHT + STEAM_DOH_ITEM_SPACING).toPx() }
    val enabledCount = endpoints.count { endpoint -> endpoint !in disabledEndpoints }
    val secondaryButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    LaunchedEffect(endpoints, draggedEndpoint) {
        if (draggedEndpoint == null) dragOrder = endpoints
    }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.padding(start = WallHubSpacing.md, end = WallHubSpacing.sm, bottom = WallHubSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_custom_doh),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.settings_doh_enabled_count, enabledCount, endpoints.size),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (hasChanges) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Text(
                text =
                    stringResource(R.string.settings_doh_reorder_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(STEAM_DOH_ITEM_SPACING),
        ) {
            itemsIndexed(
                items = endpoints,
                key = { _, endpoint -> endpoint },
            ) { _, endpoint ->
                val isDragging = draggedEndpoint == endpoint
                val itemModifier =
                    Modifier
                        .then(if (isDragging) Modifier else Modifier.animateItem())
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffsetPx
                                scaleX = 1.02f
                                scaleY = 1.02f
                                shadowElevation = WallHubSpacing.xs.toPx()
                            }
                        }
                val dragHandleModifier =
                    Modifier.pointerInput(endpoint) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedEndpoint = endpoint
                                dragOrder = endpoints
                                dragOffsetPx = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragCancel = {
                                draggedEndpoint = null
                                dragOffsetPx = 0f
                            },
                            onDragEnd = {
                                draggedEndpoint = null
                                dragOffsetPx = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetPx += dragAmount.y
                                var currentIndex = dragOrder.indexOf(endpoint)
                                while (dragOffsetPx > itemExtentPx / 2f && currentIndex < dragOrder.lastIndex) {
                                    val nextIndex = currentIndex + 1
                                    dragOrder =
                                        dragOrder.toMutableList().apply {
                                            this[currentIndex] = this[nextIndex]
                                            this[nextIndex] = endpoint
                                        }
                                    dragOffsetPx -= itemExtentPx
                                    currentIndex = nextIndex
                                    onReorder(dragOrder)
                                }
                                while (dragOffsetPx < -itemExtentPx / 2f && currentIndex > 0) {
                                    val previousIndex = currentIndex - 1
                                    dragOrder =
                                        dragOrder.toMutableList().apply {
                                            this[currentIndex] = this[previousIndex]
                                            this[previousIndex] = endpoint
                                        }
                                    dragOffsetPx += itemExtentPx
                                    currentIndex = previousIndex
                                    onReorder(dragOrder)
                                }
                                val itemInfo =
                                    listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { item -> item.key == endpoint }
                                if (itemInfo != null) {
                                    val translatedTop = itemInfo.offset + dragOffsetPx
                                    val translatedBottom = translatedTop + itemInfo.size
                                    val viewportStart = listState.layoutInfo.viewportStartOffset + 48f
                                    val viewportEnd = listState.layoutInfo.viewportEndOffset - 48f
                                    val scrollDelta =
                                        when {
                                            translatedTop < viewportStart -> -18f
                                            translatedBottom > viewportEnd -> 18f
                                            else -> 0f
                                        }
                                    if (scrollDelta != 0f) {
                                        coroutineScope.launch { listState.scrollBy(scrollDelta) }
                                    }
                                }
                            },
                        )
                    }
                SteamAccessDohEndpointItem(
                    endpoint = endpoint,
                    enabled = endpoint !in disabledEndpoints,
                    canDelete = endpoints.size > 1,
                    onEnabledChange = { enabled -> onEnabledChange(endpoint, enabled) },
                    onDelete = { onDelete(endpoint) },
                    dragHandleModifier = dragHandleModifier,
                    modifier = itemModifier,
                )
            }
            item(key = "add-endpoint") {
                WallHubSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(WallHubSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_add_url),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SettingsFilledTextField(
                            value = endpointText,
                            onValueChange = onEndpointTextChange,
                            label = { Text(stringResource(R.string.settings_https_doh_url)) },
                            placeholder = { Text("https://dns.example/dns-query") },
                            supportingText = {
                                Text(
                                    endpointError ?: stringResource(R.string.settings_new_doh_url_description),
                                )
                            },
                            isError = endpointError != null,
                            singleLine = true,
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onDone = { if (endpointText.isNotBlank()) onAddEndpoint() },
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = onAddEndpoint,
                            enabled = endpointText.isNotBlank(),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                            Text(
                                text = stringResource(R.string.settings_action_add),
                                modifier = Modifier.padding(start = WallHubSpacing.xs),
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
            ) {
                Button(
                    onClick = onRestoreDefaults,
                    enabled = endpoints != DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS || disabledEndpoints.isNotEmpty(),
                    shape = MaterialTheme.shapes.large,
                    colors = secondaryButtonColors,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_action_reset))
                }
                Button(
                    onClick = onCancel,
                    shape = MaterialTheme.shapes.large,
                    colors = secondaryButtonColors,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            }
            Button(
                onClick = onSave,
                enabled = hasChanges,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_action_save_changes))
            }
        }
    }
}

@Composable
internal fun SteamAccessDohEndpointItem(
    endpoint: String,
    enabled: Boolean,
    canDelete: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    WallHubSurfaceCard(
        modifier =
            modifier
                .fillMaxWidth()
                .height(STEAM_DOH_ITEM_HEIGHT),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = WallHubSpacing.xxs, end = WallHubSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(WallHubSpacing.xxl)
                        .then(dragHandleModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription =
                        stringResource(R.string.settings_action_drag_doh_url, endpoint),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxxs),
            ) {
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (enabled) {
                            stringResource(R.string.settings_enabled)
                        } else {
                            stringResource(R.string.settings_disabled)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
            IconButton(
                onClick = onDelete,
                enabled = canDelete,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.settings_action_delete_doh_url, endpoint),
                )
            }
        }
    }
}

@Composable
internal fun ExperimentalSettingsContent(
    preferences: AppPreferences,
    onOnlineChunkPlaybackEnabledChange: (Boolean) -> Unit,
    onOnlineStreamCacheLimitChange: (Int) -> Unit,
    onRequestNotifications: () -> Unit,
) {
    SettingsNotice(
        title = stringResource(R.string.settings_experimental_notice_title),
        message = stringResource(R.string.settings_experimental_notice_description),
    )

    SettingsSection(
        title = stringResource(R.string.settings_online_playback_title),
        supportingText = stringResource(R.string.settings_online_playback_description),
        icon = Icons.Outlined.PlayArrow,
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_steamkit_chunk_streaming),
            supportingText = stringResource(R.string.settings_steamkit_chunk_streaming_description),
            checked = preferences.onlineChunkPlaybackEnabled,
            onCheckedChange = onOnlineChunkPlaybackEnabledChange,
        )
        SettingsItemDivider()
        SteamStreamCacheSetting(
            cacheLimitMb = preferences.mediaCacheLimitMb,
            onCacheLimitChange = onOnlineStreamCacheLimitChange,
        )
    }

    SettingsSection(
        title = stringResource(R.string.settings_system_permissions_title),
        supportingText = stringResource(R.string.settings_system_permissions_description),
        icon = Icons.Outlined.Notifications,
    ) {
        SettingsListItem(
            headlineContent = { Text(stringResource(R.string.settings_background_task_notifications)) },
            supportingContent = {
                Text(
                    stringResource(R.string.settings_background_task_notifications_description),
                )
            },
        )
        SettingsActionArea {
            FilledTonalButton(
                onClick = onRequestNotifications,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                )
                Text(
                    text = stringResource(R.string.settings_action_allow_background_notifications),
                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                )
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
