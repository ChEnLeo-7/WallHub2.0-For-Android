package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopVideoReadDemand
import com.wallhub.android.core.model.WorkshopVideoReadKind
import com.wallhub.android.feature.detail.StreamByteCursor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRangeStream : SteamContentSdkRangeStream {
    val reads = mutableListOf<ReadCall>()
    val demands = mutableListOf<SteamContentSdkPlaybackDemand>()
    val order = mutableListOf<String>()
    var snapshot =
        SteamContentSdkBufferSnapshot(
            position = 0L,
            targetBytes = 0L,
            prefetchOffset = 0L,
            prefetchBytes = 0L,
            generation = 0L,
            cachedBytes = 0L,
            cancelled = false,
        )
    var bytesToReturn = 0
    var readThreadId: Long? = null
    var telemetryCalls = 0
    var telemetryValue = SteamContentSdkStreamTelemetry()
    var closed = false
    var throwOnCleanup = false
    var demandLatch = CountDownLatch(1)

    override fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        readThreadId = Thread.currentThread().id
        reads += ReadCall(position, offset, length)
        return bytesToReturn.coerceAtMost(length)
    }

    override fun telemetry(): SteamContentSdkStreamTelemetry {
        telemetryCalls += 1
        return telemetryValue
    }

    override fun updatePlaybackDemand(demand: SteamContentSdkPlaybackDemand): SteamContentSdkBufferSnapshot {
        demands += demand
        order += "demand"
        demandLatch.countDown()
        return snapshot
    }

    override fun bufferSnapshot(): SteamContentSdkBufferSnapshot = snapshot

    override fun cancelPlaybackDemand() {
        order += "cancel-demand"
        if (throwOnCleanup) error("invalid stream handle")
    }

    override fun cancel() {
        order += "cancel"
        if (throwOnCleanup) error("invalid stream handle")
    }

    override fun close() {
        order += "close"
        closed = true
        if (throwOnCleanup) error("invalid stream handle")
    }
}

private data class ReadCall(
    val position: Long,
    val offset: Int,
    val length: Int,
)

private class FakeRangeSession : SteamContentSdkSession {
    val order = mutableListOf<String>()
    val stream = FakeRangeStream()

    override val storageRoot: File = File("unused")

    override fun resolveWorkshop(
        itemId: Long,
        appIdHint: Long,
        contentManifestIdHint: Long,
    ): SteamContentSdkResolvedItem = error("this fixture serves range streams, not resolution")

    override fun startDownload(
        appId: Long,
        depotId: Long,
        manifestId: Long,
        destination: String?,
        chunkConcurrency: Int,
    ): SteamContentSdkTask = error("this fixture serves range streams, not downloads")

    override fun manifestFiles(
        appId: Long,
        depotId: Long,
        manifestId: Long,
    ): List<SteamContentSdkManifestFile> =
        error("this fixture serves range streams, not manifests")

    override fun openRangeStream(
        appId: Long,
        depotId: Long,
        filePath: String,
        manifestId: Long,
        cacheCapacityBytes: Long,
    ): SteamContentSdkRangeStream {
        order += "stream-opened"
        return stream
    }

    override fun close() {
        order += "session-closed"
    }
}

class SteamContentSdkVideoStreamSessionTest {
    @Test
    fun `synchronous restore clears account and token byte arrays after success`() {
        val account = "account".toByteArray()
        val token = "synthetic-token".toByteArray()
        var passedAccount: ByteArray? = null
        var passedToken: ByteArray? = null

        restoreWithClearedCredentials(account, token) { accountBytes, tokenBytes ->
            passedAccount = accountBytes
            passedToken = tokenBytes
        }

        assertArrayEquals(ByteArray(account.size), account)
        assertArrayEquals(ByteArray(token.size), token)
        assertArrayEquals(ByteArray(account.size), passedAccount)
        assertArrayEquals(ByteArray(token.size), passedToken)
    }

