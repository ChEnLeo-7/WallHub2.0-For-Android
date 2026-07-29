package com.wallhub.android.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.offset
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallHubPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    topBarContent: @Composable (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (topBarContent != null) {
                topBarContent()
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    },
                    actions = actions,
                    navigationIcon = { navigationIcon?.invoke() },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        },
        content = content,
    )
}

@Stable
class WallHubToastState {
    var message by mutableStateOf<String?>(null)
        private set

    private var messageToken by mutableIntStateOf(0)

    internal val token: Int
        get() = messageToken

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
        enter = fadeIn(tween(durationMillis = 160)) +
            slideInVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                initialOffsetY = { height -> -height / 2 },
            ),
        exit = fadeOut(tween(durationMillis = 140)) +
            slideOutVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                targetOffsetY = { height -> -height / 3 },
        ),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = WALLHUB_TOAST_MAX_WIDTH)
                .padding(horizontal = WALLHUB_TOAST_HORIZONTAL_MARGIN)
                .fillMaxWidth()
                .clip(shape)
                .hazeEffect(hazeState) {
                    blurRadius = WALLHUB_TOAST_BLUR_RADIUS
                    backgroundColor = toastSurface.copy(alpha = 0.54f)
                    tints = listOf(HazeTint(toastSurface.copy(alpha = 0.18f)))
                }
                .border(
                    width = WALLHUB_TOAST_BORDER_WIDTH,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                    shape = shape,
                )
                .clickable(onClick = onDismiss),
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
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
                        imageVector = WallHubIcons.Outlined.Check,
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

/** Hosts app-wide feedback above the complete navigation hierarchy. */
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
            content = content,
        )
        WallHubTopToast(
            message = toastState.message,
            onDismiss = toastState::dismiss,
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = WALLHUB_TOAST_TOP_OFFSET)
                .zIndex(WALLHUB_TOAST_Z_INDEX),
        )
    }
}

/**
 * Compatibility host for individual screens. Feedback is forwarded to the root host so it stays
 * visible while navigation and page transitions continue underneath it.
 */
@Composable
fun WallHubToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val toastState = LocalWallHubToastState.current
    LaunchedEffect(message) {
        message?.let { toastMessage ->
            toastState.show(toastMessage)
            onDismiss()
        }
    }
    Box(modifier = modifier, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> WallHubSingleChoiceSegmentedControl(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    activeBorderColor = Color.Transparent,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveBorderColor = Color.Transparent,
                ),
                label = { label(option) },
            )
        }
    }
}

@Composable
fun rememberWallHubDirectionalCollapseConnection(
    collapsed: Boolean,
    onCollapsedChanged: (Boolean) -> Unit,
    collapseDistance: Dp = 48.dp,
    expandDistance: Dp = 24.dp,
): NestedScrollConnection {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapseDistancePx = with(density) { collapseDistance.toPx() }
    val expandDistancePx = with(density) { expandDistance.toPx() }
    val connection = remember(collapseDistancePx, expandDistancePx) {
        WallHubDirectionalCollapseConnection(
            collapsed = collapsed,
            collapseDistancePx = collapseDistancePx,
            expandDistancePx = expandDistancePx,
            onCollapsedChanged = onCollapsedChanged,
        )
    }
    SideEffect {
        connection.update(collapsed, onCollapsedChanged)
    }
    return connection
}

