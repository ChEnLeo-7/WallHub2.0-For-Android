package com.wallhub.android.feature.home

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class HomeGlobalParticipantTemplate(
    val offsetInCard: Offset,
    val size: IntSize,
)

private data class HomeGlobalGridGeometry(
    val layoutKey: HomeCardLayoutKey,
    val basePosition: Offset,
    val cardSize: IntSize,
    val horizontalSpacingPx: Float,
    val verticalSpacingPx: Float,
    val participants: Map<HomeCardProjectionParticipant, HomeGlobalParticipantTemplate>,
    val exactBounds: Map<Int, Map<HomeCardProjectionParticipant, HomeCardBounds>>,
) {
    fun cardBounds(index: Int): HomeCardBounds {
        exactBounds[index]?.get(HomeCardProjectionParticipant.CARD)?.let { return it }
        val row = index / layoutKey.effectiveColumns
        val column = index % layoutKey.effectiveColumns
        return HomeCardBounds(
            position =
                basePosition +
                    Offset(
                        x = column * (cardSize.width + horizontalSpacingPx),
                        y = row * (cardSize.height + verticalSpacingPx),
                    ),
            size = cardSize,
        )
    }

    fun participantBounds(
        index: Int,
        participant: HomeCardProjectionParticipant,
    ): HomeCardBounds? {
        val card = cardBounds(index)
        if (participant == HomeCardProjectionParticipant.CARD) return card
        exactBounds[index]?.get(participant)?.let { return it }
        val template = participants[participant] ?: return null
        return HomeCardBounds(
            position = card.position + template.offsetInCard,
            size = template.size,
        )
    }
}

private data class HomeGlobalMotionRun(
    val id: Long,
    val source: HomeGlobalGridGeometry,
    val targetKey: HomeCardLayoutKey,
    val lockedTargetBounds: Map<Int, Map<HomeCardProjectionParticipant, HomeCardBounds>>? = null,
)

