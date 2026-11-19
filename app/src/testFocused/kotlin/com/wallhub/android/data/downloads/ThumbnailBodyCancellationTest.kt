package com.wallhub.android.data.downloads

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailBodyCancellationTest {
    @Test
    fun `cancelling during body read cancels call and closes body`() = runBlocking {
        val request = Request.Builder().url("https://example.test/cover").build()
        val readStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val closed = AtomicBoolean()
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted.countDown()
                if (!cancelled.await(5, TimeUnit.SECONDS)) throw IOException("cancellation timed out")
                throw IOException("call cancelled")
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = source
        }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
        val delegate = OkHttpClient().newCall(request)
        val call = object : Call by delegate {
            override fun enqueue(responseCallback: Callback) { responseCallback.onResponse(this, response) }
            override fun cancel() { cancelled.countDown() }
        }
        val job = async(Dispatchers.IO) { call.useThumbnailResponse { it.body.string() } }
        assertTrue("response body read did not start", readStarted.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue("call was not cancelled", cancelled.await(1, TimeUnit.SECONDS))
        assertTrue("body was not closed", closed.get())
        assertTrue("cancellation was lost", job.isCancelled)
    }
}
