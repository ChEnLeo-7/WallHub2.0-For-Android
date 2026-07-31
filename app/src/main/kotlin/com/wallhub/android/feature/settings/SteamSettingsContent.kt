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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
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
    language: AppLanguage,
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
        title = language.text("Steam 账户", "Steam account"),
        supportingText =
            language.text(
                "管理资料库使用的登录会话",
                "Manage the sign-in session used by Library",
            ),
        icon = Icons.Outlined.PersonOutline,
    ) {
        SettingsListItem(
            headlineContent = {
                Text(
                    if (session.phase == SteamSessionPhase.SIGNED_IN) {
                        session.accountName.orEmpty().ifBlank { "Steam" }
                    } else {
                        language.text("登录状态", "Sign-in status")
                    },
                )
            },
            supportingContent = {
                Text(session.settingsSummary(language))
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
                        text = language.text("退出 Steam", "Sign out of Steam"),
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
                                    language.text(
                                        "恢复 Steam 登录",
                                        "Restore Steam sign-in",
                                    )

                                SteamSessionPhase.SIGNING_IN,
                                SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                                SteamSessionPhase.WAITING_FOR_CODE,
                                -> language.text("查看登录进度", "View sign-in progress")

                                else -> language.text("登录 Steam", "Sign in to Steam")
                            },
                        modifier = Modifier.padding(start = WallHubSpacing.xs),
                    )
                }
            }
        }
    }

    SettingsSection(
        title = language.text("Steam 服务访问", "Steam service access"),
        supportingText =
            language.text(
                "仅在社区与 API 直连异常时启用内置无 SNI 线路",
                "Uses the built-in no-SNI route only when Community or API direct access fails",
            ),
        icon = Icons.Outlined.Language,
    ) {
        SettingsSwitchRow(
            title = language.text("自动防阻断", "Automatic anti-blocking"),
            supportingText = steamAccessState.summary(language),
            checked = steamAccessEnabled,
            onCheckedChange = onSteamAccessEnabledChange,
        )
        SettingsItemDivider()
        SteamAccessDohEndpointsSetting(
            endpoints = steamAccessDohEndpoints,
            disabledEndpoints = steamAccessDisabledDohEndpoints,
            language = language,
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
                    text = language.text("重新检测线路", "Check routes again"),
                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                )
            }
        }
    }

    SettingsSection(
        title = language.text("创意工坊数据源", "Workshop data source"),
        supportingText =
            language.text(
                "为发现、详情与评论选择严格的数据通道",
                "Choose the strict data channel used by discovery, details, and comments",
            ),
        icon = Icons.Outlined.Language,
    ) {
        SettingChoiceRow(
            title = language.text("数据获取源", "Data source"),
            selectedValue = steamWorkshopDataSource,
            values = SteamWorkshopDataSource.entries,
            label = { source ->
                when (source) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> "Steam Community HTML"
                    SteamWorkshopDataSource.WEB_API -> "Steam Web API"
                    SteamWorkshopDataSource.CM_WEBSOCKET -> "Steam CM WebSocket"
                }
            },
            supportingText =
                when (steamWorkshopDataSource) {
                    SteamWorkshopDataSource.COMMUNITY_HTML ->
                        language.text(
                            "使用 Steam Community 页面获取公开数据",
                            "Use Steam Community pages for public data",
                        )

                    SteamWorkshopDataSource.WEB_API ->
                        language.text(
                            "发现页需要有效的 Steam API Key",
                            "Discovery requires a valid Steam API key",
                        )

                    SteamWorkshopDataSource.CM_WEBSOCKET ->
                        language.text(
                            "公开发现与详情支持匿名 CM；评论需要登录",
                            "Public discovery and details support anonymous CM; comments require sign-in",
                        )
                },
            onSelected = onSteamWorkshopDataSourceChange,
        )
    }

    SettingsSection(
        title = "Steam Web API",
        supportingText =
            language.text(
                "供 Web API 数据源与匿名昵称补全使用",
                "Used by the Web API source and anonymous profile enrichment",
            ),
        icon = Icons.Outlined.Tune,
    ) {
        Column(
            modifier = Modifier.padding(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            SettingsFilledTextField(
                value = apiKey,
                onValueChange = onApiKeyChanged,
                label = { Text("Steam API Key") },
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
                                    language.text("隐藏 API Key", "Hide API key")
                                } else {
                                    language.text("显示 API Key", "Show API key")
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
                    text = language.text("获取 Steam API Key", "Get Steam API Key"),
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
                        language.text("清除 Steam API Key", "Clear Steam API Key")
                    } else {
                        language.text("保存 Steam API Key", "Save Steam API Key")
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
    language: AppLanguage,
    onSave: (List<String>, Set<String>) -> Unit,
) {
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
                    language.text(
                        "请输入有效的 HTTPS DoH 地址",
                        "Enter a valid HTTPS DoH URL",
                    )

                normalized in draftEndpoints ->
                    language.text(
                        "此 DoH 地址已在列表中",
                        "This DoH URL is already in the list",
                    )

                draftEndpoints.size >= STEAM_ACCESS_DOH_ENDPOINT_LIMIT ->
                    language.text(
                        "最多可配置 $STEAM_ACCESS_DOH_ENDPOINT_LIMIT 个 DoH 地址",
                        "Up to $STEAM_ACCESS_DOH_ENDPOINT_LIMIT DoH URLs are supported",
                    )

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
            Text(language.text("DoH 地址", "DoH URLs"))
        },
        supportingContent = {
            Text(
                language.text(
                    "已启用 $enabledCount/${endpoints.size}，拖动可调整优先级",
                    "$enabledCount/${endpoints.size} enabled; drag to change priority",
                ),
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
                        language = language,
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
            title = { Text(language.text("放弃更改？", "Discard changes?")) },
            text = {
                Text(
                    language.text(
                        "尚未保存的 DoH 地址、启用状态和优先级调整将丢失。",
                        "Unsaved DoH URLs, enabled states, and priority changes will be lost.",
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscardVisible = false }) {
                    Text(language.text("继续编辑", "Keep editing"))
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
                    Text(language.text("放弃", "Discard"))
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
    language: AppLanguage,
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
                    text = language.text("自定义 DoH", "Custom DoH"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = language.text("已启用 $enabledCount/${endpoints.size}", "$enabledCount/${endpoints.size} enabled"),
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
                    language.text(
                        "长按手柄并拖动调整优先级，顶部地址优先使用。",
                        "Long-press a handle and drag to change priority; top URLs are preferred.",
                    ),
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
                    language = language,
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
                            text = language.text("添加地址", "Add URL"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SettingsFilledTextField(
                            value = endpointText,
                            onValueChange = onEndpointTextChange,
                            label = { Text(language.text("HTTPS DoH 地址", "HTTPS DoH URL")) },
                            placeholder = { Text("https://dns.example/dns-query") },
                            supportingText = {
                                Text(
                                    endpointError ?: language.text(
                                        "新地址默认开启，并添加到列表末尾",
                                        "New URLs are enabled and added at the end",
                                    ),
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
                                text = language.text("添加", "Add"),
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
                    Text(language.text("恢复默认", "Reset"))
                }
                Button(
                    onClick = onCancel,
                    shape = MaterialTheme.shapes.large,
                    colors = secondaryButtonColors,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(language.text("取消", "Cancel"))
                }
            }
            Button(
                onClick = onSave,
                enabled = hasChanges,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(language.text("保存更改", "Save changes"))
            }
        }
    }
}

@Composable
internal fun SteamAccessDohEndpointItem(
    endpoint: String,
    enabled: Boolean,
    canDelete: Boolean,
    language: AppLanguage,
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
                        language.text(
                            "拖动 $endpoint 调整优先级",
                            "Drag $endpoint to change priority",
                        ),
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
                            language.text("已开启", "Enabled")
                        } else {
                            language.text("已关闭", "Disabled")
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
                    contentDescription = language.text("删除 $endpoint", "Delete $endpoint"),
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
    fun text(
        zh: String,
        en: String,
    ): String = if (preferences.language == AppLanguage.EN) en else zh

    SettingsNotice(
        title = text("实验功能可能改变网络与播放行为", "Experimental features may change networking and playback"),
        message =
            text(
                "遇到稳定性问题时，可关闭相关开关恢复默认流程。",
                "Turn off the related option to return to the default flow if stability issues occur.",
            ),
    )

    SettingsSection(
        title = text("在线播放", "Online playback"),
        supportingText =
            text(
                "控制 Steam 分块播放及本地缓存",
                "Control Steam chunk streaming and local cache",
            ),
        icon = Icons.Outlined.PlayArrow,
    ) {
        SettingsSwitchRow(
            title = text("SteamKit 在线分块播放", "SteamKit chunk streaming"),
            supportingText =
                text(
                    "开启后直接从 Steam 分块播放；关闭后先下载再播放",
                    "Stream directly from Steam when on; download before playback when off",
                ),
            checked = preferences.onlineChunkPlaybackEnabled,
            onCheckedChange = onOnlineChunkPlaybackEnabledChange,
        )
        SettingsItemDivider()
        SteamStreamCacheSetting(
            cacheLimitMb = preferences.mediaCacheLimitMb,
            language = preferences.language,
            onCacheLimitChange = onOnlineStreamCacheLimitChange,
        )
    }

    SettingsSection(
        title = text("系统权限", "System permissions"),
        supportingText =
            text(
                "管理后台任务需要的 Android 权限",
                "Manage Android permissions used by background work",
            ),
        icon = Icons.Outlined.Notifications,
    ) {
        SettingsListItem(
            headlineContent = { Text(text("后台任务通知", "Background task notifications")) },
            supportingContent = {
                Text(
                    text(
                        "用于显示下载、转换和导出的实时进度",
                        "Shows live progress for downloads, conversion, and export",
                    ),
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
                    text = text("允许后台通知", "Allow background notifications"),
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