private class WallHubDirectionalCollapseConnection(
    collapsed: Boolean,
    private val collapseDistancePx: Float,
    private val expandDistancePx: Float,
    onCollapsedChanged: (Boolean) -> Unit,
) : NestedScrollConnection {
    private var collapsed = collapsed
    private var onCollapsedChanged = onCollapsedChanged
    private var collapseTravel = 0f
    private var expandTravel = 0f

    fun update(collapsed: Boolean, onCollapsedChanged: (Boolean) -> Unit) {
        this.collapsed = collapsed
        this.onCollapsedChanged = onCollapsedChanged
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        when {
            available.y < 0f -> {
                expandTravel = 0f
                if (!collapsed) {
                    collapseTravel += -available.y
                    if (collapseTravel >= collapseDistancePx) requestCollapsed(true)
                }
            }

            available.y > 0f -> {
                collapseTravel = 0f
                if (collapsed) {
                    expandTravel += available.y
                    if (expandTravel >= expandDistancePx) requestCollapsed(false)
                }
            }
        }
        return Offset.Zero
    }

    private fun requestCollapsed(value: Boolean) {
        if (collapsed == value) return
        collapsed = value
        collapseTravel = 0f
        expandTravel = 0f
        onCollapsedChanged(value)
    }
}

@Composable
fun <T> WallHubSlidingSingleChoiceControl(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    indicatorPosition: Float? = null,
    height: Dp = SLIDING_CONTROL_HEIGHT,
    showPressIndication: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerShape: Shape = MaterialTheme.shapes.medium,
    indicatorShape: Shape = MaterialTheme.shapes.small,
) {
    if (options.isEmpty()) return
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(containerShape)
            .background(containerColor),
    ) {
        val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
        val itemWidth = maxWidth / options.size
        val animatedIndicatorOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex + SLIDING_CONTROL_INSET,
            animationSpec = tween(
                durationMillis = 280,
                easing = FastOutSlowInEasing,
            ),
            label = "WallHubSlidingChoice",
        )
        val indicatorOffset = indicatorPosition
            ?.coerceIn(0f, options.lastIndex.toFloat())
            ?.let { position -> itemWidth * position + SLIDING_CONTROL_INSET }
            ?: animatedIndicatorOffset
        Box(
            modifier = Modifier
                .offset(
                    x = indicatorOffset,
                    y = SLIDING_CONTROL_INSET,
                )
                .width((itemWidth - SLIDING_CONTROL_INSET * 2).coerceAtLeast(0.dp))
                .height((height - SLIDING_CONTROL_INSET * 2).coerceAtLeast(0.dp))
                .clip(indicatorShape)
                .background(indicatorColor),
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val interactionSource = remember(option) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            interactionSource = interactionSource,
                            indication = if (showPressIndication) ripple() else null,
                            role = role,
                            onClick = { onSelected(option) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides if (isSelected) {
                            selectedContentColor
                        } else {
                            unselectedContentColor
                        },
                    ) {
                        label(option)
                    }
                }
            }
        }
    }
}

