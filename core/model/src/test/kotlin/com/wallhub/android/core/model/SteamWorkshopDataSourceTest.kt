package com.wallhub.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SteamWorkshopDataSourceTest {
    @Test
    fun `community html is the default workshop data source`() {
        assertEquals(
            SteamWorkshopDataSource.COMMUNITY_HTML,
            AppPreferences().steamWorkshopDataSource,
        )
    }
}
