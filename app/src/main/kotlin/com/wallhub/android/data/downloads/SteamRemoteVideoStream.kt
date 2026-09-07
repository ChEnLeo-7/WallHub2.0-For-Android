package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Resolves a Workshop target to a direct CDN file URL when the published file
 * details expose a streamable video file. This bypasses the manifest, depot key,
 * and CDN negotiation round trips entirely, so playback can start after a single
 * range probe.
 */
internal fun remoteVideoUrlOrNull(target: WorkshopContentTarget): String? {
    val url = target.fileUrl.trim()
    if (url.isEmpty()) return null
    val path = url.substringBefore('?').substringBefore('#')
    if (path.videoFileExtension() !in VIDEO_FILE_EXTENSIONS) return null
    return url
}

internal fun parseContentRangeTotal(contentRange: String?): Long? {
    val total = contentRange?.substringAfterLast('/')?.trim().orEmpty()
    if (total.isEmpty() || total == "*") return null
    return total.toLongOrNull()?.takeIf { it > 0L }
}

/**
 * [WorkshopVideoStreamSession] backed by plain HTTP range requests against the
 * published file URL. A single in-memory forward window is prefetched so the
 * player's startup buffer fills without a network round trip on every read.
 */
internal class SteamRemoteVideoStream private constructor(
    override val title: String,
    override val fileName: String,
    private val remoteUrl: String,
    private val httpClient: OkHttpClient,
    override val contentLength: Long,
) : WorkshopVideoStreamSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bufferMutex = Mutex()
    private val aheadJobLock = Any()
    private var aheadJob: Job? = null

    @Volatile
    private var closed = false
    private var bufferData = ByteArray(0)
    private var bufferStart = 0L
    private var bufferEnd = 0L

    override suspend fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            check(!closed) { "Remote video stream is closed" }
            require(position >= 0L) { "Invalid remote video read position" }
            require(length >= 0) { "Invalid remote video read length" }
            require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
                "Invalid remote video read destination"
            }
            if (length == 0 || position >= contentLength) return@withContext 0
            val requested = min(length.toLong(), contentLength - position).toInt()
            var copied = consumeBuffered(position, requested, destination, destinationOffset)
            if (copied < requested) {
                copied += fetchRangeInto(
                    start = position + copied,
                    destination = destination,
                    destinationOffset = destinationOffset + copied,
                    length = requested - copied,
                )
            }
            scheduleAheadPrefetch(position + requested)
            copied
        }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(aheadJobLock) { aheadJob?.cancel() }
        scope.cancel()
    }

    private suspend fun consumeBuffered(
        position: Long,
        requested: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ): Int {
        bufferMutex.withLock {
            if (position < bufferStart || position >= bufferEnd) return 0
            val from = (position - bufferStart).toInt()
            val count = min(requested.toLong(), bufferEnd - position).toInt()
            System.arraycopy(bufferData, from, destination, destinationOffset, count)
            return count
        }
    }

    private fun scheduleAheadPrefetch(from: Long) {
        if (closed || from >= contentLength) return
        synchronized(aheadJobLock) {
            if (aheadJob?.isActive == true) return
            aheadJob =
                scope.launch {
                    runCatching {
                        val end = min(contentLength, from + REMOTE_AHEAD_WINDOW_BYTES)
                        val length = (end - from).toInt()
                        if (length <= 0) return@runCatching
                        val bytes = fetchRangeBytes(from, length)
                        bufferMutex.withLock {
                            // Keep the swap only when the playback position has not
                            // already moved past the prefetched window.
                            if (bufferEnd <= from) {
                                bufferData = bytes
                                bufferStart = from
                                bufferEnd = from + bytes.size
                            }
                        }
                    }.onFailure { error ->
                        Log.w(STEAM_REMOTE_STREAM_LOG_TAG, "Remote video ahead prefetch failed: ${error.message}")
                    }
                }
        }
    }

    private suspend fun fetchRangeInto(
        start: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int {
        val bytes = fetchRangeBytes(start, length)
        System.arraycopy(bytes, 0, destination, destinationOffset, bytes.size)
        return bytes.size
    }

    private suspend fun fetchRangeBytes(
        start: Long,
        length: Int,
    ): ByteArray {
        val request =
            Request
                .Builder()
                .url(remoteUrl)
                .header("Range", "bytes=$start-${start + length - 1}")
                .header("User-Agent", STEAM_REMOTE_USER_AGENT)
                .build()
        return httpClient.newCall(request).awaitCall().use { response ->
            when {
                response.code == HTTP_PARTIAL_CONTENT -> Unit
                response.code == HTTP_OK && start == 0L -> Unit
                else -> throw IOException("Remote video range request failed: HTTP ${response.code}")
            }
            val body = response.body
            if (response.code == HTTP_PARTIAL_CONTENT && start > 0L) {
                val total = parseContentRangeTotal(response.header("Content-Range"))
                check(total != null) { "Remote video range response missing Content-Range" }
            }
            val stream = body.byteStream()
            val output = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = stream.read(output, filled, length - filled)
                if (read < 0) break
                filled += read
            }
            if (filled < length) {
                throw IOException("Remote video range returned ${filled}/$length bytes")
            }
            output
        }
    }

    private suspend fun Call.awaitCall(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (continuation.isActive) continuation.resume(response)
                    }
                },
            )
        }

    internal companion object {
        private const val STEAM_REMOTE_STREAM_LOG_TAG = "SteamRemoteVideo"
        private const val STEAM_REMOTE_USER_AGENT = "WallHub-Android/0.6 (Workshop Video Stream)"
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        internal const val REMOTE_AHEAD_WINDOW_BYTES = 8L * 1024L * 1024L

        internal suspend fun open(
            title: String,
            fileName: String,
            remoteUrl: String,
            expectedSize: Long,
            clientBuilder: OkHttpClient.Builder,
        ): SteamRemoteVideoStream {
            val client =
                clientBuilder
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .build()
            val contentLength = probeContentLength(client, remoteUrl)
            check(contentLength > 0L) { "Remote video stream reported no content length" }
            if (expectedSize > 0L && contentLength != expectedSize) {
                Log.w(
                    STEAM_REMOTE_STREAM_LOG_TAG,
                    "Remote video length $contentLength differs from published size $expectedSize",
                )
            }
            return SteamRemoteVideoStream(
                title = title,
                fileName = fileName,
                remoteUrl = remoteUrl,
                httpClient = client,
                contentLength = contentLength,
            )
        }

        private suspend fun probeContentLength(
            client: OkHttpClient,
            remoteUrl: String,
        ): Long {
            val request =
                Request
                    .Builder()
                    .url(remoteUrl)
                    .header("Range", "bytes=0-1")
                    .header("User-Agent", STEAM_REMOTE_USER_AGENT)
                    .build()
            return client.newCall(request).awaitCall().use { response ->
                when {
                    response.code == HTTP_PARTIAL_CONTENT ->
                        parseContentRangeTotal(response.header("Content-Range"))
                    response.code == HTTP_OK -> response.body.contentLength().takeIf { it > 0L }
                    else -> throw IOException("Remote video probe failed: HTTP ${response.code}")
                }
            } ?: -1L
        }
    }
}
