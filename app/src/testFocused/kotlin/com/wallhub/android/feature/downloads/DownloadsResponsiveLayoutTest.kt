package com.wallhub.android.feature.downloads

import com.wallhub.android.core.designsystem.WallHubWindowLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsResponsiveLayoutTest {
    @Test
    fun `wide cards use a grid only in expanded windows`() {
        assertFalse(useExpandedDownloadGrid(WallHubWindowLayout.COMPACT, DownloadViewMode.WIDE))
        assertFalse(useExpandedDownloadGrid(WallHubWindowLayout.MEDIUM, DownloadViewMode.WIDE))
        assertFalse(useExpandedDownloadGrid(WallHubWindowLayout.EXPANDED, DownloadViewMode.SQUARE))
        assertTrue(useExpandedDownloadGrid(WallHubWindowLayout.EXPANDED, DownloadViewMode.WIDE))
    }
}