@Composable
fun WallHubFilterSheetHeader(
    title: String,
    status: String? = null,
    hasChanges: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FILTER_SHEET_HEADER_MIN_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = if (hasChanges) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
fun WallHubFilterSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

@Composable
fun WallHubFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleChoice: Boolean = false,
    minHeight: Dp = FILTER_SHEET_CHIP_MIN_HEIGHT,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primary,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val selectionIconState = remember { MutableTransitionState(selected) }
    selectionIconState.targetState = selected
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .heightIn(min = minHeight)
            .then(
                if (singleChoice) Modifier.semantics { role = Role.RadioButton } else Modifier,
            ),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
            iconColor = contentColor,
            selectedContainerColor = selectedContainerColor,
            selectedLabelColor = selectedContentColor,
            selectedLeadingIconColor = selectedContentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.6f),
            disabledLabelColor = contentColor.copy(alpha = 0.5f),
        ),
        leadingIcon = {
            Box(
                modifier = Modifier.size(FILTER_CHIP_ICON_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(
                    visibleState = selectionIconState,
                    enter = fadeIn(tween(durationMillis = 160)) + scaleIn(
                        initialScale = 0.72f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    ),
                    exit = fadeOut(tween(durationMillis = 100)) + scaleOut(
                        targetScale = 0.8f,
                        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
                    ),
                ) {
                    Icon(
                        imageVector = WallHubIcons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FILTER_CHIP_ICON_SIZE),
                    )
                }
            }
        },
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun WallHubAnimatedSelectionCheck(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = FILTER_CHIP_ICON_SIZE,
) {
    val visibleState = remember { MutableTransitionState(selected) }
    visibleState.targetState = selected
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = fadeIn(tween(durationMillis = 160)) + scaleIn(
            initialScale = 0.72f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        ),
        exit = fadeOut(tween(durationMillis = 100)) + scaleOut(
            targetScale = 0.8f,
            animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        ),
    ) {
        Icon(
            imageVector = WallHubIcons.Outlined.Check,
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun WallHubFilterSheetActions(
    secondaryLabel: String,
    cancelLabel: String,
    applyLabel: String?,
    onSecondary: () -> Unit,
    onCancel: () -> Unit,
    onApply: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondaryEnabled: Boolean = true,
    applyEnabled: Boolean = true,
) {
    val secondaryColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onSecondary,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = FILTER_SHEET_ACTION_MIN_HEIGHT),
                enabled = secondaryEnabled,
                shape = MaterialTheme.shapes.medium,
                colors = secondaryColors,
            ) {
                Text(secondaryLabel)
            }
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = FILTER_SHEET_ACTION_MIN_HEIGHT),
                shape = MaterialTheme.shapes.medium,
                colors = secondaryColors,
            ) {
                Text(cancelLabel)
            }
        }
        if (applyLabel != null && onApply != null) {
            Button(
                onClick = onApply,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FILTER_SHEET_ACTION_MIN_HEIGHT),
                enabled = applyEnabled,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(applyLabel)
            }
        }
    }
}

@Composable
fun WallHubSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape? = null,
    elevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape ?: MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = elevation,
    ) {
        content()
    }
}

@Composable
fun WallHubEmptyState(
    icon: ImageVector,
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            WallHubSecondaryButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun WallHubPrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null)
        }
        Text(text = label, modifier = Modifier.padding(start = if (icon == null) 0.dp else 8.dp))
    }
}

@Composable
fun WallHubSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
        content = content,
    )
}

fun formatMegabytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val megabytes = safeBytes / BYTES_PER_MEGABYTE
    return if (megabytes > MEGABYTES_PER_GIGABYTE) {
        String.format(Locale.getDefault(), "%.1f GB", safeBytes / BYTES_PER_GIGABYTE)
    } else {
        String.format(Locale.getDefault(), "%.1f MB", megabytes)
    }
}

private val SLIDING_CONTROL_HEIGHT = 44.dp
private val SLIDING_CONTROL_INSET = 4.dp
private val FILTER_SHEET_HEADER_MIN_HEIGHT = 48.dp
private val FILTER_SHEET_CHIP_MIN_HEIGHT = 40.dp
private val FILTER_CHIP_ICON_SIZE = 18.dp
private val FILTER_SHEET_ACTION_MIN_HEIGHT = 48.dp
val WallHubFabDefaultElevation = 3.dp
val WallHubFabActiveElevation = 4.dp
private val WALLHUB_TOAST_BLUR_RADIUS = 18.dp
private val WALLHUB_TOAST_TOP_OFFSET = 4.dp
private val WALLHUB_TOAST_HORIZONTAL_MARGIN = 16.dp
private val WALLHUB_TOAST_MAX_WIDTH = 420.dp
private val WALLHUB_TOAST_MIN_HEIGHT = WallHubSizeTokens.minimumTouchTarget
private val WALLHUB_TOAST_BORDER_WIDTH = 0.5.dp
private const val WALLHUB_TOAST_Z_INDEX = 10f
private const val WALLHUB_TOAST_DURATION_MS = 3_000L
private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
private const val MEGABYTES_PER_GIGABYTE = 1024.0
private const val BYTES_PER_GIGABYTE = BYTES_PER_MEGABYTE * MEGABYTES_PER_GIGABYTE
