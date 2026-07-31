@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wallhub.android.core.designsystem.WallHubSpacing
import kotlin.math.abs
import com.wallhub.android.core.designsystem.WallHubContextMenuCardPreview as SharedContextMenuCardPreview

internal data class HomeContextMenuTarget(
    val itemId: Long,
    val graphicsLayer: GraphicsLayer,
    val cardBounds: Rect,
    val clipBounds: Rect,
    val touchPositionInWindow: Offset,
    val shape: Shape,
)

internal class HomeContextMenuGeometry {
    var rootCoordinates: LayoutCoordinates? = null
    var gridCoordinates: LayoutCoordinates? = null

    fun captureTarget(
        itemId: Long,
        graphicsLayer: GraphicsLayer,
        cardCoordinates: LayoutCoordinates?,
        touchCoordinates: LayoutCoordinates?,
        touchPosition: Offset,
        shape: Shape,
    ): HomeContextMenuTarget? {
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
        return HomeContextMenuTarget(
            itemId = itemId,
            graphicsLayer = graphicsLayer,
            cardBounds = cardBounds,
            clipBounds = clipBounds,
            touchPositionInWindow = touchPositionInWindow,
            shape = shape,
        )
    }
}

internal class HomeCardPositionHolder {
    var cardCoordinates: LayoutCoordinates? = null
    var touchCoordinates: LayoutCoordinates? = null
}

