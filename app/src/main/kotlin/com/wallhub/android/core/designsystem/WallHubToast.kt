@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Stable
class WallHubToastState {
    var message by mutableStateOf<String?>(null)
        private set
    private var messageToken by mutableIntStateOf(0)
    internal val token: Int get() = messageToken

    fun show(message: String) {
        this.message = message
        messageToken += 1
    }

    fun dismiss() {
        message = null
    }

    internal fun dismissIfCurrent(token: Int) {
        if (token == messageToken) dismiss()
    }
}

val LocalWallHubToastState = staticCompositionLocalOf { WallHubToastState() }

@Composable
fun rememberWallHubToastState(): WallHubToastState = remember { WallHubToastState() }

@Composable
fun WallHubTopToast(
    message: String?,
    onDismiss: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    val toastSurface = MaterialTheme.colorScheme.surfaceContainerHigh
    AnimatedVisibility(
        modifier = modifier,
        visible = message != null,
        enter =
            fadeIn(tween(160)) +
                slideInVertically(tween(220, easing = FastOutSlowInEasing)) { height -> -height / 2 },
        exit =
            fadeOut(tween(140)) +
                slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { height -> -height / 3 },
    ) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = WALLHUB_TOAST_MAX_WIDTH)
                    .padding(horizontal = WALLHUB_TOAST_HORIZONTAL_MARGIN)
                    .fillMaxWidth()
                    .clip(shape)
                    .hazeEffect(hazeState) {
                        blurRadius = WALLHUB_TOAST_BLUR_RADIUS
                        backgroundColor = toastSurface.copy(alpha = 0.54f)
                        tints = listOf(HazeTint(toastSurface.copy(alpha = 0.18f)))
                    }.border(
                        width = WALLHUB_TOAST_BORDER_WIDTH,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                        shape = shape,
                    ).clickable(onClick = onDismiss),
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = WALLHUB_TOAST_MIN_HEIGHT)
                        .padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.94f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.padding(WallHubSpacing.xs),
                    )
                }
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun WallHubGlobalToastHost(
    toastState: WallHubToastState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = remember { HazeState() }
    val activeToastToken = toastState.token
    LaunchedEffect(activeToastToken) {
        if (toastState.message != null) {
            delay(WALLHUB_TOAST_DURATION_MS)
            toastState.dismissIfCurrent(activeToastToken)
        }
    }
    Box(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState), content = content)
        WallHubTopToast(
            message = toastState.message,
            onDismiss = toastState::dismiss,
            hazeState = hazeState,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = WALLHUB_TOAST_TOP_OFFSET)
                    .zIndex(WALLHUB_TOAST_Z_INDEX),
        )
    }
}

private val WALLHUB_TOAST_BLUR_RADIUS = 18.dp
private val WALLHUB_TOAST_TOP_OFFSET = 4.dp
private val WALLHUB_TOAST_HORIZONTAL_MARGIN = 16.dp
private val WALLHUB_TOAST_MAX_WIDTH = 420.dp
private val WALLHUB_TOAST_MIN_HEIGHT = WallHubSizeTokens.minimumTouchTarget
private val WALLHUB_TOAST_BORDER_WIDTH = 0.5.dp
private const val WALLHUB_TOAST_Z_INDEX = 10f
private const val WALLHUB_TOAST_DURATION_MS = 3_000L
