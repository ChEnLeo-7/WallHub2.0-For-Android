package com.wallhub.android.data.downloads

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.prototype.mpkg.VIDEO_MPKG_MAGIC
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises production WorkManager workers with a controlled Workshop payload. The test keeps the
 * scheduler, persistent task transitions, cancellation cleanup, MPKG conversion, and MediaStore
 * export real while avoiding an account- and network-dependent Steam fixture.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerDownloadAndConversionEndToEndTest {
    private lateinit var context: Context
    private lateinit var taskDao: InMemoryTaskDao
    private lateinit var workManager: WorkManager
    private lateinit var workerFactory: FormalWorkerFactory

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        taskDao = InMemoryTaskDao()
        workerFactory = FormalWorkerFactory(taskDao)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(workerFactory)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        taskDao.task(TASK_ID)?.outputUri?.let { uri ->
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
        }
        File(context.filesDir, "$WORKSHOP_STAGING_DIRECTORY_NAME/$TASK_ID").deleteRecursively()
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun cancelledDownloadCleansStagingThenRecoveredTaskConvertsAndExportsMpkg() {
        val cancelledDirectory = createCancelledStagingDirectory()
        taskDao.put(
            task(
                status = DownloadStatus.QUEUED,
                requestedAction = DownloadAction.CANCEL,
                stagingDirectory = cancelledDirectory.path,
            ),
        )

        WorkManagerDownloadWorkScheduler(context).enqueue(TASK_ID)
        val cancelledWork = awaitLatestWork(downloadWorkName())
        satisfyConstraints(cancelledWork)
        awaitWorkState(cancelledWork.id, WorkInfo.State.SUCCEEDED)

        val cancelledTask = requireNotNull(taskDao.task(TASK_ID))
        assertEquals(DownloadStatus.CANCELLED.name, cancelledTask.status)
        assertEquals(null, cancelledTask.stagingDirectory)
        assertEquals(null, cancelledTask.requestedAction)
        assertTrue(!cancelledDirectory.exists())

        taskDao.put(
            task(
                status = DownloadStatus.QUEUED,
                requestedAction = null,
                stagingDirectory = null,
            ),
        )
        WorkManagerDownloadWorkScheduler(context).enqueue(TASK_ID)
        val recoveredDownload = awaitNewWork(downloadWorkName(), cancelledWork.id)
        satisfyConstraints(recoveredDownload)
        awaitWorkState(recoveredDownload.id, WorkInfo.State.SUCCEEDED)

        val conversionWork = awaitLatestWork(conversionWorkName())
        awaitWorkState(conversionWork.id, WorkInfo.State.SUCCEEDED)

        val completedTask = requireNotNull(taskDao.task(TASK_ID))
        assertEquals(DownloadStatus.COMPLETED.name, completedTask.status)
        assertEquals(FAKE_DOWNLOAD_BYTES, completedTask.downloadedBytes)
        assertNotNull(completedTask.outputLabel)
        val outputUri = Uri.parse(requireNotNull(completedTask.outputUri))
        val header =
            requireNotNull(context.contentResolver.openInputStream(outputUri)).use { input ->
                input.readNBytes(Int.SIZE_BYTES)
                input.readNBytes(VIDEO_MPKG_MAGIC.length)
            }
        assertTrue(header.contentEquals(VIDEO_MPKG_MAGIC.toByteArray(Charsets.US_ASCII)))
        assertTrue(requireNotNull(completedTask.stagingDirectory).let(::File).isDirectory)
    }

    private fun createCancelledStagingDirectory(): File =
        File(context.filesDir, "$WORKSHOP_STAGING_DIRECTORY_NAME/$TASK_ID")
            .apply {
                deleteRecursively()
                check(mkdirs()) { "Unable to create cancellation staging directory" }
                File(this, "unfinished.bin").writeBytes(ByteArray(16))
            }

    private fun task(
        status: DownloadStatus,
        requestedAction: DownloadAction?,
        stagingDirectory: String?,
    ): FormalTaskRecordEntity =
        FormalTaskRecordEntity(
            taskId = TASK_ID,
            workshopId = WORKSHOP_ID,
            title = "Controlled WorkManager Workshop",
            type = WorkshopType.VIDEO.name,
            status = status.name,
            requestedAction = requestedAction?.name,
            stagingDirectory = stagingDirectory,
            exportFormat = ExportFormat.MPKG.name,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun awaitLatestWork(uniqueWorkName: String): WorkInfo {
        repeat(MAX_WAIT_ATTEMPTS) {
            workManager
                .getWorkInfosForUniqueWork(uniqueWorkName)
                .get()
                .lastOrNull()
                ?.let { return it }
            Thread.sleep(WAIT_INTERVAL_MILLIS)
        }
        error("Timed out waiting for $uniqueWorkName")
    }

    private fun awaitNewWork(
        uniqueWorkName: String,
        previousId: java.util.UUID,
    ): WorkInfo {
        repeat(MAX_WAIT_ATTEMPTS) {
            workManager
                .getWorkInfosForUniqueWork(uniqueWorkName)
                .get()
                .lastOrNull { it.id != previousId }
                ?.let { return it }
            Thread.sleep(WAIT_INTERVAL_MILLIS)
        }
        error("Timed out recovering $uniqueWorkName")
    }

    private fun satisfyConstraints(work: WorkInfo) {
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(work.id)
    }

    private fun awaitWorkState(
        id: java.util.UUID,
        expected: WorkInfo.State,
    ) {
        repeat(MAX_WAIT_ATTEMPTS) {
            if (workManager.getWorkInfoById(id).get()?.state == expected) return
            Thread.sleep(WAIT_INTERVAL_MILLIS)
        }
        error("Timed out waiting for $id to reach $expected")
    }

    private class FormalWorkerFactory(
        private val taskDao: FormalTaskRecordDao,
    ) : WorkerFactory() {
        private val contentClient = ControlledSteamWorkshopContentClient()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            when (workerClassName) {
                FormalWorkshopDownloadWorker::class.java.name ->
                    FormalWorkshopDownloadWorker(
                        appContext = appContext,
                        params = workerParameters,
                        taskDao = taskDao,
                        credentialProvider = NoCredentialProvider,
                        conversionScheduler = WorkManagerConversionWorkScheduler(appContext),
                        settingsRepository = DefaultSettingsRepository,
                        downloadConcurrencyGovernor = DownloadConcurrencyGovernor(),
                        steamWorkshopContentClient = SteamWorkshopContentClient(contentClient),
                    )

                FormalWorkshopConversionWorker::class.java.name ->
                    FormalWorkshopConversionWorker(
                        appContext = appContext,
                        params = workerParameters,
                        taskDao = taskDao,
                    )

                else -> null
            }
    }

    private class ControlledSteamWorkshopContentClient : WorkshopContentGateway {
        override suspend fun fetchContentTarget(
            publishedFileId: Long,
            proxyUrl: String,
        ): WorkshopContentTarget =
            WorkshopContentTarget(
                publishedFileId = publishedFileId,
                title = "Controlled WorkManager Workshop",
                appId = 431960,
                contentManifestId = 2_025_073_100L,
                expectedSize = FAKE_DOWNLOAD_BYTES,
                contentTypeHint = "video",
            )

        override suspend fun download(
            target: WorkshopContentTarget,
            destinationDirectory: File,
            credential: SteamContentCredential?,
            options: SteamContentDownloadOptions,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
        ): SteamContentDownloadResult {
            check(control() == SteamDownloadControl.CONTINUE)
            check(destinationDirectory.mkdirs() || destinationDirectory.isDirectory)
            File(destinationDirectory, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(destinationDirectory, "media").mkdirs()
            File(destinationDirectory, "media/demo.mp4").writeBytes(
                ByteArray(FAKE_DOWNLOAD_BYTES.toInt()) { it.toByte() },
            )
            File(destinationDirectory, "project.json").writeText(
                "{\"type\":\"video\",\"file\":\"media/demo.mp4\",\"title\":\"Controlled E2E\"}",
            )
            onProgress(
                SteamDownloadProgress(
                    phase = SteamDownloadPhase.DOWNLOADING,
                    completedBytes = FAKE_DOWNLOAD_BYTES,
                    totalBytes = FAKE_DOWNLOAD_BYTES,
                    completedFiles = 3,
                    totalFiles = 3,
                ),
            )
            return SteamContentDownloadResult(
                rootDirectory = destinationDirectory,
                downloadedBytes = FAKE_DOWNLOAD_BYTES,
                totalBytes = FAKE_DOWNLOAD_BYTES,
                fileCount = 3,
                usedAuthenticatedSession = false,
            )
        }
    }

    private class InMemoryTaskDao : FormalTaskRecordDao {
        private val tasks = linkedMapOf<String, FormalTaskRecordEntity>()
        private val taskFlow = MutableStateFlow<List<FormalTaskRecordEntity>>(emptyList())

        fun put(task: FormalTaskRecordEntity) {
            tasks[task.taskId] = task
            taskFlow.value = tasks.values.toList()
        }

        fun task(taskId: String): FormalTaskRecordEntity? = tasks[taskId]

        override fun observeAll(): Flow<List<FormalTaskRecordEntity>> = taskFlow

        override suspend fun find(taskId: String): FormalTaskRecordEntity? = task(taskId)

        override suspend fun listAll(): List<FormalTaskRecordEntity> = tasks.values.toList()

        override suspend fun findActiveForWorkshop(workshopId: Long): FormalTaskRecordEntity? =
            tasks.values.firstOrNull { it.workshopId == workshopId }

        override suspend fun upsert(task: FormalTaskRecordEntity) {
            put(task)
        }

        override suspend fun nextQueuePosition(): Long = 1L

        override suspend fun updateQueuePosition(
            taskId: String,
            position: Long,
        ) = Unit

        override suspend fun clearStagingDirectory(taskId: String) {
            task(taskId)?.let { put(it.copy(stagingDirectory = null)) }
        }

        override suspend fun delete(taskId: String): Int =
            if (tasks.remove(taskId) != null) {
                taskFlow.value = tasks.values.toList()
                1
            } else {
                0
            }

        override suspend fun clearFinishedHistory(): Int = 0
    }

    private object NoCredentialProvider : SteamContentCredentialProvider {
        override suspend fun loadContentCredential(): SteamContentCredential? = null
    }

    private object DefaultSettingsRepository : SettingsRepository {
        override val preferences: Flow<AppPreferences> = MutableStateFlow(AppPreferences())

        override suspend fun setTheme(theme: ThemePreference) = Unit

        override suspend fun setLanguage(language: AppLanguage) = Unit

        override suspend fun setAccent(
            accent: AccentPreference,
            customColor: String?,
        ) = Unit

        override suspend fun setHomePreferences(
            pageSize: Int,
            columns: Int,
            multiSelect: Boolean,
            cardAction: HomeCardAction,
            matureContentEnabled: Boolean,
        ) = Unit

        override suspend fun setDownloadPreferences(
            maxConcurrentDownloads: Int,
            chunkDownloadConcurrency: Int,
            proxyUrl: String,
            mediaCacheLimitMb: Int,
        ) = Unit

        override suspend fun setOutputDirectory(
            treeUri: String,
            label: String,
        ) = Unit

        override suspend fun clearOutputDirectory() = Unit
    }

    private companion object {
        const val TASK_ID = "work-manager-download-and-conversion-e2e"
        const val WORKSHOP_ID = 2_025_073_100L
        const val FAKE_DOWNLOAD_BYTES = 512L
        const val MAX_WAIT_ATTEMPTS = 100
        const val WAIT_INTERVAL_MILLIS = 100L

        fun downloadWorkName(): String = FormalWorkshopDownloadWorker.UNIQUE_DOWNLOAD_WORK_PREFIX + TASK_ID

        fun conversionWorkName(): String = FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + TASK_ID
    }
}
