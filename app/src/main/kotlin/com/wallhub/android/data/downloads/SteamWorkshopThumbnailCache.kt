package com.wallhub.android.data.downloads

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal class SteamWorkshopThumbnailCache internal constructor(
    private val directory: File,
    clientBuilder: OkHttpClient.Builder,
    private val maxCacheBytes: Long,
    private val validateImage: (File) -> Boolean,
    private val openFile: (File) -> InputStream = { it.inputStream() },
) {
    constructor(context: Context, clientBuilder: OkHttpClient.Builder) : this(
        File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() },
        clientBuilder,
        MAX_CACHE_BYTES,
        { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        },
    )
    private val fileLock = Any()
    private val requestLocks = Array(64) { Mutex() }
    private val downloads = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val client =
        clientBuilder
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    suspend fun open(workshopId: Long, knownPreviewUrl: String?): InputStream? =
        suspendCancellableCoroutine { continuation ->
            val job = CoroutineScope(continuation.context).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val input = openInternal(workshopId, knownPreviewUrl)
                    continuation.resume(input) { _, undelivered, _ -> undelivered?.close() }
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
            continuation.invokeOnCancellation { job.cancel() }
        }

    private suspend fun openInternal(workshopId: Long, knownPreviewUrl: String?): InputStream? {
        if (workshopId <= 0L) return null
        val knownUrl = knownPreviewUrl?.takeIf { it.startsWith("https://") }
        val target = cacheFile(workshopId, knownUrl)
        return requestLocks[(workshopId % requestLocks.size).toInt()].withLock {
            synchronized(fileLock) {
                openCached(target)?.also { input ->
                    try {
                        trimCache()
                    } catch (error: Throwable) {
                        input.close()
                        throw error
                    }
                }
            }?.let { return@withLock it }
            downloads.withPermit {
                val url = knownUrl ?: fetchPreviewUrls(setOf(workshopId))[workshopId]
                    ?: return@withPermit null
                cachePreview(target, url)
            }
        }
    }

    private fun openCached(file: File): InputStream? {
        if (!file.isFile || file.length() <= 0L) return null
        val input = try {
            openFile(file)
        } catch (_: IOException) {
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return input
    }

    private suspend fun fetchPreviewUrls(workshopIds: Set<Long>): Map<Long, String> {
        if (workshopIds.isEmpty()) return emptyMap()
        val resolved = mutableMapOf<Long, String>()
        workshopIds.chunked(MAX_DETAILS_BATCH_SIZE).forEach { batch ->
            val form =
                FormBody
                    .Builder()
                    .add("itemcount", batch.size.toString())
                    .apply {
                        batch.forEachIndexed { index, id ->
                            add("publishedfileids[$index]", id.toString())
                        }
                    }.build()
            val request =
                Request
                    .Builder()
                    .url(PUBLISHED_FILE_DETAILS_URL)
                    .post(form)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
            val previews =
                try {
                    client.newCall(request).useThumbnailResponse { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Steam cover request failed: HTTP ${response.code}")
                        }
                        parsePreviewUrls(response.body.string())
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyMap()
                }
            previews.forEach { (id, url) -> resolved[id] = url }
        }
        return resolved
    }

    private suspend fun cachePreview(
        target: File,
        previewUrl: String,
    ): InputStream? {
        val temporary = File(directory, "${target.name}.part")
        return try {
            if (!downloadPreviewToTemporaryFile(previewUrl, temporary) || !validateImage(temporary)) return null
            val context = currentCoroutineContext()
            context.ensureActive()
            synchronized(fileLock) {
                if (!promoteTemporaryPreview(temporary, target)) return@synchronized null
                val input = openCached(target) ?: return@synchronized null
                try {
                    context.ensureActive()
                    trimCache()
                    input
                } catch (error: Throwable) {
                    input.close()
                    throw error
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            null
        } finally {
            temporary.delete()
        }
    }

    private suspend fun downloadPreviewToTemporaryFile(
        previewUrl: String,
        temporary: File,
    ): Boolean {
        if (!previewUrl.startsWith("https://")) return false
        temporary.delete()
        val request =
            Request
                .Builder()
                .url(previewUrl)
                .get()
                .header("Accept", "image/avif,image/webp,image/*")
                .header("User-Agent", USER_AGENT)
                .build()
        return client.newCall(request).useThumbnailResponse { response ->
            if (!response.isSuccessful) return@useThumbnailResponse false
            val body = response.body
            if (body.contentLength() !in -1L..MAX_THUMBNAIL_BYTES) return@useThumbnailResponse false
            copyPreviewBody(body, temporary)
        }
    }

    private suspend fun copyPreviewBody(
        body: okhttp3.ResponseBody,
        temporary: File,
    ): Boolean =
        body.byteStream().use { input ->
            temporary.outputStream().buffered().use { output ->
                copyPreviewStream(input, output)
            }
        }

    private suspend fun copyPreviewStream(
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_THUMBNAIL_BYTES) return false
            output.write(buffer, 0, read)
        }
        return total > 0L
    }

    private fun promoteTemporaryPreview(
        temporary: File,
        target: File,
    ): Boolean {
        if (target.exists() && !target.delete()) return false
        if (!temporary.renameTo(target)) return false
        target.setLastModified(System.currentTimeMillis())
        return true
    }

    private fun trimCache() {
        val files =
            directory
                .listFiles { file -> file.isFile && file.extension == "cover" }
                ?.sortedByDescending(File::lastModified)
                .orEmpty()
        var retainedBytes = 0L
        files.forEach { file ->
            retainedBytes += file.length().coerceAtLeast(0L)
            if (retainedBytes > maxCacheBytes) file.delete()
        }
        directory
            .listFiles { file -> file.name.endsWith(".part") }
            ?.filter { file -> System.currentTimeMillis() - file.lastModified() > STALE_PART_AGE_MS }
            ?.forEach(File::delete)
    }

    private fun cacheFile(workshopId: Long, previewUrl: String?): File {
        val suffix = previewUrl?.let { url ->
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            "-" + digest.joinToString("") { "%02x".format(it) }
        }.orEmpty()
        return File(directory, "$workshopId$suffix.cover")
    }

    private companion object {
        const val CACHE_DIRECTORY = "steam-workshop-covers"
        const val MAX_DETAILS_BATCH_SIZE = 50
        const val MAX_CONCURRENT_DOWNLOADS = 3
        const val MAX_THUMBNAIL_BYTES = 12L * 1024L * 1024L
        const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        const val COPY_BUFFER_BYTES = 16 * 1024
        const val STALE_PART_AGE_MS = 24L * 60L * 60L * 1_000L
        const val USER_AGENT = "WallHub-Android/0.8 (Local Cover Cache)"
        const val PUBLISHED_FILE_DETAILS_URL =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
    }
}

@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> Call.useThumbnailResponse(block: suspend (Response) -> T): T {
    val context = currentCoroutineContext()
    val cancellation = context.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) cancel()
    }
    try {
        return awaitThumbnailResponse().use { response -> block(response) }
    } catch (error: IOException) {
        context.ensureActive()
        throw error
    } finally {
        cancellation.dispose()
    }
}

internal suspend fun Call.awaitThumbnailResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    error: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
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

private fun parsePreviewUrls(body: String): Map<Long, String> {
    val details =
        JSONObject(body)
            .optJSONObject("response")
            ?.optJSONArray("publishedfiledetails")
            ?: return emptyMap()
    return buildMap {
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            val id =
                when (val value = detail.opt("publishedfileid")) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                } ?: continue
            val previewUrl = detail.optString("preview_url").trim()
            if (id > 0L && previewUrl.startsWith("https://")) put(id, previewUrl)
        }
    }
}
