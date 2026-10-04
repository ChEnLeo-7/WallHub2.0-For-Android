package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import io.steamcontent.sdk.TaskEventKind
import io.steamcontent.sdk.TaskFailure
import io.steamcontent.sdk.TaskState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

private fun failed(ordinal: Long): SteamContentSdkTaskStatus =
    SteamContentSdkTaskStatus(
        state = TaskState.FAILED,
        committedBytes = 0L,
        totalDecodedBytes = 0L,
        errorOrdinal = ordinal,
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
                        networkBytes = 3L,
                        networkBytesPerSecond = 2L,
                        firstContentReceived = true,
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
                        SteamDownloadPhase.VERIFYING,
                    ),
                    progress.map { it.phase },
                )
                assertEquals(4L, progress[3].completedBytes)
                assertEquals(9L, progress[3].totalBytes)
                assertEquals(3L, progress[3].networkBytes)
                assertEquals(2L, progress[3].networkBytesPerSecond)
                assertTrue(progress[3].firstContentReceived)
                assertEquals(9L, progress[4].completedBytes)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun terminalStatusStillDrainsTheFirstDurableChunkEventOnce() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.events += SteamContentSdkTaskEvent(TaskEventKind.MANIFEST_RESOLVED, 0L)
                fixture.task.events += SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 4L)
                fixture.task.events += SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 9L)
                fixture.task.statuses += completed(committedBytes = 9L)
                var firstChunkCalls = 0

                fixture.download(onFirstChunkCommitted = { firstChunkCalls += 1 })

                assertEquals(1, firstChunkCalls)
                assertTrue(fixture.task.events.isEmpty())
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
    fun exhaustedCdnCandidatesRetryOnceAgainstTheSameDestination() =
        runBlocking {
            val fixture = Fixture()
            try {
                val retryTask = fixture.queueTask()
                fixture.task.statuses += failed(TaskFailure.NETWORK)
                fixture.task.diagnostic = "CDN candidate set exhausted"
                retryTask.statuses += completed(committedBytes = 9L)

                fixture.download()

                assertEquals(2, fixture.session.resolveCalls)
                assertEquals(2, fixture.session.starts.size)
                assertEquals(1, fixture.task.closeCalls)
                assertEquals(1, retryTask.closeCalls)
                assertEquals(
                    setOf("wallhub-workshop/task-1"),
                    fixture.session.starts.map { it.destination }.toSet(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun exhaustedCdnCandidatesPropagateAfterTheSecondFailure() {
        val fixture = Fixture()
        try {
            val retryTask = fixture.queueTask()
            fixture.task.statuses += failed(TaskFailure.NETWORK)
            fixture.task.diagnostic = "CDN candidate set exhausted"
            retryTask.statuses += failed(TaskFailure.NETWORK)
            retryTask.diagnostic = "CDN candidate set exhausted"

            assertThrows(SteamContentSdkTaskFailureException::class.java) {
                runBlocking { fixture.download() }
            }

            assertEquals(2, fixture.session.resolveCalls)
            assertEquals(2, fixture.session.starts.size)
            assertEquals(1, fixture.task.closeCalls)
            assertEquals(1, retryTask.closeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun otherNetworkDiagnosticsDoNotRetry() {
        val fixture = Fixture()
        try {
            fixture.task.statuses += failed(TaskFailure.NETWORK)
            fixture.task.diagnostic = "cdn connection reset"

            assertThrows(SteamContentSdkTaskFailureException::class.java) {
                runBlocking { fixture.download() }
            }

            assertEquals(1, fixture.session.resolveCalls)
            assertEquals(1, fixture.session.starts.size)
            assertEquals(1, fixture.task.closeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun authorizationFailuresDoNotRetry() {
        val fixture = Fixture()
        try {
            fixture.task.statuses += failed(TaskFailure.AUTHORIZATION)
            fixture.task.diagnostic = "CDN candidate set exhausted"

            assertThrows(SteamContentSdkTaskFailureException::class.java) {
                runBlocking { fixture.download() }
            }

            assertEquals(1, fixture.session.resolveCalls)
            assertEquals(1, fixture.session.starts.size)
            assertEquals(1, fixture.task.closeCalls)
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

    /**
     * The pause is the last call made to the live task, so what stops the run is the SDK's own durable
     * boundary and not a close that only aborts it.
     */
    @Test
    fun thePauseWaitsForTheSdkPausedStateBeforeTheTaskIsClosed() {
        val fixture = Fixture()
        try {
            fixture.task.statuses +=
                SteamContentSdkTaskStatus(
                    state = TaskState.DOWNLOADING,
                    committedBytes = 4L,
                    totalDecodedBytes = 9L,
                    errorOrdinal = TaskFailure.NONE,
                )
            fixture.task.statuses +=
                SteamContentSdkTaskStatus(
                    state = TaskState.PAUSED,
                    committedBytes = 4L,
                    totalDecodedBytes = 9L,
                    errorOrdinal = TaskFailure.NONE,
                )

            assertThrows(SteamDownloadPausedException::class.java) {
                runBlocking { fixture.download(control = { SteamDownloadControl.PAUSE }) }
            }

            assertEquals(listOf("pause", "status", "status", "close"), fixture.task.calls)
        } finally {
            fixture.close()
        }
    }

    /**
     * A pause the SDK refuses is still a pause.
     *
     * The SDK refuses one only when the run already ended or its handle is gone, and the durable checkpoint
     * survives either way. Reading that refusal as a failure would take the task out of the paused state the
     * resume action is offered from, so the app would offer a retry where it should offer a resume.
     */
    @Test
    fun aRefusedSdkPauseStillRaisesTheAppPauseSignal() {
        val fixture = Fixture()
        try {
            fixture.task.pauseFailure = IllegalStateException("this task has already finished")
            assertThrows(SteamDownloadPausedException::class.java) {
                runBlocking { fixture.download(control = { SteamDownloadControl.PAUSE }) }
            }
            assertEquals(1, fixture.task.pauseCalls)
            assertEquals(1, fixture.task.closeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelWaitsForTheSdkCancelledStateBeforeTheTaskIsClosed() {
        val fixture = Fixture()
        try {
            fixture.task.statuses +=
                SteamContentSdkTaskStatus(
                    state = TaskState.DOWNLOADING,
                    committedBytes = 4L,
                    totalDecodedBytes = 9L,
                    errorOrdinal = TaskFailure.NONE,
                )
            fixture.task.statuses +=
                SteamContentSdkTaskStatus(
                    state = TaskState.CANCELLED,
                    committedBytes = 4L,
                    totalDecodedBytes = 9L,
                    errorOrdinal = TaskFailure.CANCELLED,
                )

            assertThrows(SteamDownloadCancelledException::class.java) {
                runBlocking { fixture.download(control = { SteamDownloadControl.CANCEL }) }
            }

            assertEquals(1, fixture.task.cancelCalls)
            assertEquals(listOf("cancel", "status", "status", "close"), fixture.task.calls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun stoppingTheCallerCancelsTheSdkTaskBeforeClosingIt() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses +=
                    SteamContentSdkTaskStatus(
                        state = TaskState.DOWNLOADING,
                        committedBytes = 5L,
                        totalDecodedBytes = 9L,
                        errorOrdinal = TaskFailure.NONE,
                    )
                val reachedTransfer = CompletableDeferred<Unit>()
                val downloader =
                    launch {
                        fixture.download(
                            onProgress = { progress ->
                                if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                                    reachedTransfer.complete(Unit)
                                    awaitCancellation()
                                }
                            },
                        )
                    }
                reachedTransfer.await()
                downloader.cancelAndJoin()

                // The worker is stopped by WorkManager on this path, not by the control probe, so
                // closing the task is all that used to happen and the SDK transfer kept running.
                assertEquals(1, fixture.task.cancelCalls)
                assertEquals(1, fixture.task.closeCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun anSdkPausedTaskIsResumedWhileTheAppKeepsDownloading() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses +=
                    SteamContentSdkTaskStatus(
                        state = TaskState.PAUSED,
                        committedBytes = 0L,
                        totalDecodedBytes = 0L,
                        errorOrdinal = TaskFailure.NONE,
                    )
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

    /**
     * Two overlapping downloads must share one session instead of queueing on it.
     *
     * The product's "concurrent downloads" setting is inert if the transfer holds a lock for the whole
     * download: the second item then waits for the first to finish, however many workers the governor
     * admitted. Steam refuses a second live session for one account, so the fix is to share one session and
     * keep it alive until the last download releases it - not to give each download its own.
     */
    @Test
    fun overlappingDownloadsShareOneSessionInsteadOfQueueingOnIt() =
        runBlocking {
            val fixture = Fixture()
            try {
                // Both resolves have to be inside the session at once: with a lock around the download the
                // first would hold it while waiting here, and this latch would never reach zero.
                val bothInside = CountDownLatch(2)
                fixture.session.onResolve = {
                    bothInside.countDown()
                    check(bothInside.await(5, TimeUnit.SECONDS)) {
                        "the second download never entered the shared session"
                    }
                }
                fixture.task.statuses += completed(committedBytes = 9L)
                val second = File(fixture.root, "wallhub-workshop/task-2").apply { mkdirs() }

                val first = launch(Dispatchers.IO) { fixture.download() }
                val secondRun = launch(Dispatchers.IO) { fixture.download(destination = second) }
                first.join()
                secondRun.join()

                assertEquals(0L, bothInside.count)
                assertEquals("both downloads must share one session", 1, fixture.openerCalls)
                assertEquals("the shared session must be closed exactly once", 1, fixture.session.closeCalls)
                assertEquals(2, fixture.session.resolveCalls)
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

        fun queueTask(): FakeTask = FakeTask().also(session::queueTask)
        var openerCalls = 0
        private var opener: suspend (SteamContentCredential) -> SteamContentSdkSession = { session }
        private val transfer =
            SteamContentSdkWorkshopTransfer(
                sessionBroker =
                    SteamContentSdkSessionBroker(
                        opener =
                            object : SteamContentSdkSessionOpener {
                                override suspend fun open(credential: SteamContentCredential): SteamContentSdkSession {
                                    openerCalls += 1
                                    return opener(credential)
                                }
                            },
                    ),
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
            onFirstChunkCommitted: (suspend () -> Unit)? = null,
            opener: (suspend (SteamContentCredential) -> SteamContentSdkSession)? = null,
        ): SteamContentDownloadResult {
            if (opener != null) this.opener = opener
            return transfer.download(
                target = target,
                destinationDirectory = destination,
                credential = credential,
                options = SteamContentDownloadOptions(onFirstChunkCommitted = onFirstChunkCommitted),
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
        private val queuedTasks = ArrayDeque<SteamContentSdkTask>()
        var resolved: SteamContentSdkResolvedItem = resolved
        val starts = mutableListOf<StartCall>()

        fun queueTask(task: SteamContentSdkTask) {
            queuedTasks += task
        }
        var resolveCalls = 0
        var closeCalls = 0
        var closed = false

        /** Runs inside a resolve, so a test can hold one download open while another starts. */
        var onResolve: (() -> Unit)? = null

        override fun resolveWorkshop(itemId: Long): SteamContentSdkResolvedItem {
            resolveCalls += 1
            onResolve?.invoke()
            return resolved
        }

        override fun startDownload(
            appId: Long,
            depotId: Long,
            manifestId: Long,
            destination: String?,
            chunkConcurrency: Int,
        ): SteamContentSdkTask {
            starts += StartCall(appId, depotId, manifestId, destination, chunkConcurrency)
            return if (starts.size == 1) task else queuedTasks.removeFirstOrNull() ?: task
        }

        override fun openRangeStream(
            appId: Long,
            depotId: Long,
            filePath: String,
            manifestId: Long,
            cacheCapacityBytes: Long,
        ): SteamContentSdkRangeStream = error("this fixture serves downloads, not range streams")

        override fun manifestFiles(
            appId: Long,
            depotId: Long,
            manifestId: Long,
        ): List<SteamContentSdkManifestFile> = error("this fixture serves downloads, not manifests")

        override fun close() {
            closeCalls += 1
            closed = true
        }
    }

    private data class StartCall(
        val appId: Long,
        val depotId: Long,
        val manifestId: Long,
        val destination: String?,
        val chunkConcurrency: Int,
    )

    private class FakeTask : SteamContentSdkTask {
        val statuses = ArrayDeque<SteamContentSdkTaskStatus>()
        val events = ArrayDeque<SteamContentSdkTaskEvent>()
        var diagnostic: String? = null
        var pauseCalls = 0
        var resumeCalls = 0
        var cancelCalls = 0
        var closeCalls = 0

        /** Calls in the order the transfer made them, so the pause-then-close order is pinned. */
        val calls = mutableListOf<String>()

        /** What the SDK's pause call throws, if it refuses the request. */
        var pauseFailure: Throwable? = null

        override fun status(): SteamContentSdkTaskStatus {
            calls += "status"
            return statuses.removeFirstOrNull() ?: SteamContentSdkTaskStatus(
                state = if (pauseCalls > 0) TaskState.PAUSED else if (cancelCalls > 0) TaskState.CANCELLED else TaskState.COMPLETED,
                committedBytes = 0L,
                totalDecodedBytes = 0L,
                errorOrdinal = if (cancelCalls > 0) TaskFailure.CANCELLED else TaskFailure.NONE,
            )
        }

        override fun pollEvent(timeoutMs: Int): SteamContentSdkTaskEvent? = synchronized(events) { events.removeFirstOrNull() }

        override fun lastError(): String? = diagnostic

        override fun pause() {
            pauseCalls += 1
            calls += "pause"
            pauseFailure?.let { throw it }
        }

        override fun resume() {
            resumeCalls += 1
            calls += "resume"
        }

        override fun cancel() {
            cancelCalls += 1
            calls += "cancel"
        }

        override fun close() {
            closeCalls += 1
            calls += "close"
        }
    }

    @Test
    fun `every published failure ordinal has a name, and the refusal is not a network one`() {
        // The ordinals are the published vocabulary; an unnamed one renders as a bare number in the UI.
        assertEquals("cancelled", sdkFailureName(TaskFailure.CANCELLED))
        assertEquals("network", sdkFailureName(TaskFailure.NETWORK))
        assertEquals("authorization", sdkFailureName(TaskFailure.AUTHORIZATION))
        // The device reading this pins: a permanent source refusal used to arrive as `NETWORK`, so the user was
        // told to retry what the service had already refused.
        assertEquals("cdn refused", sdkFailureName(TaskFailure.CDN_REFUSED))
    }
}
