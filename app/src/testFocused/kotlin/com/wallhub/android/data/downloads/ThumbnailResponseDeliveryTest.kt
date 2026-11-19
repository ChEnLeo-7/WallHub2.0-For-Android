package com.wallhub.android.data.downloads

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThumbnailResponseDeliveryTest {
    @Test
    fun `cancel after response before delivery closes undelivered body`() = runTest {
        val request = Request.Builder().url("https://steamcontent.com/cover").build()
        val call = ThumbnailTestCall(OkHttpClient().newCall(request))
        val closeCount = AtomicInteger()
        val response = response(request, closeCount)
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) { call.awaitThumbnailResponse().use { } }
        call.respond(response)
        assertEquals(0, closeCount.get())
        awaiting.cancel()
        runCurrent()
        assertTrue(awaiting.isCancelled)
        assertTrue(call.cancelled)
        assertEquals(1, closeCount.get())
    }

    @Test
    fun `response after cancellation is closed`() = runTest {
        val request = Request.Builder().url("https://steamcontent.com/cover").build()
        val call = ThumbnailTestCall(OkHttpClient().newCall(request))
        val closeCount = AtomicInteger()
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) { call.awaitThumbnailResponse().use { } }
        awaiting.cancel()
        call.respond(response(request, closeCount))
        runCurrent()
        assertTrue(call.cancelled)
        assertEquals(1, closeCount.get())
    }

    @Test
    fun `delivered response remains readable and is closed by caller`() = runTest {
        val request = Request.Builder().url("https://steamcontent.com/cover").build()
        val call = ThumbnailTestCall(OkHttpClient().newCall(request))
        val closeCount = AtomicInteger()
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) { call.awaitThumbnailResponse() }
        call.respond(response(request, closeCount))
        runCurrent()
        awaiting.await().use { assertEquals("cover", it.body.source().readUtf8()) }
        assertEquals(1, closeCount.get())
    }

    @Test
    fun `network failure reaches caller`() = runTest {
        val request = Request.Builder().url("https://steamcontent.com/cover").build()
        val call = ThumbnailTestCall(OkHttpClient().newCall(request))
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<IOException> { call.awaitThumbnailResponse() }
        }
        call.fail(IOException("network failed"))
        runCurrent()
        awaiting.await()
    }

    private fun response(request: Request, closeCount: AtomicInteger): Response {
        val source = object : ForwardingSource(Buffer().writeUtf8("cover")) {
            override fun close() {
                closeCount.incrementAndGet()
                super.close()
            }
        }.buffer()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = 5
                override fun source(): BufferedSource = source
            }).build()
    }
}

private class ThumbnailTestCall(private val delegate: Call) : Call by delegate {
    private lateinit var callback: Callback
    var cancelled = false
        private set

    override fun enqueue(responseCallback: Callback) { callback = responseCallback }
    override fun cancel() { cancelled = true }
    override fun isCanceled(): Boolean = cancelled
    fun respond(response: Response) = callback.onResponse(this, response)
    fun fail(error: IOException) = callback.onFailure(this, error)
}
