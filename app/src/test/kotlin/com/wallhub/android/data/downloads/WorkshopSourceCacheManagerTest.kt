package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.SettingsRepository
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopSourceCacheManagerTest {
    @Test
    fun `retain measures nested files touches row and evicts when disabled`() = runTest {
        val filesDir = Files.createTempDirectory("source-cache").toFile()
        try {
            val source = filesDir.resolve("wallhub-workshop/task").apply { mkdirs() }
            source.resolve("one.bin").writeBytes(ByteArray(3))
            source.resolve("nested/two.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(5)) }
            val fake = FakeDao(record("task", source.path))
            val manager = WorkshopSourceCacheManager(filesDir, fake.proxy, Settings(false), now = { 42L })

            manager.retainCompleted("task")

            assertFalse(source.exists())
            assertEquals(0L, fake.task.stagingRetainedBytes)
            assertEquals("NONE", fake.task.stagingCacheState)
            assertTrue(fake.touched.any { it == Triple("task", 8L, 42L) })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `clear retained removes private staging without touching export metadata`() = runTest {
        val filesDir = Files.createTempDirectory("source-cache").toFile()
        try {
            val source = filesDir.resolve("wallhub-workshop/task").apply { mkdirs() }
            source.resolve("source.bin").writeBytes(ByteArray(8))
            val sdkSource = filesDir.resolve(".sc-staging/wallhub-workshop/task").apply { mkdirs() }
            sdkSource.resolve("checkpoint.bin").writeBytes(ByteArray(4))
            val fake =
                FakeDao(
                    record("task", source.path).copy(
                        stagingRetainedBytes = 8,
                        stagingCacheState = "RETAINED",
                        outputUri = "content://exported/wallpaper",
                        conversionReportJson = "report",
                    ),
                )
            val manager = WorkshopSourceCacheManager(filesDir, fake.proxy, Settings(true))

            assertEquals(8L, manager.retainedBytes())
            assertEquals(8L, manager.clearRetained())

            assertFalse(source.exists())
            assertFalse(sdkSource.exists())
            assertEquals("content://exported/wallpaper", fake.task.outputUri)
            assertEquals("report", fake.task.conversionReportJson)
            assertEquals("NONE", fake.task.stagingCacheState)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `reconcile clears missing source without touching successful output or report`() = runTest {
        val filesDir = Files.createTempDirectory("source-cache").toFile()
        try {
            val fake = FakeDao(
                record("missing", filesDir.resolve("wallhub-workshop/missing").path).copy(
                    stagingRetainedBytes = 99,
                    stagingCacheState = "RETAINED",
                    outputUri = "content://success",
                    conversionReportJson = "report",
                ),
            )
            WorkshopSourceCacheManager(filesDir, fake.proxy, Settings(true)).reconcileAndEnforceBudget()

            assertEquals("content://success", fake.task.outputUri)
            assertEquals("report", fake.task.conversionReportJson)
            assertEquals("NONE", fake.task.stagingCacheState)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private class Settings(enabled: Boolean) : SettingsRepository {
        override val preferences = flowOf(AppPreferences(workshopSourceCacheEnabled = enabled))
        override suspend fun setTheme(theme: com.wallhub.android.core.model.ThemePreference) = Unit
        override suspend fun setAccent(accent: com.wallhub.android.core.model.AccentPreference, customColor: String?) = Unit
        override suspend fun setHomePreferences(pageSize: Int, columns: Int, multiSelect: Boolean, cardAction: com.wallhub.android.core.model.HomeCardAction, matureContentEnabled: Boolean) = Unit
        override suspend fun setDownloadPreferences(maxConcurrentDownloads: Int, chunkDownloadConcurrency: Int, proxyUrl: String, mediaCacheLimitMb: Int) = Unit
        override suspend fun setOutputDirectory(treeUri: String, label: String) = Unit
        override suspend fun clearOutputDirectory() = Unit
    }

    private class FakeDao(initial: FormalTaskRecordEntity) {
        var task = initial
        val touched = mutableListOf<Triple<String, Long, Long>>()
        val proxy = Proxy.newProxyInstance(FormalTaskRecordDao::class.java.classLoader, arrayOf(FormalTaskRecordDao::class.java)) { _, method, args ->
            when (method.name) {
                "find" -> task.takeIf { it.taskId == args!![0] }
                "listRetainedStaging" -> listOf(task).filter { it.stagingCacheState == "RETAINED" }
                "touchRetainedStaging" -> {
                    val values = Triple(args!![0] as String, args[1] as Long, args[2] as Long)
                    touched += values
                    task = task.copy(stagingRetainedBytes = values.second, stagingLastAccessedAt = values.third, stagingCacheState = "RETAINED")
                    1
                }
                "clearStagingCache" -> {
                    task = task.copy(stagingDirectory = null, stagingRetainedBytes = 0, stagingLastAccessedAt = 0, stagingCacheState = "NONE", conversionPlanJson = null)
                    1
                }
                else -> error("Unexpected DAO call ${method.name}")
            }
        } as FormalTaskRecordDao
    }

    private fun record(id: String, path: String) = FormalTaskRecordEntity(
        taskId = id,
        workshopId = 1,
        title = id,
        type = "SCENE",
        status = "COMPLETED",
        stagingDirectory = path,
        createdAt = 1,
        updatedAt = 1,
    )
}
