package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Separates the externally hosted Steam session from WorkManager task orchestration. */
internal interface WorkshopContentGateway {
    suspend fun acquireContentTransportLease(): Closeable = Closeable {}

    suspend fun prewarmContentAccess(
        target: WorkshopContentTarget,
        credential: SteamContentCredential?,
    ) = Unit

    suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget

    suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult
}

internal class FormalSteamWorkshopContentGateway(
    private val httpClientFactory: SteamHttpClientFactory,
    private val contentDownloader: SteamContentDownloader,
) : WorkshopContentGateway {
    override suspend fun acquireContentTransportLease(): Closeable =
        contentDownloader.acquireContentTransportLease()

    override suspend fun prewarmContentAccess(
        target: WorkshopContentTarget,
        credential: SteamContentCredential?,
    ) {
        contentDownloader.prewarmContentAccess(target, credential)
    }

    override suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget =
        SteamWorkshopContentApi(
            httpClientFactory.newBuilder().applyDownloadProxy(proxyUrl),
        ).fetchContentTarget(publishedFileId)

    override suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult {
        if (workshopDownloadSource(target) == WorkshopDownloadSource.DIRECT_FILE) {
            return downloadDirectVideo(
                target = target,
                destinationDirectory = destinationDirectory,
                proxyUrl = options.proxyUrl,
                control = control,
                onProgress = onProgress,
            )
        }
        return contentDownloader.download(
            target = target,
            destinationDirectory = destinationDirectory,
            credential = credential,
            options = options,
            control = control,
            onProgress = onProgress,
        )
    }

    private suspend fun downloadDirectVideo(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        proxyUrl: String,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult =
        withContext(Dispatchers.IO) {
            val remoteUrl = requireNotNull(remoteVideoUrlOrNull(target)) {
                "Steam file_url is not a recognized video file"
            }
            val videoName = directVideoFileName(target, remoteUrl)
            val previewUrl = target.previewUrl.trim()
            require(previewUrl.isNotEmpty()) { "Direct video Workshop item has no preview URL" }
            destinationDirectory.mkdirs()
            check(destinationDirectory.isDirectory) { "Failed to create download staging directory" }
            val client = httpClientFactory.newBuilder().applyDownloadProxy(proxyUrl).build()
            try {
                onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.RESOLVING))
                val videoFile = WorkshopStagingPath.resolve(destinationDirectory, videoName)
                val downloadedBytes =
                    downloadDirectFile(
                        client = client,
                        url = remoteUrl,
                        destination = videoFile,
                        expectedSize = target.expectedSize,
                        control = control,
                    ) { completedBytes, totalBytes ->
                        onProgress(
                            SteamDownloadProgress(
                                phase = SteamDownloadPhase.DOWNLOADING,
                                currentFile = videoName,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                totalFiles = 1,
                            ),
                        )
                    }
                val previewCandidate = WorkshopStagingPath.resolve(destinationDirectory, directPreviewFileName(previewUrl))
                downloadDirectFile(
                    client = client,
                    url = previewUrl,
                    destination = previewCandidate,
                    expectedSize = 0L,
                    maxBytes = MAX_DIRECT_PREVIEW_BYTES,
                    control = control,
                    onProgress = { _, _ -> },
                )
                val previewExtension = validatedPreviewExtension(previewCandidate)
                if (previewExtension == null) {
                    previewCandidate.delete()
                    error("Steam direct preview is not a supported image")
                }
                val previewFile = WorkshopStagingPath.resolve(destinationDirectory, "preview.$previewExtension")
                if (previewCandidate != previewFile) {
                    if (previewFile.exists()) check(previewFile.delete()) { "Failed to replace stale Steam preview" }
                    check(previewCandidate.renameTo(previewFile)) { "Failed to finalize Steam preview" }
                }
                val project =
                    JSONObject()
                        .put("type", "video")
                        .put("title", target.title)
                        .put("file", videoName)
                        .put("preview", previewFile.name)
                val projectFile = WorkshopStagingPath.resolve(destinationDirectory, "project.json")
                val temporaryProjectFile = File(destinationDirectory, "project.json.wallhub.part")
                temporaryProjectFile.writeText(project.toString(2), Charsets.UTF_8)
                if (projectFile.exists()) check(projectFile.delete()) { "Failed to replace stale project.json" }
                check(temporaryProjectFile.renameTo(projectFile)) { "Failed to finalize project.json" }
                onProgress(
                    SteamDownloadProgress(
                        phase = SteamDownloadPhase.DOWNLOADING,
                        currentFile = videoName,
                        completedBytes = downloadedBytes,
                        totalBytes = downloadedBytes,
                        completedFiles = 1,
                        totalFiles = 1,
                    ),
                )
                SteamContentDownloadResult(
                    rootDirectory = destinationDirectory,
                    downloadedBytes = downloadedBytes,
                    totalBytes = downloadedBytes,
                    fileCount = 1,
                    usedAuthenticatedSession = false,
                )
            } finally {
                runCatching { client.dispatcher.executorService.shutdown() }
            }
        }
}

