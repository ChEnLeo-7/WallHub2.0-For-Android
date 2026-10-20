package com.wallhub.android.data.downloads

import io.steamcontent.sdk.TaskEventKind
import io.steamcontent.sdk.TaskFailure
import io.steamcontent.sdk.TaskState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `chunk and checkpoint info logs are aggregated without dropping events`() {
        var nowMs = 1_000L
        val logger = RecordingTaskEventInfoLog()
        val eventLog = SdkTaskEventLog(logger = logger, clockMs = { nowMs })

        eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 4L))
        eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 9L))
        eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHECKPOINT_COMMITTED, 9L))
        assertEquals(1, logger.messages.size)
        assertTrue(logger.messages.single().contains("CHUNK_PROGRESS"))

        nowMs += 1_000L
        eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 12L))

        assertEquals(2, logger.messages.size)
        assertTrue(logger.messages.last().contains("aggregated"))
        assertTrue(logger.messages.last().contains("count=3"))
        assertTrue(logger.messages.last().contains("value_delta=8"))
    }

    @Test
    fun `phase and every terminal event flush aggregate before retaining their own info line`() {
        listOf(
            TaskEventKind.COMPLETED to "COMPLETED",
            TaskEventKind.FAILED to "FAILED",
            TaskEventKind.CANCELLED to "CANCELLED",
        ).forEach { (terminal, terminalName) ->
            val logger = RecordingTaskEventInfoLog()
            val eventLog = SdkTaskEventLog(logger = logger, clockMs = { 1_000L })
            eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 4L))
            eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 9L))

            eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.PHASE_CHANGED, 2L))
            eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 12L))
            eventLog.accept(SteamContentSdkTaskEvent(terminal, 12L))

            assertEquals(5, logger.messages.size)
            assertTrue(logger.messages[1].contains("aggregated"))
            assertTrue(logger.messages[2].contains("PHASE_CHANGED"))
            assertTrue(logger.messages[3].contains("aggregated"))
            assertTrue(logger.messages[4].contains(terminalName))
        }
    }

    @Test
    fun `closing event log flushes pending aggregate for exceptional exit`() {
        val logger = RecordingTaskEventInfoLog()
        val eventLog = SdkTaskEventLog(logger = logger, clockMs = { 1_000L })
        eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 4L))
        eventLog.accept(SteamContentSdkTaskEvent(TaskEventKind.CHUNK_PROGRESS, 9L))

        eventLog.close()
        eventLog.close()

        assertEquals(2, logger.messages.size)
        assertTrue(logger.messages.last().contains("aggregated"))
        assertTrue(logger.messages.last().contains("count=1"))
    }

    @Test
    fun aCompleteWallpaperEngineIdentitySkipsProductInfoAndStartsTheSdkTaskAtTheAppLayoutDestination() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses += completed(committedBytes = 9L)
                val result = fixture.download()

                assertEquals(0, fixture.session.resolveCalls.get())
                assertTrue(fixture.session.resolveHints.isEmpty())
                val started = fixture.session.starts.single()
                assertEquals(431960L, started.appId)
                assertEquals(431960L, started.depotId)
                assertEquals(42L, started.manifestId)
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
    fun sessionUnavailableAuthorizationFailureReopensAndRetriesExactlyOnce() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses += failed(TaskFailure.AUTHORIZATION)
                fixture.task.diagnostic = "content resolution failed at session_unavailable"
                val recovered = FakeTask().apply { statuses += completed(committedBytes = 9L) }
                fixture.session.queueTask(recovered)

                val result = fixture.download()

                assertEquals(9L, result.downloadedBytes)
                assertEquals(2, fixture.session.starts.size)
                assertEquals(2, fixture.openerCalls)
                assertEquals(1, fixture.task.closeCalls)
                assertEquals(1, recovered.closeCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun noProgressFailureDoesNotGuessThatTheSessionWasLost() {
        val failure =
            SteamContentSdkTaskFailureException(
                ordinal = TaskFailure.NO_PROGRESS,
                diagnostic = null,
                message = "no progress",
            )

        assertEquals(DownloadRecoveryDecision.FAIL, downloadRecoveryDecision(failure))
    }

    @Test
    fun adapterWatchdogRetriesOnlyItsOwnTypedNetworkFailure() {
        val watchdog =
            SteamContentSdkTaskFailureException(
                ordinal = TaskFailure.NETWORK,
                diagnostic = "sdk_download_no_progress",
                message = "stalled",
            )
        val otherNetwork =
            SteamContentSdkTaskFailureException(
                ordinal = TaskFailure.NETWORK,
                diagnostic = "other",
                message = "network",
            )

        assertEquals(DownloadRecoveryDecision.RETRY_DATA_PLANE_ONCE, downloadRecoveryDecision(watchdog))
        assertEquals(DownloadRecoveryDecision.FAIL, downloadRecoveryDecision(otherNetwork))
    }

    @Test
    fun sessionGenerationLossReopensAndRetriesExactlyOnce() =
        assertTypedAttemptReopensExactlyOnce(TaskFailure.SESSION_GENERATION_LOST)

    @Test
    fun contentDirectoryFailureReopensAndRetriesExactlyOnce() =
        assertTypedAttemptReopensExactlyOnce(SDK_TASK_FAILURE_CONTENT_DIRECTORY_UNAVAILABLE)

    @Test
    fun manifestTransportFailureRetriesTheTaskExactlyOnce() =
        assertTypedAttemptReopensExactlyOnce(SDK_TASK_FAILURE_MANIFEST_TRANSPORT_UNAVAILABLE)

    private fun assertTypedAttemptReopensExactlyOnce(failure: Long) =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses += failed(failure)
                val recovered = FakeTask().apply { statuses += completed(committedBytes = 9L) }
                fixture.session.queueTask(recovered)

                val result = fixture.download()

                assertEquals(9L, result.downloadedBytes)
                assertEquals(2, fixture.session.starts.size)
                assertEquals(2, fixture.openerCalls)
                assertEquals(1, fixture.task.closeCalls)
                assertEquals(1, recovered.closeCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun nonSessionAuthorizationFailureDoesNotRetry() {
        val failure =
            SteamContentSdkTaskFailureException(
                ordinal = TaskFailure.AUTHORIZATION,
                diagnostic = "content resolution failed at depot_key",
                message = "authorization",
            )

        assertFalse(isRecoverableDownloadAttempt(failure))
    }

    @Test
    fun anIncompleteIdentityHintFallsBackToTheSdkDetailsPath() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses += completed(committedBytes = 9L)
                fixture.download(target = fixture.target.copy(contentManifestId = 0L))

                assertEquals(listOf(431960L to 0L), fixture.session.resolveHints)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun anUnsignedManifestIdentityHintPreservesItsSignedLongBits() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses += completed(committedBytes = 9L)
                fixture.download(target = fixture.target.copy(contentManifestId = Long.MIN_VALUE))

                assertTrue(fixture.session.resolveHints.isEmpty())
                assertEquals(Long.MIN_VALUE, fixture.session.starts.single().manifestId)
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
    fun completedResultUsesTheFullyPublishedTotalBytes() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.task.statuses +=
                    SteamContentSdkTaskStatus(
                        state = TaskState.COMPLETED,
                        // A resumed task may have last reported only the newly committed suffix while its
                        // completed total names the fully published item.
                        committedBytes = 4L,
                        totalDecodedBytes = 9L,
                        errorOrdinal = TaskFailure.NONE,
                    )

                val result = fixture.download()

                assertEquals(9L, result.downloadedBytes)
                assertEquals(9L, result.totalBytes)
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
    fun exhaustedCdnCandidatesStayInsideOneSdkTaskBoundary() {
        val fixture = Fixture()
        try {
            fixture.task.statuses += failed(TaskFailure.NETWORK)
            fixture.task.diagnostic = "CDN candidate set exhausted"

            assertThrows(SteamContentSdkTaskFailureException::class.java) {
                runBlocking { fixture.download() }
            }

            assertEquals(0, fixture.session.resolveCalls.get())
            assertEquals(1, fixture.session.starts.size)
            assertEquals(1, fixture.task.closeCalls)
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

            assertEquals(0, fixture.session.resolveCalls.get())
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

            assertEquals(0, fixture.session.resolveCalls.get())
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
            assertTrue(failure.message.orEmpty().contains("authorization"))
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
                    runBlocking { fixture.download(target = fixture.target.copy(contentManifestId = 0L)) }
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
                val secondTask = FakeTask().apply { statuses += completed(committedBytes = 9L) }
                fixture.session.queueTask(secondTask)
                val second = File(fixture.root, "wallhub-workshop/task-2").apply { mkdirs() }

                val incompleteTarget = fixture.target.copy(contentManifestId = 0L)
                val first = launch(Dispatchers.IO) { fixture.download(target = incompleteTarget) }
                val secondRun = launch(Dispatchers.IO) { fixture.download(target = incompleteTarget, destination = second) }
                first.join()
                secondRun.join()

                assertEquals(0L, bothInside.count)
                assertEquals("the thin adapter owns no shared account-session state", 2, fixture.openerCalls)
                assertEquals("each non-owning facade is released", 2, fixture.session.closeCalls)
                assertEquals(2, fixture.session.resolveCalls.get())
            } finally {
                fixture.close()
            }
        }

    private class RecordingTaskEventInfoLog : TaskEventInfoLog {
        val messages = mutableListOf<String>()

        override fun info(message: String) {
            messages += message
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
        private var opener: suspend () -> SteamContentSdkSession = { session }
        private val transfer =
            SteamContentSdkWorkshopTransfer(
                sessionBroker =
                    SteamContentSdkSessionBroker {
                        openerCalls += 1
                        opener()
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
            target: WorkshopContentTarget = this.target,
            control: suspend () -> SteamDownloadControl = { SteamDownloadControl.CONTINUE },
            onProgress: suspend (SteamDownloadProgress) -> Unit = {},
            onFirstChunkCommitted: (suspend () -> Unit)? = null,
            opener: (suspend () -> SteamContentSdkSession)? = null,
        ): SteamContentDownloadResult {
            if (opener != null) this.opener = opener
            return transfer.download(
                target = target,
                destinationDirectory = destination,
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
        val resolveCalls = AtomicInteger()
        val resolveHints = mutableListOf<Pair<Long, Long>>()
        var closeCalls = 0
        var closed = false

        /** Runs inside a resolve, so a test can hold one download open while another starts. */
        var onResolve: (() -> Unit)? = null

        override fun resolveWorkshop(
            itemId: Long,
            appIdHint: Long,
            contentManifestIdHint: Long,
        ): SteamContentSdkResolvedItem {
            resolveCalls.incrementAndGet()
            resolveHints += appIdHint to contentManifestIdHint
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
