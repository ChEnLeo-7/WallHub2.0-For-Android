package com.wallhub.android.data.downloads

import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStagingManagerTest {
    @Test
    fun `discard reports failure while either managed directory remains`() {
        val root = Files.createTempDirectory("wallhub-staging-delete-test").toFile()
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val persistent = File(files, WORKSHOP_STAGING_DIRECTORY_NAME)
        val legacy = File(cache, WORKSHOP_STAGING_DIRECTORY_NAME)
        val manager = DownloadStagingManager(persistent, legacy, files, deleteDirectory = { false }, testOnly = Unit)
        val staging = File(manager.persistentRoot, "task").apply { mkdirs() }
        val sdk = requireNotNull(manager.sdkDirectory("task")).apply { mkdirs() }
        try {
            assertFalse(manager.discardTask("task", staging))
            assertTrue(staging.exists())
            assertTrue(sdk.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
