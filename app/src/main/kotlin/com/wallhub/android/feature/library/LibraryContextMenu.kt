@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubContextMenuDefaults
import com.wallhub.android.core.designsystem.WallHubContextMenuLayer
import com.wallhub.android.core.designsystem.WallHubContextMenuState
import com.wallhub.android.core.designsystem.WallHubContextMenuSurface
import com.wallhub.android.core.designsystem.WallHubContextMenuTarget
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.designsystem.WallHubContextMenuAction as LibraryContextMenuAction
import com.wallhub.android.core.designsystem.WallHubContextMenuMetadataItem as LibraryContextMenuMetadataItem
import com.wallhub.android.core.designsystem.WallHubContextMenuPositionProvider as LibraryContextMenuPositionProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow

internal typealias LibraryContextMenuCoordinator = WallHubContextMenuState
internal typealias LibraryContextMenuTarget = WallHubContextMenuTarget

@Composable
internal fun rememberLibraryContextMenuCoordinator(): LibraryContextMenuCoordinator =
    com.wallhub.android.core.designsystem.rememberWallHubContextMenuState()

@Composable
internal fun LibraryContextMenuLayer(
    coordinator: LibraryContextMenuCoordinator,
    onActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    WallHubContextMenuLayer(
        state = coordinator,
        onActiveChanged = onActiveChanged,
        modifier = modifier,
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryContextMenuCard(
    item: WorkshopSummary,
    coordinator: LibraryContextMenuCoordinator,
    onOpen: () -> Unit,
    onSearchAuthor: () -> Unit,
    authorDisplayName: String?,
    onAuthorDisplayNameRequested: () -> Unit,
    onDownload: () -> Unit,
    removeActionLabel: String?,
    onRemoveFromCollection: () -> Unit,
    onPlayVideo: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (onShowActions: () -> Unit) -> Unit,
) {
    val previewLayer = rememberGraphicsLayer()
    val position = remember { LibraryCardPositionHolder() }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var menuMounted by remember { mutableStateOf(false) }
    var menuEntranceRequest by remember { mutableStateOf(0) }
    var menuVisible by remember { mutableStateOf(false) }
    var target by remember(item.id) { mutableStateOf<LibraryContextMenuTarget?>(null) }
    var touchPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val shape = MaterialTheme.shapes.medium
    val viewDetailsLabel = stringResource(R.string.library_view_details)
    val openActionsMenuLabel = stringResource(R.string.library_open_actions_menu)
    val wallpaperTitleCopiedMessage = stringResource(R.string.library_wallpaper_title_copied)
    val projectIdCopiedMessage = stringResource(R.string.library_project_id_copied)
    val positionProvider =
        remember(touchPositionInWindow, density) {
            LibraryContextMenuPositionProvider(
                touchPosition = touchPositionInWindow,
                touchOffsetPx = with(density) { WallHubContextMenuDefaults.TouchOffset.roundToPx() },
            )
        }
    val menuAlpha by animateFloatAsState(
        targetValue = if (menuVisible) 1f else 0f,
        animationSpec =
            tween(
                durationMillis =
                    if (menuVisible) {
                        WallHubContextMenuDefaults.EnterDurationMillis
                    } else {
                        WallHubContextMenuDefaults.ExitDurationMillis
                    },
                easing = WallHubContextMenuDefaults.Easing,
            ),
        label = "LibraryContextMenuFade",
    )
    // Let the Popup compose while hidden before starting its entrance transition.
    LaunchedEffect(menuMounted, menuEntranceRequest) {
        if (menuMounted) {
            withFrameNanos { }
            if (coordinator.activeTarget?.itemId == item.id) {
                menuVisible = true
            }
        }
    }
    LaunchedEffect(menuVisible, coordinator.activeTarget?.itemId) {
        if (!menuVisible && menuMounted && coordinator.activeTarget?.itemId != item.id) {
            kotlinx.coroutines.delay(WallHubContextMenuDefaults.ExitDurationMillis.toLong())
            if (coordinator.activeTarget?.itemId != item.id) menuMounted = false
        }
    }
    DisposableEffect(item.id) {
        onDispose { coordinator.dismiss(item.id) }
    }

    fun dismissMenu() {
        menuVisible = false
        coordinator.dismiss(item.id)
    }

    fun openMenuAt(
        touchPosition: Offset,
        performHapticFeedback: Boolean = true,
    ) {
        val captured =
            coordinator.captureTarget(
                itemId = item.id,
                graphicsLayer = previewLayer,
                cardCoordinates = position.cardCoordinates,
                touchCoordinates = position.touchCoordinates,
                touchPosition = touchPosition,
                shape = shape,
            ) ?: return
        target = captured
        touchPositionInWindow = captured.touchPositionInWindow
        onAuthorDisplayNameRequested()
        if (performHapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        coordinator.open(captured)
        menuVisible = false
        menuMounted = true
        menuEntranceRequest++
    }
    fun openMenuAtCenter() {
        val size = position.touchCoordinates?.size ?: return
        if (size.width > 0 && size.height > 0) {
            openMenuAt(
                touchPosition = Offset(size.width / 2f, size.height / 2f),
                performHapticFeedback = false,
            )
        }
    }
    val interactionModifier =
        Modifier
            .testTag("library-workshop-${item.id}")
            .pointerInput(item.id) {
                detectTapGestures(
                    onPress = { pressPosition ->
                        val press = PressInteraction.Press(pressPosition)
                        interactionSource.emit(press)
                        interactionSource.emit(
                            if (tryAwaitRelease()) {
                                PressInteraction.Release(press)
                            } else {
                                PressInteraction.Cancel(press)
                            },
                        )
                    },
                    onTap = { onOpen() },
                    onLongPress = ::openMenuAt,
                )
            }.semantics {
                role = Role.Button
                onClick(label = viewDetailsLabel) {
                    onOpen()
                    true
                }
                onLongClick(label = openActionsMenuLabel) {
                    val size = position.touchCoordinates?.size
                    if (size == null || size.width <= 0 || size.height <= 0) {
                        false
                    } else {
                        openMenuAt(Offset(size.width / 2f, size.height / 2f))
                        true
                    }
                }
            }
    val isPreviewed = coordinator.previewItemId == item.id
    val pressedScale by animateFloatAsState(
        targetValue = if (pressed && !isPreviewed) CONTEXT_MENU_PRESS_SCALE else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = WallHubContextMenuDefaults.PressStiffness,
            ),
        label = "LibraryCardPressScale",
    )
    Box(modifier = modifier.fillMaxWidth()) {
        WallHubSurfaceCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { position.cardCoordinates = it }
                    .drawWithContent recordCard@{
                        previewLayer.record { this@recordCard.drawContent() }
                        if (!isPreviewed) drawLayer(previewLayer)
                    }.graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        scaleX = pressedScale
                        scaleY = pressedScale
                    }.onGloballyPositioned { position.touchCoordinates = it }
                    .then(interactionModifier),
            shape = shape,
            content = { content(::openMenuAtCenter) },
        )
        if (menuMounted) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = ::dismissMenu,
                properties = PopupProperties(focusable = true),
            ) {
                val menuWidth =
                    WallHubContextMenuDefaults.menuWidth(
                        cardWidth =
                            target?.let { captured ->
                                with(density) { captured.cardBounds.width.toDp() }
                            },
                    )
                WallHubContextMenuSurface(
                    width = menuWidth,
                    modifier =
                        Modifier
                            .graphicsLayer { alpha = menuAlpha }
                            .then(
                                if (menuVisible) {
                                    Modifier
                                } else {
                                    Modifier
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                do {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    event.changes.forEach { it.consume() }
                                                } while (event.changes.any { it.pressed })
                                            }
                                        }.clearAndSetSemantics {}
                                },
                            ),
                ) {
                    val title = item.localizedTitle()
                    LibraryContextMenuMetadataItem(
                        label = stringResource(R.string.library_wallpaper_title),
                        value = title,
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onCopyText(
                                title,
                                wallpaperTitleCopiedMessage,
                            )
                            dismissMenu()
                        },
                    )
                    LibraryContextMenuMetadataItem(
                        label = stringResource(R.string.library_author),
                        value =
                            authorDisplayName
                                ?: item.author.takeIf { item.authorPlaceholder == WorkshopAuthorPlaceholder.NONE }
                                ?: stringResource(R.string.library_loading_steam_username),
                        icon = Icons.Outlined.PersonOutline,
                        onClick = {
                            dismissMenu()
                            onSearchAuthor()
                        },
                    )
                    LibraryContextMenuMetadataItem(
                        label = stringResource(R.string.library_project_id),
                        value = item.id.toString(),
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onCopyText(
                                item.id.toString(),
                                projectIdCopiedMessage,
                            )
                            dismissMenu()
                        },
                    )
                    Spacer(modifier = Modifier.height(WallHubSpacing.xxxs))
                    LibraryContextMenuAction(
                        text = stringResource(R.string.library_download),
                        icon = Icons.Outlined.Download,
                        onClick = {
                            dismissMenu()
                            onDownload()
                        },
                    )
                    if (removeActionLabel != null) {
                        LibraryContextMenuAction(
                            text = removeActionLabel,
                            icon = Icons.Outlined.Cancel,
                            onClick = {
                                dismissMenu()
                                onRemoveFromCollection()
                            },
                        )
                    }
                    if (item.type == com.wallhub.android.core.model.WorkshopType.VIDEO) {
                        LibraryContextMenuAction(
                            text = stringResource(R.string.library_open_video_details),
                            icon = Icons.Outlined.PlayArrow,
                            onClick = {
                                dismissMenu()
                                onPlayVideo()
                            },
                        )
                    }
                    LibraryContextMenuAction(
                        text = stringResource(R.string.library_open_in_steam),
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        onClick = {
                            dismissMenu()
                            onOpenSteam()
                        },
                    )
                }
            }
        }
    }
}

private class LibraryCardPositionHolder {
    var cardCoordinates: LayoutCoordinates? = null
    var touchCoordinates: LayoutCoordinates? = null
}

private const val CONTEXT_MENU_PRESS_SCALE = 0.985f