@Composable
internal fun HomeContextMenuCardPreview(
    target: HomeContextMenuTarget,
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

/**
 * Mirrors the Web card's layout projection when the discover grid switches
 * between its grid and single-column modes. Lazy grids place every item at its
 * new bounds in one pass, so the card and its main children each apply an
 * inverse transform first and then ease it away. This keeps position, width and
 * height continuous instead of changing card content at the first layout frame.
 */
internal data class HomeLayoutTransaction(
    val epoch: Long,
    val requestId: Long,
    val sourceKey: HomeCardLayoutKey,
    val targetKey: HomeCardLayoutKey,
)

internal class HomeLayoutTransactionState(
    initialKey: HomeCardLayoutKey,
) {
    var requestedKey: HomeCardLayoutKey = initialKey
        private set
    var measuredKey: HomeCardLayoutKey = initialKey
        private set
    var epoch: Long = 0L
        private set
    var requestId: Long = 0L
        private set

    fun request(key: HomeCardLayoutKey) {
        if (key != requestedKey) requestId += 1L
        requestedKey = key
    }

    fun consumeForMeasurement(expectedRequestId: Long = requestId): HomeLayoutTransaction? {
        if (expectedRequestId != requestId) return null
        if (requestedKey == measuredKey) return null
        val transaction =
            HomeLayoutTransaction(
                epoch = epoch + 1L,
                requestId = requestId,
                sourceKey = measuredKey,
                targetKey = requestedKey,
            )
        epoch = transaction.epoch
        return transaction
    }

    fun commit(transaction: HomeLayoutTransaction): Boolean {
        if (transaction.epoch != epoch || transaction.requestId != requestId) return false
        measuredKey = transaction.targetKey
        return true
    }
}

internal class HomeLayoutEdgeEntryState(
    initialKey: HomeCardLayoutKey,
) {
    private var layoutKey = initialKey
    private var completedRequestId by mutableLongStateOf(0L)

    var requestId: Long = 0L
        private set

    val isActive: Boolean
        get() = requestId != completedRequestId

    fun update(key: HomeCardLayoutKey): Long {
        if (key != layoutKey) {
            layoutKey = key
            requestId += 1L
        }
        return requestId
    }

    fun complete(expectedRequestId: Long): Boolean {
        if (expectedRequestId != requestId) return false
        completedRequestId = expectedRequestId
        return true
    }
}

internal enum class HomeCardProjectionParticipant {
    CARD,
    MEDIA,
    TAG,
    CONTENT,
    ACTION,
}

internal data class HomeCardProjectionTransforms(
    val card: HomeCardLayoutTransform,
    val media: HomeCardLayoutTransform,
    val tag: HomeCardLayoutTransform,
    val content: HomeCardLayoutTransform,
    val action: HomeCardLayoutTransform,
) {
    operator fun get(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform =
        when (participant) {
            HomeCardProjectionParticipant.CARD -> card
            HomeCardProjectionParticipant.MEDIA -> media
            HomeCardProjectionParticipant.TAG -> tag
            HomeCardProjectionParticipant.CONTENT -> content
            HomeCardProjectionParticipant.ACTION -> action
        }

    fun with(
        participant: HomeCardProjectionParticipant,
        transform: HomeCardLayoutTransform,
    ): HomeCardProjectionTransforms =
        when (participant) {
            HomeCardProjectionParticipant.CARD -> copy(card = transform)
            HomeCardProjectionParticipant.MEDIA -> copy(media = transform)
            HomeCardProjectionParticipant.TAG -> copy(tag = transform)
            HomeCardProjectionParticipant.CONTENT -> copy(content = transform)
            HomeCardProjectionParticipant.ACTION -> copy(action = transform)
        }

    companion object {
        val Identity =
            HomeCardProjectionTransforms(
                card = HomeCardLayoutTransform.Identity,
                media = HomeCardLayoutTransform.Identity,
                tag = HomeCardLayoutTransform.Identity,
                content = HomeCardLayoutTransform.Identity,
                action = HomeCardLayoutTransform.Identity,
            )
    }
}

internal class HomeCardProjectionGroupRun internal constructor(
    val id: Long,
    val epoch: Long,
    val transforms: HomeCardProjectionTransforms,
    val cardInitialAlpha: Float,
    val shouldAnimate: Boolean,
    initialProgress: Float,
) {
    var progress by mutableFloatStateOf(initialProgress)
        private set

    internal fun updateProgress(value: Float) {
        progress = value
    }
}

internal class HomeCardProjectionGroupStage internal constructor(
    val transaction: HomeLayoutTransaction,
    val sourceTransforms: HomeCardProjectionTransforms,
    val sourceCardAlpha: Float,
    val edgeEntryRequired: Boolean,
    sourceBounds: Map<HomeCardProjectionParticipant, HomeCardBounds?>,
) {
    private val sourceBounds = sourceBounds.toMap()
    private val measuredBounds = mutableMapOf<HomeCardProjectionParticipant, HomeCardBounds?>()
    private var stagedTransforms = HomeCardProjectionTransforms.Identity
    private val readyParticipants = mutableSetOf<HomeCardProjectionParticipant>()

    val epoch: Long
        get() = transaction.epoch
    val requestId: Long
        get() = transaction.requestId
    val targetKey: HomeCardLayoutKey
        get() = transaction.targetKey
    val isReady: Boolean
        get() = readyParticipants.size == HomeCardProjectionParticipant.entries.size

    fun sourceBounds(participant: HomeCardProjectionParticipant): HomeCardBounds? = sourceBounds[participant]

    fun hasMeasurement(participant: HomeCardProjectionParticipant): Boolean = measuredBounds.containsKey(participant)

    fun targetBounds(participant: HomeCardProjectionParticipant): HomeCardBounds? = measuredBounds[participant]

    fun recordMeasurement(
        participant: HomeCardProjectionParticipant,
        bounds: HomeCardBounds?,
    ) {
        if (participant !in readyParticipants) measuredBounds[participant] = bounds
    }

    fun markMissingMeasurements() {
        HomeCardProjectionParticipant.entries.forEach { participant ->
            measuredBounds.putIfAbsent(participant, null)
        }
    }

    fun isParticipantReady(participant: HomeCardProjectionParticipant): Boolean = participant in readyParticipants

    fun stage(
        participant: HomeCardProjectionParticipant,
        transform: HomeCardLayoutTransform,
    ) {
        if (participant in readyParticipants) return
        stagedTransforms = stagedTransforms.with(participant, transform)
        readyParticipants += participant
    }

    fun stagedTransform(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform? =
        stagedTransforms[participant].takeIf { participant in readyParticipants }

    fun displayTransform(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform =
        stagedTransform(participant) ?: sourceTransforms[participant]

    fun committedTransforms(): HomeCardProjectionTransforms {
        check(isReady)
        return stagedTransforms
    }
}

internal class HomeCardProjectionGroupState(
    initialEpoch: Long = 0L,
) {
    private var nextRunId = 0L
    private var displayVersion by mutableIntStateOf(0)
    var activeRun by mutableStateOf(
        HomeCardProjectionGroupRun(
            id = nextRunId,
            epoch = initialEpoch,
            transforms = HomeCardProjectionTransforms.Identity,
            cardInitialAlpha = 1f,
            shouldAnimate = false,
            initialProgress = 1f,
        ),
    )
        private set
    var pendingStage by mutableStateOf<HomeCardProjectionGroupStage?>(null)
        private set

    val progress: Float
        get() {
            displayVersion
            return if (pendingStage != null) 0f else activeRun.progress
        }

    val requiresGraphicsLayer: Boolean
        get() = pendingStage != null || activeRun.progress < 1f

    fun currentTransform(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform {
        displayVersion
        return pendingStage?.displayTransform(participant)
            ?: activeRun.transforms[participant].at(activeRun.progress)
    }

    fun captureCurrentTransforms(): HomeCardProjectionTransforms {
        var transforms = HomeCardProjectionTransforms.Identity
        HomeCardProjectionParticipant.entries.forEach { participant ->
            transforms = transforms.with(participant, currentTransform(participant))
        }
        return transforms
    }

    fun currentCardAlpha(): Float {
        displayVersion
        return pendingStage?.sourceCardAlpha ?: activeCardAlpha()
    }

    fun beginStage(
        transaction: HomeLayoutTransaction,
        sourceBounds: Map<HomeCardProjectionParticipant, HomeCardBounds?>,
        edgeEntryEnabled: Boolean = false,
    ): HomeCardProjectionGroupStage {
        val replacedStage = pendingStage
        val edgeEntryRequired =
            sourceBounds[HomeCardProjectionParticipant.CARD]?.hasArea() != true &&
                (edgeEntryEnabled || replacedStage?.edgeEntryRequired == true)
        val stage =
            HomeCardProjectionGroupStage(
                transaction = transaction,
                sourceTransforms = replacedStage?.sourceTransforms ?: captureCurrentTransforms(),
                sourceCardAlpha = replacedStage?.sourceCardAlpha ?: if (edgeEntryRequired) 0f else activeCardAlpha(),
                edgeEntryRequired = edgeEntryRequired,
                sourceBounds = sourceBounds,
            )
        pendingStage = stage
        displayVersion += 1
        return stage
    }

    fun recordMeasurement(
        expectedStage: HomeCardProjectionGroupStage,
        participant: HomeCardProjectionParticipant,
        bounds: HomeCardBounds?,
    ): Boolean {
        if (pendingStage !== expectedStage) return false
        expectedStage.recordMeasurement(participant, bounds)
        displayVersion += 1
        return true
    }

    fun stageParticipant(
        expectedStage: HomeCardProjectionGroupStage,
        participant: HomeCardProjectionParticipant,
        transform: HomeCardLayoutTransform,
    ): Boolean {
        if (pendingStage !== expectedStage) return false
        expectedStage.stage(participant, transform)
        displayVersion += 1
        if (expectedStage.isReady) commit(expectedStage)
        return true
    }

    fun markMissingMeasurements(expectedStage: HomeCardProjectionGroupStage): Boolean {
        if (pendingStage !== expectedStage) return false
        expectedStage.markMissingMeasurements()
        displayVersion += 1
        return true
    }

    fun settleUnreadyParticipants(expectedStage: HomeCardProjectionGroupStage): Boolean {
        if (pendingStage !== expectedStage) return false
        HomeCardProjectionParticipant.entries.forEach { participant ->
            if (!expectedStage.isParticipantReady(participant)) {
                expectedStage.stage(participant, HomeCardLayoutTransform.Identity)
            }
        }
        displayVersion += 1
        if (expectedStage.isReady) commit(expectedStage)
        return true
    }

    fun updateProgress(
        expectedRun: HomeCardProjectionGroupRun,
        value: Float,
    ): Boolean {
        expectedRun.updateProgress(value)
        return activeRun === expectedRun && pendingStage == null
    }

    fun startStandalone(
        transforms: HomeCardProjectionTransforms,
        cardInitialAlpha: Float,
    ) {
        pendingStage = null
        nextRunId += 1L
        activeRun =
            HomeCardProjectionGroupRun(
                id = nextRunId,
                epoch = activeRun.epoch,
                transforms = transforms,
                cardInitialAlpha = cardInitialAlpha,
                shouldAnimate = true,
                initialProgress = 0f,
            )
        displayVersion += 1
    }

    fun cancelStageIntoRun(expectedStage: HomeCardProjectionGroupStage): Boolean {
        if (pendingStage !== expectedStage) return false
        pendingStage = null
        nextRunId += 1L
        activeRun =
            HomeCardProjectionGroupRun(
                id = nextRunId,
                epoch = activeRun.epoch,
                transforms = expectedStage.sourceTransforms,
                cardInitialAlpha = expectedStage.sourceCardAlpha,
                shouldAnimate = true,
                initialProgress = 0f,
            )
        displayVersion += 1
        return true
    }

    private fun activeCardAlpha(): Float = activeRun.cardInitialAlpha + (1f - activeRun.cardInitialAlpha) * activeRun.progress

    private fun commit(stage: HomeCardProjectionGroupStage) {
        if (pendingStage !== stage) return
        nextRunId += 1L
        activeRun =
            HomeCardProjectionGroupRun(
                id = nextRunId,
                epoch = stage.epoch,
                transforms = stage.committedTransforms(),
                cardInitialAlpha = stage.sourceCardAlpha,
                shouldAnimate = true,
                initialProgress = 0f,
            )
        pendingStage = null
        displayVersion += 1
    }
}

@Composable
internal fun rememberHomeViewCardLayoutMotion(
    layoutKey: HomeCardLayoutKey,
    animateEdgeEntry: Boolean,
): HomeViewCardLayoutMotion {
    val motion = remember { HomeViewCardLayoutMotion(layoutKey, animateEdgeEntry) }
    motion.updateLayout(layoutKey, animateEdgeEntry)

    HomeProjectionGroupEffect(motion)

    return motion
}

@Composable
internal fun HomeProjectionGroupEffect(motion: HomeViewCardLayoutMotion) {
    val run = motion.activeGroupRun
    LaunchedEffect(run) {
        if (run.shouldAnimate) {
            motion.animate(run)
        }
    }
    val pendingStage = motion.pendingGroupStage
    LaunchedEffect(pendingStage) {
        if (pendingStage != null) {
            withFrameNanos { }
            motion.settleMissingParticipants(pendingStage)
        }
    }
}

internal class HomeCardProjectionSlot(
    initialLayoutKey: HomeCardLayoutKey,
) {
    var previousBounds: HomeCardBounds? = null
    var previousLayoutKey: HomeCardLayoutKey = initialLayoutKey
    var consumedLayoutEpoch: Long = 0L
}

internal class HomeViewCardLayoutMotion(
    initialLayoutKey: HomeCardLayoutKey,
    initialAnimateEdgeEntry: Boolean,
) {
    private val transactions = HomeLayoutTransactionState(initialLayoutKey)
    private val projectionGroup = HomeCardProjectionGroupState()
    private val slots =
        HomeCardProjectionParticipant.entries.associateWith {
            HomeCardProjectionSlot(initialLayoutKey)
        }
    private val layoutKey: HomeCardLayoutKey
        get() = transactions.requestedKey
    private var animateEdgeEntry = initialAnimateEdgeEntry
    private var coverCornerFrom by mutableStateOf(HomeCoverCorners.forListMode(initialLayoutKey.listMode))
    private var coverCornerTo by mutableStateOf(HomeCoverCorners.forListMode(initialLayoutKey.listMode))
    private var actionCornerFrom by mutableStateOf(HomeActionCorners.forListMode(initialLayoutKey.listMode))
    private var actionCornerTo by mutableStateOf(HomeActionCorners.forListMode(initialLayoutKey.listMode))
    private var actionLabelFrom by mutableFloatStateOf(if (initialLayoutKey.listMode) 0f else 1f)
    private var actionLabelTo by mutableFloatStateOf(if (initialLayoutKey.listMode) 0f else 1f)

    val activeGroupRun: HomeCardProjectionGroupRun
        get() = projectionGroup.activeRun
    val pendingGroupStage: HomeCardProjectionGroupStage?
        get() = projectionGroup.pendingStage

    fun updateLayout(
        value: HomeCardLayoutKey,
        shouldAnimateEdgeEntry: Boolean,
    ) {
        transactions.request(value)
        animateEdgeEntry = shouldAnimateEdgeEntry
        val pendingStage = projectionGroup.pendingStage
        if (
            pendingStage != null &&
            pendingStage.requestId != transactions.requestId &&
            value == transactions.measuredKey
        ) {
            beginProjectedVisualTransition(value.listMode)
            projectionGroup.cancelStageIntoRun(pendingStage)
        }
    }

    private fun beginProjectedVisualTransition(targetListMode: Boolean) {
        coverCornerFrom = currentCoverCorners()
        coverCornerTo = HomeCoverCorners.forListMode(targetListMode)
        actionCornerFrom = currentActionCorners()
        actionCornerTo = HomeActionCorners.forListMode(targetListMode)
        actionLabelFrom = actionLabelVisibility()
        actionLabelTo = if (targetListMode) 0f else 1f
    }

    fun cardModifier(onPositioned: (Offset) -> Unit = {}): Modifier {
        val callbackKey = layoutKey
        val callbackRequestId = transactions.requestId
        val cardSlot = slot(HomeCardProjectionParticipant.CARD)
        return Modifier
            .onGloballyPositioned { coordinates ->
                val currentBounds =
                    HomeCardBounds(
                        position = coordinates.positionInRoot(),
                        size = coordinates.size,
                    )
                onPositioned(currentBounds.position)
                val wasUnmeasured = cardSlot.previousBounds == null
                recordProjectionMeasurement(
                    participant = HomeCardProjectionParticipant.CARD,
                    callbackKey = callbackKey,
                    callbackRequestId = callbackRequestId,
                    bounds = currentBounds,
                )
                if (
                    wasUnmeasured &&
                    animateEdgeEntry &&
                    projectionGroup.pendingStage == null &&
                    currentBounds.hasArea()
                ) {
                    projectionGroup.startStandalone(
                        transforms =
                            HomeCardProjectionTransforms.Identity.with(
                                HomeCardProjectionParticipant.CARD,
                                HomeCardLayoutTransform(
                                    translationX = 0f,
                                    translationY = currentBounds.size.height * HOME_VIEW_EDGE_ENTRY_OFFSET_FRACTION,
                                    scaleX = 1f,
                                    scaleY = 1f,
                                ),
                            ),
                        cardInitialAlpha = 0f,
                    )
                }
            }.then(
                if (
                    animateEdgeEntry ||
                    projectionGroup.requiresGraphicsLayer ||
                    cardSlot.previousLayoutKey != layoutKey
                ) {
                    Modifier.graphicsLayer {
                        applyProjectionToLayer(
                            participant = HomeCardProjectionParticipant.CARD,
                            layerScope = this,
                        )
                    }
                } else {
                    Modifier
                },
            )
    }

    fun mediaModifier(): Modifier =
        projectionModifier(
            participant = HomeCardProjectionParticipant.MEDIA,
            parentParticipant = HomeCardProjectionParticipant.CARD,
        )

    fun tagModifier(): Modifier {
        val callbackKey = layoutKey
        val callbackRequestId = transactions.requestId
        val tagSlot = slot(HomeCardProjectionParticipant.TAG)
        return Modifier
            .onGloballyPositioned { coordinates ->
                recordProjectionMeasurement(
                    participant = HomeCardProjectionParticipant.TAG,
                    callbackKey = callbackKey,
                    callbackRequestId = callbackRequestId,
                    bounds =
                        HomeCardBounds(
                            position = coordinates.positionInParent(),
                            size = coordinates.size,
                        ),
                )
            }.then(
                if (
                    animateEdgeEntry ||
                    projectionGroup.requiresGraphicsLayer ||
                    tagSlot.previousLayoutKey != layoutKey
                ) {
                    Modifier.graphicsLayer {
                        applyProjectionToLayer(
                            participant = HomeCardProjectionParticipant.TAG,
                            layerScope = this,
                            parentTransform = projectionGroup.currentTransform(HomeCardProjectionParticipant.MEDIA),
                        )
                    }
                } else {
                    Modifier
                },
            )
    }

    fun contentModifier(): Modifier =
        projectionModifier(
            participant = HomeCardProjectionParticipant.CONTENT,
            parentParticipant = HomeCardProjectionParticipant.CARD,
        )

    fun actionModifier(): Modifier =
        projectionModifier(
            participant = HomeCardProjectionParticipant.ACTION,
            // Grid buttons and the list action have different aspect ratios. Project
            // both dimensions so the control changes size continuously with the card.
            parentParticipant = HomeCardProjectionParticipant.CARD,
        )

    fun actionContentModifier(): Modifier {
        val actionSlot = slot(HomeCardProjectionParticipant.ACTION)
        if (
            !animateEdgeEntry &&
            !projectionGroup.requiresGraphicsLayer &&
            actionSlot.previousLayoutKey == layoutKey
        ) {
            return Modifier
        }
        return Modifier.graphicsLayer {
            // Keep content compensation on the same draw frame as the outer action projection.
            val actionTransform = projectionGroup.currentTransform(HomeCardProjectionParticipant.ACTION)
            transformOrigin = TransformOrigin.Center
            scaleX = 1f / actionTransform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
            scaleY = 1f / actionTransform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        }
    }

    fun actionLabelVisibility(): Float {
        val contentProgress =
            (
                (projectionGroup.progress - HOME_VIEW_ACTION_CONTENT_FADE_START) /
                    (HOME_VIEW_ACTION_CONTENT_FADE_END - HOME_VIEW_ACTION_CONTENT_FADE_START)
            ).coerceIn(0f, 1f)
        return actionLabelFrom + (actionLabelTo - actionLabelFrom) * contentProgress
    }

    fun cardShape(): Shape =
        HomeCoverCorners.forCard().toProjectedShape(
            transform = projectionGroup.currentTransform(HomeCardProjectionParticipant.CARD),
        )

    fun coverShape(): Shape =
        currentCoverCorners().toProjectedShape(
            transform = projectionGroup.currentTransform(HomeCardProjectionParticipant.MEDIA),
        )

    fun actionShape(): Shape =
        currentActionCorners().toCoverCorners().toProjectedShape(
            transform = projectionGroup.currentTransform(HomeCardProjectionParticipant.ACTION),
        )

    private fun projectionModifier(
        participant: HomeCardProjectionParticipant,
        parentParticipant: HomeCardProjectionParticipant,
    ): Modifier {
        val callbackKey = layoutKey
        val callbackRequestId = transactions.requestId
        val projectionSlot = slot(participant)
        return Modifier
            .onGloballyPositioned { coordinates ->
                recordProjectionMeasurement(
                    participant = participant,
                    callbackKey = callbackKey,
                    callbackRequestId = callbackRequestId,
                    bounds =
                        HomeCardBounds(
                            position = coordinates.positionInRoot(),
                            size = coordinates.size,
                        ),
                )
            }.then(
                if (
                    animateEdgeEntry ||
                    projectionGroup.requiresGraphicsLayer ||
                    projectionSlot.previousLayoutKey != layoutKey
                ) {
                    Modifier.graphicsLayer {
                        applyProjectionToLayer(
                            participant = participant,
                            layerScope = this,
                            parentTransform = projectionGroup.currentTransform(parentParticipant),
                        )
                    }
                } else {
                    Modifier
                },
            )
    }

    private fun slot(participant: HomeCardProjectionParticipant): HomeCardProjectionSlot = checkNotNull(slots[participant])

    private fun recordProjectionMeasurement(
        participant: HomeCardProjectionParticipant,
        callbackKey: HomeCardLayoutKey,
        callbackRequestId: Long,
        bounds: HomeCardBounds,
    ) {
        if (callbackRequestId != transactions.requestId || callbackKey != layoutKey) return

        var stage = projectionGroup.pendingStage
        val transaction =
            if (stage == null || stage.requestId != callbackRequestId) {
                transactions.consumeForMeasurement(callbackRequestId)
            } else {
                null
            }
        if (transaction != null) {
            beginProjectedVisualTransition(transaction.targetKey.listMode)
            val sourceBounds =
                HomeCardProjectionParticipant.entries.associateWith { sourceParticipant ->
                    val sourceSlot = slot(sourceParticipant)
                    sourceSlot.previousBounds
                        ?.takeIf(HomeCardBounds::hasArea)
                        ?.takeIf { sourceSlot.previousLayoutKey == transaction.sourceKey }
                }
            stage =
                projectionGroup.beginStage(
                    transaction = transaction,
                    sourceBounds = sourceBounds,
                    edgeEntryEnabled = animateEdgeEntry,
                )
        }

        if (
            stage != null &&
            stage.requestId == callbackRequestId &&
            stage.targetKey == callbackKey
        ) {
            if (
                projectionGroup.recordMeasurement(
                    expectedStage = stage,
                    participant = participant,
                    bounds = bounds.takeIf(HomeCardBounds::hasArea),
                )
            ) {
                resolveStage(stage)
            }
        } else {
            val projectionSlot = slot(participant)
            projectionSlot.previousBounds = bounds
            projectionSlot.previousLayoutKey = callbackKey
        }
    }

    private fun resolveStage(stage: HomeCardProjectionGroupStage) {
        if (projectionGroup.pendingStage !== stage) return
        stageCard(stage)
        stageChild(stage, HomeCardProjectionParticipant.MEDIA, HomeChildScaleMode.UNIFORM)
        stageChild(stage, HomeCardProjectionParticipant.CONTENT, HomeChildScaleMode.NONE)
        stageChild(stage, HomeCardProjectionParticipant.ACTION, HomeChildScaleMode.NON_UNIFORM)
        stageTag(stage)
        if (projectionGroup.pendingStage !== stage) finalizeStage(stage)
    }

    private fun stageCard(stage: HomeCardProjectionGroupStage) {
        val participant = HomeCardProjectionParticipant.CARD
        if (!stage.hasMeasurement(participant) || stage.isParticipantReady(participant)) return
        val sourceBounds = stage.sourceBounds(participant)
        val targetBounds = stage.targetBounds(participant)
        val transform =
            calculateHomeCardInitialProjection(
                sourceBounds = sourceBounds,
                sourceTransform = stage.sourceTransforms[participant],
                targetBounds = targetBounds,
                edgeEntryRequired = stage.edgeEntryRequired,
            )
        projectionGroup.stageParticipant(stage, participant, transform)
    }

    private fun stageChild(
        stage: HomeCardProjectionGroupStage,
        participant: HomeCardProjectionParticipant,
        scaleMode: HomeChildScaleMode,
    ) {
        if (
            !stage.isParticipantReady(HomeCardProjectionParticipant.CARD) ||
            !stage.hasMeasurement(participant) ||
            stage.isParticipantReady(participant)
        ) {
            return
        }
        val sourceCardBounds = stage.sourceBounds(HomeCardProjectionParticipant.CARD)
        val targetCardBounds = stage.targetBounds(HomeCardProjectionParticipant.CARD)
        val visibleCardBounds =
            sourceCardBounds?.project(
                stage.sourceTransforms[HomeCardProjectionParticipant.CARD],
            )
        val sourceBounds = stage.sourceBounds(participant)
        val targetBounds = stage.targetBounds(participant)
        val transform =
            if (
                sourceCardBounds != null &&
                targetCardBounds != null &&
                visibleCardBounds != null &&
                sourceBounds != null &&
                targetBounds != null
            ) {
                val oldVisibleBounds =
                    sourceBounds.projectWithinCard(
                        cardBounds = sourceCardBounds,
                        visibleCardBounds = visibleCardBounds,
                        transform = stage.sourceTransforms[participant],
                    )
                when (scaleMode) {
                    HomeChildScaleMode.NONE ->
                        targetBounds.inversePositionProjectionWithinCard(
                            cardBounds = targetCardBounds,
                            visibleCardBounds = visibleCardBounds,
                            targetVisibleBounds = oldVisibleBounds,
                        )

                    HomeChildScaleMode.UNIFORM ->
                        targetBounds.inverseUniformScaleProjectionWithinCard(
                            cardBounds = targetCardBounds,
                            visibleCardBounds = visibleCardBounds,
                            targetVisibleBounds = oldVisibleBounds,
                        )

                    HomeChildScaleMode.NON_UNIFORM ->
                        targetBounds.inverseScaleProjectionWithinCard(
                            cardBounds = targetCardBounds,
                            visibleCardBounds = visibleCardBounds,
                            targetVisibleBounds = oldVisibleBounds,
                        )
                }
            } else {
                HomeCardLayoutTransform.Identity
            }
        projectionGroup.stageParticipant(stage, participant, transform)
    }

    private fun stageTag(stage: HomeCardProjectionGroupStage) {
        val participant = HomeCardProjectionParticipant.TAG
        if (
            !stage.isParticipantReady(HomeCardProjectionParticipant.CARD) ||
            !stage.isParticipantReady(HomeCardProjectionParticipant.MEDIA) ||
            !stage.hasMeasurement(participant) ||
            stage.isParticipantReady(participant)
        ) {
            return
        }
        val sourceCardBounds = stage.sourceBounds(HomeCardProjectionParticipant.CARD)
        val targetCardBounds = stage.targetBounds(HomeCardProjectionParticipant.CARD)
        val sourceMediaBounds = stage.sourceBounds(HomeCardProjectionParticipant.MEDIA)
        val targetMediaBounds = stage.targetBounds(HomeCardProjectionParticipant.MEDIA)
        val sourceBounds = stage.sourceBounds(participant)
        val targetBounds = stage.targetBounds(participant)
        val initialMediaTransform = stage.stagedTransform(HomeCardProjectionParticipant.MEDIA)
        val transform =
            if (
                sourceCardBounds != null &&
                targetCardBounds != null &&
                sourceMediaBounds != null &&
                targetMediaBounds != null &&
                sourceBounds != null &&
                targetBounds != null &&
                initialMediaTransform != null
            ) {
                calculateHomeTagProjection(
                    sourceBounds = sourceBounds,
                    targetBounds = targetBounds,
                    sourceTagTransform = stage.sourceTransforms[participant],
                    sourceMediaTransform = stage.sourceTransforms[HomeCardProjectionParticipant.MEDIA],
                    initialMediaTransform = initialMediaTransform,
                )
            } else {
                HomeCardLayoutTransform.Identity
            }
        projectionGroup.stageParticipant(stage, participant, transform)
    }

    private fun finalizeStage(stage: HomeCardProjectionGroupStage) {
        if (!transactions.commit(stage.transaction)) return
        HomeCardProjectionParticipant.entries.forEach { participant ->
            val projectionSlot = slot(participant)
            projectionSlot.previousBounds = stage.targetBounds(participant)
            projectionSlot.previousLayoutKey = stage.targetKey
            projectionSlot.consumedLayoutEpoch = stage.epoch
        }
    }

    fun settleMissingParticipants(stage: HomeCardProjectionGroupStage) {
        if (!projectionGroup.markMissingMeasurements(stage)) return
        resolveStage(stage)
        if (projectionGroup.pendingStage === stage) {
            projectionGroup.settleUnreadyParticipants(stage)
            if (projectionGroup.pendingStage !== stage) finalizeStage(stage)
        }
    }

    suspend fun animate(run: HomeCardProjectionGroupRun) {
        animate(
            initialValue = run.progress,
            targetValue = 1f,
            animationSpec =
                tween(
                    durationMillis = HOME_VIEW_CARD_LAYOUT_DURATION_MS,
                    easing = HOME_VIEW_LAYOUT_EASING,
                ),
        ) { value, _ ->
            projectionGroup.updateProgress(run, value)
        }
    }

    private fun currentCoverCorners(): HomeCoverCorners = coverCornerFrom.interpolateTo(coverCornerTo, projectionGroup.progress)

    private fun currentActionCorners(): HomeActionCorners = actionCornerFrom.interpolateTo(actionCornerTo, projectionGroup.progress)

    private fun applyProjectionToLayer(
        participant: HomeCardProjectionParticipant,
        layerScope: androidx.compose.ui.graphics.GraphicsLayerScope,
        parentTransform: HomeCardLayoutTransform? = null,
    ) {
        val visibleTransform = projectionGroup.currentTransform(participant)
        val parentScaleX = parentTransform?.scaleX ?: 1f
        val parentScaleY = parentTransform?.scaleY ?: 1f
        layerScope.transformOrigin = TransformOrigin(0f, 0f)
        layerScope.translationX = visibleTransform.translationX
        layerScope.translationY = visibleTransform.translationY
        layerScope.alpha =
            if (participant == HomeCardProjectionParticipant.CARD) {
                projectionGroup.currentCardAlpha()
            } else {
                1f
            }
        layerScope.scaleX = visibleTransform.scaleX / parentScaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        layerScope.scaleY = visibleTransform.scaleY / parentScaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
    }
}

internal enum class HomeChildScaleMode {
    NONE,
    UNIFORM,
    NON_UNIFORM,
}

internal data class HomeCardBounds(
    val position: Offset,
    val size: IntSize,
) {
    fun hasArea(): Boolean = size.width > 0 && size.height > 0

    fun project(transform: HomeCardLayoutTransform): HomeCardVisualBounds =
        HomeCardVisualBounds(
            position = position + Offset(transform.translationX, transform.translationY),
            width = size.width * transform.scaleX,
            height = size.height * transform.scaleY,
        )

    fun projectWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        transform: HomeCardLayoutTransform,
    ): HomeCardVisualBounds {
        val cardScaleX = visibleCardBounds.width / cardBounds.size.width
        val cardScaleY = visibleCardBounds.height / cardBounds.size.height
        return HomeCardVisualBounds(
            position =
                visibleCardBounds.position +
                    Offset(
                        (position.x - cardBounds.position.x + transform.translationX) * cardScaleX,
                        (position.y - cardBounds.position.y + transform.translationY) * cardScaleY,
                    ),
            width = size.width * transform.scaleX,
            height = size.height * transform.scaleY,
        )
    }

    fun inversePositionProjectionWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        targetVisibleBounds: HomeCardVisualBounds,
    ): HomeCardLayoutTransform {
        val cardScaleX = visibleCardBounds.width / cardBounds.size.width
        val cardScaleY = visibleCardBounds.height / cardBounds.size.height
        return HomeCardLayoutTransform(
            translationX =
                (targetVisibleBounds.position.x - visibleCardBounds.position.x) / cardScaleX -
                    (position.x - cardBounds.position.x),
            translationY =
                (targetVisibleBounds.position.y - visibleCardBounds.position.y) / cardScaleY -
                    (position.y - cardBounds.position.y),
            scaleX = 1f,
            scaleY = 1f,
        )
    }

    fun inverseUniformScaleProjectionWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        targetVisibleBounds: HomeCardVisualBounds,
    ): HomeCardLayoutTransform {
        val positionTransform =
            inversePositionProjectionWithinCard(
                cardBounds = cardBounds,
                visibleCardBounds = visibleCardBounds,
                targetVisibleBounds = targetVisibleBounds,
            )
        // Both grid and list covers are square. One shared scale factor keeps
        // that aspect ratio locked while restoring the Web-style size motion.
        val uniformScale = targetVisibleBounds.width / size.width
        return positionTransform.copy(
            scaleX = uniformScale,
            scaleY = uniformScale,
        )
    }

    fun inverseScaleProjectionWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        targetVisibleBounds: HomeCardVisualBounds,
    ): HomeCardLayoutTransform {
        val positionTransform =
            inversePositionProjectionWithinCard(
                cardBounds = cardBounds,
                visibleCardBounds = visibleCardBounds,
                targetVisibleBounds = targetVisibleBounds,
            )
        return positionTransform.copy(
            scaleX = targetVisibleBounds.width / size.width,
            scaleY = targetVisibleBounds.height / size.height,
        )
    }
}

internal data class HomeCardVisualBounds(
    val position: Offset,
    val width: Float,
    val height: Float,
) {
    fun hasArea(): Boolean = width > 0f && height > 0f
}

internal fun calculateHomeCardInitialProjection(
    sourceBounds: HomeCardBounds?,
    sourceTransform: HomeCardLayoutTransform,
    targetBounds: HomeCardBounds?,
    edgeEntryRequired: Boolean,
): HomeCardLayoutTransform {
    val visibleBounds = sourceBounds?.project(sourceTransform)
    return when {
        visibleBounds != null && targetBounds != null ->
            HomeCardLayoutTransform(
                translationX = visibleBounds.position.x - targetBounds.position.x,
                translationY = visibleBounds.position.y - targetBounds.position.y,
                scaleX = visibleBounds.width / targetBounds.size.width,
                scaleY = visibleBounds.height / targetBounds.size.height,
            )

        edgeEntryRequired && targetBounds != null ->
            HomeCardLayoutTransform.Identity.copy(
                translationY = targetBounds.size.height * HOME_VIEW_EDGE_ENTRY_OFFSET_FRACTION,
            )

        else -> HomeCardLayoutTransform.Identity
    }
}

internal fun calculateHomeTagProjection(
    sourceBounds: HomeCardBounds,
    targetBounds: HomeCardBounds,
    sourceTagTransform: HomeCardLayoutTransform,
    sourceMediaTransform: HomeCardLayoutTransform,
    initialMediaTransform: HomeCardLayoutTransform,
): HomeCardLayoutTransform {
    val sourceOffset =
        Offset(
            x = (sourceBounds.position.x + sourceTagTransform.translationX) * sourceMediaTransform.scaleX,
            y = (sourceBounds.position.y + sourceTagTransform.translationY) * sourceMediaTransform.scaleY,
        )
    val initialMediaScaleX = initialMediaTransform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
    val initialMediaScaleY = initialMediaTransform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
    val uniformScale =
        sourceBounds.size.width.toFloat() * sourceTagTransform.scaleX /
            targetBounds.size.width.toFloat()
    return HomeCardLayoutTransform(
        translationX = sourceOffset.x / initialMediaScaleX - targetBounds.position.x,
        translationY = sourceOffset.y / initialMediaScaleY - targetBounds.position.y,
        scaleX = uniformScale,
        scaleY = uniformScale,
    )
}

internal data class HomeCoverCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) {
    fun interpolateTo(
        target: HomeCoverCorners,
        progress: Float,
    ): HomeCoverCorners =
        HomeCoverCorners(
            topStart = topStart.interpolateTo(target.topStart, progress),
            topEnd = topEnd.interpolateTo(target.topEnd, progress),
            bottomEnd = bottomEnd.interpolateTo(target.bottomEnd, progress),
            bottomStart = bottomStart.interpolateTo(target.bottomStart, progress),
        )

