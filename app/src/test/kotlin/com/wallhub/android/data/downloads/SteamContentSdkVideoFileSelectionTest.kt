package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotFileFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun file(
    path: String,
    size: Long,
    flags: Int = 0,
): SteamContentSdkManifestFile = SteamContentSdkManifestFile(path = path, size = size, flags = flags)

class SteamContentSdkVideoFileSelectionTest {
    @Test
    fun theLargestVideoFileIsChosen() {
        val selected =
            selectStreamableVideoFile(
                listOf(
                    file("preview.jpg", 5_000),
                    file("video/small.mp4", 1_000),
                    file("video/large.webm", 9_000),
                    file("readme.txt", 20_000),
                ),
            )

        assertEquals("video/large.webm", selected?.path)
        assertEquals(9_000L, selected?.size)
    }

    @Test
    fun aDirectoryOrSymlinkNamedLikeAVideoIsNotChosen() {
        val selected =
            selectStreamableVideoFile(
                listOf(
                    file("video/folder.mp4", 90_000, DepotFileFlag.Directory.code),
                    file("video/link.mp4", 80_000, DepotFileFlag.Symlink.code),
                    file("video/real.mp4", 1_000),
                ),
            )

        assertEquals("video/real.mp4", selected?.path)
    }

    @Test
    fun anotherFlagBesideTheVideoExtensionIsStillAFile() {
        val selected =
            selectStreamableVideoFile(
                listOf(file("video/readonly.mp4", 10L, DepotFileFlag.ReadOnly.code)),
            )

        assertEquals("video/readonly.mp4", selected?.path)
    }

    @Test
    fun aManifestWithNoVideoFileHasNoSelection() {
        assertNull(selectStreamableVideoFile(emptyList()))
        assertNull(selectStreamableVideoFile(listOf(file("scene.pkg", 10L), file("preview.jpg", 20L))))
    }
}
