package com.wallhub.android.data.downloads

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalWallpaperScannerAssignmentTest {
    @Test
    fun `node is assigned to nearest nested project`() {
        val projects = setOf("collection", "collection/nested")

        assertEquals(
            "collection/nested",
            nearestProjectParent("collection/nested/assets/video.mp4", projects),
        )
    }

    @Test
    fun `node outside indexed projects remains standalone`() {
        assertNull(nearestProjectParent("standalone/video.mp4", setOf("collection")))
    }

    @Test
    fun `root project owns descendants`() {
        assertEquals("", nearestProjectParent("assets/video.mp4", setOf("")))
    }
}
