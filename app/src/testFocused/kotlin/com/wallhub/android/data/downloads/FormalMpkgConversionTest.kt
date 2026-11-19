package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.PkgReader
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormalMpkgConversionTest {
    @Test
    fun `video workshop converts to an indexed MPKG with its media and project`() {
        val root = Files.createTempDirectory("wallhub-video-mpkg-focused").toFile()
        try {
            File(root, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "media").mkdirs()
            File(root, "media/demo.mp4").writeBytes(ByteArray(512) { it.toByte() })
            File(root, "project.json").writeText("{\"type\":\"video\",\"file\":\"media/demo.mp4\",\"title\":\"Demo\"}")
            val output = File(root, "output.mpkg")

            val report = WorkshopConverter.convert(root, output, "video")
            val archive = PkgReader.readIndex(output)

            assertEquals(output, report.outputFile)
            assertEquals(listOf("project.json", "preview.jpg", "media/demo.mp4").toSet(), archive.entries.map { it.path }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation preserves the previously published output and removes candidates`() {
        val root = Files.createTempDirectory("wallhub-video-mpkg-cancel-focused").toFile()
        try {
            File(root, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "demo.mp4").writeBytes(ByteArray(2 * 1024 * 1024) { it.toByte() })
            File(root, "project.json").writeText("{\"type\":\"video\",\"file\":\"demo.mp4\"}")
            val output = File(root, "output.mpkg")
            val previous = "published-output".toByteArray()
            output.writeBytes(previous)
            var checks = 0

            assertFailsWith<CancellationException> {
                WorkshopConverter.convert(
                    inputDir = root,
                    outputFile = output,
                    contentTypeHint = "video",
                    checkCancellation = {
                        checks += 1
                        if (checks >= 4) throw CancellationException("focused cancellation")
                    },
                )
            }

            assertContentEquals(previous, output.readBytes())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".output.mpkg.") })
        } finally {
            root.deleteRecursively()
        }
    }
}
