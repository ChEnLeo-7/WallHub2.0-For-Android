package com.wallhub.android.data.downloads

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedSteamContentSdkRangeStreamTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reopened stream reuses a fully read page from disk`() = runBlocking {
        val root = Files.createTempDirectory(temporaryFolder.root.toPath(), "range-cache").toFile()
        val expected = ByteArray(16) { it.toByte() }
        val firstDelegate = TestRangeStream(expected)
        val first = CachedSteamContentSdkRangeStream(firstDelegate, cache(root), expected.size.toLong(), pageSize = 8)
        val firstRead = ByteArray(5)
        assertEquals(5, first.readAt(2, firstRead, 0, firstRead.size))
        assertArrayEquals(expected.copyOfRange(2, 7), firstRead)
        assertEquals(1, firstDelegate.readCalls)
        first.close()

        val secondDelegate = TestRangeStream(ByteArray(16))
        val second = CachedSteamContentSdkRangeStream(secondDelegate, cache(root), 16, pageSize = 8)
        val secondRead = ByteArray(5)
        assertEquals(5, second.readAt(2, secondRead, 0, secondRead.size))
        assertArrayEquals(expected.copyOfRange(2, 7), secondRead)
        assertEquals(0, secondDelegate.readCalls)
        second.close()
    }

    @Test
    fun `seek during an old page read prevents the old generation from being committed`() = runBlocking {
        val root = Files.createTempDirectory(temporaryFolder.root.toPath(), "range-cache-seek-race").toFile()
        val cache = cache(root)
        val original = ByteArray(8) { 1 }
        val delegate = BlockingRangeStream(original)
        val stream = CachedSteamContentSdkRangeStream(delegate, cache, 16, pageSize = 8)
        val oldRead = async(kotlinx.coroutines.Dispatchers.IO) {
            stream.readAt(0, ByteArray(8), 0, 8)
        }
        assertEquals(true, delegate.entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        stream.seek(8)
        delegate.release.countDown()
        assertEquals(true, runCatching { oldRead.await() }.isFailure)
        stream.close()

        val reopened = CachedSteamContentSdkRangeStream(TestRangeStream(ByteArray(16) { 9 }), cache(root), 16, pageSize = 8)
        val oldPage = ByteArray(8)
        reopened.readAt(0, oldPage, 0, oldPage.size)
        assertArrayEquals(ByteArray(8) { 9 }, oldPage)
        reopened.close()
    }

    @Test
    fun `cache budget never exceeds configured bytes after overflow eviction`() = runBlocking {
        val root = Files.createTempDirectory(temporaryFolder.root.toPath(), "range-cache-budget").toFile()
        val firstCache = SteamVideoStreamCache(root, "budget", limitBytes = 16, sweepDebounceMs = 0L)
        val first = CachedSteamContentSdkRangeStream(TestRangeStream(ByteArray(32) { 1 }), firstCache, 32, 8)
        first.readAt(0, ByteArray(8), 0, 8)
        first.readAt(8, ByteArray(8), 0, 8)
        first.readAt(16, ByteArray(8), 0, 8)
        first.close()

        val cachedBytes = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".chunk") }
            .sumOf(File::length)
        assertEquals(true, cachedBytes <= 16L)
    }

    @Test
    fun `different cache namespaces never share page contents`() = runBlocking {
        val root = Files.createTempDirectory(temporaryFolder.root.toPath(), "range-cache-isolation").toFile()
        val first = CachedSteamContentSdkRangeStream(TestRangeStream(ByteArray(8) { 3 }), cache(root, "one"), 8, 8)
        val buffer = ByteArray(8)
        first.readAt(0, buffer, 0, buffer.size)
        first.close()

        val secondDelegate = TestRangeStream(ByteArray(8) { 9 })
        val second = CachedSteamContentSdkRangeStream(secondDelegate, cache(root, "two"), 8, 8)
        val actual = ByteArray(8)
        second.readAt(0, actual, 0, actual.size)
        assertArrayEquals(ByteArray(8) { 9 }, actual)
        assertEquals(1, secondDelegate.readCalls)
        second.close()
    }

    private class BlockingRangeStream(private val content: ByteArray) : TestRangeStream(content) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            return super.readAt(position, destination, offset, length)
        }
    }

    private fun cache(root: File, namespace: String = "same-content"): SteamVideoStreamCache =
        SteamVideoStreamCache(root, namespace, 1024L * 1024L, sweepDebounceMs = 0L)

    private open class TestRangeStream(private val content: ByteArray) : SteamContentSdkRangeStream {
        var readCalls = 0

        override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
            readCalls += 1
            if (position >= content.size) return 0
            val count = minOf(length, content.size - position.toInt())
            content.copyInto(destination, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun updatePlaybackDemand(demand: SteamContentSdkPlaybackDemand) =
            SteamContentSdkBufferSnapshot(0, 0, 0, 0, 0, 0)

        override fun bufferSnapshot() = SteamContentSdkBufferSnapshot(0, 0, 0, 0, 0, 0)
        override fun seek(position: Long) = position
        override fun cancelPlaybackDemand() = Unit
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
