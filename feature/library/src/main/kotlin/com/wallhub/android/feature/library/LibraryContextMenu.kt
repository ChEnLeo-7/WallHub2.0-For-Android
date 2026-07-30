@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.library

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wallhub.android.core.designsystem.WallHubContextMenuDefaults
import com.wallhub.android.core.designsystem.WallHubContextMenuSurface
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.designsystem.WallHubContextMenuAction as LibraryContextMenuAction
import com.wallhub.android.core.designsystem.WallHubContextMenuCardPreview as SharedContextMenuCardPreview
import com.wallhub.android.core.designsystem.WallHubContextMenuMetadataItem as LibraryContextMenuMetadataItem
import com.wallhub.android.core.designsystem.WallHubContextMenuPositionProvider as LibraryContextMenuPositionProvider
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

internal class LibraryContextMenuCoordinator {
    var rootCoordinates: LayoutCoordinates? = null
    var gridCoordinates: LayoutCoordinates? = null
    var activeTarget by mutableStateOf<LibraryContextMenuTarget?>(null)
        private set
    var renderedTarget by mutableStateOf<LibraryContextMenuTarget?>(null)
        private set

    val previewItemId: Long?
        get() = renderedTarget?.itemId

    fun open(target: LibraryContextMenuTarget) {
        activeTarget = target
        renderedTarget = target
    }

    fun dismiss(itemId: Long) {
        if (activeTarget?.itemId == itemId) activeTarget = null
    }

    fun finishDismiss() {
        if (activeTarget == null) renderedTarget = null
    }

    fun captureTarget(
        itemId: Long,
        graphicsLayer: GraphicsLayer,
        cardCoordinates: LayoutCoordinates?,
        touchCoordinates: LayoutCoordinates?,
        touchPosition: Offset,
        shape: Shape,
    ): LibraryContextMenuTarget? {
        val root = rootCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val grid = gridCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val card = cardCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val touchTarget = touchCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val cardBounds = root.localBoundingBoxOf(card, clipBounds = false)
        val clipBounds = root.localBoundingBoxOf(grid, clipBounds = true)
        val touchPositionInWindow = touchTarget.localToWindow(touchPosition)
        if (
            cardBounds.width <= 0f ||
            cardBounds.height <= 0f ||
            clipBounds.width <= 0f ||
            clipBounds.height <= 0f ||
            !touchPositionInWindow.x.isFinite() ||
            !touchPositionInWindow.y.isFinite()
        ) {
            return null
        }
        return LibraryContextMenuTarget(
            itemId = itemId,
            graphicsLayer = graphicsLayer,
            cardBounds = cardBounds,
            clipBounds = clipBounds,
            touchPositionInWindow = touchPositionInWindow,
            shape = shape,
        )
    }
}

@Composable
internal fun rememberLibraryContextMenuCoordinator(): LibraryContextMenuCoordinator = remember { LibraryContextMenuCoordinator() }