    @Test
    fun `synchronous restore clears account and token byte arrays after failure`() {
        val account = "account".toByteArray()
        val token = "synthetic-token".toByteArray()

        assertThrows(IllegalStateException::class.java) {
            restoreWithClearedCredentials(account, token) { _, _ -> error("restore refused") }
        }

        assertArrayEquals(ByteArray(account.size), account)
        assertArrayEquals(ByteArray(token.size), token)
    }

    @Test
    fun `byte cursor distinguishes contiguous reopen from a real seek`() {
        val cursor = StreamByteCursor()

        assertEquals(WorkshopVideoReadKind.SEQUENTIAL, cursor.classifyOpen(0L))
        cursor.advance(512L)
        assertEquals(WorkshopVideoReadKind.SEQUENTIAL, cursor.classifyOpen(512L))
        assertEquals(WorkshopVideoReadKind.SEEK, cursor.classifyOpen(4_096L))
    }

    @Test
    fun `repository open uses a mobile-safe per-stream memory cache when the media cache setting is 512 MB`() =
        runBlocking {
            val root = Files.createTempDirectory("video-stream-repository").toFile()
            val sdkSession = RecordingRepositorySession(root)
            val repository =
                SteamWorkshopVideoStreamRepository(
                    cacheRootDirectory = File(root, "cache"),
                    credentialProvider =
                        object : SteamContentCredentialProvider {
                            override suspend fun loadContentCredential() =
                                SteamContentCredential("account", "synthetic-test-token")
                        },
                    settingsRepository = RepositoryTestSettings(flowOf(AppPreferences(mediaCacheLimitMb = 512))),
                    contentTargetFetcher = {
                        WorkshopContentTarget(
                            publishedFileId = it,
                            title = "Video",
                            appId = 431960,
                            contentManifestId = 42L,
                            expectedSize = 9L,
                            contentTypeHint = "video",
                        )
                    },
                    playbackCoordinator = PlaybackDownloadCoordinator(),
                    sdkSessionBroker = SteamContentSdkSessionBroker { sdkSession },
                )

            val stream = repository.open(7L)
            try {
                assertEquals(32L * 1024L * 1024L, sdkSession.requestedCacheCapacityBytes)
            } finally {
                stream.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun aReadForwardsTheRangeToTheSdkStream() =
        runBlocking {
            val fixture = fixture(contentLength = 100L)
            fixture.stream.bytesToReturn = 8

            val read =
                fixture.session.readAt(
                    position = 40L,
                    destination = ByteArray(16),
                    destinationOffset = 4,
                    length = 8,
                )

            assertEquals(8, read)
            assertEquals(listOf(ReadCall(position = 40L, offset = 4, length = 8)), fixture.stream.reads)
        }

    @Test
    fun `blocking read calls the synchronous SDK stream on the caller thread`() {
        val fixture = fixture(contentLength = 100L)
        fixture.stream.bytesToReturn = 8
        val callerThread = Thread.currentThread().id

        val read = fixture.session.readAtBlocking(40L, ByteArray(16), 4, 8)

        assertEquals(8, read)
        assertEquals(callerThread, fixture.stream.readThreadId)
        assertEquals(listOf(ReadCall(position = 40L, offset = 4, length = 8)), fixture.stream.reads)
    }

    @Test
    fun aReadIsClampedToTheRemainingContentLength() =
        runBlocking {
            val fixture = fixture(contentLength = 100L)
            fixture.stream.bytesToReturn = 16

            fixture.session.readAt(
                position = 95L,
                destination = ByteArray(16),
                destinationOffset = 0,
                length = 16,
            )

            assertEquals(listOf(ReadCall(position = 95L, offset = 0, length = 5)), fixture.stream.reads)
        }

    @Test
    fun aReadAtOrPastTheEndAnswersZeroWithoutTouchingTheStream() =
        runBlocking {
            val fixture = fixture(contentLength = 100L)

            val pastEnd =
                fixture.session.readAt(
                    position = 100L,
                    destination = ByteArray(8),
                    destinationOffset = 0,
                    length = 8,
                )
            val zeroLength =
                fixture.session.readAt(
                    position = 0L,
                    destination = ByteArray(8),
                    destinationOffset = 0,
                    length = 0,
                )

            assertEquals(0, pastEnd)
            assertEquals(0, zeroLength)
            assertTrue(fixture.stream.reads.isEmpty())
        }

    @Test
    fun `disabled detailed logging does no JNI telemetry work`() {
        val logger = RecordingTelemetryLog(debugEnabled = false)
        val fixture = fixture(contentLength = 1_000L, telemetryLog = logger)

        fixture.session.updateReadDemand(WorkshopVideoReadDemand(250L, 350L, WorkshopVideoReadKind.SEQUENTIAL))

        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        assertEquals(0, fixture.stream.telemetryCalls)
        assertTrue(logger.debugMessages.isEmpty())
    }

    @Test
    fun `telemetry is sampled no faster than one second and reports deltas`() {
        var nowMs = 10_000L
        val sampler = StreamTelemetrySampler(clockMs = { nowMs })
        var value = SteamContentSdkStreamTelemetry(fetchStarted = 10L, cacheHits = 20L)
        var sourceCalls = 0

        assertEquals(value, sampler.sample { sourceCalls += 1; value })
        nowMs += 999L
        value = SteamContentSdkStreamTelemetry(fetchStarted = 12L, cacheHits = 25L)
        assertEquals(null, sampler.sample { sourceCalls += 1; value })
        nowMs += 1L
        assertEquals(
            SteamContentSdkStreamTelemetry(fetchStarted = 2L, cacheHits = 5L),
            sampler.sample { sourceCalls += 1; value },
        )
        assertEquals(2, sourceCalls)
    }

    @Test
    fun `forced telemetry sample bypasses cadence for seek rebuffer failure or close`() {
        var nowMs = 10_000L
        val sampler = StreamTelemetrySampler(clockMs = { nowMs })
        sampler.sample { SteamContentSdkStreamTelemetry(fetchStarted = 1L) }
        nowMs += 10L

        assertEquals(
            SteamContentSdkStreamTelemetry(fetchStarted = 1L),
            sampler.sample(force = true) { SteamContentSdkStreamTelemetry(fetchStarted = 2L) },
        )
    }

    @Test
    fun `byte demand is forwarded without estimating from the media timeline`() {
        val fixture = fixture(contentLength = 1_000L)

        fixture.session.updateReadDemand(
            WorkshopVideoReadDemand(
                position = 250L,
                bufferedBytes = 350L,
                kind = WorkshopVideoReadKind.SEQUENTIAL,
            ),
        )

        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        assertEquals(
            SteamContentSdkPlaybackDemand(
                position = 250L,
                bufferedBytes = 350L,
                throughputBytesPerSecond = 0L,
                bitrateBitsPerSecond = 1L,
                rebuffered = false,
            ),
            fixture.stream.demands.single(),
        )
    }

    @Test
    fun `a real seek cancels stale native demand before issuing its byte demand`() {
        val fixture = fixture(contentLength = 1_000L)

        fixture.session.updateReadDemand(WorkshopVideoReadDemand(700L, 100L, WorkshopVideoReadKind.SEEK))

        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("cancel-demand", "demand"), fixture.stream.order)
        assertEquals(700L, fixture.stream.demands.single().position)
    }

    @Test
    fun `one actual rebuffer is forwarded on only the next byte demand`() {
        val fixture = fixture(contentLength = 1_000L)

        fixture.session.reportPlaybackRebuffer()
        fixture.session.updateReadDemand(WorkshopVideoReadDemand(100L, 100L, WorkshopVideoReadKind.SEQUENTIAL))
        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        fixture.stream.demandLatch = CountDownLatch(1)
        fixture.session.updateReadDemand(WorkshopVideoReadDemand(200L, 100L, WorkshopVideoReadKind.SEQUENTIAL))
        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(true, false), fixture.stream.demands.map { it.rebuffered })
    }

