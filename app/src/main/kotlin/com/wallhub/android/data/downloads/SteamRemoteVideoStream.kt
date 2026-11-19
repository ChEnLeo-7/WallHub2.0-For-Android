package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
    val parsed = target.fileUrl.trim().toHttpUrlOrNull() ?: return null
    if (!parsed.isHttps || parsed.port != 443 || !isTrustedSteamFileHost(parsed.host)) return null
    if (parsed.encodedPath.videoFileExtension() !in VIDEO_FILE_EXTENSIONS) return null
    return parsed.toString()
}

private fun isTrustedSteamFileHost(hostname: String): Boolean {
    val host = hostname.lowercase().trimEnd('.')
    return TRUSTED_STEAM_FILE_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }
}

internal fun parseContentRangeTotal(contentRange: String?): Long? {
    val total = contentRange?.substringAfterLast('/')?.trim().orEmpty()
    if (total.isEmpty() || total == "*") return null
    return total.toLongOrNull()?.takeIf { it > 0L }
}

private val TRUSTED_STEAM_FILE_SUFFIXES =
    setOf(
        "steamusercontent.com",
        "steamcontent.com",
        "steamstatic.com",
        "steampowered.com",
        "steamusercontent-a.akamaihd.net",
        "steamcdn-a.akamaihd.net",
    )

internal suspend fun Call.awaitCall(): Response =
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
                    continuation.resume(response) { _, undelivered, _ -> undelivered.close() }
                }
            },
        )
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
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private var aheadJob: Job? = null
    private var aheadCall: Call? = null
    private var aheadStart = 0L
    private var aheadEnd = 0L
    private var latestReadStart = 0L
    private var latestReadEnd = 0L

    @Volatile
    private var cancellationRequested = false

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
            check(!cancellationRequested && !closed) { "Remote video stream is closed" }
            require(position >= 0L) { "Invalid remote video read position" }
            require(length >= 0) { "Invalid remote video read length" }
            require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
                "Invalid remote video read destination"
            }
            if (length == 0 || position >= contentLength) return@withContext 0
            val requested = minOf(length.toLong(), contentLength - position).toInt()
            synchronized(aheadJobLock) {
                latestReadStart = position
                latestReadEnd = position + requested
                if (aheadJob?.isActive == true && (position >= aheadEnd || latestReadEnd < aheadStart)) {
                    aheadJob?.cancel()
                    aheadCall?.cancel()
                }
            }
            var copied = consumeBuffered(position, requested, destination, destinationOffset)
            if (copied < requested) {
                awaitOverlappingPrefetch(position + copied, position + requested)
                while (copied < requested) {
                    val next = position + copied
                    val buffered = consumeBuffered(next, requested - copied, destination, destinationOffset + copied)
                    if (buffered > 0) {
                        copied += buffered
                    } else {
                        val gap = bufferMutex.withLock {
                            if (next < bufferStart && bufferStart < position + requested) {
                                (bufferStart - next).toInt()
                            } else {
                                requested - copied
                            }
                        }
                        copied += fetchRangeInto(next, destination, destinationOffset + copied, gap)
                    }
                }
            }
            scheduleAheadPrefetch(position + requested)
            copied
        }

    override fun cancelPendingReads() {
        cancellationRequested = true
        synchronized(aheadJobLock) { aheadJob?.cancel() }
        activeCalls.forEach(Call::cancel)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPendingReads()
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
            val count = minOf(requested.toLong(), bufferEnd - position).toInt()
            System.arraycopy(bufferData, from, destination, destinationOffset, count)
            return count
        }
    }

    private suspend fun awaitOverlappingPrefetch(start: Long, end: Long) {
        val job = synchronized(aheadJobLock) {
            val active = aheadJob?.takeIf { it.isActive && start < aheadEnd && end > aheadStart }
            if (active != null && (start < aheadStart || end > aheadEnd)) {
                active.cancel()
                aheadCall?.cancel()
                null
            } else {
                active
            }
        } ?: return
        if (withTimeoutOrNull(PREFETCH_WAIT_MILLIS) { job.join(); true } == null) {
            synchronized(aheadJobLock) {
                if (aheadJob === job) {
                    job.cancel()
                    aheadCall?.cancel()
                }
            }
        }
    }

    private suspend fun scheduleAheadPrefetch(from: Long) {
        if (cancellationRequested || closed || from >= contentLength) return
        bufferMutex.withLock {
            if (from >= bufferStart && from < bufferEnd) return
            synchronized(aheadJobLock) {
                if (cancellationRequested || closed || aheadJob?.isActive == true) return
                val end = from + minOf(contentLength - from, REMOTE_AHEAD_WINDOW_BYTES)
                aheadStart = from
                aheadEnd = end
                aheadJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val prefetchJob = currentCoroutineContext()[Job]
                        val bytes = fetchRangeBytes(from, (end - from).toInt(), prefetch = true)
                        bufferMutex.withLock {
                            synchronized(aheadJobLock) {
                                if (aheadJob === prefetchJob && prefetchJob?.isActive == true &&
                                    latestReadStart < end && latestReadEnd >= from
                                ) {
                                    bufferData = bytes
                                    bufferStart = from
                                    bufferEnd = from + bytes.size
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(STEAM_REMOTE_STREAM_LOG_TAG, "Remote video ahead prefetch failed: ${error.message}")
                    }
                }
                aheadJob?.start()
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
        prefetch: Boolean = false,
    ): ByteArray {
        val request =
            Request
                .Builder()
                .url(remoteUrl)
                .header("Range", "bytes=$start-${start + length - 1}")
                .header("User-Agent", STEAM_REMOTE_USER_AGENT)
                .build()
        val call = httpClient.newCall(request)
        activeCalls += call
        if (prefetch) {
            val job = currentCoroutineContext()[Job]
            synchronized(aheadJobLock) {
                if (aheadJob === job && job?.isActive == true) aheadCall = call else call.cancel()
            }
        }
        if (cancellationRequested || closed) call.cancel()
        return try {
            call.awaitCall().use { response ->
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
        } finally {
            if (prefetch) synchronized(aheadJobLock) { if (aheadCall === call) aheadCall = null }
            activeCalls -= call
        }
    }

    internal companion object {
        private const val STEAM_REMOTE_STREAM_LOG_TAG = "SteamRemoteVideo"
        private const val STEAM_REMOTE_USER_AGENT = "WallHub-Android/0.6 (Workshop Video Stream)"
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val PREFETCH_WAIT_MILLIS = 1_000L
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
                    .followRedirects(false)
                    .followSslRedirects(false)
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
