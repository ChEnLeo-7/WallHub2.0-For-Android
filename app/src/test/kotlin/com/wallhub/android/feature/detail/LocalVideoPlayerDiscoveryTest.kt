package com.wallhub.android.feature.detail

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalVideoPlayerDiscoveryTest {
    @Test
    fun `declared project video wins over fallback traversal`() =
        runTest {
            val root = createTempDirectory("wallhub-video").toFile()
            try {
                val declared = File(root, "nested/declared.mp4").apply {
                    parentFile.mkdirs()
                    writeText("declared")
                }
                File(root, "fallback.webm").writeText("fallback")
                File(root, "project.json").writeText("""{"file":"nested/declared.mp4"}""")

                assertEquals(declared.canonicalFile, resolveVideoFile(root)?.canonicalFile)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `declared path cannot escape project root`() =
        runTest {
            val parent = createTempDirectory("wallhub-video-parent").toFile()
            try {
                val root = File(parent, "project").apply { mkdirs() }
                File(parent, "outside.mp4").writeText("outside")
                File(root, "project.json").writeText("""{"file":"../outside.mp4"}""")

                assertNull(resolveVideoFile(root))
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `fallback traversal cannot escape project root through symlink`() =
        runTest {
            val parent = createTempDirectory("wallhub-video-parent").toFile()
            try {
                val root = File(parent, "project").apply { mkdirs() }
                val outside = File(parent, "outside").apply { mkdirs() }
                File(outside, "outside.mp4").writeText("outside")
                Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())

                assertNull(resolveVideoFile(root))
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `oversized project metadata falls back to bounded tree discovery`() =
        runTest {
            val root = createTempDirectory("wallhub-video").toFile()
            try {
                File(root, "project.json").writeText("x".repeat(1024 * 1024 + 1))
                val fallback = File(root, "nested/fallback.mkv").apply {
                    parentFile.mkdirs()
                    writeText("fallback")
                }

                assertEquals(fallback.canonicalFile, resolveVideoFile(root)?.canonicalFile)
            } finally {
                root.deleteRecursively()
            }
        }
}
