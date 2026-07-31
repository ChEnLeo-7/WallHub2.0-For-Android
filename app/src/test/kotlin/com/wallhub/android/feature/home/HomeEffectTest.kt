package com.wallhub.android.feature.home

import com.wallhub.android.core.model.WorkshopSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HomeEffectTest {
    @Test
    fun `download request resolves permission before repository work`() {
        val item = WorkshopSummary(id = 42L, title = "test", author = "creator")

        val effect = HomeAction.RequestDownload(item).immediateEffect()

        assertEquals(item, assertIs<HomeEffect.ResolveLegacyStoragePermission>(effect).item)
    }

    @Test
    fun `navigation copy and external actions preserve payloads`() {
        assertEquals(
            42L,
            assertIs<HomeEffect.OpenDetail>(HomeAction.OpenDetail(42L).immediateEffect()).workshopId,
        )
        assertEquals(
            "creator",
            assertIs<HomeEffect.SearchAuthor>(HomeAction.SearchAuthor("creator").immediateEffect()).creator,
        )
        assertEquals(
            "copied",
            assertIs<HomeEffect.CopyText>(
                HomeAction.CopyText("value", "copied").immediateEffect(),
            ).message,
        )
        assertEquals(
            42L,
            assertIs<HomeEffect.OpenSteam>(HomeAction.OpenSteam(42L).immediateEffect()).workshopId,
        )
    }

    @Test
    fun `permission result remains a view model stateful action`() {
        val item = WorkshopSummary(id = 42L, title = "test", author = "creator")

        assertNull(HomeAction.LegacyStoragePermissionResult(item, granted = false).immediateEffect())
    }
}
