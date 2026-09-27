package com.wallhub.android.data.downloads

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRangeStream : SteamContentSdkRangeStream {
    val reads = mutableListOf<ReadCall>()
    var bytesToReturn = 0
    var closed = false

    override fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        reads += ReadCall(position, offset, length)
        return bytesToReturn.coerceAtMost(length)
    }

    override fun close() {
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

    override fun resolveWorkshop(itemId: Long): SteamContentSdkResolvedItem =
        error("this fixture serves range streams, not resolution")

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
    fun closingTheSessionReleasesTheStreamBeforeTheSdkSession() {
        val fixture = fixture(contentLength = 100L)

        fixture.session.close()

        assertTrue(fixture.stream.closed)
        assertEquals(listOf("stream-opened", "session-closed"), fixture.sdkSession.order)
    }

    private fun fixture(contentLength: Long): Fixture {
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
                    session = sdkSession,
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
        val session: SteamContentSdkVideoStreamSession,
    )
}
