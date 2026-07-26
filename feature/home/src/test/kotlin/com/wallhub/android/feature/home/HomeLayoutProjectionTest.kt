package com.wallhub.android.feature.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class HomeLayoutProjectionTest {
    private val easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    @Test
    fun `layout key ignores hidden list columns but distinguishes one-column grid`() {
        val listFromTwoColumns = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 2)
        val listFromFourColumns = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 4)
        val oneColumnGrid = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 1)

        assertEquals(listFromTwoColumns, listFromFourColumns)
        assertNotEquals(listFromTwoColumns, oneColumnGrid)
        assertNotEquals(
            HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2),
            HomeCardLayoutKey.resolve(HomeViewMode.GRID, 3),
        )
    }

    @Test
    fun `latest request wins before measurement and epochs advance independently of transforms`() {
        val grid = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2)
        val list = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 2)
        val state = HomeLayoutTransactionState(grid)

        state.request(list)
        state.request(grid)
        assertNull(state.consumeForMeasurement())
        assertEquals(0L, state.epoch)

        state.request(list)
        val toList = checkNotNull(state.consumeForMeasurement())
        assertEquals(1L, toList.epoch)
        assertEquals(grid, toList.sourceKey)
        assertEquals(list, toList.targetKey)
        assertEquals(grid, state.measuredKey)
        assertTrue(state.commit(toList))

        state.request(grid)
        val backToGrid = checkNotNull(state.consumeForMeasurement())
        assertEquals(2L, backToGrid.epoch)
        assertEquals(list, backToGrid.sourceKey)
        assertEquals(grid, backToGrid.targetKey)
    }

    @Test
    fun `edge entry window remains active until the latest layout request completes`() {
        val grid = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2)
        val list = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 2)
        val state = HomeLayoutEdgeEntryState(grid)

        assertFalse(state.isActive)
        assertEquals(0L, state.update(grid))

        val firstRequest = state.update(list)
        assertTrue(state.isActive)
        assertFalse(state.complete(firstRequest - 1L))

        val latestRequest = state.update(grid)
        assertTrue(state.isActive)
        assertFalse(state.complete(firstRequest))
        assertTrue(state.isActive)
        assertTrue(state.complete(latestRequest))
        assertFalse(state.isActive)
    }

    @Test
    fun `layout switch card without source bounds uses edge entry projection`() {
        val grid = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2)
        val list = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 2)
        val transaction = HomeLayoutTransaction(
            epoch = 1L,
            requestId = 1L,
            sourceKey = grid,
            targetKey = list,
        )
        val sourceBounds = HomeCardProjectionParticipant.entries.associateWith { null }
        val stage = HomeCardProjectionGroupState().beginStage(
            transaction = transaction,
            sourceBounds = sourceBounds,
            edgeEntryEnabled = true,
        )
        val targetBounds = bounds(16f, 620f, 360, 104)

        assertTrue(stage.edgeEntryRequired)
        assertClose(0f, stage.sourceCardAlpha)
        assertTransformClose(
            HomeCardLayoutTransform.Identity.copy(translationY = 104f * 0.08f),
            calculateHomeCardInitialProjection(
                sourceBounds = null,
                sourceTransform = HomeCardLayoutTransform.Identity,
                targetBounds = targetBounds,
                edgeEntryRequired = stage.edgeEntryRequired,
            ),
        )
        assertTransformClose(
            HomeCardLayoutTransform.Identity,
            calculateHomeCardInitialProjection(
                sourceBounds = null,
                sourceTransform = HomeCardLayoutTransform.Identity,
                targetBounds = targetBounds,
                edgeEntryRequired = false,
            ),
        )
    }

    @Test
    fun `edge entry does not replace projection for a positioned card`() {
        val grid = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2)
        val list = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 2)
        val sourceCard = bounds(16f, 420f, 180, 264)
        val targetCard = bounds(16f, 420f, 360, 104)
        val sourceBounds = HomeCardProjectionParticipant.entries.associateWith { participant ->
            sourceCard.takeIf { participant == HomeCardProjectionParticipant.CARD }
        }
        val stage = HomeCardProjectionGroupState().beginStage(
            transaction = HomeLayoutTransaction(
                epoch = 1L,
                requestId = 1L,
                sourceKey = grid,
                targetKey = list,
            ),
            sourceBounds = sourceBounds,
            edgeEntryEnabled = true,
        )
        val transform = calculateHomeCardInitialProjection(
            sourceBounds = sourceCard,
            sourceTransform = HomeCardLayoutTransform.Identity,
            targetBounds = targetCard,
            edgeEntryRequired = stage.edgeEntryRequired,
        )

        assertFalse(stage.edgeEntryRequired)
        assertClose(1f, stage.sourceCardAlpha)
        assertVisualBoundsClose(
            sourceCard.project(HomeCardLayoutTransform.Identity),
            targetCard.project(transform),
        )
    }

    @Test
    fun `group commits atomically after participants arrive in arbitrary order`() {
        val group = HomeCardProjectionGroupState()
        val originalRun = group.activeRun
        val stage = beginStage(group, epoch = 1L, requestId = 1L)
        val order = listOf(
            HomeCardProjectionParticipant.TAG,
            HomeCardProjectionParticipant.ACTION,
            HomeCardProjectionParticipant.CARD,
            HomeCardProjectionParticipant.CONTENT,
            HomeCardProjectionParticipant.MEDIA,
        )
        val expectedTransforms = participantTransforms(40f)

        order.dropLast(1).forEach { participant ->
            assertTrue(group.stageParticipant(stage, participant, expectedTransforms[participant]))
            assertSame(originalRun, group.activeRun)
            assertSame(stage, group.pendingStage)
            assertClose(0f, group.progress)
        }

        val lastParticipant = order.last()
        assertTrue(group.stageParticipant(stage, lastParticipant, expectedTransforms[lastParticipant]))
        assertNull(group.pendingStage)
        assertNotSame(originalRun, group.activeRun)
        assertEquals(1L, group.activeRun.epoch)
        assertClose(0f, group.activeRun.progress)
        HomeCardProjectionParticipant.entries.forEach { participant ->
            assertTransformClose(expectedTransforms[participant], group.activeRun.transforms[participant])
        }
    }

    @Test
    fun `cross-frame staging freezes source and uses one shared progress`() {
        val group = HomeCardProjectionGroupState()
        val firstTransforms = participantTransforms(20f)
        group.startStandalone(firstTransforms, cardInitialAlpha = 1f)
        val firstRun = group.activeRun
        assertTrue(group.updateProgress(firstRun, 0.25f))
        val frozenSource = group.captureCurrentTransforms()
        val stage = beginStage(group, epoch = 1L, requestId = 1L)
        val nextTransforms = participantTransforms(80f)

        assertTrue(
            group.stageParticipant(
                stage,
                HomeCardProjectionParticipant.CARD,
                nextTransforms[HomeCardProjectionParticipant.CARD],
            ),
        )
        assertFalse(group.updateProgress(firstRun, 0.9f))
        assertSame(firstRun, group.activeRun)
        assertClose(0f, group.progress)
        assertTransformClose(
            nextTransforms[HomeCardProjectionParticipant.CARD],
            group.currentTransform(HomeCardProjectionParticipant.CARD),
        )
        assertTransformClose(
            frozenSource[HomeCardProjectionParticipant.MEDIA],
            group.currentTransform(HomeCardProjectionParticipant.MEDIA),
        )

        HomeCardProjectionParticipant.entries
            .filterNot { it == HomeCardProjectionParticipant.CARD }
            .forEach { participant ->
                group.stageParticipant(stage, participant, nextTransforms[participant])
            }
        val committedRun = group.activeRun
        assertNotSame(firstRun, committedRun)
        assertTrue(group.updateProgress(committedRun, 0.5f))
        HomeCardProjectionParticipant.entries.forEach { participant ->
            assertTransformClose(
                nextTransforms[participant].at(0.5f),
                group.currentTransform(participant),
            )
        }
    }

    @Test
    fun `late callback from replaced epoch cannot enter current stage`() {
        val group = HomeCardProjectionGroupState()
        val staleStage = beginStage(group, epoch = 1L, requestId = 1L)
        val currentStage = beginStage(group, epoch = 2L, requestId = 2L)

        assertFalse(
            group.stageParticipant(
                staleStage,
                HomeCardProjectionParticipant.CARD,
                HomeCardLayoutTransform(90f, 0f, 0.5f, 0.5f),
            ),
        )
        assertSame(currentStage, group.pendingStage)
        assertFalse(currentStage.isParticipantReady(HomeCardProjectionParticipant.CARD))
    }

    @Test
    fun `identity and missing participants explicitly complete the epoch`() {
        val group = HomeCardProjectionGroupState()
        val stage = beginStage(group, epoch = 1L, requestId = 1L)

        assertTrue(
            group.stageParticipant(
                stage,
                HomeCardProjectionParticipant.CARD,
                HomeCardLayoutTransform.Identity,
            ),
        )
        assertTrue(group.markMissingMeasurements(stage))
        assertTrue(group.settleUnreadyParticipants(stage))

        assertNull(group.pendingStage)
        assertTrue(group.activeRun.shouldAnimate)
        assertClose(0f, group.activeRun.progress)
        HomeCardProjectionParticipant.entries.forEach { participant ->
            assertTransformClose(HomeCardLayoutTransform.Identity, group.activeRun.transforms[participant])
        }
    }

    @Test
    fun `68ms reverse captures every participant from one presentation boundary`() {
        val group = HomeCardProjectionGroupState()
        val outboundTransforms = participantTransforms(64f)
        group.startStandalone(outboundTransforms, cardInitialAlpha = 1f)
        val outboundRun = group.activeRun
        val progressAtReverse = easing.transform(68f / 400f)
        assertTrue(group.updateProgress(outboundRun, progressAtReverse))
        val presentationAtReverse = group.captureCurrentTransforms()
        val reverseStage = beginStage(group, epoch = 1L, requestId = 1L)

        HomeCardProjectionParticipant.entries.forEach { participant ->
            group.stageParticipant(reverseStage, participant, presentationAtReverse[participant])
        }

        val reverseRun = group.activeRun
        assertFalse(group.updateProgress(outboundRun, 1f))
        HomeCardProjectionParticipant.entries.forEach { participant ->
            assertTransformClose(
                presentationAtReverse[participant],
                group.currentTransform(participant),
            )
            assertTransformClose(
                presentationAtReverse[participant],
                reverseRun.transforms[participant],
            )
        }
    }

    @Test
    fun `card reverse is presentation-continuous at required interruption times`() {
        val grid = bounds(16f, 24f, 180, 264)
        val list = bounds(16f, 24f, 360, 104)
        val gridToList = inverseCardProjection(grid.project(HomeCardLayoutTransform.Identity), list)

        listOf(16, 68, 80, 200, 384).forEach { elapsedMillis ->
            val progress = easing.transform(elapsedMillis / 400f)
            val visibleBeforeReverse = list.project(gridToList.at(progress))
            val listToGrid = inverseCardProjection(visibleBeforeReverse, grid)
            val visibleAfterReverse = grid.project(listToGrid.at(0f))

            assertVisualBoundsClose(visibleBeforeReverse, visibleAfterReverse)
        }
    }

    @Test
    fun `rapid alternating sequence preserves every presented frame`() {
        val grid = bounds(16f, 24f, 180, 264)
        val list = bounds(16f, 24f, 360, 104)
        val intervals = listOf(0, 8, 16, 33, 68, 80, 200)
        val targets = listOf(list, grid, list, grid, list, grid, list)
        var activeTarget = grid
        var activeTransform = HomeCardLayoutTransform.Identity

        intervals.zip(targets).forEach { (elapsedMillis, nextTarget) ->
            val progress = easing.transform(elapsedMillis / 400f)
            val presented = activeTarget.project(activeTransform.at(progress))
            val redirectedTransform = inverseCardProjection(presented, nextTarget)

            assertVisualBoundsClose(presented, nextTarget.project(redirectedTransform.at(0f)))
            activeTarget = nextTarget
            activeTransform = redirectedTransform
        }

        assertEquals(list, activeTarget)
    }

    @Test
    fun `two to three to four columns uses distinct epochs and continuous bounds`() {
        val key2 = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2)
        val key3 = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 3)
        val key4 = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 4)
        val state = HomeLayoutTransactionState(key2)
        val layouts = listOf(
            bounds(16f, 294f, 180, 264),
            bounds(258f, 190f, 113, 202),
            bounds(292f, 138f, 82, 174),
        )
        var activeTarget = layouts.first()
        var activeTransform = HomeCardLayoutTransform.Identity

        listOf(key3, key4).zip(layouts.drop(1)).forEachIndexed { index, (key, nextBounds) ->
            state.request(key)
            assertEquals((index + 1).toLong(), checkNotNull(state.consumeForMeasurement()).epoch)
            val presented = activeTarget.project(activeTransform.at(easing.transform(80f / 400f)))
            activeTransform = inverseCardProjection(presented, nextBounds)
            assertVisualBoundsClose(presented, nextBounds.project(activeTransform.at(0f)))
            activeTarget = nextBounds
        }
    }

    @Test
    fun `media content and action keep their established compensation paths`() {
        val sourceCard = bounds(16f, 50f, 180, 264)
        val visibleCard = sourceCard.project(HomeCardLayoutTransform.Identity)
        val targetCard = bounds(16f, 50f, 360, 104)

        val sourceMedia = bounds(16f, 50f, 180, 180)
        val targetMedia = bounds(16f, 50f, 104, 104)
        val visibleMedia = sourceMedia.projectWithinCard(
            cardBounds = sourceCard,
            visibleCardBounds = visibleCard,
            transform = HomeCardLayoutTransform.Identity,
        )
        val mediaTransform = targetMedia.inverseUniformScaleProjectionWithinCard(
            cardBounds = targetCard,
            visibleCardBounds = visibleCard,
            targetVisibleBounds = visibleMedia,
        )
        assertVisualBoundsClose(
            visibleMedia,
            targetMedia.projectWithinCard(targetCard, visibleCard, mediaTransform),
        )

        val sourceContent = bounds(26f, 240f, 160, 54)
        val targetContent = bounds(132f, 58f, 184, 54)
        val visibleContent = sourceContent.projectWithinCard(
            sourceCard,
            visibleCard,
            HomeCardLayoutTransform.Identity,
        )
        val contentTransform = targetContent.inversePositionProjectionWithinCard(
            targetCard,
            visibleCard,
            visibleContent,
        )
        val projectedContent = targetContent.projectWithinCard(targetCard, visibleCard, contentTransform)
        assertOffsetClose(visibleContent.position, projectedContent.position)

        val sourceAction = bounds(26f, 304f, 160, 40)
        val targetAction = bounds(326f, 82f, 40, 40)
        val visibleAction = sourceAction.projectWithinCard(
            sourceCard,
            visibleCard,
            HomeCardLayoutTransform.Identity,
        )
        val actionTransform = targetAction.inverseScaleProjectionWithinCard(
            targetCard,
            visibleCard,
            visibleAction,
        )
        assertVisualBoundsClose(
            visibleAction,
            targetAction.projectWithinCard(targetCard, visibleCard, actionTransform),
        )
    }

    @Test
    fun `tag projection preserves media-local presentation`() {
        val sourceBounds = bounds(8f, 8f, 52, 24)
        val targetBounds = bounds(6f, 6f, 38, 18)
        val sourceTagTransform = HomeCardLayoutTransform(2f, 3f, 0.9f, 0.9f)
        val sourceMediaTransform = HomeCardLayoutTransform(0f, 0f, 0.76f, 0.76f)
        val initialMediaTransform = HomeCardLayoutTransform(0f, 0f, 1.18f, 1.18f)
        val projected = calculateHomeTagProjection(
            sourceBounds,
            targetBounds,
            sourceTagTransform,
            sourceMediaTransform,
            initialMediaTransform,
        )

        val sourceOffset = Offset(
            (sourceBounds.position.x + sourceTagTransform.translationX) * sourceMediaTransform.scaleX,
            (sourceBounds.position.y + sourceTagTransform.translationY) * sourceMediaTransform.scaleY,
        )
        val targetOffset = Offset(
            (targetBounds.position.x + projected.translationX) * initialMediaTransform.scaleX,
            (targetBounds.position.y + projected.translationY) * initialMediaTransform.scaleY,
        )
        assertOffsetClose(sourceOffset, targetOffset)
        assertClose(
            sourceBounds.size.width * sourceTagTransform.scaleX,
            targetBounds.size.width * projected.scaleX,
        )
    }

    @Test
    fun `scroll offset during interruption remains in presentation capture`() {
        val oldTarget = bounds(16f, -137f, 180, 264)
        val nextTarget = bounds(16f, -221f, 360, 104)
        val activeTransform = HomeCardLayoutTransform(42f, 96f, 1.4f, 0.72f)
        val presented = oldTarget.project(activeTransform.at(easing.transform(200f / 400f)))
        val redirected = inverseCardProjection(presented, nextTarget)

        assertVisualBoundsClose(presented, nextTarget.project(redirected.at(0f)))
    }

    private fun beginStage(
        group: HomeCardProjectionGroupState,
        epoch: Long,
        requestId: Long,
    ): HomeCardProjectionGroupStage = group.beginStage(
        transaction = HomeLayoutTransaction(
            epoch = epoch,
            requestId = requestId,
            sourceKey = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2),
            targetKey = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 2),
        ),
        sourceBounds = HomeCardProjectionParticipant.entries.associateWith { null },
    )

    private fun participantTransforms(seed: Float): HomeCardProjectionTransforms {
        var transforms = HomeCardProjectionTransforms.Identity
        HomeCardProjectionParticipant.entries.forEachIndexed { index, participant ->
            transforms = transforms.with(
                participant,
                HomeCardLayoutTransform(
                    translationX = seed + index * 11f,
                    translationY = -seed / 2f + index * 7f,
                    scaleX = 0.62f + index * 0.07f,
                    scaleY = 1.28f - index * 0.06f,
                ),
            )
        }
        return transforms
    }

    private fun bounds(x: Float, y: Float, width: Int, height: Int): HomeCardBounds = HomeCardBounds(
        position = Offset(x, y),
        size = IntSize(width, height),
    )

    private fun inverseCardProjection(
        presented: HomeCardVisualBounds,
        target: HomeCardBounds,
    ): HomeCardLayoutTransform = HomeCardLayoutTransform(
        translationX = presented.position.x - target.position.x,
        translationY = presented.position.y - target.position.y,
        scaleX = presented.width / target.size.width,
        scaleY = presented.height / target.size.height,
    )

    private fun assertVisualBoundsClose(expected: HomeCardVisualBounds, actual: HomeCardVisualBounds) {
        assertOffsetClose(expected.position, actual.position)
        assertClose(expected.width, actual.width)
        assertClose(expected.height, actual.height)
    }

    private fun assertTransformClose(expected: HomeCardLayoutTransform, actual: HomeCardLayoutTransform) {
        assertClose(expected.translationX, actual.translationX)
        assertClose(expected.translationY, actual.translationY)
        assertClose(expected.scaleX, actual.scaleX)
        assertClose(expected.scaleY, actual.scaleY)
    }

    private fun assertOffsetClose(expected: Offset, actual: Offset) {
        assertClose(expected.x, actual.x)
        assertClose(expected.y, actual.y)
    }

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "Expected $expected, actual $actual, tolerance $tolerance",
        )
    }
}
