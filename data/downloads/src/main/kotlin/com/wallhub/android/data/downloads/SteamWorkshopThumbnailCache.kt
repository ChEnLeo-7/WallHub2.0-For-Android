package com.wallhub.android.data.downloads

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal class SteamWorkshopThumbnailCache(
    context: Context,
    clientBuilder: OkHttpClient.Builder,
) {
    private val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val resolveMutex = Mutex()
    private val client = clientBuilder
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun cachedUri(workshopId: Long): String? {
        if (workshopId <= 0L) return null
        val file = cacheFile(workshopId)
        if (!file.isFile || file.length() <= 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return Uri.fromFile(file).toString()
    }

    suspend fun resolve(
        workshopIds: Set<Long>,
        knownPreviewUrls: Map<Long, String>,
    ): Map<Long, String> = withContext(Dispatchers.IO) {
        resolveMutex.withLock {
            val validIds = workshopIds.filterTo(linkedSetOf()) { it > 0L }
            if (validIds.isEmpty()) return@withLock emptyMap()

            trimCache()
            val resolved = validIds
                .mapNotNull { id -> cachedUri(id)?.let { id to it } }
                .toMap()
                .toMutableMap()
            val missingIds = validIds - resolved.keys
            if (missingIds.isEmpty()) return@withLock resolved

            val previewUrls = knownPreviewUrls
                .filterKeys { it in missingIds }
                .filterValues { it.startsWith("https://") }
                .toMutableMap()
            val idsNeedingLookup = missingIds - previewUrls.keys
            fetchPreviewUrls(idsNeedingLookup).forEach { (id, url) ->
                previewUrls.putIfAbsent(id, url)
            }

            val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
            coroutineScope {
                missingIds.mapNotNull { id ->
                    val url = previewUrls[id] ?: return@mapNotNull null
                    async {
                        semaphore.withPermit {
                            cachePreview(id, url)?.let { uri -> id to uri }
                        }
                    }
                }.awaitAll().filterNotNull().forEach { (id, uri) ->
                    resolved[id] = uri
                }
            }
            trimCache()
            resolved
        }
    }

    private suspend fun fetchPreviewUrls(workshopIds: Set<Long>): Map<Long, String> {
        if (workshopIds.isEmpty()) return emptyMap()
        val resolved = mutableMapOf<Long, String>()
        workshopIds.chunked(MAX_DETAILS_BATCH_SIZE).forEach { batch ->
            val form = FormBody.Builder()
                .add("itemcount", batch.size.toString())
                .apply {
                    batch.forEachIndexed { index, id ->
                        add("publishedfileids[$index]", id.toString())
                    }
                }
                .build()
            val request = Request.Builder()
                .url(PUBLISHED_FILE_DETAILS_URL)
                .post(form)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            val previews = try {
                client.newCall(request).awaitResponse().use { response ->
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

    private suspend fun cachePreview(workshopId: Long, previewUrl: String): String? {
        val target = cacheFile(workshopId)
        val temporary = File(directory, "${target.name}.part")
        return try {
            require(previewUrl.startsWith("https://")) { "Invalid Steam cover URL" }
            temporary.delete()
            val request = Request.Builder()
                .url(previewUrl)
                .get()
                .header("Accept", "image/avif,image/webp,image/*")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Steam cover download failed: HTTP ${response.code}")
                }
                val body = response.body
                val declaredSize = body.contentLength()
                require(declaredSize in -1L..MAX_THUMBNAIL_BYTES) { "Steam cover is too large" }
                body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_THUMBNAIL_BYTES) { "Steam cover is too large" }
                            output.write(buffer, 0, read)
                        }
                        require(total > 0L) { "Steam cover is empty" }
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "Steam cover is not a valid image"
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace cover cache")
            }
            if (!temporary.renameTo(target)) throw IOException("Unable to write cover cache")
            target.setLastModified(System.currentTimeMillis())
            Uri.fromFile(target).toString()
        } catch (error: CancellationException) {
            temporary.delete()
            throw error
        } catch (_: Throwable) {
            temporary.delete()
            null
        }
    }

    private fun trimCache() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "cover" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = 0L
        files.forEach { file ->
            retainedBytes += file.length().coerceAtLeast(0L)
            if (retainedBytes > MAX_CACHE_BYTES) file.delete()
        }
        directory.listFiles { file -> file.name.endsWith(".part") }
            ?.filter { file -> System.currentTimeMillis() - file.lastModified() > STALE_PART_AGE_MS }
            ?.forEach(File::delete)
    }

    private fun cacheFile(workshopId: Long): File = File(directory, "$workshopId.cover")

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

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(response))
                } else {
                    response.close()
                }
            }
        },
    )
}

internal fun parsePreviewUrls(body: String): Map<Long, String> {
    val details = JSONObject(body)
        .optJSONObject("response")
        ?.optJSONArray("publishedfiledetails")
        ?: return emptyMap()
    return buildMap {
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            val id = when (val value = detail.opt("publishedfileid")) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            } ?: continue
            val previewUrl = detail.optString("preview_url").trim()
            if (id > 0L && previewUrl.startsWith("https://")) put(id, previewUrl)
        }
    }
}
