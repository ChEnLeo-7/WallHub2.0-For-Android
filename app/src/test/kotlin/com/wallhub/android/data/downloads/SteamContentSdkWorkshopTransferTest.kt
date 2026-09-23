package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import io.steamcontent.sdk.TaskFailure
import io.steamcontent.sdk.TaskState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private val SDK_CREDENTIAL = SteamContentCredential(accountName = "account", refreshToken = "synthetic-test-token")

private fun completed(committedBytes: Long): SteamContentSdkTaskStatus =
    SteamContentSdkTaskStatus(
        state = TaskState.COMPLETED,
        committedBytes = committedBytes,
        totalDecodedBytes = committedBytes,
        errorOrdinal = TaskFailure.NONE,
    )

class SteamContentSdkWorkshopTransferTest {
    @Test
    fun aDownloadResolvesTheItemAndStartsTheSdkTaskAtTheAppLayoutDestination() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses += completed(committedBytes = 9L)
                val result = fixture.download()

                assertEquals(1, fixture.session.resolveCalls)
                val started = fixture.session.starts.single()
                assertEquals(fixture.resolved.appId, started.appId)
                assertEquals(fixture.resolved.depotId, started.depotId)
                assertEquals(fixture.resolved.manifestId, started.manifestId)
                assertEquals("wallhub-workshop/task-1", started.destination)
                assertEquals(9L, result.downloadedBytes)
                assertEquals(9L, result.totalBytes)
                assertTrue(result.usedAuthenticatedSession)
                assertTrue(fixture.session.closed)
                assertEquals(1, fixture.task.closeCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun progressAndTerminalStatesMapOntoTheAppProgressContract() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses +=
                    SteamContentSdkTaskStatus(
                        state = TaskState.DOWNLOADING,
                        committedBytes = 4L,
                        totalDecodedBytes = 9L,
                        errorOrdinal = TaskFailure.NONE,
                    )
                fixture.task.statuses += completed(committedBytes = 9L)
                val progress = mutableListOf<SteamDownloadProgress>()

                fixture.download(onProgress = { progress += it })

                assertEquals(
                    listOf(
                        SteamDownloadPhase.CONNECTING,
                        SteamDownloadPhase.RESOLVING,
                        SteamDownloadPhase.PREPARING_DOWNLOAD,
                        SteamDownloadPhase.DOWNLOADING,
                        SteamDownloadPhase.DOWNLOADING,
                    ),
                    progress.map { it.phase },
                )
                assertEquals(4L, progress[3].completedBytes)
                assertEquals(9L, progress[3].totalBytes)
                assertEquals(9L, progress[4].completedBytes)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aTaskFailureSurfacesTheRedactedSdkDiagnostic() {
        val fixture = Fixture()
        try {
            fixture.task.statuses +=
                SteamContentSdkTaskStatus(
                    state = TaskState.FAILED,
                    committedBytes = 0L,
                    totalDecodedBytes = 0L,
                    errorOrdinal = TaskFailure.NETWORK,
                )
            fixture.task.diagnostic = "cdn connection reset"

            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { fixture.download() }
                }
            assertTrue(failure.message.orEmpty().contains("network"))
            assertTrue(failure.message.orEmpty().contains("cdn connection reset"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failuresNeverCarryTheRefreshToken() {
        val fixture = Fixture()
        try {
            fixture.task.statuses +=
                SteamContentSdkTaskStatus(
                    state = TaskState.FAILED,
                    committedBytes = 0L,
                    totalDecodedBytes = 0L,
                    errorOrdinal = TaskFailure.AUTHORIZATION,
                )
            fixture.task.diagnostic = "authorization denied"
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { fixture.download() }
                }
            assertTrue(!failure.message.orEmpty().contains(SDK_CREDENTIAL.refreshToken))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun anSdkCancellationStateIsTheAppsCancellationSignal() {
        val fixture = Fixture()
        try {
            fixture.task.statuses +=
                SteamContentSdkTaskStatus(
                    state = TaskState.CANCELLED,
                    committedBytes = 0L,
                    totalDecodedBytes = 0L,
                    errorOrdinal = TaskFailure.CANCELLED,
                )
            assertThrows(SteamDownloadCancelledException::class.java) {
                runBlocking { fixture.download() }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pauseReachesTheSdkTaskAndRaisesTheAppPauseSignal() {
        val fixture = Fixture()
        try {
            assertThrows(SteamDownloadPausedException::class.java) {
                runBlocking { fixture.download(control = { SteamDownloadControl.PAUSE }) }
            }
            assertEquals(1, fixture.task.pauseCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelReachesTheSdkTaskAndRaisesTheAppCancelSignal() {
        val fixture = Fixture()
        try {
            assertThrows(SteamDownloadCancelledException::class.java) {
                runBlocking { fixture.download(control = { SteamDownloadControl.CANCEL }) }
            }
            assertEquals(1, fixture.task.cancelCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun anSdkPausedTaskIsResumedWhileTheAppKeepsDownloading() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses += SteamContentSdkTaskStatus(TaskState.PAUSED, 0L, 0L, TaskFailure.NONE)
                fixture.task.statuses += completed(committedBytes = 9L)

                fixture.download()

                assertEquals(1, fixture.task.resumeCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aResumeAfterPauseStartsANewTaskAtTheSameDestination() =
        runBlocking {
            val fixture = Fixture()
            try {
                assertThrows(SteamDownloadPausedException::class.java) {
                    runBlocking { fixture.download(control = { SteamDownloadControl.PAUSE }) }
                }
                fixture.task.statuses += completed(committedBytes = 9L)
                fixture.download()

                assertEquals(2, fixture.session.starts.size)
                assertEquals(
                    setOf("wallhub-workshop/task-1"),
                    fixture.session.starts.map { it.destination }.toSet(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aMissingRefreshTokenFailsInsteadOfFallingBack() {
        val fixture = Fixture()
        try {
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { fixture.download(credential = null) }
                }
            assertTrue(failure.message.orEmpty().contains("refresh token"))
            assertEquals(0, fixture.openerCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aBlankRefreshTokenFailsInsteadOfFallingBack() {
        val fixture = Fixture()
        try {
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        fixture.download(credential = SteamContentCredential("account", "   "))
                    }
                }
            assertTrue(failure.message.orEmpty().contains("refresh token"))
            assertEquals(0, fixture.openerCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun anUnreadableTokenFailsInsteadOfFallingBack() {
        val fixture = Fixture()
        try {
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        fixture.download(opener = { throw IllegalStateException("credential store is unreadable") })
                    }
                }
            assertTrue(failure.message.orEmpty().contains("unreadable"))
            assertTrue(fixture.session.starts.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aDestinationOutsideTheSdkStorageRootFailsBeforeAnyTaskStarts() {
        val fixture = Fixture()
        val outside = Files.createTempDirectory("sdk-transfer-outside").toFile()
        try {
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { fixture.download(destination = outside) }
                }
            assertTrue(failure.message.orEmpty().contains("storage root"))
            assertTrue(fixture.session.starts.isEmpty())
        } finally {
            outside.deleteRecursively()
            fixture.close()
        }
    }

    @Test
    fun anUnresolvedItemFailsBeforeAnyTaskStarts() {
        val fixture = Fixture()
        try {
            fixture.session.resolved = fixture.resolved.copy(resolved = false, appId = 0L, depotId = 0L, manifestId = 0L)
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { fixture.download() }
                }
            assertTrue(failure.message.orEmpty().contains("did not resolve"))
            assertTrue(fixture.session.starts.isEmpty())
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("sdk-transfer-root").toFile()
        val destination: File = File(root, "wallhub-workshop/task-1").apply { mkdirs() }
        val resolved =
            SteamContentSdkResolvedItem(
                itemId = 7L,
                appId = 431960L,
                depotId = 431961L,
                manifestId = 42L,
                resolved = true,
            )
        val task = FakeTask()
        val session = FakeSession(root, resolved, task)
        var openerCalls = 0
        private var opener: suspend (SteamContentCredential) -> SteamContentSdkSession = { session }
        private val transfer =
            SteamContentSdkWorkshopTransfer(
                sessionOpener =
                    object : SteamContentSdkSessionOpener {
                        override suspend fun open(credential: SteamContentCredential): SteamContentSdkSession {
                            openerCalls += 1
                            return opener(credential)
                        }
                    },
            )
        val target =
            WorkshopContentTarget(
                publishedFileId = 7L,
                title = "Scene",
                appId = 431960,
                contentManifestId = 42L,
                expectedSize = 9L,
                contentTypeHint = "scene",
            )

        suspend fun download(
            destination: File = this.destination,
            credential: SteamContentCredential? = SDK_CREDENTIAL,
            control: suspend () -> SteamDownloadControl = { SteamDownloadControl.CONTINUE },
            onProgress: suspend (SteamDownloadProgress) -> Unit = {},
            opener: (suspend (SteamContentCredential) -> SteamContentSdkSession)? = null,
        ): SteamContentDownloadResult {
            if (opener != null) this.opener = opener
            return transfer.download(
                target = target,
                destinationDirectory = destination,
                credential = credential,
                options = SteamContentDownloadOptions(),
                control = control,
                onProgress = onProgress,
            )
        }

        fun close() {
            root.deleteRecursively()
        }
    }

    private class FakeSession(
        override val storageRoot: File,
        resolved: SteamContentSdkResolvedItem,
        private val task: FakeTask,
    ) : SteamContentSdkSession {
        var resolved: SteamContentSdkResolvedItem = resolved
        val starts = mutableListOf<StartCall>()
        var resolveCalls = 0
        var closed = false

        override fun resolveWorkshop(itemId: Long): SteamContentSdkResolvedItem {
            resolveCalls += 1
            return resolved
        }

        override fun startDownload(
            appId: Long,
            depotId: Long,
            manifestId: Long,
            destination: String?,
        ): SteamContentSdkTask {
            starts += StartCall(appId, depotId, manifestId, destination)
            return task
        }

        override fun openRangeStream(
            appId: Long,
            depotId: Long,
            filePath: String,
            manifestId: Long,
        ): SteamContentSdkRangeStream = error("this fixture serves downloads, not range streams")

        override fun close() {
            closed = true
        }
    }

    private data class StartCall(
        val appId: Long,
        val depotId: Long,
        val manifestId: Long,
        val destination: String?,
    )

    private class FakeTask : SteamContentSdkTask {
        val statuses = ArrayDeque<SteamContentSdkTaskStatus>()
        var diagnostic: String? = null
        var pauseCalls = 0
        var resumeCalls = 0
        var cancelCalls = 0
        var closeCalls = 0

        override fun status(): SteamContentSdkTaskStatus =
            statuses.removeFirstOrNull() ?: SteamContentSdkTaskStatus(
                state = TaskState.COMPLETED,
                committedBytes = 0L,
                totalDecodedBytes = 0L,
                errorOrdinal = TaskFailure.NONE,
            )

        override fun pollEvent(timeoutMs: Int): SteamContentSdkTaskEvent? = null

        override fun lastError(): String? = diagnostic

        override fun pause() {
            pauseCalls += 1
        }

        override fun resume() {
            resumeCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
