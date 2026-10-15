package com.wallhub.android.data.downloads

import android.content.ContextWrapper
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadEnqueueResult
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopType
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDownloadTaskRepositoryTest {
    @Test
    fun `pending UI control is derived from unapplied durable intent`() {
        val task =
            queuedRecord(taskId = "pending-cancel").copy(
                desiredIntent = "CANCEL",
                desiredIntentRevision = 2,
                appliedIntentRevision = 1,
            ).toModel()

        assertEquals(DownloadAction.CANCEL, task.pendingControl)
        assertTrue(task.availableActions.isEmpty())
    }

    @Test
    fun `enqueue creates persisted task only after scheduler accepts unique work`() = runTest {
        val dao = FakeTaskDao()
        val scheduler = RecordingScheduler()
        val repository = repository(dao, scheduler)

        val result = repository.enqueue(request(taskId = "created-task"))

        assertTrue(result is DownloadEnqueueResult.Created)
        assertEquals("created-task", result.task.id)
        assertEquals(1, scheduler.wakeCount)
        assertEquals(DownloadStatus.QUEUED.name, dao.records.single().status)
    }

    @Test
    fun `conversion preset stable IDs are explicit and unknown IDs fall back to balanced`() {
        assertEquals("balanced", MpkgConversionPreset.BALANCED.stableId)
        assertEquals("compatibility", MpkgConversionPreset.COMPATIBILITY.stableId)
        assertEquals("high_quality", MpkgConversionPreset.HIGH_QUALITY.stableId)
        assertEquals("source_fidelity", MpkgConversionPreset.SOURCE_FIDELITY.stableId)
        assertEquals(MpkgConversionPreset.BALANCED, MpkgConversionPreset.fromStableId("future-mode"))
    }

    @Test
    fun `enqueue snapshots conversion preferences and starts without a plan or report`() = runTest {
        val dao = FakeTaskDao()
        val repository = repository(dao, RecordingScheduler())

        val result =
            repository.enqueue(
                request(
                    taskId = "preset-task",
                    preset = MpkgConversionPreset.BALANCED,
                    preserveAudio = true,
                ),
            )

        assertEquals(MpkgConversionPreset.BALANCED, result.task.mpkgConversionPreset)
        assertTrue(result.task.preserveAudio)
        assertEquals("balanced", dao.records.single().conversionPreset)
        assertTrue(dao.records.single().preserveAudio)
        assertEquals(null, dao.records.single().conversionPlanJson)
        assertEquals(null, dao.records.single().conversionReportJson)
        assertEquals(1, dao.records.single().conversionPlanVersion)
    }

    @Test
    fun `retry preserves conversion snapshot and planning artifacts`() = runTest {
        val original =
            queuedRecord(taskId = "retry-preset").copy(
                status = DownloadStatus.FAILED.name,
                conversionPreset = "balanced",
                preserveAudio = true,
                conversionPlanJson = "{\"plan\":true}",
                conversionReportJson = "{\"report\":true}",
                conversionPlanVersion = 7,
            )
        val dao = FakeTaskDao(original)
        val repository = repository(dao, RecordingScheduler())

        repository.requestAction(original.taskId, DownloadAction.RETRY)

        val retried = dao.records.single()
        assertEquals("balanced", retried.conversionPreset)
        assertTrue(retried.preserveAudio)
        assertEquals("{\"plan\":true}", retried.conversionPlanJson)
        assertEquals("{\"report\":true}", retried.conversionReportJson)
        assertEquals(7, retried.conversionPlanVersion)
    }

    @Test
    fun `repository persists conversion plan and report without replacing either snapshot`() = runTest {
        val original = queuedRecord(taskId = "planning-task")
        val dao = FakeTaskDao(original)
        val repository = repository(dao, RecordingScheduler())

        repository.persistConversionPlan(original.taskId, "{\"plan\":1}", 3)
        repository.persistConversionReport(original.taskId, "{\"report\":1}")

        val persisted = dao.records.single()
        assertEquals("balanced", persisted.conversionPreset)
        assertEquals("{\"plan\":1}", persisted.conversionPlanJson)
        assertEquals(3, persisted.conversionPlanVersion)
        assertEquals("{\"report\":1}", persisted.conversionReportJson)
    }

    @Test
    fun `repository rejects oversized conversion JSON before DAO write`() = runTest {
        val original = queuedRecord(taskId = "oversized-planning-task")
        val dao = FakeTaskDao(original)
        val repository = repository(dao, RecordingScheduler())
        val oversized = "x".repeat(128 * 1024 + 1)

        val failure = runCatching { repository.persistConversionPlan(original.taskId, oversized, 1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, dao.records.single().conversionPlanJson)
    }

    @Test
    fun `unknown persisted conversion preset falls back to balanced`() {
        val task = queuedRecord(taskId = "unknown-preset").copy(conversionPreset = "future-mode").toModel()

        assertEquals(MpkgConversionPreset.BALANCED, task.mpkgConversionPreset)
    }

    @Test
    fun `enqueue existing queued task repairs missing unique work and reports already active`() = runTest {
        val existing = queuedRecord(taskId = "persisted-before-death")
        val dao = FakeTaskDao(existing)
        val scheduler = RecordingScheduler()
        val repository = repository(dao, scheduler)

        val result = repository.enqueue(request())

        assertTrue(result is DownloadEnqueueResult.AlreadyActive)
        assertEquals(existing.taskId, result.task.id)
        assertEquals(1, scheduler.wakeCount)
        assertSame(existing, dao.records.single())
    }

    @Test
    fun `enqueue remains durably accepted when wake scheduling fails`() = runTest {
        val dao = FakeTaskDao()
        val failure = IllegalStateException("scheduler rejected work")
        val repository = repository(dao, RecordingScheduler(failure))

        val result = repository.enqueue(request(taskId = "durable-task"))

        assertTrue(result is DownloadEnqueueResult.Created)
        assertEquals(DownloadStatus.QUEUED.name, dao.records.single().status)
        assertEquals(listOf("RUN"), dao.commands.map { it.command })
    }

    @Test
    fun `enqueue existing queued task does not report success when repair scheduling fails`() = runTest {
        val existing = queuedRecord(taskId = "orphaned-task")
        val dao = FakeTaskDao(existing)
        val failure = IllegalStateException("scheduler rejected repair")
        val repository = repository(dao, RecordingScheduler(failure))

        val thrown = runCatching { repository.enqueue(request()) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(DownloadStatus.QUEUED.name, dao.records.single().status)
    }

    private fun repository(
        dao: FakeTaskDao,
        scheduler: DownloadWorkScheduler,
    ): WorkshopDownloads =
        RoomDownloadTaskRepository(
            context = ContextWrapper(null),
            taskDao = dao.proxy,
            credentialProvider =
                object : SteamContentCredentialProvider {
                    override suspend fun loadContentCredential(): SteamContentCredential? = null
                },
            workScheduler = scheduler,
            conversionScheduler = NoOpConversionScheduler,
            settingsRepository = TestSettingsRepository,
            downloadConcurrencyGovernor = DownloadConcurrencyGovernor(),
            steamWorkshopContentClient =
                SteamWorkshopContentClient(
                    gateway = object : WorkshopContentGateway {
                        override suspend fun fetchContentTarget(
                            publishedFileId: Long,
                            proxyUrl: String,
                        ): WorkshopContentTarget = error("not used")

                        override suspend fun download(
                            target: WorkshopContentTarget,
                            destinationDirectory: java.io.File,
                            credential: SteamContentCredential?,
                            options: SteamContentDownloadOptions,
                            control: suspend () -> SteamDownloadControl,
                            onProgress: suspend (SteamDownloadProgress) -> Unit,
                        ): SteamContentDownloadResult = error("not used")
                    },
                ),
            queuedMessage = "queued",
            enqueueFailedMessage = { type -> "enqueue failed: $type" },
        )

    private fun request(
        taskId: String? = null,
        preset: MpkgConversionPreset = MpkgConversionPreset.BALANCED,
        preserveAudio: Boolean = false,
    ) =
        DownloadRequest(
            workshopId = WORKSHOP_ID,
            title = "Test wallpaper",
            type = WorkshopType.SCENE,
            taskId = taskId,
            mpkgConversionPreset = preset,
            preserveAudio = preserveAudio,
        )

    private fun queuedRecord(taskId: String) =
        FormalTaskRecordEntity(
            taskId = taskId,
            workshopId = WORKSHOP_ID,
            title = "Test wallpaper",
            type = WorkshopType.SCENE.name,
            status = DownloadStatus.QUEUED.name,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private class RecordingScheduler(
        private val failure: Throwable? = null,
    ) : DownloadWorkScheduler {
        var wakeCount = 0

        override suspend fun wake() {
            wakeCount += 1
            failure?.let { throw it }
        }
    }

    private class FakeTaskDao(vararg initial: FormalTaskRecordEntity) {
        val records = initial.toMutableList()
        val commands = mutableListOf<com.wallhub.android.core.database.DownloadCommandEntity>()
        private val observed = MutableStateFlow(records.toList())

        val proxy: FormalTaskRecordDao =
            Proxy.newProxyInstance(
                FormalTaskRecordDao::class.java.classLoader,
                arrayOf(FormalTaskRecordDao::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "observeAll" -> observed
                    "find" -> records.firstOrNull { it.taskId == args!![0] }
                    "findActiveForWorkshop" ->
                        records
                            .filter { it.workshopId == args!![0] }
                            .filter { it.status in ACTIVE_STATUSES }
                            .maxByOrNull(FormalTaskRecordEntity::updatedAt)
                    "listAll" -> records.toList()
                    "upsert", "upsertWithCommand" -> {
                        val record = args!![0] as FormalTaskRecordEntity
                        records.removeAll { it.taskId == record.taskId }
                        records += record
                        if (method.name == "upsertWithCommand") {
                            commands += args[1] as com.wallhub.android.core.database.DownloadCommandEntity
                        }
                        observed.value = records.toList()
                        Unit
                    }
                    "insertCommand" -> {
                        commands += args!![0] as com.wallhub.android.core.database.DownloadCommandEntity
                        Unit
                    }
                    "persistConversionPlan" -> {
                        val taskId = args!![0] as String
                        val index = records.indexOfFirst { it.taskId == taskId }
                        if (index < 0) {
                            0
                        } else {
                            records[index] =
                                records[index].copy(
                                    conversionPlanJson = args[1] as String?,
                                    conversionPlanVersion = args[2] as Int,
                                )
                            observed.value = records.toList()
                            1
                        }
                    }
                    "persistConversionReport" -> {
                        val taskId = args!![0] as String
                        val index = records.indexOfFirst { it.taskId == taskId }
                        if (index < 0) {
                            0
                        } else {
                            records[index] = records[index].copy(conversionReportJson = args[1] as String?)
                            observed.value = records.toList()
                            1
                        }
                    }
                    "nextQueuePosition" -> (records.maxOfOrNull { it.queuePosition } ?: 0L) + 1L
                    else -> error("Unexpected DAO call: ${method.name}")
                }
            } as FormalTaskRecordDao
    }

    private object NoOpConversionScheduler : ConversionWorkScheduler {
        override suspend fun enqueue(taskId: String) = Unit
        override suspend fun replace(taskId: String) = Unit
        override suspend fun cancel(taskId: String) = Unit
    }

    private object TestSettingsRepository : SettingsRepository {
        override val preferences: Flow<AppPreferences> = MutableStateFlow(AppPreferences())
        override suspend fun setTheme(theme: com.wallhub.android.core.model.ThemePreference) = Unit
        override suspend fun setAccent(
            accent: com.wallhub.android.core.model.AccentPreference,
            customColor: String?,
        ) = Unit
        override suspend fun setHomePreferences(
            pageSize: Int,
            columns: Int,
            multiSelect: Boolean,
            cardAction: com.wallhub.android.core.model.HomeCardAction,
            matureContentEnabled: Boolean,
        ) = Unit
        override suspend fun setDownloadPreferences(
            maxConcurrentDownloads: Int,
            chunkDownloadConcurrency: Int,
            proxyUrl: String,
            mediaCacheLimitMb: Int,
        ) = Unit
        override suspend fun setOutputDirectory(treeUri: String, label: String) = Unit
        override suspend fun clearOutputDirectory() = Unit
    }

    private companion object {
        const val WORKSHOP_ID = 123L
        val ACTIVE_STATUSES =
            setOf("QUEUED", "RESOLVING", "DOWNLOADING", "PAUSED", "CONVERTING", "EXPORTING")
    }
}
