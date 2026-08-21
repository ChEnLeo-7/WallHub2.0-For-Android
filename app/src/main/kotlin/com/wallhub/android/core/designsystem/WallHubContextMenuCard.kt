package com.wallhub.android.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** Reusable card host with the same long-press presentation used by Home Workshop cards. */
@Composable
fun WallHubContextMenuCard(
    itemId: Long,
    shape: Shape,
    state: WallHubContextMenuState,
    onClick: () -> Unit,
    clickLabel: String,
    longClickLabel: String,
    modifier: Modifier = Modifier,
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val previewLayer = rememberGraphicsLayer()
    var cardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var touchCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var menuVisible by remember { mutableStateOf(false) }
    var menuMounted by remember { mutableStateOf(false) }
    var menuRequested by remember { mutableStateOf(false) }
    var entranceRequest by remember { mutableIntStateOf(0) }
    var target by remember(itemId) { mutableStateOf<WallHubContextMenuTarget?>(null) }
    var touchPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val positionProvider =
        remember(touchPositionInWindow, density) {
            WallHubContextMenuPositionProvider(
                touchPosition = touchPositionInWindow,
                touchOffsetPx = with(density) { WallHubContextMenuDefaults.TouchOffset.roundToPx() },
            )
        }
    val menuAlpha by animateFloatAsState(
        targetValue = if (menuVisible) 1f else 0f,
        animationSpec =
            tween(
                durationMillis =
                    if (menuVisible) WallHubContextMenuDefaults.EnterDurationMillis else WallHubContextMenuDefaults.ExitDurationMillis,
                easing = WallHubContextMenuDefaults.Easing,
            ),
        label = "WallHubContextMenuFade",
    )
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed && !menuMounted) 0.98f else 1f,
        animationSpec = tween(120),
        label = "WallHubContextMenuPress",
    )

    fun dismiss() {
        menuRequested = false
        menuVisible = false
        state.dismiss(itemId)
    }

    fun open(position: Offset) {
        val captured =
            state.captureTarget(
                itemId = itemId,
                graphicsLayer = previewLayer,
                cardCoordinates = cardCoordinates,
                touchCoordinates = touchCoordinates,
                touchPosition = position,
                shape = shape,
            ) ?: return
        target = captured
        touchPositionInWindow = captured.touchPositionInWindow
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        state.open(captured)
        menuRequested = true
        menuVisible = false
        menuMounted = true
        entranceRequest += 1
    }

    LaunchedEffect(menuVisible) {
        if (!menuVisible && menuMounted) {
            delay(WallHubContextMenuDefaults.ExitDurationMillis.toLong())
            if (!menuRequested) menuMounted = false
        }
    }
    LaunchedEffect(menuMounted, entranceRequest) {
        if (menuMounted) {
            withFrameNanos { }
            if (menuRequested) menuVisible = true
        }
    }
    DisposableEffect(itemId) {
        onDispose { state.dismiss(itemId) }
    }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { cardCoordinates = it }
                .drawWithContent {
                    if (isPressed || menuMounted || state.previewItemId == itemId) {
                        previewLayer.record { this@drawWithContent.drawContent() }
                        if (state.previewItemId != itemId) drawLayer(previewLayer)
                    } else {
                        drawContent()
                    }
                }.graphicsLayer {
                    transformOrigin = TransformOrigin.Center
                    scaleX = pressedScale
                    scaleY = pressedScale
                }.onGloballyPositioned { touchCoordinates = it }
                .pointerInput(itemId) {
                    detectTapGestures(
                        onPress = { position ->
                            val press = PressInteraction.Press(position)
                            interactionSource.emit(press)
                            interactionSource.emit(
                                if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onClick() },
                        onLongPress = ::open,
                    )
                }.semantics {
                    role = Role.Button
                    onClick(clickLabel) {
                        onClick()
                        true
                    }
                    onLongClick(longClickLabel) {
                        val size = touchCoordinates?.size
                        if (size == null || size.width <= 0 || size.height <= 0) {
                            false
                        } else {
                            open(Offset(size.width / 2f, size.height / 2f))
                            true
                        }
                    }
                },
        ) { content() }

        if (menuMounted) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = ::dismiss,
                properties = PopupProperties(focusable = true),
            ) {
                WallHubContextMenuSurface(
                    width =
                        WallHubContextMenuDefaults.menuWidth(
                            target?.let { with(density) { it.cardBounds.width.toDp() } },
                        ),
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
                                        }.clearAndSetSemantics { }
                                },
                            ),
                ) { menuContent(::dismiss) }
            }
        }
    }
}