    fun toProjectedShape(transform: HomeCardLayoutTransform): Shape =
        HomeProjectedRoundedCornerShape(
            topStart = topStart.project(transform),
            topEnd = topEnd.project(transform),
            bottomEnd = bottomEnd.project(transform),
            bottomStart = bottomStart.project(transform),
        )

    companion object {
        fun forCard(): HomeCoverCorners =
            HomeCoverCorners(
                topStart = HOME_COVER_CORNER_RADIUS,
                topEnd = HOME_COVER_CORNER_RADIUS,
                bottomEnd = HOME_COVER_CORNER_RADIUS,
                bottomStart = HOME_COVER_CORNER_RADIUS,
            )

        fun forListMode(listMode: Boolean): HomeCoverCorners =
            if (listMode) {
                HomeCoverCorners(
                    topStart = HOME_COVER_CORNER_RADIUS,
                    topEnd = WallHubSpacing.none,
                    bottomEnd = WallHubSpacing.none,
                    bottomStart = HOME_COVER_CORNER_RADIUS,
                )
            } else {
                HomeCoverCorners(
                    topStart = HOME_COVER_CORNER_RADIUS,
                    topEnd = HOME_COVER_CORNER_RADIUS,
                    bottomEnd = WallHubSpacing.none,
                    bottomStart = WallHubSpacing.none,
                )
            }
    }
}

