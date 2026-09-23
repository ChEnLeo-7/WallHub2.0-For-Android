package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WorkshopDownloadPathSelectionTest {
    @Test
    fun theDefaultFlagSelectsTheKSteamAndWallHubRustPath() {
        assertEquals(
            WorkshopDownloadPath.KSTEAM_AND_WALLHUB_RUST,
            selectWorkshopDownloadPath(useSdkDepotCore = false),
        )
    }

    @Test
    fun theOptInFlagSelectsTheSteamContentSdkPath() {
        assertEquals(
            WorkshopDownloadPath.STEAM_CONTENT_SDK,
            selectWorkshopDownloadPath(useSdkDepotCore = true),
        )
    }

    @Test
    fun theRouterInvokesOnlyTheSelectedTransfer() =
        runBlocking {
            val legacy = RecordingTransfer()
            val sdk = RecordingTransfer()

            val unchecked = WorkshopDownloadTransferRouter(legacy, sdk, useSdkDepotCore = false)
            assertSame(legacy, unchecked.transfer())
            unchecked.transfer().download(
                target = target(),
                destinationDirectory = File("unused"),
                credential = CREDENTIAL,
                options = SteamContentDownloadOptions(),
                control = { SteamDownloadControl.CONTINUE },
                onProgress = {},
            )
            assertEquals(1, legacy.invocations)
            assertEquals(0, sdk.invocations)

            val optedIn = WorkshopDownloadTransferRouter(legacy, sdk, useSdkDepotCore = true)
            assertSame(sdk, optedIn.transfer())
            optedIn.transfer().download(
                target = target(),
                destinationDirectory = File("unused"),
                credential = CREDENTIAL,
                options = SteamContentDownloadOptions(),
                control = { SteamDownloadControl.CONTINUE },
                onProgress = {},
            )
            assertEquals(1, legacy.invocations)
            assertEquals(1, sdk.invocations)
        }

    private fun target(): WorkshopContentTarget =
        WorkshopContentTarget(
            publishedFileId = 7L,
            title = "Scene",
            appId = 431960,
            contentManifestId = 42L,
            expectedSize = 9L,
            contentTypeHint = "scene",
        )

    private class RecordingTransfer : WorkshopContentTransfer {
        var invocations = 0

        override suspend fun download(
            target: WorkshopContentTarget,
            destinationDirectory: File,
            credential: SteamContentCredential?,
            options: SteamContentDownloadOptions,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
        ): SteamContentDownloadResult {
            invocations += 1
            return SteamContentDownloadResult(
                rootDirectory = destinationDirectory,
                downloadedBytes = 0L,
                totalBytes = 0L,
                fileCount = 0,
                usedAuthenticatedSession = true,
            )
        }
    }

    private companion object {
        val CREDENTIAL = SteamContentCredential(accountName = "account", refreshToken = "token")
    }
}
