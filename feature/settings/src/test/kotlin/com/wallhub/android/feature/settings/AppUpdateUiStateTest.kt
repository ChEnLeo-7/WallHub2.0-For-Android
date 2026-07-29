package com.wallhub.android.feature.settings

import com.wallhub.android.core.model.AppReleaseInfo
import com.wallhub.android.core.model.InstalledAppInfo
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppUpdateUiStateTest {
    private val installed = InstalledAppInfo(
        appName = "WallHub",
        packageName = "com.wallhub.android",
        versionName = "0.8.25",
        versionCode = 35L,
        lastUpdateTimeMillis = 1_700_000_000_000L,
    )
    private val release = AppReleaseInfo(
        tagName = "v0.8.26",
        versionName = "0.8.26",
        releaseName = "WallHub 0.8.26",
        notes = "# Update",
        publishedAt = "2026-07-29T00:00:00Z",
        htmlUrl = "https://github.com/ChEnLeo-7/WallHub2.0-For-Android/releases/tag/v0.8.26",
        assetName = "wallhub-0.8.26-universal.apk",
        assetUrl = "https://example.com/wallhub.apk",
        assetSizeBytes = 1_024L,
        sha256 = "00",
        isNewer = true,
    )

    @Test
    fun `download action only appears for available or retryable releases`() {
        assertTrue(
            AppUpdateUiState(
                installed = installed,
                phase = AppUpdatePhase.AVAILABLE,
                release = release,
            ).canDownloadRelease,
        )
        assertTrue(
            AppUpdateUiState(
                installed = installed,
                phase = AppUpdatePhase.FAILED,
                release = release,
            ).canDownloadRelease,
        )
        listOf(
            AppUpdatePhase.IDLE,
            AppUpdatePhase.CHECKING,
            AppUpdatePhase.UP_TO_DATE,
            AppUpdatePhase.DOWNLOADING,
            AppUpdatePhase.DOWNLOADED,
        ).forEach { phase ->
            assertEquals(
                false,
                AppUpdateUiState(
                    installed = installed,
                    phase = phase,
                    release = release,
                ).canDownloadRelease,
                "Unexpected download action for $phase",
            )
        }
        assertEquals(
            false,
            AppUpdateUiState(
                installed = installed,
                phase = AppUpdatePhase.FAILED,
                release = release,
                downloadedApkPath = "/tmp/wallhub.apk",
            ).canDownloadRelease,
        )
    }

    @Test
    fun `about metadata keeps community and contribution details`() {
        assertEquals("WallHub For Android", WALLHUB_PROJECT_TITLE)
        assertEquals("1082323527", WALLHUB_QQ_GROUP_NUMBER)
        assertTrue(WALLHUB_QQ_GROUP_JOIN_URI.startsWith("mqqapi://card/show_pslcard?"))
        assertTrue(WALLHUB_QQ_GROUP_JOIN_URI.contains("uin=1082323527"))
        assertTrue(WALLHUB_QQ_GROUP_JOIN_URI.contains("card_type=group"))
        assertEquals("2023-11-14", formatAppUpdateDate(installed.lastUpdateTimeMillis, ZoneOffset.UTC))
        assertEquals("—", formatAppUpdateDate(0L, ZoneOffset.UTC))
        assertEquals("CHENLEO_7", WALLHUB_AUTHOR.displayName)
        assertEquals("ChEnLeo-7", WALLHUB_AUTHOR.githubAccount)
        assertEquals(
            listOf(
                "uwugl" to "LOGO 设计、技术指导",
                "cccp114" to "LOGO 修改、UI 建议",
                "hf5203344" to "参与了早期开发的深度内测",
            ),
            WALLHUB_CONTRIBUTORS.map { contributor ->
                contributor.displayName to contributor.roleZh
            },
        )
        assertTrue(
            (listOf(WALLHUB_AUTHOR) + WALLHUB_CONTRIBUTORS).all { person ->
                person.avatarUrl == "https://github.com/${person.githubAccount}.png?size=160"
            },
        )
    }

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
