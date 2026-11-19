package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.safeChunkDownloadConcurrency
import java.io.File

/**
 * The download vocabulary every per-item transfer speaks.
 *
 * These types used to sit beside the kSteam-backed `SteamContentDownloader`. That implementation is gone and
 * the Steam Content SDK serves every depot download, but the vocabulary stayed because it is the product's
 * own: the SDK depot transfer, the download runner, the direct-file path, and
 * the progress UI all pass these values around.
 */

internal enum class SteamDownloadPhase {
    CONNECTING,
    AUTHENTICATING,
    RECOVERING_SESSION,
    RESOLVING,
    PREPARING_DOWNLOAD,
    DOWNLOADING,
    VERIFYING,
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
    val networkBytes: Long = 0L,
    val networkBytesPerSecond: Long = 0L,
    val firstContentReceived: Boolean = false,
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
): Int = safeChunkDownloadConcurrency(configuredConcurrency)

/** Reads the caller's control callback and turns a pause or a cancel into the matching exception. */
internal suspend fun checkDownloadControl(control: suspend () -> SteamDownloadControl) {
    when (control()) {
        SteamDownloadControl.CONTINUE -> return
        SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()
        SteamDownloadControl.CANCEL -> throw SteamDownloadCancelledException()
    }
}
