package com.wallhub.android.data.downloads

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two decisions that make a pause keep its data and a resume continue from it.
 *
 * The SDK reports committed bytes per run, so a resumed run's own counter starts at zero even though its
 * journal already covers the checkpoint the paused run reached. The task's durable reading therefore has
 * to be published against that checkpoint, or a resume reads as a restart. And the partial bytes really
 * live in the SDK's own staging directory, so a cancel that removes only the app's destination leaves the
 * checkpoint a later retry resumes from.
 */
class DownloadResumeCheckpointTest {
    @Test
    fun `a resumed run keeps the checkpoint the pause reached`() {
        assertEquals(
            20_000L,
            durableDownloadedBytes(checkpointBytes = 20_000L, reportedBytes = 0L),
        )
    }

    @Test
    fun `a reading past the checkpoint is the one published`() {
        assertEquals(
            25_000L,
            durableDownloadedBytes(checkpointBytes = 20_000L, reportedBytes = 25_000L),
        )
    }

    @Test
    fun `a run with no reusable checkpoint starts from what the sdk reports`() {
        assertEquals(0L, durableDownloadedBytes(checkpointBytes = 0L, reportedBytes = 0L))
        assertEquals(5_000L, durableDownloadedBytes(checkpointBytes = 0L, reportedBytes = 5_000L))
    }

    @Test
    fun `a negative reading never moves the checkpoint backwards`() {
        assertEquals(
            1_000L,
            durableDownloadedBytes(checkpointBytes = 1_000L, reportedBytes = -1L),
        )
    }

    @Test
    fun `the sdk staging directory is named under the sdk storage root`() {
        val root = Files.createTempDirectory("wallhub-sdk-staging").toFile()
        try {
            assertEquals(
                File(root, ".sc-staging/wallhub-workshop/task-1").canonicalFile,
                sdkStagingDirectory(root, "task-1"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a task id cannot name a directory outside the sdk staging root`() {
        val root = Files.createTempDirectory("wallhub-sdk-staging-escape").toFile()
        try {
            assertNull(sdkStagingDirectory(root, "../escape"))
            assertNull(sdkStagingDirectory(root, "nested/task"))
            assertNull(sdkStagingDirectory(root, " "))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `deleting the sdk staging discards the staged chunks and leaves the destination alone`() {
        val root = Files.createTempDirectory("wallhub-sdk-staging-delete").toFile()
        try {
            val staging = requireNotNull(sdkStagingDirectory(root, "task-1"))
            staging.mkdirs()
            val partial = File(staging, ".sc-42-0.part").apply { writeText("partial") }
            val destination = File(root, "wallhub-workshop/task-1").apply { mkdirs() }
            val published = File(destination, "scene.json").apply { writeText("{}") }

            assertTrue(staging.deleteRecursively())

            assertTrue(!partial.exists())
            assertTrue(published.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