internal class HomeGlobalLayoutMotionState(
    private val horizontalSpacingPx: Float,
    private val verticalSpacingPx: Float,
    private val participants: Set<HomeCardProjectionParticipant>,
) {
    private val measuredBounds =
        mutableStateMapOf<Int, Map<HomeCardProjectionParticipant, HomeCardBounds>>()
    private val placedVisualBounds =
        mutableMapOf<Int, MutableMap<HomeCardProjectionParticipant, HomeCardBounds>>()
    private var nextRunId = 0L
    private var run by mutableStateOf<HomeGlobalMotionRun?>(null)
    private var mediaProgress by mutableFloatStateOf(1f)
    private var contentProgress by mutableFloatStateOf(1f)

    val isRunning: Boolean
        get() = run != null

    fun request(
        sourceKey: HomeCardLayoutKey,
        targetKey: HomeCardLayoutKey,
        visibleIndices: List<Int>,
    ): Boolean {
        if (sourceKey == targetKey || isRunning) return false
        val referenceIndex =
            visibleIndices.firstOrNull { index -> measuredBounds[index].hasCompleteMeasurement(participants) }
                ?: measuredBounds.entries.firstOrNull { (_, bounds) -> bounds.hasCompleteMeasurement(participants) }?.key
                ?: return false
        val reference = measuredBounds.getValue(referenceIndex)
        val referenceCard = reference.getValue(HomeCardProjectionParticipant.CARD)
        val referenceRow = referenceIndex / sourceKey.effectiveColumns
        val referenceColumn = referenceIndex % sourceKey.effectiveColumns
        val templates =
            participants
                .filterNot { participant -> participant == HomeCardProjectionParticipant.CARD }
                .associateWith { participant ->
                    val bounds = reference.getValue(participant)
                    HomeGlobalParticipantTemplate(
                        offsetInCard = bounds.position - referenceCard.position,
                        size = bounds.size,
                    )
                }
        val exactSourceBounds = measuredBounds.mapValues { (_, bounds) -> bounds.toMap() }
        measuredBounds.clear()
        placedVisualBounds.clear()
        nextRunId += 1L
        mediaProgress = 0f
        contentProgress = 0f
        run =
            HomeGlobalMotionRun(
                id = nextRunId,
                source =
                    HomeGlobalGridGeometry(
                        layoutKey = sourceKey,
                        basePosition =
                            referenceCard.position -
                                Offset(
                                    x = referenceColumn * (referenceCard.size.width + horizontalSpacingPx),
                                    y = referenceRow * (referenceCard.size.height + verticalSpacingPx),
                                ),
                        cardSize = referenceCard.size,
                        horizontalSpacingPx = horizontalSpacingPx,
                        verticalSpacingPx = verticalSpacingPx,
                        participants = templates,
                        exactBounds = exactSourceBounds,
                    ),
                targetKey = targetKey,
            )
        return true
    }

    internal fun runId(): Long? = run?.id

    suspend fun animateCurrentRun() {
        val activeRun = run ?: return
        try {
            var previousCompleteIndices = emptySet<Int>()
            var stableFrames = 0
            var waitedFrames = 0
            while (run?.id == activeRun.id && stableFrames < HOME_TARGET_MEASUREMENT_STABLE_FRAMES) {
                withFrameNanos { }
                waitedFrames += 1
                val completeIndices =
                    measuredBounds
                        .filterValues { bounds -> bounds.hasCompleteMeasurement(participants) }
                        .keys
                        .toSet()
                val allMeasuredItemsComplete =
                    completeIndices.isNotEmpty() && completeIndices.size == measuredBounds.size
                if (allMeasuredItemsComplete && completeIndices == previousCompleteIndices) {
                    stableFrames += 1
                } else {
                    stableFrames = 0
                    previousCompleteIndices = completeIndices
                }
                if (
                    waitedFrames >= HOME_TARGET_MEASUREMENT_MAX_WAIT_FRAMES &&
                    completeIndices.isNotEmpty()
                ) {
                    break
                }
            }
            if (run?.id != activeRun.id) return

            val lockedTargetBounds =
                measuredBounds
                    .filterValues { bounds -> bounds.hasCompleteMeasurement(participants) }
                    .mapValues { (_, bounds) -> bounds.toMap() }
            if (lockedTargetBounds.isEmpty()) return
            run = activeRun.copy(lockedTargetBounds = lockedTargetBounds)

            // Render one frame at the source projection using the immutable target snapshot.
            withFrameNanos { }
            coroutineScope {
                launch {
                    animate(
                        initialValue = contentProgress,
                        targetValue = 1f,
                        animationSpec =
                            tween(
                                durationMillis = HOME_VIEW_CARD_LAYOUT_DURATION_MS,
                                easing = HOME_VIEW_LAYOUT_EASING,
                            ),
                    ) { value, _ ->
                        if (run?.id == activeRun.id) contentProgress = value
                    }
                }
                launch {
                    animate(
                        initialValue = mediaProgress,
                        targetValue = 1f,
                        animationSpec =
                            tween(
                                durationMillis = HOME_VIEW_MEDIA_LAYOUT_DURATION_MS,
                                easing = HOME_VIEW_LAYOUT_EASING,
                            ),
                    ) { value, _ ->
                        if (run?.id == activeRun.id) mediaProgress = value
                    }
                }
            }
            if (run?.id == activeRun.id) {
                mediaProgress = 1f
                contentProgress = 1f
                // Keep the fully settled, unclipped target for one frame before restoring clipping.
                withFrameNanos { }
            }
        } finally {
            if (run?.id == activeRun.id) {
                mediaProgress = 1f
                contentProgress = 1f
                run = null
            }
        }
    }

    fun participantModifier(
        index: Int,
        participant: HomeCardProjectionParticipant,
        onPositioned: (Offset) -> Unit = {},
    ): Modifier =
        Modifier
            .onPlaced { coordinates ->
                val visualBounds = HomeCardBounds(coordinates.positionInRoot(), coordinates.size)
                placedVisualBounds.getOrPut(index, ::mutableMapOf)[participant] = visualBounds
                recordLayoutBounds(index)
                if (participant == HomeCardProjectionParticipant.CARD) {
                    onPositioned(visualBounds.position)
                }
            }.graphicsLayer {
                val layerTransform = layerTransform(index, participant)
                transformOrigin = TransformOrigin.Center
                translationX = layerTransform.translationX
                translationY = layerTransform.translationY
                scaleX = layerTransform.scaleX
                scaleY = layerTransform.scaleY
            }

    private fun recordLayoutBounds(index: Int) {
        val itemVisualBounds = placedVisualBounds[index] ?: return
        val cardBounds = itemVisualBounds[HomeCardProjectionParticipant.CARD] ?: return
        val activeRun = run
        val cardTransform =
            if (activeRun == null || activeRun.lockedTargetBounds != null) {
                HomeCardLayoutTransform.Identity
            } else {
                activeRun.source.cardBounds(index).fullProjectionTo(cardBounds).at(mediaProgress)
            }
        val nextBounds =
            itemVisualBounds.mapValues { (participant, visualBounds) ->
                val rawPosition =
                    if (participant == HomeCardProjectionParticipant.CARD) {
                        visualBounds.position
                    } else {
                        cardTransform.removeFromPosition(
                            position = visualBounds.position,
                            parentBounds = cardBounds,
                        )
                    }
                HomeCardBounds(position = rawPosition, size = visualBounds.size)
            }
        if (nextBounds.isNotEmpty() && measuredBounds[index] != nextBounds) {
            measuredBounds[index] = nextBounds
        }
    }

    fun mediaContentScaleCompensationModifier(index: Int): Modifier =
        Modifier.graphicsLayer {
            val mediaTransform = transform(index, HomeCardProjectionParticipant.MEDIA)
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = 1f / mediaTransform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
            scaleY = 1f / mediaTransform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        }

    fun actionContentModifier(index: Int): Modifier =
        Modifier.graphicsLayer {
            val actionTransform = transform(index, HomeCardProjectionParticipant.ACTION)
            transformOrigin = TransformOrigin.Center
            scaleX = 1f / actionTransform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
            scaleY = 1f / actionTransform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        }

    fun cardShape(index: Int): Shape =
        HomeCoverCorners.forCard().toProjectedShape(
            transform(index, HomeCardProjectionParticipant.CARD),
        )

    fun coverShape(
        index: Int,
        settledListMode: Boolean,
    ): Shape {
        val activeRun = run
        val corners =
            if (activeRun == null) {
                HomeCoverCorners.forListMode(settledListMode)
            } else {
                HomeCoverCorners
                    .forListMode(activeRun.source.layoutKey.listMode)
                    .interpolateTo(
                        HomeCoverCorners.forListMode(activeRun.targetKey.listMode),
                        mediaProgress,
                    )
            }
        return corners.toProjectedShape(transform(index, HomeCardProjectionParticipant.MEDIA))
    }

    fun actionShape(settledListMode: Boolean): Shape {
        val activeRun = run
        val corners =
            if (activeRun == null) {
                HomeActionCorners.forListMode(settledListMode)
            } else {
                HomeActionCorners
                    .forListMode(activeRun.source.layoutKey.listMode)
                    .interpolateTo(
                        HomeActionCorners.forListMode(activeRun.targetKey.listMode),
                        contentProgress,
                    )
            }
        return RoundedCornerShape(corners.radius)
    }

    fun actionLabelVisibility(settledListMode: Boolean): Float {
        val activeRun = run ?: return if (settledListMode) 0f else 1f
        val source = if (activeRun.source.layoutKey.listMode) 0f else 1f
        val target = if (activeRun.targetKey.listMode) 0f else 1f
        return source + (target - source) * contentProgress
    }

    private fun layerTransform(
        index: Int,
        participant: HomeCardProjectionParticipant,
    ): HomeCardLayoutTransform {
        val participantTransform = transform(index, participant)
        if (participant == HomeCardProjectionParticipant.CARD) return participantTransform

        val activeRun = run ?: return participantTransform
        val target =
            activeRun.lockedTargetBounds?.get(index)
                ?: measuredBounds[index]
                ?: return HomeCardLayoutTransform.Identity
        val targetCard = target[HomeCardProjectionParticipant.CARD] ?: return HomeCardLayoutTransform.Identity
        val targetParticipant = target[participant] ?: return HomeCardLayoutTransform.Identity
        val cardTransform = transform(index, HomeCardProjectionParticipant.CARD)
        val cardScaleX = cardTransform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        val cardScaleY = cardTransform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        val participantOffset = targetParticipant.center - targetCard.center
        // Motion projects around element centers, then removes the active parent tree scale.
        val participantVisualCenter =
            targetParticipant.center +
                Offset(participantTransform.translationX, participantTransform.translationY)
        val cardVisualCenter =
            targetCard.center +
                Offset(cardTransform.translationX, cardTransform.translationY)
        return HomeCardLayoutTransform(
            translationX =
                (participantVisualCenter.x - cardVisualCenter.x) / cardScaleX -
                    participantOffset.x,
            translationY =
                (participantVisualCenter.y - cardVisualCenter.y) / cardScaleY -
                    participantOffset.y,
            scaleX = participantTransform.scaleX / cardScaleX,
            scaleY = participantTransform.scaleY / cardScaleY,
        )
    }

    private fun transform(
        index: Int,
        participant: HomeCardProjectionParticipant,
    ): HomeCardLayoutTransform {
        val activeRun = run ?: return HomeCardLayoutTransform.Identity
        val target =
            activeRun.lockedTargetBounds?.get(index)
                ?: measuredBounds[index]
                ?: return HomeCardLayoutTransform.Identity
        val targetCard = target[HomeCardProjectionParticipant.CARD] ?: return HomeCardLayoutTransform.Identity
        val sourceCard = activeRun.source.cardBounds(index)
        if (participant == HomeCardProjectionParticipant.CARD) {
            return sourceCard.fullProjectionTo(targetCard).at(mediaProgress)
        }

        val targetParticipant = target[participant] ?: return HomeCardLayoutTransform.Identity
        val sourceParticipant =
            activeRun.source.participantBounds(
                index = index,
                participant = participant,
            ) ?: return HomeCardLayoutTransform.Identity
        val initialTransform =
            when (participant) {
                HomeCardProjectionParticipant.MEDIA -> sourceParticipant.fullProjectionTo(targetParticipant)

                HomeCardProjectionParticipant.TITLE,
                HomeCardProjectionParticipant.METADATA,
                -> sourceParticipant.preserveAspectProjectionTo(targetParticipant)

                HomeCardProjectionParticipant.ACTION -> sourceParticipant.fullProjectionTo(targetParticipant)

                HomeCardProjectionParticipant.CARD -> HomeCardLayoutTransform.Identity
            }
        val participantProgress =
            when (participant) {
                HomeCardProjectionParticipant.MEDIA -> mediaProgress
                HomeCardProjectionParticipant.TITLE,
                HomeCardProjectionParticipant.METADATA,
                HomeCardProjectionParticipant.ACTION,
                -> contentProgress

                HomeCardProjectionParticipant.CARD -> mediaProgress
            }
        return initialTransform.at(participantProgress)
    }
}

