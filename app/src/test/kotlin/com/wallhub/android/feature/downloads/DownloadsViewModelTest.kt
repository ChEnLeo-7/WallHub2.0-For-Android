package com.wallhub.android.feature.downloads

import androidx.lifecycle.SavedStateHandle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    @Test
    fun `ordinary task action executes without requesting storage permission`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val repository = FakeDownloadTaskRepository(task())
                val viewModel = DownloadsViewModel(repository, EmptySettingsRepository)
                val effects = mutableListOf<DownloadsEffect>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.effects.collect(effects::add)
                }

                viewModel.onAction(
                    DownloadsAction.RequestTaskAction(TASK_ID, DownloadAction.PAUSE),
                )
                advanceUntilIdle()

                assertEquals(listOf(TASK_ID to DownloadAction.PAUSE), repository.requestedActions)
                assertTrue(effects.isEmpty())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `export waits for permission result then executes and reports once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val repository = FakeDownloadTaskRepository(task())
                val viewModel = DownloadsViewModel(repository, EmptySettingsRepository)
                val effects = mutableListOf<DownloadsEffect>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.effects.collect(effects::add)
                }

                viewModel.onAction(
                    DownloadsAction.RequestTaskAction(TASK_ID, DownloadAction.EXPORT),
                )
                advanceUntilIdle()

                val permissionEffect =
                    assertIs<DownloadsEffect.ResolveLegacyStoragePermission>(
                        effects.single(),
                    )
                assertTrue(repository.requestedActions.isEmpty())

                viewModel.onAction(
                    DownloadsAction.LegacyStoragePermissionResult(
                        operation = permissionEffect.operation,
                        granted = true,
                    ),
                )
                advanceUntilIdle()

                assertEquals(listOf(TASK_ID to DownloadAction.EXPORT), repository.requestedActions)
                assertEquals(
                    "已加入转换和导出任务",
                    assertIs<DownloadsEffect.ShowMessage>(effects.last()).message,
                )
                assertEquals(2, effects.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `denied permission emits feedback without running pending operation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val repository = FakeDownloadTaskRepository(task())
                val viewModel = DownloadsViewModel(repository, EmptySettingsRepository)
                val effects = mutableListOf<DownloadsEffect>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.effects.collect(effects::add)
                }
                val pending = DownloadsPendingOperation.TaskAction(TASK_ID, DownloadAction.EXPORT)

                viewModel.onAction(
                    DownloadsAction.LegacyStoragePermissionResult(pending, granted = false),
                )
                advanceUntilIdle()

                assertTrue(repository.requestedActions.isEmpty())
                assertEquals(
                    "未授予存储权限，无法导出到 Download/WallHub",
                    assertIs<DownloadsEffect.ShowMessage>(effects.single()).message,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `download filters survive state source recreation`() =
        runTest {
            val savedStateHandle = SavedStateHandle()
            val repository = FakeDownloadTaskRepository(task())
            val first = DownloadsStateSource(repository, savedStateHandle)

            first.setFilter(DownloadFilter.FAILED)
            first.setTypeFilter(DownloadTypeFilter.VIDEO)

            val restored = DownloadsStateSource(repository, savedStateHandle).states.first()

            assertEquals(DownloadFilter.FAILED, restored.filter)
            assertEquals(DownloadTypeFilter.VIDEO, restored.typeFilter)
        }

    private fun task() =
        DownloadTask(
            id = TASK_ID,
            workshopId = 1L,
            title = "test",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
        )

    private companion object {
        const val TASK_ID = "task-1"
    }
}

private class FakeDownloadTaskRepository(
    task: DownloadTask,
) : DownloadTaskRepository {
    private val mutableTasks = MutableStateFlow(listOf(task))
    val requestedActions = mutableListOf<Pair<String, DownloadAction>>()

    override val tasks: Flow<List<DownloadTask>> = mutableTasks

    override suspend fun find(taskId: String): DownloadTask? =
        mutableTasks.value.firstOrNull { task ->
            task.id == taskId
        }

    override suspend fun upsert(task: DownloadTask) {
        mutableTasks.value = mutableTasks.value.filterNot { current -> current.id == task.id } + task
    }

    override suspend fun enqueue(request: DownloadRequest): DownloadTask =
        DownloadTask(
            id = request.workshopId.toString(),
            workshopId = request.workshopId,
            title = request.title,
            type = request.type,
            status = DownloadStatus.QUEUED,
        )

    override suspend fun requestAction(
        taskId: String,
        action: DownloadAction,
    ) {
        requestedActions += taskId to action
    }

    override suspend fun reorder(taskIds: List<String>) = Unit

    override suspend fun clearFinishedHistory(): Int = 0
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
