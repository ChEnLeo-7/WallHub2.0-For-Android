package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotChunkSpec
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamDownloadCheckpointTest {
    @Test
    fun `committed offsets survive reload when manifest shape is unchanged`() {
        val directory = Files.createTempDirectory("wallhub-checkpoint").toFile()
        try {
            val chunks = chunks()
            val checkpoint = SteamDownloadCheckpoint(directory, manifestId = 42L)
            directory.resolve("wallpaper.bin.wallhub.part").createNewFile()
            checkpoint.save("wallpaper.bin", chunks, setOf(0L))

            assertEquals(setOf(0L), checkpoint.load("wallpaper.bin", chunks))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `changed manifest invalidates the checkpoint`() {
        val directory = Files.createTempDirectory("wallhub-checkpoint").toFile()
        try {
            val original = chunks()
            directory.resolve("wallpaper.bin.wallhub.part").createNewFile()
            SteamDownloadCheckpoint(directory, manifestId = 42L).save("wallpaper.bin", original, setOf(0L))

            assertNull(
                SteamDownloadCheckpoint(directory, manifestId = 43L).load("wallpaper.bin", original),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `playback activity blocks background work until released`() =
        runTest {
            val coordinator = PlaybackDownloadCoordinator()
            val lease = coordinator.registerPlayback()
            val waiter = async { coordinator.awaitBackgroundWorkAllowed() }

            assertTrue(!waiter.isCompleted)
            lease.close()
            waiter.await()
            assertTrue(waiter.isCompleted)
        }

    private fun chunks() =
        listOf(
            DepotChunkSpec(
                chunkId = byteArrayOf(1, 2, 3),
                checksum = 7,
                offset = 0L,
                compressedLength = 4,
                uncompressedLength = 8,
            ),
            DepotChunkSpec(
                chunkId = byteArrayOf(4, 5, 6),
                checksum = 8,
                offset = 8L,
                compressedLength = 5,
                uncompressedLength = 9,
            ),
        )
}