internal enum class WorkshopDownloadSource {
    DIRECT_FILE,
    STEAM_PIPE,
}

internal fun workshopDownloadSource(target: WorkshopContentTarget): WorkshopDownloadSource =
    if (target.fileUrl.isNotBlank()) WorkshopDownloadSource.DIRECT_FILE else WorkshopDownloadSource.STEAM_PIPE

internal fun directVideoFileName(
    target: WorkshopContentTarget,
    remoteUrl: String,
): String {
    val extension = remoteUrl.substringBefore('?').substringBefore('#').videoFileExtension()
    require(extension in VIDEO_FILE_EXTENSIONS) { "Unsupported direct video extension" }
    val supplied = target.rawFileName.trim()
    if (supplied.isBlank() || '/' in supplied || '\\' in supplied || supplied.videoFileExtension() != extension) {
        return "wallpaper.$extension"
    }
    val sanitizedStem =
        supplied
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim(' ', '.')
            .take(MAX_DIRECT_FILE_STEM_LENGTH)
            .ifBlank { "wallpaper" }
    return "$sanitizedStem.$extension"
}

internal fun directPreviewFileName(previewUrl: String): String {
    val extension = previewUrl.substringBefore('?').substringBefore('#').videoFileExtension()
    return "preview.${extension.takeIf { it in setOf("gif", "jpg", "jpeg", "png", "webp") } ?: "jpg"}"
}

internal fun validatedPreviewExtension(file: File): String? {
    if (!file.isFile || file.length() !in 1..MAX_DIRECT_PREVIEW_BYTES) return null
    val header = ByteArray(12)
    val length = file.inputStream().use { it.read(header) }
    return when {
        length >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "jpg"
        length >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "png"
        length >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
        length >= 12 &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }
}

private suspend fun downloadDirectFile(
    client: okhttp3.OkHttpClient,
    url: String,
    destination: File,
    expectedSize: Long,
    maxBytes: Long = MAX_MANIFEST_FILE_BYTES,
    control: suspend () -> SteamDownloadControl,
    onProgress: suspend (completedBytes: Long, totalBytes: Long) -> Unit,
): Long {
    checkDownloadControl(control)
    val temporary = File(destination.parentFile, "${destination.name}.wallhub.part")
    if (expectedSize > 0L && destination.isFile && destination.length() == expectedSize) {
        onProgress(expectedSize, expectedSize)
        return expectedSize
    }
    if (destination.exists()) check(destination.delete()) { "Failed to replace stale Steam direct file" }
    if (temporary.length() > maxBytes || (expectedSize > 0L && temporary.length() > expectedSize)) {
        check(temporary.delete()) { "Failed to discard invalid Steam partial download" }
    }
    val resumeOffset = temporary.length().coerceAtLeast(0L)
    val request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", DIRECT_FILE_USER_AGENT)
            .apply { if (resumeOffset > 0L) header("Range", "bytes=$resumeOffset-") }
            .build()
    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Steam direct file request failed: HTTP ${response.code}")
        val resumed = resumeOffset > 0L && response.code == HTTP_PARTIAL_CONTENT
        val responseLength = response.body.contentLength().takeIf { it >= 0L }
        val totalBytes =
            if (resumed) {
                val contentRange = parseDirectContentRange(response.header("Content-Range"))
                    ?: throw IOException("Steam direct file range response is invalid")
                check(contentRange.start == resumeOffset) { "Steam direct file resumed at the wrong offset" }
                contentRange.total
            } else {
                responseLength ?: expectedSize
            }
        if (expectedSize > 0L && totalBytes > 0L) {
            check(totalBytes == expectedSize) { "Steam direct file length mismatch: $totalBytes != $expectedSize" }
        }
        check(totalBytes <= maxBytes || totalBytes <= 0L) { "Steam direct file exceeds size limit" }
        var copied = if (resumed) resumeOffset else 0L
        response.body.byteStream().use { input ->
            FileOutputStream(temporary, resumed).use { output ->
                if (resumed) onProgress(copied, totalBytes.coerceAtLeast(copied))
                val buffer = ByteArray(DIRECT_FILE_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    checkDownloadControl(control)
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    require(copied <= maxBytes) { "Steam direct file exceeds size limit" }
                    onProgress(copied, totalBytes.coerceAtLeast(copied))
                }
            }
        }
        if (expectedSize > 0L) check(copied == expectedSize) {
            "Steam direct file length mismatch: $copied != $expectedSize"
        }
        if (totalBytes > 0L) check(copied == totalBytes) {
            "Steam direct file ended early: $copied != $totalBytes"
        }
        check(temporary.renameTo(destination)) { "Failed to finalize Steam direct file" }
        copied
    }
}

