@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:property-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.wallhub.android.core.model.AppLanguage
import kotlin.math.roundToInt

object WallHubContextMenuDefaults {
    val TouchOffset = 12.dp
    val CardWidthInset = 12.dp
    val MinWidth = 144.dp
    val MaxWidthZh = 204.dp
    val MaxWidthEn = 220.dp
    val MaxHeight = 480.dp
    val ActionHeight = 46.dp
    val BackgroundBlurRadius = 16.dp
    val CardElevation = 8.dp

    const val PressStiffness = 500f
    const val EnterDurationMillis = 260
    const val ExitDurationMillis = 200
    const val LightScrimAlpha = 0.14f
    const val DarkScrimAlpha = 0.20f

    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun menuWidth(
        cardWidth: Dp?,
        language: AppLanguage,
    ): Dp {
        val maximum = if (language == AppLanguage.EN) MaxWidthEn else MaxWidthZh
        return cardWidth
            ?.minus(CardWidthInset)
            ?.coerceIn(minimumValue = MinWidth, maximumValue = maximum)
            ?: maximum
    }
}

@Composable
fun WallHubContextMenuSurface(
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.width(width),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .heightIn(max = WallHubContextMenuDefaults.MaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = WallHubSpacing.xxs),
            content = content,
        )
    }
}

@Composable
fun WallHubContextMenuMetadataItem(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    shape = RoundedCornerShape(10.dp)
                    clip = true
                }.clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = WallHubSpacing.xs, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier =
                Modifier
                    .padding(top = 3.dp)
                    .size(WallHubSizeTokens.compactIcon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun WallHubContextMenuAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(WallHubContextMenuDefaults.ActionHeight)
                .graphicsLayer {
                    shape = RoundedCornerShape(10.dp)
                    clip = true
                }.clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = WallHubSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(WallHubSizeTokens.compactIcon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun WallHubContextMenuCardPreview(
    graphicsLayer: GraphicsLayer,
    cardBounds: Rect,
    clipBounds: Rect,
    shape: Shape,
    elevationProgress: Float,
) {
    val density = LocalDensity.current
    val cardWidth = with(density) { cardBounds.width.toDp() }
    val cardHeight = with(density) { cardBounds.height.toDp() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .drawWithContent previewClip@{
                    clipRect(
                        left = clipBounds.left,
                        top = clipBounds.top,
                        right = clipBounds.right,
                        bottom = clipBounds.bottom,
                    ) {
                        this@previewClip.drawContent()
                    }
                },
    ) {
        Canvas(
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            x = cardBounds.left.roundToInt(),
                            y = cardBounds.top.roundToInt(),
                        )
                    }.size(cardWidth, cardHeight)
                    .graphicsLayer {
                        this.shape = shape
                        clip = true
                        shadowElevation = WallHubContextMenuDefaults.CardElevation.toPx() * elevationProgress
                    },
        ) {
            if (!graphicsLayer.isReleased) drawLayer(graphicsLayer)
        }
    }
}

class WallHubContextMenuPositionProvider(
    private val touchPosition: Offset,
    private val touchOffsetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val desiredRightX = touchPosition.x.roundToInt() + touchOffsetPx
        val desiredBottomY = touchPosition.y.roundToInt() + touchOffsetPx
        val opensLeft = desiredRightX > maxX
        val opensAbove = desiredBottomY > maxY
        val x =
            if (!opensLeft) {
                desiredRightX
            } else {
                (touchPosition.x.roundToInt() - popupContentSize.width - touchOffsetPx)
                    .coerceIn(0, maxX)
            }
        val y =
            if (!opensAbove) {
                desiredBottomY
            } else {
                (touchPosition.y.roundToInt() - popupContentSize.height - touchOffsetPx)
                    .coerceIn(0, maxY)
            }
        return IntOffset(x, y)
    }
}