@Composable
internal fun LibraryContextMenuLayer(
    coordinator: LibraryContextMenuCoordinator,
    onActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val targetActive = coordinator.activeTarget != null
    val active = coordinator.renderedTarget != null
    val progress by animateFloatAsState(
        targetValue = if (targetActive) 1f else 0f,
        animationSpec =
            tween(
                durationMillis =
                    if (targetActive) {
                        WallHubContextMenuDefaults.EnterDurationMillis
                    } else {
                        WallHubContextMenuDefaults.ExitDurationMillis
                    },
                easing = WallHubContextMenuDefaults.Easing,
            ),
        label = "LibraryContextMenuBackdrop",
        finishedListener = { completedProgress ->
            if (completedProgress == 0f) coordinator.finishDismiss()
        },
    )
    LaunchedEffect(active) {
        onActiveChanged(active)
    }
    DisposableEffect(Unit) {
        onDispose { onActiveChanged(false) }
    }
    Box(
        modifier = modifier.onGloballyPositioned { coordinator.rootCoordinates = it },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && progress > 0f) {
                            Modifier.blur(WallHubContextMenuDefaults.BackgroundBlurRadius * progress)
                        } else {
                            Modifier
                        },
                    ).then(
                        if (coordinator.renderedTarget != null) {
                            Modifier.semantics { invisibleToUser() }
                        } else {
                            Modifier
                        },
                    ),
        ) { content() }
        if (progress > 0f) {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(
                                alpha =
                                    (
                                        if (dark) {
                                            WallHubContextMenuDefaults.DarkScrimAlpha
                                        } else {
                                            WallHubContextMenuDefaults.LightScrimAlpha
                                        }
                                    ) * progress,
                            ),
                        ),
            )
        }
        coordinator.renderedTarget?.let { target ->
            LibraryContextMenuCardPreview(target = target, elevationProgress = progress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryContextMenuCard(
    item: WorkshopSummary,
    language: AppLanguage,
    coordinator: LibraryContextMenuCoordinator,
    onOpen: () -> Unit,
    onSearchAuthor: () -> Unit,
    authorDisplayName: String?,
    onAuthorDisplayNameRequested: () -> Unit,
    onDownload: () -> Unit,
    onPlayVideo: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
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

    fun openMenuAt(touchPosition: Offset) {
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
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        coordinator.open(captured)
        menuVisible = false
        menuMounted = true
        menuEntranceRequest++
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
                onClick(label = language.text("查看详情", "View details")) {
                    onOpen()
                    true
                }
                onLongClick(label = language.text("打开操作菜单", "Open actions menu")) {
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
            content = content,
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
                        language = language,
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
                    LibraryContextMenuMetadataItem(
                        label = language.text("Wallpaper 标题", "Wallpaper title"),
                        value = item.title,
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onCopyText(
                                item.title,
                                language.text("已复制 Wallpaper 标题", "Wallpaper title copied"),
                            )
                            dismissMenu()
                        },
                    )
                    LibraryContextMenuMetadataItem(
                        label = language.text("作者", "Author"),
                        value =
                            authorDisplayName
                                ?: item.author.takeUnless(String::isSteamAuthorPlaceholder)
                                ?: language.text(
                                    "正在获取 Steam 用户名",
                                    "Loading Steam username…",
                                ),
                        icon = Icons.Outlined.PersonOutline,
                        onClick = {
                            dismissMenu()
                            onSearchAuthor()
                        },
                    )
                    LibraryContextMenuMetadataItem(
                        label = language.text("项目 ID", "Project ID"),
                        value = item.id.toString(),
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onCopyText(
                                item.id.toString(),
                                language.text("已复制项目 ID", "Project ID copied"),
                            )
                            dismissMenu()
                        },
                    )
                    Spacer(modifier = Modifier.height(WallHubSpacing.xxxs))
                    LibraryContextMenuAction(
                        text = language.text("下载", "Download"),
                        icon = Icons.Outlined.Download,
                        onClick = {
                            dismissMenu()
                            onDownload()
                        },
                    )
                    if (item.type == com.wallhub.android.core.model.WorkshopType.VIDEO) {
                        LibraryContextMenuAction(
                            text = language.text("视频播放", "Open video details"),
                            icon = Icons.Outlined.PlayArrow,
                            onClick = {
                                dismissMenu()
                                onPlayVideo()
                            },
                        )
                    }
                    LibraryContextMenuAction(
                        text = language.text("打开 Steam", "Open in Steam"),
                        icon = Icons.Outlined.OpenInNew,
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

@Composable
private fun LibraryContextMenuCardPreview(
    target: LibraryContextMenuTarget,
    elevationProgress: Float,
) {
    SharedContextMenuCardPreview(
        graphicsLayer = target.graphicsLayer,
        cardBounds = target.cardBounds,
        clipBounds = target.clipBounds,
        shape = target.shape,
        elevationProgress = elevationProgress,
    )
}

internal data class LibraryContextMenuTarget(
    val itemId: Long,
    val graphicsLayer: GraphicsLayer,
    val cardBounds: Rect,
    val clipBounds: Rect,
    val touchPositionInWindow: Offset,
    val shape: Shape,
)

private class LibraryCardPositionHolder {
    var cardCoordinates: LayoutCoordinates? = null
    var touchCoordinates: LayoutCoordinates? = null
}

private fun String.isSteamAuthorPlaceholder(): Boolean = this == "Steam 创作者" || startsWith("Steam 用户 ")

private const val CONTEXT_MENU_PRESS_SCALE = 0.985f
