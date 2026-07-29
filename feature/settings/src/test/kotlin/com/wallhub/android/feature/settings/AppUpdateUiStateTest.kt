package com.wallhub.android.feature.settings

import com.wallhub.android.core.model.InstalledAppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdateUiStateTest {
    private val installed = InstalledAppInfo(
        appName = "WallHub",
        packageName = "com.wallhub.android",
        versionName = "0.8.25",
        versionCode = 35L,
    )

    @Test
    fun `download progress uses bounded byte ratio`() {
        assertEquals(
            0.5f,
            AppUpdateUiState(
                installed = installed,
                downloadedBytes = 50L,
                totalBytes = 100L,
            ).progress,
        )
        assertEquals(
            1f,
            AppUpdateUiState(
                installed = installed,
                downloadedBytes = 120L,
                totalBytes = 100L,
            ).progress,
        )
    }

    @Test
    fun `unknown total reports zero progress`() {
        assertEquals(
            0f,
            AppUpdateUiState(
                installed = installed,
                downloadedBytes = 50L,
                totalBytes = 0L,
            ).progress,
        )
    }
}