internal data class DirectContentRange(
    val start: Long,
    val total: Long,
)

internal fun parseDirectContentRange(value: String?): DirectContentRange? {
    val match = DIRECT_CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return DirectContentRange(start, total).takeIf { start >= 0L && end >= start && total > end }
}

private const val DIRECT_FILE_BUFFER_SIZE = 64 * 1024
private const val DIRECT_FILE_USER_AGENT = "WallHub-Android/0.8 (Workshop Direct Download)"
private const val MAX_DIRECT_FILE_STEM_LENGTH = 120
internal const val MAX_DIRECT_PREVIEW_BYTES = 32L * 1024L * 1024L
private const val HTTP_PARTIAL_CONTENT = 206
private val DIRECT_CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

@Singleton
internal class SteamWorkshopContentClient private constructor(
    private val gateway: WorkshopContentGateway,
    private val nowMs: () -> Long,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    private data class CachedTarget(
        val target: WorkshopContentTarget,
        val expiresAtMs: Long,
    )

    private val targetCache =
        object : LinkedHashMap<Long, CachedTarget>(TARGET_CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedTarget>?): Boolean =
                size > TARGET_CACHE_MAX_ENTRIES
        }
    private val targetLocks = Array(TARGET_CACHE_LOCK_COUNT) { Mutex() }

    private fun cachedTarget(publishedFileId: Long): WorkshopContentTarget? =
        synchronized(targetCache) {
            targetCache[publishedFileId]
                ?.takeIf { cached -> cached.expiresAtMs > nowMs() }
                ?.target
                ?: run {
                    targetCache.remove(publishedFileId)
                    null
                }
        }

    @Inject
    constructor(
        httpClientFactory: SteamHttpClientFactory,
        contentDownloader: SteamContentDownloader,
    ) : this(FormalSteamWorkshopContentGateway(httpClientFactory, contentDownloader), System::currentTimeMillis, Unit)

    internal constructor(
        gateway: WorkshopContentGateway,
        nowMs: () -> Long = System::currentTimeMillis,
    ) : this(gateway, nowMs, Unit)

    internal suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget {
        cachedTarget(publishedFileId)?.let { return it }
        return targetLocks[Math.floorMod(publishedFileId.hashCode(), targetLocks.size)].withLock {
            cachedTarget(publishedFileId)?.let { cached -> return@withLock cached }
            gateway.fetchContentTarget(publishedFileId, proxyUrl).also { target ->
                synchronized(targetCache) {
                    targetCache[publishedFileId] = CachedTarget(target, nowMs() + TARGET_CACHE_TTL_MS)
                }
            }
        }
    }

    internal suspend fun prewarmContentAccess(
        target: WorkshopContentTarget,
        credential: SteamContentCredential?,
    ) = gateway.prewarmContentAccess(target, credential)

    internal fun invalidateContentTarget(publishedFileId: Long) {
        synchronized(targetCache) { targetCache.remove(publishedFileId) }
    }

    internal suspend fun acquireContentTransportLease(): Closeable =
        gateway.acquireContentTransportLease()

    internal suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult =
        gateway.download(
            target = target,
            destinationDirectory = destinationDirectory,
            credential = credential,
            options = options,
            control = control,
            onProgress = onProgress,
        )

    private companion object {
        const val TARGET_CACHE_TTL_MS = 5 * 60 * 1_000L
        const val TARGET_CACHE_MAX_ENTRIES = 64
        const val TARGET_CACHE_LOCK_COUNT = 16
    }
}
