package com.wallhub.android.data.downloads

import java.io.File

/**
 * Staging-directory safety and the direct-file vocabulary.
 *
 * A Steam manifest names the files inside an item, and a direct-file item names its own remote URL. Both are
 * untrusted input, so every path a download writes to is resolved through [WorkshopStagingPath] and every
 * remote name is checked against [VIDEO_FILE_EXTENSIONS].
 */

/** A direct download's size ceiling: past this the file is refused rather than written. */
internal const val MAX_DIRECT_FILE_BYTES = 64L * 1024L * 1024L * 1024L

internal val VIDEO_FILE_EXTENSIONS = setOf("mp4", "webm", "mkv", "m4v")

/**
 * The lower-case extension of a URL or path's last segment, or an empty string when it has none.
 *
 * The input is a URL Steam published or a manifest path, so it may carry a query string, a fragment, or
 * Windows separators; the caller decides whether the result is one of [VIDEO_FILE_EXTENSIONS].
 */
internal fun String.videoFileExtension(): String {
    val fileName = substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}

/** Resolves a manifest path inside the staging root, refusing anything that could leave it. */
internal object WorkshopStagingPath {
    fun resolve(
        rootDirectory: File,
        relativePath: String,
    ): File {
        val normalized = relativePath.replace('\\', '/')
        val segments = normalized.split('/')
        require(normalized.isNotBlank()) { "Steam manifest contains an empty file path" }
        require(!normalized.startsWith('/')) { "Steam manifest contains an absolute file path" }
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Steam manifest contains an unsafe file path"
        }
        val root = rootDirectory.canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "Steam manifest file path escapes the staging directory"
        }
        return target
    }
}