    @Test
    fun `close cancels demand before closing stream and lease and remains idempotent`() {
        val fixture = fixture(contentLength = 100L)

        fixture.session.close()
        fixture.session.close()

        assertEquals(listOf("cancel", "cancel-demand", "close"), fixture.stream.order)
        assertEquals(listOf("stream-opened", "session-closed"), fixture.sdkSession.order)
    }

    @Test
    fun `stale native handle cleanup still releases the shared session lease`() {
        val fixture = fixture(contentLength = 100L)
        fixture.stream.throwOnCleanup = true

        fixture.session.close()

        assertEquals(listOf("cancel", "cancel-demand", "close"), fixture.stream.order)
        assertEquals(listOf("stream-opened", "session-closed"), fixture.sdkSession.order)
    }

    @Test
    fun closingTheSessionReleasesTheStreamBeforeTheSdkSession() {
        val fixture = fixture(contentLength = 100L)

        fixture.session.close()

        assertTrue(fixture.stream.closed)
        assertEquals(listOf("stream-opened", "session-closed"), fixture.sdkSession.order)
    }

    private fun fixture(
        contentLength: Long,
        telemetryLog: StreamTelemetryLog = RecordingTelemetryLog(debugEnabled = false),
        clockMs: () -> Long = { 0L },
    ): Fixture {
        val sdkSession = FakeRangeSession()
        val stream: SteamContentSdkRangeStream =
            sdkSession.openRangeStream(
                appId = 431960L,
                depotId = 431961L,
                filePath = "scene.mp4",
                manifestId = 42L,
                cacheCapacityBytes = 1L shl 20,
            )
        return Fixture(
            // The fixture keeps the concrete stream for its own assertions and hands the session the interface
            // it actually holds, which is what the production call site does too.
            stream = sdkSession.stream,
            sdkSession = sdkSession,
            session =
                SteamContentSdkVideoStreamSession(
                    sessionLease = SteamContentSdkSessionBroker.Lease.create(sdkSession) { sdkSession.close() },
                    stream = stream,
                    title = "Scene",
                    fileName = "scene.mp4",
                    contentLength = contentLength,
                    telemetryLog = telemetryLog,
                    clockMs = clockMs,
                ),
        )
    }

