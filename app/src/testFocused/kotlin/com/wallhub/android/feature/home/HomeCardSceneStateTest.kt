package com.wallhub.android.feature.home

import androidx.compose.ui.unit.dp
import com.wallhub.android.core.model.HomeCardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardSceneStateTest {
    @Test
    fun onlyOneCardCanBeActive() {
        val scene = HomeCardSceneState()
        scene.open(itemId = 1L)
        scene.open(itemId = 2L)
        assertEquals(1L, scene.activeItemId)
    }

    @Test
    fun openWithoutMeasuredSlotStillOpensTheQuickSheet() {
        val scene = HomeCardSceneState()
        scene.open(itemId = 9L)
        assertEquals(9L, scene.activeItemId)
    }

    @Test
    fun closeClearsTheActiveCardState() {
        val scene = HomeCardSceneState()
        scene.open(itemId = 1L)
        scene.requestClose()
        assertTrue(scene.closing)
        assertEquals(1L, scene.activeItemId)
        scene.finishClose()
        assertNull(scene.activeItemId)
        assertFalse(scene.closing)
    }

    @Test
    fun hostContentIsPublishedAndCleared() {
        val scene = HomeCardSceneState()
        val content =
            HomeCardHostContent(
                items = emptyList(),
                authorDisplayNames = emptyMap(),
                layoutKey = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2),
                metrics = HomeCardMetrics(0.dp, 0.dp, 0.dp),
                coverSizePx = 0,
                action = HomeCardAction.DOWNLOAD,
                onOpenDetail = {},
                onPrimaryAction = {},
                onDownload = {},
                onSearchAuthor = {},
                onCopyText = { _, _ -> },
                onOpenSteam = {},
                onAuthorNameRequested = {},
            )
        scene.updateHostContent(content)
        assertEquals(content, scene.hostContent)
        scene.clearHostContent()
        assertNull(scene.hostContent)
    }
}
