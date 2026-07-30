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
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.ExportFormat
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

/** Exercises the production WorkManager scheduler, converter, and MediaStore export on a device. */
@RunWith(AndroidJUnit4::class)
class WorkManagerConversionEndToEndTest {
    private lateinit var context: Context
    private lateinit var taskDao: InMemoryTaskDao
    private lateinit var workManager: WorkManager
    private lateinit var taskDirectory: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        taskDao = InMemoryTaskDao()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(ConversionWorkerFactory(taskDao))
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        taskDirectory = File(context.filesDir, "$WORKSHOP_STAGING_DIRECTORY_NAME/$TASK_ID")
        createVideoWorkshop(taskDirectory)
        taskDao.upsertBlocking(
            FormalTaskRecordEntity(
                taskId = TASK_ID,
                workshopId = WORKSHOP_ID,
                title = "WorkManager conversion E2E",
                type = WorkshopType.VIDEO.name,
                status = DownloadStatus.CONVERTING.name,
                stagingDirectory = taskDirectory.path,
                exportFormat = ExportFormat.MPKG.name,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    @After
    fun tearDown() {
        taskDao.task(TASK_ID)?.outputUri?.let { uri ->
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
        }
        taskDirectory.deleteRecursively()
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun conversionWorkerExportsAnInspectableMpkgThroughWorkManager() {
        WorkManagerConversionWorkScheduler(context).enqueue(TASK_ID)

        val completedWork = awaitWorkCompletion()
        assertEquals(WorkInfo.State.SUCCEEDED, completedWork.state)

        val completedTask = requireNotNull(taskDao.task(TASK_ID))
        assertEquals(DownloadStatus.COMPLETED.name, completedTask.status)
        val outputUri = requireNotNull(completedTask.outputUri)
        val header =
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(outputUri))).use { input ->
                input.readNBytes(Int.SIZE_BYTES)
                input.readNBytes(VIDEO_MPKG_MAGIC.length)
            }
        assertNotNull(completedTask.outputLabel)
        assertTrue(header.contentEquals(VIDEO_MPKG_MAGIC.toByteArray(Charsets.US_ASCII)))
        assertTrue(taskDirectory.isDirectory)
    }

    private fun awaitWorkCompletion(): WorkInfo {
        repeat(MAX_WAIT_ATTEMPTS) {
            val work =
                workManager
                    .getWorkInfosForUniqueWork(
                        FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + TASK_ID,
                    ).get()
                    .singleOrNull()
            if (work?.state?.isFinished == true) return work
            Thread.sleep(WAIT_INTERVAL_MILLIS)
        }
        error("Timed out waiting for WorkManager conversion")
    }

    private fun createVideoWorkshop(directory: File) {
        directory.deleteRecursively()
        check(directory.mkdirs()) { "Unable to create test workshop directory" }
        File(directory, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(directory, "media").mkdirs()
        File(directory, "media/demo.mp4").writeBytes(ByteArray(512) { it.toByte() })
        File(directory, "project.json").writeText(
            "{\"type\":\"video\",\"file\":\"media/demo.mp4\",\"title\":\"WorkManager E2E\"}",
        )
    }

    private class ConversionWorkerFactory(
        private val taskDao: FormalTaskRecordDao,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == FormalWorkshopConversionWorker::class.java.name) {
                FormalWorkshopConversionWorker(
                    appContext = appContext,
                    params = workerParameters,
                    taskDao = taskDao,
                )
            } else {
                null
            }
    }

    private class InMemoryTaskDao : FormalTaskRecordDao {
        private val tasks = linkedMapOf<String, FormalTaskRecordEntity>()
        private val taskFlow = MutableStateFlow<List<FormalTaskRecordEntity>>(emptyList())

        fun upsertBlocking(task: FormalTaskRecordEntity) {
            tasks[task.taskId] = task
            taskFlow.value = tasks.values.toList()
        }

        fun task(taskId: String): FormalTaskRecordEntity? = tasks[taskId]

        override fun observeAll(): Flow<List<FormalTaskRecordEntity>> = taskFlow

        override suspend fun find(taskId: String): FormalTaskRecordEntity? = task(taskId)

        override suspend fun listAll(): List<FormalTaskRecordEntity> = tasks.values.toList()

        override suspend fun findActiveForWorkshop(workshopId: Long): FormalTaskRecordEntity? =
            tasks.values.firstOrNull {
                it.workshopId == workshopId && !it.status.isFinishedDownloadStatus()
            }

        override suspend fun upsert(task: FormalTaskRecordEntity) {
            upsertBlocking(task)
        }

        override suspend fun nextQueuePosition(): Long = (tasks.values.maxOfOrNull(FormalTaskRecordEntity::queuePosition) ?: 0L) + 1L

        override suspend fun updateQueuePosition(
            taskId: String,
            position: Long,
        ) {
            task(taskId)?.let { upsertBlocking(it.copy(queuePosition = position)) }
        }

        override suspend fun clearStagingDirectory(taskId: String) {
            task(taskId)?.let { upsertBlocking(it.copy(stagingDirectory = null)) }
        }

        override suspend fun delete(taskId: String): Int =
            if (tasks.remove(taskId) != null) {
                taskFlow.value = tasks.values.toList()
                1
            } else {
                0
            }

        override suspend fun clearFinishedHistory(): Int {
            val finished = tasks.values.filter { it.status.isFinishedDownloadStatus() }
            finished.forEach { tasks.remove(it.taskId) }
            taskFlow.value = tasks.values.toList()
            return finished.size
        }

        private fun String.isFinishedDownloadStatus(): Boolean =
            this in
                setOf(
                    DownloadStatus.CANCELLED.name,
                    DownloadStatus.COMPLETED.name,
                    DownloadStatus.FAILED.name,
                )
    }

    private companion object {
        const val TASK_ID = "work-manager-conversion-e2e"
        const val WORKSHOP_ID = 2_025_073_100L
        const val MAX_WAIT_ATTEMPTS = 100
        const val WAIT_INTERVAL_MILLIS = 100L
    }
}
