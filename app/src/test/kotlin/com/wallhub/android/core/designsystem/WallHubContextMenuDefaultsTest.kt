package com.wallhub.android.core.designsystem

import androidx.compose.ui.unit.dp
import com.wallhub.android.core.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class WallHubContextMenuDefaultsTest {
    @Test
    fun `menu width follows card width and shared language caps`() {
        assertEquals(
            144.dp,
            WallHubContextMenuDefaults.menuWidth(cardWidth = 120.dp, language = AppLanguage.ZH),
        )
        assertEquals(
            188.dp,
            WallHubContextMenuDefaults.menuWidth(cardWidth = 200.dp, language = AppLanguage.ZH),
        )
        assertEquals(
            204.dp,
            WallHubContextMenuDefaults.menuWidth(cardWidth = 320.dp, language = AppLanguage.ZH),
        )
        assertEquals(
            220.dp,
            WallHubContextMenuDefaults.menuWidth(cardWidth = 320.dp, language = AppLanguage.EN),
        )
        assertEquals(
            204.dp,
            WallHubContextMenuDefaults.menuWidth(cardWidth = null, language = AppLanguage.ZH),
        )
    }
}