internal fun Dp.interpolateTo(
    target: Dp,
    progress: Float,
): Dp = (value + (target.value - value) * progress.coerceIn(0f, 1f)).dp

internal fun Dp.project(transform: HomeCardLayoutTransform): HomeProjectedCornerRadius =
    HomeProjectedCornerRadius(
        horizontal = (value / transform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)).dp,
        vertical = (value / transform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)).dp,
    )

internal data class HomeActionCorners(
    val radius: Dp,
) {
    fun interpolateTo(
        target: HomeActionCorners,
        progress: Float,
    ): HomeActionCorners = HomeActionCorners(radius = radius.interpolateTo(target.radius, progress))

    fun toCoverCorners(): HomeCoverCorners =
        HomeCoverCorners(
            topStart = radius,
            topEnd = radius,
            bottomEnd = radius,
            bottomStart = radius,
        )

    companion object {
        fun forListMode(listMode: Boolean): HomeActionCorners =
            HomeActionCorners(
                radius =
                    if (listMode) {
                        LIST_CARD_ACTION_CORNER_RADIUS
                    } else {
                        GRID_CARD_ACTION_CORNER_RADIUS
                    },
            )
    }
}

internal data class HomeProjectedCornerRadius(
    val horizontal: Dp,
    val vertical: Dp,
)

