package com.wallhub.android.data.downloads

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SteamRemoteVideoStreamPrefetchTest {
    @Test
    fun `partial overlap does not delay foreground read`() = runBlocking {
        val releaseOld = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")!!
                if (range == "bytes=1-8388608") {
                    check(releaseOld.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503)
                }
                val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                return MockResponse().setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-$end/8388614")
                    .setBody(okio.Buffer().write(ByteArray(end - start + 1) { (start + it).toByte() }))
            }
        }
        server.start()
        val stream = SteamRemoteVideoStream.open("video", "video.mp4", server.url("/video").toString(), 8_388_614, OkHttpClient.Builder())
        try {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(1, stream.readAt(0, ByteArray(1), 0, 1))
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("bytes=1-8388608", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))
            val destination = ByteArray(2)
            val read = async(Dispatchers.IO) { stream.readAt(8_388_608, destination, 0, 2) }
            assertEquals("bytes=8388608-8388609", assertNotNull(server.takeRequest(600, TimeUnit.MILLISECONDS)).getHeader("Range"))
            assertEquals(2, withTimeout(2_000) { read.await() })
            assertContentEquals(byteArrayOf(0, 1), destination)
        } finally {
            releaseOld.countDown()
            stream.close()
            server.shutdown()
        }
    }

    @Test
    fun `seek cancels obsolete prefetch and starts a new window`() = runBlocking {
        val releaseOld = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")!!
                if (range == "bytes=1-8388608") {
                    check(releaseOld.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503)
                }
                val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                val bytes = ByteArray(end - start + 1) { (start + it).toByte() }
                return MockResponse().setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-$end/8388614")
                    .setBody(okio.Buffer().write(bytes))
            }
        }
        server.start()
        val stream = SteamRemoteVideoStream.open("video", "video.mp4", server.url("/video").toString(), 8_388_614, OkHttpClient.Builder())
        try {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(1, stream.readAt(0, ByteArray(1), 0, 1))
            assertEquals("bytes=0-0", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))
            assertEquals("bytes=1-8388608", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))
            assertEquals(1, stream.readAt(8_388_610, ByteArray(1), 0, 1))
            assertEquals("bytes=8388610-8388610", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))
            assertEquals("bytes=8388611-8388613", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))
            releaseOld.countDown()
            val destination = ByteArray(1)
            assertEquals(1, stream.readAt(8_388_611, destination, 0, 1))
            assertContentEquals(byteArrayOf(3), destination)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS), "Late old prefetch must not displace the new window")
        } finally {
            releaseOld.countDown()
            stream.close()
            server.shutdown()
        }
    }

    @Test
    fun `slow prefetch body is cancelled and foreground read proceeds`() = runBlocking {
        val bodyStarted = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")!!
                val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                val response = MockResponse().setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-$end/16")
                    .setBody(okio.Buffer().write(ByteArray(end - start + 1) { (start + it).toByte() }))
                return if (range == "bytes=1-15") response.throttleBody(1, 3, TimeUnit.SECONDS) else response
            }
        }
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                if (call.request().header("Range") == "bytes=1-15") bodyStarted.countDown()
            }

            override fun canceled(call: Call) {
                if (call.request().header("Range") == "bytes=1-15") callCancelled.countDown()
            }
        })
        server.start()
        val stream = SteamRemoteVideoStream.open("video", "video.mp4", server.url("/video").toString(), 16, client)
        try {
            assertEquals(1, stream.readAt(0, ByteArray(1), 0, 1))
            assertEquals(true, bodyStarted.await(2, TimeUnit.SECONDS))
            val destination = ByteArray(1)
            assertEquals(1, withTimeout(5_000) { stream.readAt(1, destination, 0, 1) })
            assertContentEquals(byteArrayOf(1), destination)
            assertEquals(true, callCancelled.await(2, TimeUnit.SECONDS))
        } finally {
            stream.close()
            server.shutdown()
        }
    }

    @Test
    fun `reads reuse active and completed prefetch without overlapping requests`() = runBlocking {
        val releasePrefetch = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range == "bytes=1-15") {
                    check(releasePrefetch.await(5, TimeUnit.SECONDS))
                }
                val (start, end) = range!!.removePrefix("bytes=").split('-').map(String::toInt)
                val bytes = ByteArray(end - start + 1) { (start + it).toByte() }
                return MockResponse().setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-$end/16")
                    .setBody(okio.Buffer().write(bytes))
            }
        }
        server.start()
        val stream = SteamRemoteVideoStream.open("video", "video.mp4", server.url("/video").toString(), 16, OkHttpClient.Builder())
        try {
            assertEquals("bytes=0-1", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))
            assertEquals(1, stream.readAt(0, ByteArray(1), 0, 1))
            assertEquals("bytes=0-0", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))
            assertEquals("bytes=1-15", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Range"))

            val destination = ByteArray(1)
            val read = async(Dispatchers.IO) { stream.readAt(1, destination, 0, 1) }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS), "A read covered by an active prefetch must not start another request")
            releasePrefetch.countDown()
            assertEquals(1, read.await())
            assertContentEquals(byteArrayOf(1), destination)
            assertEquals(1, stream.readAt(2, destination, 0, 1))
            assertContentEquals(byteArrayOf(2), destination)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS), "Cached reads must not prefetch an already covered range")
        } finally {
            releasePrefetch.countDown()
            stream.close()
            server.shutdown()
        }
    }
}
