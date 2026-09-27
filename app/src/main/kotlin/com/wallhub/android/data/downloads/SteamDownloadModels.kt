package com.wallhub.android.data.downloads

import java.io.File

/**
 * The download vocabulary every per-item transfer speaks.
 *
 * These types used to sit beside the kSteam-backed `SteamContentDownloader`. That implementation is gone and
 * the Steam Content SDK serves every depot download, but the vocabulary stayed because it is the product's
 * own: [WorkshopContentTransfer] and its SDK implementation, the download worker, the direct-file path, and
 * the progress UI all pass these values around.
 */

internal enum class SteamDownloadPhase {
    CONNECTING,
    AUTHENTICATING,
    RESOLVING,
    PREPARING_DOWNLOAD,
    DOWNLOADING,
}

internal enum class SteamDownloadControl {
    CONTINUE,
    PAUSE,
    CANCEL,
}

internal class SteamDownloadPausedException : Exception("Steam download was paused")

internal class SteamDownloadCancelledException : Exception("Steam download was cancelled")

internal data class SteamDownloadProgress(
    val phase: SteamDownloadPhase,
    val currentFile: String? = null,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
)

internal data class SteamContentDownloadResult(
    val rootDirectory: File,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fileCount: Int,
    val usedAuthenticatedSession: Boolean,
)

internal data class SteamContentDownloadOptions(
    val chunkConcurrency: Int = 24,
    val proxyUrl: String = "",
    val timingContext: DownloadTimingContext? = null,
    val onFirstChunkCommitted: (suspend () -> Unit)? = null,
) {
    fun normalized(): SteamContentDownloadOptions =
        copy(
            chunkConcurrency = safeChunkConcurrency(chunkConcurrency, Runtime.getRuntime().maxMemory()),
            proxyUrl = proxyUrl.trim(),
        )
}

internal fun safeChunkConcurrency(
    configuredConcurrency: Int,
    @Suppress("UNUSED_PARAMETER") maxHeapBytes: Long,
): Int = configuredConcurrency.coerceIn(MIN_CONFIGURED_CHUNK_CONCURRENCY, MAX_CONFIGURED_CHUNK_CONCURRENCY)

/**
 * The chunk concurrency a download may ask the SDK for.
 *
 * The floor is 1 rather than 12 because the link, not the product, decides how many connections a depot can
 * take: a device or a CDN host that refuses a burst needs a setting that says "one chunk at a time", and the
 * SDK's own window is exactly this number (`MAX_LIVE_CLIENTS` caps the clients a run can hold). The ceiling is
 * the top of the SDK's client pool, so no value offered here is silently reduced.
 */
internal const val MIN_CONFIGURED_CHUNK_CONCURRENCY = 1
internal const val MAX_CONFIGURED_CHUNK_CONCURRENCY = 48

/** Reads the caller's control callback and turns a pause or a cancel into the matching exception. */
internal suspend fun checkDownloadControl(control: suspend () -> SteamDownloadControl) {
    when (control()) {
        SteamDownloadControl.CONTINUE -> return
        SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()
        SteamDownloadControl.CANCEL -> throw SteamDownloadCancelledException()
    }
}
