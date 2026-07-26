package com.wallhub.android.feature.local

import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperImportState
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalWallpaperUiStateTest {
    @Test
    fun `filters resources by format favorite tag and search`() {
        val state = LocalWallpaperUiState(
            scan = LocalWallpaperScanSnapshot(
                resources = listOf(
                    resource(
                        id = "mpkg",
                        title = "Rainy Room",
                        format = LocalWallpaperFormat.MPKG,
                        favorite = true,
                        tags = setOf("calm"),
                    ),
                    resource(
                        id = "html",
                        title = "Portfolio",
                        format = LocalWallpaperFormat.HTML,
                    ),
                ),
            ),
            formatFilter = LocalWallpaperFormatFilter.MPKG,
            favoriteOnly = true,
            selectedTag = "calm",
            searchQuery = "rain",
        )

        assertEquals(listOf("mpkg"), state.resources.map(LocalWallpaperResource::id))
        assertEquals(3, state.activeFilterCount)
    }

    @Test
    fun `import filter distinguishes requested resources`() {
        val requested = resource(
            id = "requested",
            title = "Requested",
            importRequestedAt = 42L,
        )
        val notImported = resource(id = "new", title = "New")

        val state = LocalWallpaperUiState(
            scan = LocalWallpaperScanSnapshot(resources = listOf(requested, notImported)),
            importFilter = LocalWallpaperImportFilter.IMPORT_REQUESTED,
        )

        assertEquals(LocalWallpaperImportState.IMPORT_REQUESTED, requested.importState)
        assertEquals(listOf("requested"), state.resources.map(LocalWallpaperResource::id))
    }

    @Test
    fun `empty filtered result reports no matching resources`() {
        val state = LocalWallpaperUiState(
            scan = LocalWallpaperScanSnapshot(resources = listOf(resource(id = "one", title = "One"))),
            searchQuery = "missing",
        )

        assertTrue(state.resources.isEmpty())
        assertEquals("没有符合条件的本地资源", state.summary)
    }

    private fun resource(
        id: String,
        title: String,
        format: LocalWallpaperFormat = LocalWallpaperFormat.MPKG,
        favorite: Boolean = false,
        tags: Set<String> = emptySet(),
        importRequestedAt: Long? = null,
    ) = LocalWallpaperResource(
        id = id,
        contentUri = "content://wallhub/$id",
        displayName = "$title.file",
        title = title,
        format = format,
        sourceId = "download",
        sourceLabel = "Download/WallHub",
        relativePath = "$title.file",
        detectionReason = "test",
        isFavorite = favorite,
        tags = tags,
        importRequestedAt = importRequestedAt,
    )
}