    private class Fixture(
        val stream: FakeRangeStream,
        val sdkSession: FakeRangeSession,
        val session: SteamContentSdkVideoStreamSession,
    )

    private class RecordingTelemetryLog(
        private val debugEnabled: Boolean,
    ) : StreamTelemetryLog {
        val debugMessages = mutableListOf<String>()

        override fun isDebugEnabled(): Boolean = debugEnabled

        override fun isWarnEnabled(): Boolean = true

        override fun debug(message: String) {
            debugMessages += message
        }

        override fun warn(message: String) = Unit
    }

    private class RecordingRepositorySession(
        override val storageRoot: File,
    ) : SteamContentSdkSession {
        var requestedCacheCapacityBytes = -1L

        override fun resolveWorkshop(itemId: Long, appIdHint: Long, contentManifestIdHint: Long) =
            SteamContentSdkResolvedItem(itemId, 431960L, 431961L, 42L, resolved = true)

        override fun manifestFiles(appId: Long, depotId: Long, manifestId: Long) =
            listOf(SteamContentSdkManifestFile("wallpaper.mp4", 9L, 0))

        override fun openRangeStream(
            appId: Long,
            depotId: Long,
            filePath: String,
            manifestId: Long,
            cacheCapacityBytes: Long,
        ): SteamContentSdkRangeStream {
            require(cacheCapacityBytes <= 64L * 1024L * 1024L) {
                "stream cache capacity exceeds the per-stream ceiling"
            }
            requestedCacheCapacityBytes = cacheCapacityBytes
            return FakeRangeStream()
        }

        override fun startDownload(
            appId: Long,
            depotId: Long,
            manifestId: Long,
            destination: String?,
            chunkConcurrency: Int,
        ): SteamContentSdkTask = error("not used")

        override fun close() = Unit
    }

    private class RepositoryTestSettings(
        override val preferences: Flow<AppPreferences>,
    ) : SettingsRepository {
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
}
