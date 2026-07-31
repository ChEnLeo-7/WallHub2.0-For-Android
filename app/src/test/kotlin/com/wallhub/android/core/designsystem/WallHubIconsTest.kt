package com.wallhub.android.core.designsystem

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallHubIconsTest {
    @Test
    fun allWallHubIconsUseMaterialVectors() {
        val icons =
            listOf(
                WallHubIcons.ArrowLeft,
                WallHubIcons.Bell,
                WallHubIcons.Bookmark,
                WallHubIcons.Check,
                WallHubIcons.ChevronDown,
                WallHubIcons.ChevronLeft,
                WallHubIcons.ChevronRight,
                WallHubIcons.CircleX,
                WallHubIcons.Compass,
                WallHubIcons.Copy,
                WallHubIcons.Download,
                WallHubIcons.ExternalLink,
                WallHubIcons.Filter,
                WallHubIcons.FolderOpen,
                WallHubIcons.Filled.FolderOpen,
                WallHubIcons.Grid3X3,
                WallHubIcons.Heart,
                WallHubIcons.ImageOff,
                WallHubIcons.Languages,
                WallHubIcons.List,
                WallHubIcons.LockKeyhole,
                WallHubIcons.LogOut,
                WallHubIcons.Moon,
                WallHubIcons.Palette,
                WallHubIcons.Pause,
                WallHubIcons.Play,
                WallHubIcons.RotateCw,
                WallHubIcons.Search,
                WallHubIcons.Settings,
                WallHubIcons.SlidersHorizontal,
                WallHubIcons.Smartphone,
                WallHubIcons.Star,
                WallHubIcons.Trash2,
                WallHubIcons.Upload,
                WallHubIcons.UserRound,
                WallHubIcons.VerticalAlignTop,
            )

        icons.forEach(::assertMaterialViewport)
    }

    private fun assertMaterialViewport(icon: ImageVector) {
        assertEquals(24f, icon.viewportWidth)
        assertEquals(24f, icon.viewportHeight)
        val paths = icon.root.filterIsInstance<VectorPath>()
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.pathData.isNotEmpty() })
        assertTrue(paths.all { it.fill != null || it.stroke != null })
    }
}
