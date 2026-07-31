package com.wallhub.android.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.SettableFuture
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkManagerRecoveryTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var workerFactory: FormalWorkFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerFactory = FormalWorkFactory()
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
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun `formal download scheduler enqueues its network constrained worker with the task id`() {
        WorkManagerDownloadWorkScheduler(context).enqueue(DOWNLOAD_TASK_ID)

        val download = awaitOnlyWork(DOWNLOAD_WORK_NAME)

        assertEquals(
            DOWNLOAD_TASK_ID,
            inputDataFor(download).getString(FormalWorkshopDownloadWorker.KEY_TASK_ID),
        )
        assertEquals(androidx.work.NetworkType.CONNECTED, download.constraints.requiredNetworkType)
        assertTrue(download.tags.contains(FormalWorkshopDownloadWorker::class.java.name))
        assertTrue(download.tags.contains(FORMAL_DOWNLOAD_TAG))
    }

    @Test
    fun `formal MPKG conversion work can be cancelled and recovered with its original task id`() {
        val scheduler = WorkManagerConversionWorkScheduler(context)
        scheduler.enqueue(CONVERSION_TASK_ID)

        val initial = awaitOnlyWork(CONVERSION_WORK_NAME)
        assertFormalConversionRequest(initial)

        scheduler.cancel(CONVERSION_TASK_ID)
        awaitWorkState(initial.id, WorkInfo.State.CANCELLED)

        scheduler.enqueue(CONVERSION_TASK_ID)
        val recovered = awaitWorkInfos(CONVERSION_WORK_NAME).single { it.id != initial.id }

        assertNotEquals(initial.id, recovered.id)
        assertFormalConversionRequest(recovered)
    }

    @Test
    fun `formal MPKG worker persists cancellation and clears its staging directory`() {
        val stagingDirectory =
            File(context.filesDir, "$WORKSHOP_STAGING_DIRECTORY_NAME/$CONVERSION_CANCEL_TASK_ID")
                .apply {
                    mkdirs()
                    File(this, "scene.json").writeText("{}")
                }
        workerFactory.taskDao.put(
            FormalTaskRecordEntity(
                taskId = CONVERSION_CANCEL_TASK_ID,
                workshopId = 42L,
                title = "Cancellation test",
                type = "SCENE",
                status = DownloadStatus.CONVERTING.name,
                requestedAction = DownloadAction.CANCEL.name,
                stagingDirectory = stagingDirectory.path,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        workerFactory.executeFormalConversion(CONVERSION_CANCEL_TASK_ID)

        WorkManagerConversionWorkScheduler(context).enqueue(CONVERSION_CANCEL_TASK_ID)
        val work = awaitOnlyWork(conversionWorkName(CONVERSION_CANCEL_TASK_ID))
        awaitWorkState(work.id, WorkInfo.State.SUCCEEDED)

        assertEquals(
            DownloadStatus.CANCELLED.name,
            workerFactory.taskDao.task(CONVERSION_CANCEL_TASK_ID)?.status,
        )
        assertFalse(stagingDirectory.exists())
    }

    private fun assertFormalConversionRequest(work: WorkInfo) {
        assertEquals(
            CONVERSION_TASK_ID,
            inputDataFor(work).getString(FormalWorkshopConversionWorker.KEY_TASK_ID),
        )
        assertTrue(work.tags.contains(FormalWorkshopConversionWorker::class.java.name))
        assertTrue(
            work.tags.contains(FormalWorkshopConversionWorker.WORK_TAG_PREFIX + CONVERSION_TASK_ID),
        )
    }

    private fun inputDataFor(work: WorkInfo) =
        requireNotNull(
            WorkManagerImpl
                .getInstance(context)
                .workDatabase
                .workSpecDao()
                .getWorkSpec(work.id.toString()),
        ).input

    private fun awaitOnlyWork(uniqueWorkName: String): WorkInfo = awaitWorkInfos(uniqueWorkName).single()

    private fun awaitWorkInfos(uniqueWorkName: String): List<WorkInfo> {
        repeat(MAX_AWAIT_ATTEMPTS) {
            val work = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
            if (work.isNotEmpty()) return work
            Thread.yield()
        }
        error("Timed out waiting for $uniqueWorkName")
    }

    private fun awaitWorkState(
        id: java.util.UUID,
        expected: WorkInfo.State,
    ) {
        repeat(MAX_AWAIT_ATTEMPTS) {
            if (workManager.getWorkInfoById(id).get()?.state == expected) return
            Thread.yield()
        }
        error("Timed out waiting for $id to reach $expected")
    }

    private class FormalWorkFactory : WorkerFactory() {
        val taskDao = InMemoryTaskDao()
        private val executedConversionTaskIds = mutableSetOf<String>()

        fun executeFormalConversion(taskId: String) {
            executedConversionTaskIds += taskId
        }

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            val taskId = workerParameters.inputData.getString(FormalWorkshopConversionWorker.KEY_TASK_ID)
            return if (
                workerClassName == FormalWorkshopConversionWorker::class.java.name &&
                taskId in executedConversionTaskIds
            ) {
                FormalWorkshopConversionWorker(appContext, workerParameters, taskDao)
            } else {
                HoldingWorker(appContext, workerParameters)
            }
        }
    }

    private class HoldingWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : ListenableWorker(appContext, params) {
        override fun startWork() = SettableFuture.create<Result>()
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
            tasks.values.firstOrNull { task -> task.workshopId == workshopId }

        override suspend fun upsert(task: FormalTaskRecordEntity) {
            put(task)
        }

        override suspend fun nextQueuePosition(): Long = (tasks.values.maxOfOrNull(FormalTaskRecordEntity::queuePosition) ?: 0L) + 1L

        override suspend fun updateQueuePosition(
            taskId: String,
            position: Long,
        ) {
            tasks[taskId]?.let { task -> put(task.copy(queuePosition = position)) }
        }

        override suspend fun clearStagingDirectory(taskId: String) {
            tasks[taskId]?.let { task -> put(task.copy(stagingDirectory = null)) }
        }

        override suspend fun delete(taskId: String): Int =
            if (tasks.remove(taskId) != null) {
                taskFlow.value = tasks.values.toList()
                1
            } else {
                0
            }

        override suspend fun clearFinishedHistory(): Int {
            val finished =
                tasks.values
                    .filter { task ->
                        task.status in
                            setOf(
                                DownloadStatus.CANCELLED.name,
                                DownloadStatus.COMPLETED.name,
                                DownloadStatus.FAILED.name,
                            )
                    }
            finished.forEach { task -> tasks.remove(task.taskId) }
            taskFlow.value = tasks.values.toList()
            return finished.size
        }
    }

    private companion object {
        const val DOWNLOAD_TASK_ID = "download-task-42"
        const val CONVERSION_TASK_ID = "conversion-task-42"
        const val CONVERSION_CANCEL_TASK_ID = "conversion-cancel-task-42"
        const val FORMAL_DOWNLOAD_TAG = "wallhub_formal_workshop_download"
        const val MAX_AWAIT_ATTEMPTS = 100

        val DOWNLOAD_WORK_NAME =
            FormalWorkshopDownloadWorker.UNIQUE_DOWNLOAD_WORK_PREFIX + DOWNLOAD_TASK_ID
        val CONVERSION_WORK_NAME =
            FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + CONVERSION_TASK_ID

        fun conversionWorkName(taskId: String): String = FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + taskId
    }
}