internal data class HomeProjectedRoundedCornerShape(
    val topStart: HomeProjectedCornerRadius,
    val topEnd: HomeProjectedCornerRadius,
    val bottomEnd: HomeProjectedCornerRadius,
    val bottomStart: HomeProjectedCornerRadius,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        fun HomeProjectedCornerRadius.toCornerRadius(): CornerRadius =
            CornerRadius(
                x = with(density) { horizontal.toPx() }.coerceIn(0f, size.width / 2f),
                y = with(density) { vertical.toPx() }.coerceIn(0f, size.height / 2f),
            )
        val topLeft = if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
        val topRight = if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd
        return Outline.Rounded(
            RoundRect(
                0f,
                0f,
                size.width,
                size.height,
                topLeft.toCornerRadius(),
                topRight.toCornerRadius(),
                bottomRight.toCornerRadius(),
                bottomLeft.toCornerRadius(),
            ),
        )
    }
}

internal data class HomeCardLayoutTransform(
    val translationX: Float,
    val translationY: Float,
    val scaleX: Float,
    val scaleY: Float,
) {
    fun at(progress: Float): HomeCardLayoutTransform {
        val remaining = 1f - progress.coerceIn(0f, 1f)
        return HomeCardLayoutTransform(
            translationX = translationX * remaining,
            translationY = translationY * remaining,
            scaleX = 1f + (scaleX - 1f) * remaining,
            scaleY = 1f + (scaleY - 1f) * remaining,
        )
    }

    fun isVisible(): Boolean =
        abs(translationX) > HOME_VIEW_LAYOUT_POSITION_EPSILON_PX ||
            abs(translationY) > HOME_VIEW_LAYOUT_POSITION_EPSILON_PX ||
            abs(scaleX - 1f) > HOME_VIEW_LAYOUT_SCALE_EPSILON ||
            abs(scaleY - 1f) > HOME_VIEW_LAYOUT_SCALE_EPSILON

    companion object {
        val Identity =
            HomeCardLayoutTransform(
                translationX = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
            )
    }
}
