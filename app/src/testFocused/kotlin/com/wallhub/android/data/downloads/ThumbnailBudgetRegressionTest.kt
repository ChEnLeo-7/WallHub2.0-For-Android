package com.wallhub.android.data.downloads

import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThumbnailBudgetRegressionTest {
    @Test
    fun `open stream remains readable while another cover is cached`() = runBlocking {
        val root = Files.createTempDirectory("covers-test").toFile()
        try {
            val cache = cache(root, AtomicInteger())
            val first = requireNotNull(cache.open(1, "https://example.test/1"))
            coverFile(root, 1).setLastModified(1L)
            requireNotNull(cache.open(2, "https://example.test/2")).use { }
            assertArrayEquals(byteArrayOf(1, 1, 1, 1), first.use { it.readBytes() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `closed cover is evicted within budget and fetched again`() = runBlocking {
        val root = Files.createTempDirectory("covers-test").toFile()
        try {
            val calls = AtomicInteger()
            val cache = cache(root, calls)
            requireNotNull(cache.open(1, "https://example.test/1")).use { }
            val firstCover = coverFile(root, 1)
            firstCover.setLastModified(1L)
            requireNotNull(cache.open(2, "https://example.test/2")).use { }
            assertFalse(firstCover.exists())
            requireNotNull(cache.open(1, "https://example.test/1")).use {
                assertArrayEquals(byteArrayOf(1, 1, 1, 1), it.readBytes())
            }
            assertEquals(3, calls.get())
            assertEquals(4L, root.listFiles().orEmpty().filter { it.extension == "cover" }.sumOf { it.length() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed download leaves no temporary file and next request can succeed`() = runBlocking {
        val root = Files.createTempDirectory("covers-test").toFile()
        try {
            val calls = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val first = calls.incrementAndGet() == 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (first) 503 else 200)
                    .message(if (first) "Unavailable" else "OK")
                    .body(byteArrayOf(7).toResponseBody("image/png".toMediaType()))
                    .build()
            }
            val cache = SteamWorkshopThumbnailCache(root, client, maxCacheBytes = 4, validateImage = { true })
            assertEquals(null, cache.open(1, "https://example.test/1"))
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".part") })
            requireNotNull(cache.open(1, "https://example.test/1")).use {
                assertArrayEquals(byteArrayOf(7), it.readBytes())
            }
            assertEquals(2, calls.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent requests for one cover download once`() = runBlocking {
        val root = Files.createTempDirectory("covers-test").toFile()
        try {
            val calls = AtomicInteger()
            val cache = cache(root, calls)
            (1..8).map {
                async { requireNotNull(cache.open(1, "https://example.test/1")).use { it.readBytes() } }
            }.awaitAll().forEach { assertArrayEquals(byteArrayOf(1, 1, 1, 1), it) }
            assertEquals(1, calls.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `changed preview URL returns the new cover for the same workshop item`() = runBlocking {
        val root = Files.createTempDirectory("covers-test").toFile()
        try {
            val calls = AtomicInteger()
            val cache = cache(root, calls)
            requireNotNull(cache.open(1, "https://example.test/1")).use {
                assertArrayEquals(byteArrayOf(1, 1, 1, 1), it.readBytes())
            }
            requireNotNull(cache.open(1, "https://example.test/2")).use {
                assertArrayEquals(byteArrayOf(2, 2, 2, 2), it.readBytes())
            }
            assertEquals(2, calls.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation before delivery closes opened stream`() = runTest {
        val root = Files.createTempDirectory("covers-test").toFile()
        try {
            File(root, "1.cover").writeBytes(byteArrayOf(1))
            val closes = AtomicInteger()
            lateinit var result: Deferred<java.io.InputStream?>
            val cache = SteamWorkshopThumbnailCache(
                root,
                OkHttpClient.Builder(),
                maxCacheBytes = 4,
                validateImage = { true },
                openFile = { file ->
                    result.cancel()
                    object : FilterInputStream(file.inputStream()) {
                        override fun close() {
                            closes.incrementAndGet()
                            super.close()
                        }
                    }
                },
            )
            result = async { cache.open(1, null) }
            runCurrent()
            assertEquals(1, closes.get())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun coverFile(root: File, workshopId: Long): File =
        root.listFiles().orEmpty().single { it.name.startsWith("$workshopId-") && it.extension == "cover" }

    private fun cache(root: File, calls: AtomicInteger): SteamWorkshopThumbnailCache {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val value = chain.request().url.encodedPath.substringAfterLast('/').toInt().toByte()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(4) { value }.toResponseBody("image/png".toMediaType()))
                .build()
        }
        return SteamWorkshopThumbnailCache(root, client, maxCacheBytes = 4, validateImage = { true })
    }
}
