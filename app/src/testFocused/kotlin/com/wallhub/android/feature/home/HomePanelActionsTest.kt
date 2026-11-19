package com.wallhub.android.feature.home

import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePanelActionsTest {
    @Test
    fun downloadDefaultOnVideoShowsPlayAsSecondaryAndOpenWorkshopAsTertiary() {
        val actions = homePanelActions(HomeCardAction.DOWNLOAD, WorkshopType.VIDEO)
        assertEquals(HomeCardAction.DOWNLOAD, actions.primary)
        assertEquals(HomeCardAction.PLAY_VIDEO, actions.typeAction)
        assertTrue(actions.showOpenWorkshop)
    }

    @Test
    fun playDefaultOnVideoDoesNotDuplicatePlay() {
        val actions = homePanelActions(HomeCardAction.PLAY_VIDEO, WorkshopType.VIDEO)
        assertEquals(HomeCardAction.PLAY_VIDEO, actions.primary)
        assertNull(actions.typeAction)
        assertTrue(actions.showOpenWorkshop)
    }

    @Test
    fun downloadDefaultOnSceneDoesNotDuplicateDownload() {
        val actions = homePanelActions(HomeCardAction.DOWNLOAD, WorkshopType.SCENE)
        assertEquals(HomeCardAction.DOWNLOAD, actions.primary)
        assertNull(actions.typeAction)
        assertTrue(actions.showOpenWorkshop)
    }

    @Test
    fun playDefaultOnSceneFallsBackToDownload() {
        val actions = homePanelActions(HomeCardAction.PLAY_VIDEO, WorkshopType.SCENE)
        assertEquals(HomeCardAction.DOWNLOAD, actions.primary)
        assertNull(actions.typeAction)
        assertTrue(actions.showOpenWorkshop)
    }

    @Test
    fun openSteamDefaultOnVideoHidesTheDuplicateOpenButton() {
        val actions = homePanelActions(HomeCardAction.OPEN_STEAM, WorkshopType.VIDEO)
        assertEquals(HomeCardAction.OPEN_STEAM, actions.primary)
        assertEquals(HomeCardAction.PLAY_VIDEO, actions.typeAction)
        assertFalse(actions.showOpenWorkshop)
    }

    @Test
    fun openSteamDefaultOnSceneShowsDownloadAsSecondary() {
        val actions = homePanelActions(HomeCardAction.OPEN_STEAM, WorkshopType.SCENE)
        assertEquals(HomeCardAction.OPEN_STEAM, actions.primary)
        assertEquals(HomeCardAction.DOWNLOAD, actions.typeAction)
        assertFalse(actions.showOpenWorkshop)
    }
}