private const val HOME_TARGET_MEASUREMENT_STABLE_FRAMES = 2
private const val HOME_TARGET_MEASUREMENT_MAX_WAIT_FRAMES = 8
private const val HOME_PRESERVE_ASPECT_RATIO_TOLERANCE = 0.2f

private fun HomeCardLayoutTransform.removeFromPosition(
    position: Offset,
    parentBounds: HomeCardBounds,
): Offset {
    val parentCenter = parentBounds.center
    return Offset(
        x =
            parentCenter.x +
                (position.x - parentCenter.x - translationX) /
                scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE),
        y =
            parentCenter.y +
                (position.y - parentCenter.y - translationY) /
                scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE),
    )
}

private val HomeCardBounds.center: Offset
    get() =
        position +
            Offset(
                x = size.width / 2f,
                y = size.height / 2f,
            )

private fun HomeCardBounds.fullProjectionTo(target: HomeCardBounds): HomeCardLayoutTransform =
    HomeCardLayoutTransform(
        translationX = center.x - target.center.x,
        translationY = center.y - target.center.y,
        scaleX = size.width.toFloat() / target.size.width.coerceAtLeast(1),
        scaleY = size.height.toFloat() / target.size.height.coerceAtLeast(1),
    )

private fun HomeCardBounds.preserveAspectProjectionTo(target: HomeCardBounds): HomeCardLayoutTransform {
    val sourceAspect = size.width.toFloat() / size.height.coerceAtLeast(1)
    val targetAspect = target.size.width.toFloat() / target.size.height.coerceAtLeast(1)
    return if (abs(sourceAspect - targetAspect) > HOME_PRESERVE_ASPECT_RATIO_TOLERANCE) {
        HomeCardLayoutTransform(
            translationX = position.x - target.position.x,
            translationY = position.y - target.position.y,
            scaleX = 1f,
            scaleY = 1f,
        )
    } else {
        fullProjectionTo(target)
    }
}

private fun Map<HomeCardProjectionParticipant, HomeCardBounds>?.hasCompleteMeasurement(
    participants: Set<HomeCardProjectionParticipant>,
): Boolean =
    this != null &&
        participants.all { participant -> this[participant]?.hasArea() == true }

@Composable
internal fun rememberHomeGlobalLayoutMotionState(
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    participants: Set<HomeCardProjectionParticipant> = HomeCardProjectionParticipant.entries.toSet(),
): HomeGlobalLayoutMotionState {
    val density = LocalDensity.current
    val horizontalSpacingPx = with(density) { horizontalSpacing.toPx() }
    val verticalSpacingPx = with(density) { verticalSpacing.toPx() }
    val state =
        remember(horizontalSpacingPx, verticalSpacingPx, participants) {
            HomeGlobalLayoutMotionState(horizontalSpacingPx, verticalSpacingPx, participants)
        }
    val runId = state.runId()
    LaunchedEffect(state, runId) {
        if (runId != null) state.animateCurrentRun()
    }
    return state
}
