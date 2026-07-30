package com.wallhub.android.data.downloads

import android.content.ContextWrapper
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomDownloadTaskRepositoryTest {
    @Test
    fun `pause request is persisted without falsely changing active download phase`() =
        runBlocking {
            val dao =
                FakeTaskDao(
                    record =
                        record(
                            status = DownloadStatus.DOWNLOADING.name,
                            requestedAction = null,
                        ),
                )
            val repository =
                RoomDownloadTaskRepository(
                    context = ContextWrapper(null),
                    taskDao = dao,
                    credentialProvider = NoCredentialProvider,
                    workScheduler = NoOpDownloadScheduler,
                    conversionScheduler = NoOpConversionScheduler,
                    settingsRepository = EmptySettingsRepository,
                    downloadConcurrencyGovernor = DownloadConcurrencyGovernor(),
                )

            repository.requestAction(TASK_ID, DownloadAction.PAUSE)

            val stored = dao.find(TASK_ID) ?: error("task was not stored")
            assertEquals(DownloadStatus.DOWNLOADING.name, stored.status)
            assertEquals(DownloadAction.PAUSE.name, stored.requestedAction)
            assertEquals("已请求暂停，下载器会保留已验证的数据", stored.message)
        }

    @Test
    fun `cancel request signals active conversion`() =
        runBlocking {
            val dao = FakeTaskDao(record(DownloadStatus.CONVERTING.name, requestedAction = null))
            val repository =
                RoomDownloadTaskRepository(
                    context = ContextWrapper(null),
                    taskDao = dao,
                    credentialProvider = NoCredentialProvider,
                    workScheduler = NoOpDownloadScheduler,
                    conversionScheduler = NoOpConversionScheduler,
                    settingsRepository = EmptySettingsRepository,
                    downloadConcurrencyGovernor = DownloadConcurrencyGovernor(),
                )

            try {
                repository.requestAction(TASK_ID, DownloadAction.CANCEL)

                assertEquals(DownloadAction.CANCEL.name, dao.find(TASK_ID)?.requestedAction)
                assertTrue(FormalWorkshopConversionCancellation.isRequested(TASK_ID))
            } finally {
                FormalWorkshopConversionCancellation.clear(TASK_ID)
            }
        }

    private fun record(
        status: String,
        requestedAction: String?,
    ) = FormalTaskRecordEntity(
        taskId = TASK_ID,
        workshopId = 100L,
        title = "Task",
        type = "VIDEO",
        status = status,
        downloadedBytes = 20L,
        totalBytes = 100L,
        bytesPerSecond = 5L,
        accountName = "wallhub-test",
        requestedAction = requestedAction,
        isResumable = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class FakeTaskDao(
        record: FormalTaskRecordEntity,
    ) : FormalTaskRecordDao {
        private val records = linkedMapOf(record.taskId to record)
        private val flow = MutableStateFlow(records.values.toList())

        override fun observeAll(): Flow<List<FormalTaskRecordEntity>> = flow

        override suspend fun find(taskId: String): FormalTaskRecordEntity? = records[taskId]

        override suspend fun listAll(): List<FormalTaskRecordEntity> = records.values.toList()

        override suspend fun findActiveForWorkshop(workshopId: Long): FormalTaskRecordEntity? =
            records.values
                .firstOrNull { it.workshopId == workshopId && it.status in ACTIVE_STATUSES }

        override suspend fun upsert(task: FormalTaskRecordEntity) {
            records[task.taskId] = task
            flow.value = records.values.toList()
        }

        override suspend fun nextQueuePosition(): Long = records.size.toLong()

        override suspend fun updateQueuePosition(
            taskId: String,
            position: Long,
        ) {
            records[taskId]?.let { task -> records[taskId] = task.copy(queuePosition = position) }
            flow.value = records.values.toList()
        }

        override suspend fun clearStagingDirectory(taskId: String) {
            records[taskId]?.let { task -> records[taskId] = task.copy(stagingDirectory = null) }
            flow.value = records.values.toList()
        }

        override suspend fun delete(taskId: String): Int {
            val removed = if (records.remove(taskId) != null) 1 else 0
            flow.value = records.values.toList()
            return removed
        }

        override suspend fun clearFinishedHistory(): Int {
            val finished = records.values.filter { it.status in setOf("COMPLETED", "FAILED", "CANCELLED") }
            finished.forEach { records.remove(it.taskId) }
            flow.value = records.values.toList()
            return finished.size
        }
    }

    private companion object {
        const val TASK_ID = "task-1"
        val ACTIVE_STATUSES =
            setOf(
                DownloadStatus.QUEUED.name,
                DownloadStatus.RESOLVING.name,
                DownloadStatus.DOWNLOADING.name,
                DownloadStatus.PAUSED.name,
                DownloadStatus.CONVERTING.name,
                DownloadStatus.EXPORTING.name,
            )
    }

    private object NoCredentialProvider : SteamContentCredentialProvider {
        override suspend fun loadContentCredential(): SteamContentCredential? = null
    }

    private object NoOpDownloadScheduler : DownloadWorkScheduler {
        override fun enqueue(taskId: String) = Unit
    }

    private object NoOpConversionScheduler : ConversionWorkScheduler {
        override fun enqueue(taskId: String) = Unit

        override fun cancel(taskId: String) = Unit
    }

    private object EmptySettingsRepository : SettingsRepository {
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
}
