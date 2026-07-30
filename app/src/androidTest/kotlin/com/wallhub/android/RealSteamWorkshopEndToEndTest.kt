package com.wallhub.android

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.prototype.mpkg.VIDEO_MPKG_MAGIC
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in device acceptance test for a publicly accessible Steam Workshop video.
 *
 * This is deliberately excluded from ordinary CI because it transfers third-party Workshop
 * content. Invoke it only on an authorized device with `wallhub.realSteamE2e=true`.
 */
@RunWith(AndroidJUnit4::class)
class RealSteamWorkshopEndToEndTest {
    private lateinit var context: Context
    private lateinit var workshopRepository: WorkshopRepository
    private lateinit var downloadTaskRepository: DownloadTaskRepository
    private lateinit var steamAccessRepository: SteamAccessRepository
    private var taskId: String? = null

    @Before
    fun setUp() {
        assumeTrue(
            "Set wallhub.realSteamE2e=true to authorize the live Steam acceptance test.",
            InstrumentationRegistry
                .getArguments()
                .getString(REAL_STEAM_E2E_ARGUMENT)
                .equals("true", ignoreCase = true),
        )
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WallHubApplicationEntryPoint::class.java,
            )
        workshopRepository = entryPoint.workshopRepository()
        downloadTaskRepository = entryPoint.downloadTaskRepository()
        steamAccessRepository = entryPoint.steamAccessRepository()
    }

    @After
    fun cleanUp() =
        runBlocking {
            val id = taskId ?: return@runBlocking
            val current = downloadTaskRepository.find(id) ?: return@runBlocking
            if (!current.status.isTerminal()) {
                runCatching { downloadTaskRepository.requestAction(id, DownloadAction.CANCEL) }
                awaitTerminalTask(id)
            }
            val completed = downloadTaskRepository.find(id) ?: return@runBlocking
            completed.outputUri?.let { uri ->
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            }
            if (DownloadAction.DELETE in completed.availableActions) {
                runCatching { downloadTaskRepository.requestAction(id, DownloadAction.DELETE) }
            }
            completed.stagingDirectory
                ?.let(::File)
                ?.takeIf(::isManagedStagingDirectory)
                ?.deleteRecursively()
        }

    @Test
    fun publicSteamVideoDownloadsAndConvertsToMpkgThroughProductionWorkManager() =
        runBlocking {
            assertTrue(
                "Steam access route prewarm failed on this device.",
                steamAccessRepository.prewarmSteamIp(SteamWorkshopDataSource.COMMUNITY_HTML),
            )
            val workshop = findEligibleVideoWorkshop()
            val queued =
                downloadTaskRepository.enqueue(
                    DownloadRequest(
                        workshopId = workshop.summary.id,
                        title = workshop.summary.title,
                        type = WorkshopType.VIDEO,
                        previewUrl = workshop.summary.previewUrl,
                        expectedTotalBytes = requireNotNull(workshop.fileSizeBytes),
                        exportFormat = ExportFormat.MPKG,
                    ),
                )
            taskId = queued.id

            val completed = awaitTerminalTask(queued.id)
            assertEquals(
                "Steam task failed: ${completed.message}",
                DownloadStatus.COMPLETED,
                completed.status,
            )
            assertEquals(WorkshopType.VIDEO, completed.type)
            assertTrue(completed.downloadedBytes > 0L)
            assertNotNull(completed.outputLabel)
            val outputUri = Uri.parse(requireNotNull(completed.outputUri))
            val magic =
                requireNotNull(context.contentResolver.openInputStream(outputUri)).use { input ->
                    input.readNBytes(Int.SIZE_BYTES)
                    input.readNBytes(VIDEO_MPKG_MAGIC.length)
                }
            assertTrue(magic.contentEquals(VIDEO_MPKG_MAGIC.toByteArray(Charsets.US_ASCII)))
        }

    private suspend fun findEligibleVideoWorkshop(): WorkshopDetail {
        val candidates =
            workshopRepository
                .browse(
                    WorkshopBrowseQuery(
                        pageSize = BROWSE_PAGE_SIZE,
                        type = WorkshopType.VIDEO,
                        days = 365,
                        sort = WorkshopSort.MOST_RECENT,
                    ),
                ).items
        candidates.forEach { summary ->
            val detail = runCatching { workshopRepository.getDetail(summary.id) }.getOrNull() ?: return@forEach
            val size = detail.fileSizeBytes ?: detail.summary.fileSizeBytes
            if (detail.summary.type == WorkshopType.VIDEO && size in 1L..MAX_WORKSHOP_BYTES) {
                return detail.copy(fileSizeBytes = size)
            }
        }
        error("Steam did not return a public video within the ${MAX_WORKSHOP_BYTES / MEBIBYTE} MiB test budget")
    }

    private suspend fun awaitTerminalTask(id: String): DownloadTask {
        repeat(MAX_POLL_ATTEMPTS) {
            val task = downloadTaskRepository.find(id)
            if (task?.status?.isTerminal() == true) return task
            delay(POLL_INTERVAL_MILLIS)
        }
        error("Timed out waiting for the real Steam task to finish")
    }

    private fun isManagedStagingDirectory(directory: File): Boolean =
        runCatching {
            directory.canonicalFile.parentFile == File(context.filesDir, "wallhub-workshop").canonicalFile
        }.getOrDefault(false)

    private fun DownloadStatus.isTerminal(): Boolean =
        this in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)

    private companion object {
        const val REAL_STEAM_E2E_ARGUMENT = "wallhub.realSteamE2e"
        const val BROWSE_PAGE_SIZE = 20
        const val MEBIBYTE = 1024L * 1024L
        const val MAX_WORKSHOP_BYTES = 32L * MEBIBYTE
        const val MAX_POLL_ATTEMPTS = 180
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
