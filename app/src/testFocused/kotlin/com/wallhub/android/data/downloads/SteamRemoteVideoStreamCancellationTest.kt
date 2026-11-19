package com.wallhub.android.data.downloads

import java.util.concurrent.atomic.AtomicBoolean
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamRemoteVideoStreamCancellationTest {
    @Test
    fun `response cancelled after callback but before delivery is closed`() =
        runTest {
            val request = Request.Builder().url("https://steamcontent.com/video.mp4").build()
            val call = ControllableCall(OkHttpClient().newCall(request))
            val closed = AtomicBoolean()
            val source =
                object : ForwardingSource(Buffer()) {
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
            val response =
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .body(
                        object : ResponseBody() {
                            override fun contentType(): MediaType? = null
                            override fun contentLength(): Long = 0L
                            override fun source(): BufferedSource = source
                        },
                    ).build()
            try {
                val awaiting = async(start = CoroutineStart.UNDISPATCHED) { call.awaitCall().use { } }
                call.respond(response)
                assertFalse(closed.get())

                awaiting.cancel()
                runCurrent()

                assertTrue(awaiting.isCancelled)
                assertTrue(call.cancelled)
                assertTrue(closed.get(), "An undelivered OkHttp response must be closed")
            } finally {
                response.close()
            }
        }
}

private class ControllableCall(
    private val delegate: Call,
) : Call by delegate {
    private lateinit var callback: Callback
    var cancelled: Boolean = false
        private set

    override fun enqueue(responseCallback: Callback) {
        check(!::callback.isInitialized)
        callback = responseCallback
    }

    override fun cancel() {
        cancelled = true
    }

    override fun isCanceled(): Boolean = cancelled

    fun respond(response: Response) {
        callback.onResponse(this, response)
    }
}
