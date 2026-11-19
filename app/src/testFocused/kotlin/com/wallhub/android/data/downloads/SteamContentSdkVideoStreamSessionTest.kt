package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.normalizedStreamCacheLimitMb
import com.wallhub.android.core.model.WorkshopVideoReadDemand
import com.wallhub.android.core.model.WorkshopVideoReadKind
import com.wallhub.android.core.model.WorkshopVideoOpenRequest
import com.wallhub.android.core.model.WorkshopVideoStreamFailure
import com.wallhub.android.core.model.WorkshopVideoStreamFailureException
import io.steamcontent.sdk.CdnProvider
import io.steamcontent.sdk.StreamReadFailure
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    var readFailure: Throwable? = null
    var closed = false
    var demandLatch = CountDownLatch(1)
    var seekFailure: Throwable? = null
    val seeks = mutableListOf<Long>()

    override fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        reads += ReadCall(position, offset, length)
        readFailure?.let { throw it }
        return bytesToReturn.coerceAtMost(length)
    }

    override fun updatePlaybackDemand(demand: SteamContentSdkPlaybackDemand): SteamContentSdkBufferSnapshot {
        demands += demand
        order += "demand"
        demandLatch.countDown()
        return snapshot
    }

    override fun bufferSnapshot(): SteamContentSdkBufferSnapshot = snapshot

    override fun seek(position: Long): Long {
        order += "seek"
        seeks += position
        seekFailure?.let { throw it }
        return seeks.size.toLong()
    }

    override fun cancelPlaybackDemand() {
        order += "cancel"
    }

    override fun cancel() {
        order += "read-cancel"
    }

    override fun close() {
        order += "close"
        closed = true
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
    fun `SDK per-stream memory cache remains fixed regardless of persistent disk budget`() {
        assertEquals(512, 512.normalizedStreamCacheLimitMb())
        assertEquals(8192, 16384.normalizedStreamCacheLimitMb())
    }

    @Test
    fun `repository open keeps SDK stream memory cache fixed while using configured disk budget`() =
        runBlocking {
            val root = Files.createTempDirectory("video-stream-repository").toFile()
            val sdkSession = RecordingRepositorySession(root)
            val repository =
                SteamWorkshopVideoStreamRepository(
                    cacheRootDirectory = File(root, "cache"),
                    settingsRepository = RepositoryTestSettings(flowOf(AppPreferences(mediaCacheLimitMb = 2048))),
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
    fun sdkVideoOpenGateSerializesConcurrentOpens() =
        runBlocking {
            val gate = SdkVideoOpenGate()
            val active = AtomicInteger(0)
            val maximumActive = AtomicInteger(0)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                val first = async {
                    gate.withOpen {
                        val count = active.incrementAndGet()
                        maximumActive.updateAndGet { maxOf(it, count) }
                        entered.complete(Unit)
                        release.await()
                        active.decrementAndGet()
                    }
                }
                withTimeout(5_000) { entered.await() }
                val second = async {
                    gate.withOpen {
                        val count = active.incrementAndGet()
                        maximumActive.updateAndGet { maxOf(it, count) }
                        active.decrementAndGet()
                    }
                }
                delay(100)
                assertEquals(1, maximumActive.get())
                release.complete(Unit)
                first.await()
                second.await()
                assertEquals(1, maximumActive.get())
            } finally {
                release.complete(Unit)
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
    fun `sequential read demand forwards player buffer and delivered throughput`() {
        val fixture = fixture(contentLength = 1_000L)

        fixture.session.updateReadDemand(
            WorkshopVideoReadDemand(
                position = 250L,
                bufferedBytes = 350L,
                kind = WorkshopVideoReadKind.SEQUENTIAL,
                deliveredBytesPerSecond = 4_000_000L,
            ),
        )

        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        assertEquals(
            SteamContentSdkPlaybackDemand(
                position = 250L,
                bufferedBytes = 350L,
                throughputBytesPerSecond = 4_000_000L,
                bitrateBitsPerSecond = 1L,
                rebuffered = false,
            ),
            fixture.stream.demands.single(),
        )
    }

    @Test
    fun `seek advances generation synchronously before forwarding demand clamped to file end`() {
        val fixture = fixture(contentLength = 1_000L)

        fixture.session.updateReadDemand(
            WorkshopVideoReadDemand(
                position = 900L,
                bufferedBytes = 400L,
                kind = WorkshopVideoReadKind.SEEK,
            ),
        )

        assertEquals(listOf(900L), fixture.stream.seeks)
        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("seek", "demand"), fixture.stream.order)
        assertEquals(900L, fixture.stream.demands.single().position)
        assertEquals(100L, fixture.stream.demands.single().bufferedBytes)
    }

    @Test
    fun `repeated head tail head seeks each advance generation before demand`() {
        val fixture = fixture(contentLength = 1_000L)
        for (position in listOf(0L, 900L, 0L)) {
            fixture.stream.demandLatch = CountDownLatch(1)
            fixture.session.updateReadDemand(
                WorkshopVideoReadDemand(position, 100L, WorkshopVideoReadKind.SEEK),
            )
            assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        }

        assertEquals(listOf(0L, 900L, 0L), fixture.stream.seeks)
        assertEquals(
            listOf("seek", "demand", "seek", "demand", "seek", "demand"),
            fixture.stream.order,
        )
    }

    @Test
    fun `seek failure is synchronous and does not publish new demand`() {
        val fixture = fixture(contentLength = 1_000L)
        val failure = IOException("seek refused")
        fixture.stream.seekFailure = failure

        val thrown =
            assertThrows(IOException::class.java) {
                fixture.session.updateReadDemand(
                    WorkshopVideoReadDemand(900L, 100L, WorkshopVideoReadKind.SEEK),
                )
            }

        assertSame(failure, thrown)
        assertEquals(listOf("seek"), fixture.stream.order)
        assertTrue(fixture.stream.demands.isEmpty())
    }

    @Test
    fun `SDK cancellation remains cancellation instead of retryable IO failure`() {
        val failure =
            StreamReadFailure.CANCELLED.toAppStreamReadException(
                generation = 7L,
                cdnProvider = CdnProvider.VALVE,
            )

        assertTrue(failure is CancellationException)
        assertTrue(failure !is IOException)
    }

    @Test
    fun `one actual rebuffer is forwarded on only the next demand`() {
        val fixture = fixture(contentLength = 1_000L)

        fixture.session.reportPlaybackRebuffer()
        fixture.session.updateReadDemand(
            WorkshopVideoReadDemand(100L, 100L, WorkshopVideoReadKind.SEQUENTIAL),
        )
        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))
        fixture.stream.demandLatch = CountDownLatch(1)
        fixture.session.updateReadDemand(
            WorkshopVideoReadDemand(200L, 100L, WorkshopVideoReadKind.SEQUENTIAL),
        )
        assertTrue(fixture.stream.demandLatch.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(true, false), fixture.stream.demands.map { it.rebuffered })
    }

    @Test
    fun `session invalidation reports the failed lease once and preserves the typed failure`() {
        val fixture = fixture(contentLength = 100L)
        val failure =
            WorkshopVideoStreamFailureException(
                WorkshopVideoStreamFailure.SESSION_INVALIDATED,
                IOException("generation lost"),
            )
        fixture.stream.readFailure = failure

        val first =
            assertThrows(WorkshopVideoStreamFailureException::class.java) {
                fixture.session.readAtBlocking(0L, ByteArray(8), 0, 8)
            }
        val second =
            assertThrows(WorkshopVideoStreamFailureException::class.java) {
                fixture.session.readAtBlocking(0L, ByteArray(8), 0, 8)
            }

        assertSame(failure, first)
        assertSame(failure, second)
        assertEquals(listOf(1L), fixture.reportedLosses)
    }

    @Test
    fun `CDN route exhaustion preserves the typed failure without retiring the account session`() {
        val fixture = fixture(contentLength = 100L)
        val failure =
            WorkshopVideoStreamFailureException(
                WorkshopVideoStreamFailure.CDN_ROUTE_EXHAUSTED,
                IOException("no CDN candidates"),
            )
        fixture.stream.readFailure = failure

        val thrown =
            assertThrows(WorkshopVideoStreamFailureException::class.java) {
                fixture.session.readAtBlocking(0L, ByteArray(8), 0, 8)
            }

        assertSame(failure, thrown)
        assertTrue(fixture.reportedLosses.isEmpty())
    }

    @Test
    fun `close cancels reads and demand before closing stream and lease and remains idempotent`() {
        val fixture = fixture(contentLength = 100L)

        fixture.session.close()
        fixture.session.close()

        assertEquals(listOf("read-cancel", "cancel", "close"), fixture.stream.order)
        assertEquals(listOf("stream-opened", "session-closed"), fixture.sdkSession.order)
    }

    @Test
    fun closingTheSessionReleasesTheStreamBeforeTheSdkSession() {
        val fixture = fixture(contentLength = 100L)

        fixture.session.close()

        assertTrue(fixture.stream.closed)
        assertEquals(listOf("stream-opened", "session-closed"), fixture.sdkSession.order)
    }

    private fun fixture(contentLength: Long): Fixture {
        val sdkSession = FakeRangeSession()
        val reportedLosses = mutableListOf<Long>()
        val broker =
            SteamContentSdkSessionBroker(
                testBorrow = { _ -> sdkSession to 1L },
                reportLoss = reportedLosses::add,
            )
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
            reportedLosses = reportedLosses,
            session =
                SteamContentSdkVideoStreamSession(
                    sessionLease = runBlocking { broker.acquire() },
                    stream = stream,
                    title = "Scene",
                    fileName = "scene.mp4",
                    contentLength = contentLength,
                ),
        )
    }

    private class Fixture(
        val stream: FakeRangeStream,
        val sdkSession: FakeRangeSession,
        val reportedLosses: List<Long>,
        val session: SteamContentSdkVideoStreamSession,
    )

    private open class RecordingRepositorySession(
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
