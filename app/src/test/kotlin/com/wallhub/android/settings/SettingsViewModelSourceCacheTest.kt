package com.wallhub.settings

import android.test.mock.MockContext
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.AppReleaseInfo
import com.wallhub.android.core.model.AppUpdateRepository
import com.wallhub.android.core.model.DiagnosticExportRepository
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.InstalledAppInfo
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopVideoOpenRequest
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import com.wallhub.android.data.downloads.WorkshopSourceCacheManager
import com.wallhub.android.testutil.MainDispatcherRule
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsViewModelSourceCacheTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `source cache actions persist enabled state and positive budget`() {
        val settings = RecordingSettingsRepository()
        val filesDir = Files.createTempDirectory("settings-source-cache").toFile()
        try {
            val viewModel = createViewModel(settings, sourceCacheManager(filesDir, settings))

            viewModel.onAction(SettingsAction.WorkshopSourceCacheEnabledChanged(false))
            viewModel.onAction(SettingsAction.WorkshopSourceCacheBudgetChanged(2))
            viewModel.onAction(SettingsAction.WorkshopSourceCacheBudgetChanged(0))

            assertEquals(false, settings.enabled)
            assertEquals(2, settings.budgetGiB)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun createViewModel(
        settings: SettingsRepository,
        sourceCacheManager: WorkshopSourceCacheManager,
    ) = SettingsViewModel(
        context = MockContext(),
        settingsRepository = settings,
        launcherIconController = object : LauncherIconController {
            override fun setThemedIconEnabled(enabled: Boolean) = Unit
        },
        steamSessionRepository = EmptySteamSessionRepository,
        diagnosticExportRepository = object : DiagnosticExportRepository {
            override suspend fun exportTo(destinationUri: String) = Unit
        },
        steamAccessRepository = EmptySteamAccessRepository,
        appUpdateRepository = EmptyAppUpdateRepository,
        workshopVideoStreamRepository = EmptyWorkshopVideoStreamRepository,
        workshopSourceCacheManager = sourceCacheManager,
    )

    private fun sourceCacheManager(
        filesDir: java.io.File,
        settings: SettingsRepository,
    ): WorkshopSourceCacheManager {
        val dao =
            Proxy.newProxyInstance(
                FormalTaskRecordDao::class.java.classLoader,
                arrayOf(FormalTaskRecordDao::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "listRetainedStaging" -> emptyList<Any>()
                    else -> error("Unexpected DAO call ${method.name}")
                }
            } as FormalTaskRecordDao
        return WorkshopSourceCacheManager(filesDir, dao, settings)
    }

    private class RecordingSettingsRepository : SettingsRepository {
        override val preferences = MutableStateFlow(AppPreferences())
        var enabled: Boolean? = null
        var budgetGiB: Int? = null

        override suspend fun setWorkshopSourceCacheEnabled(enabled: Boolean) {
            this.enabled = enabled
            preferences.value = preferences.value.copy(workshopSourceCacheEnabled = enabled)
        }

        override suspend fun setWorkshopSourceCacheBudgetGiB(budgetGiB: Int) {
            this.budgetGiB = budgetGiB
            preferences.value = preferences.value.copy(workshopSourceCacheBudgetGiB = budgetGiB)
        }

        override suspend fun setTheme(theme: ThemePreference) = Unit
        override suspend fun setAccent(accent: AccentPreference, customColor: String?) = Unit
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

        override suspend fun setOutputDirectory(treeUri: String, label: String) = Unit
        override suspend fun clearOutputDirectory() = Unit
    }

    private object EmptySteamSessionRepository : SteamSessionRepository {
        override val session = MutableStateFlow(SteamSessionState())
        override fun restorePersistedSession() = Unit
        override fun login(accountName: String, password: String) = Unit
        override fun submitSteamGuardCode(code: String) = Unit
        override fun useManualSteamGuardFallback() = Unit
        override fun logout() = Unit
    }

    private object EmptySteamAccessRepository : SteamAccessRepository {
        override val state = MutableStateFlow(SteamAccessState())
        override fun refresh() = Unit
    }

    private object EmptyAppUpdateRepository : AppUpdateRepository {
        override val installedAppInfo = InstalledAppInfo("WallHub", "com.wallhub.android", "test", 1)
        override suspend fun latestRelease(): AppReleaseInfo = error("Not used")
        override suspend fun downloadRelease(
            release: AppReleaseInfo,
            onProgress: (Long, Long) -> Unit,
        ): String = error("Not used")
    }

    private object EmptyWorkshopVideoStreamRepository : WorkshopVideoStreamRepository {
        override suspend fun open(request: WorkshopVideoOpenRequest): WorkshopVideoStreamSession = error("Not used")
    }
}
