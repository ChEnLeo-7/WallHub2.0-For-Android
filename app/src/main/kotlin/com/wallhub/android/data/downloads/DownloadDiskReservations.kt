package com.wallhub.android.data.downloads

import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Destination-space accounting for concurrent downloads.
 *
 * Each reservation holds the space one download expects to need against the destination volume, so two
 * downloads started together cannot both pass a free-space check that only one of them can honour. The
 * bookkeeping is per volume, and releasing is non-cancellable so a cancelled download still returns its
 * reservation.
 */

/** The space a download of [pendingBytes] needs, including the safety reserve, or a failure if it cannot fit. */
internal fun requiredDownloadSpace(
    pendingBytes: Long,
    usableSpace: Long,
): Long {
    require(pendingBytes >= 0L) { "Steam download pending size is negative: $pendingBytes" }
    require(usableSpace >= 0L) { "Destination usable space is negative: $usableSpace" }
    if (pendingBytes == 0L) return 0L
    val percentageReserve = pendingBytes / DOWNLOAD_SPACE_RESERVE_DIVISOR
    val reserve = maxOf(MIN_DOWNLOAD_SPACE_RESERVE_BYTES, percentageReserve).coerceAtMost(MAX_DOWNLOAD_SPACE_RESERVE_BYTES)
    require(pendingBytes <= Long.MAX_VALUE - reserve) { "Steam download disk requirement is too large" }
    val required = pendingBytes + reserve
    require(usableSpace >= required) {
        "Insufficient destination space: need $required bytes ($pendingBytes download + $reserve safety reserve), " +
            "but only $usableSpace bytes are usable"
    }
    return required
}

internal object DownloadDiskReservations {
    private val mutex = Mutex()
    private val reservedByVolume = mutableMapOf<String, Long>()

    suspend fun <T> withReservation(
        destinationDirectory: File,
        pendingBytes: Long,
        block: suspend () -> T,
    ): T {
        val storageRoot =
            generateSequence(destinationDirectory.absoluteFile) { it.parentFile }.firstOrNull(File::exists)
                ?: error("Unable to locate destination storage for ${destinationDirectory.absolutePath}")
        val key = "${storageRoot.canonicalFile.toPath().root}:${storageRoot.totalSpace}"
        val required = requiredDownloadSpace(pendingBytes, Long.MAX_VALUE)
        mutex.withLock {
            val alreadyReserved = reservedByVolume[key] ?: 0L
            require(alreadyReserved <= storageRoot.usableSpace && required <= storageRoot.usableSpace - alreadyReserved) {
                "Insufficient destination space: need $required bytes with $alreadyReserved bytes reserved by other downloads, " +
                    "but only ${storageRoot.usableSpace} bytes are usable"
            }
            reservedByVolume[key] = alreadyReserved + required
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val remaining = (reservedByVolume[key] ?: required) - required
                    if (remaining > 0L) reservedByVolume[key] = remaining else reservedByVolume.remove(key)
                }
            }
        }
    }
}

internal const val MIN_DOWNLOAD_SPACE_RESERVE_BYTES = 512L * 1024L * 1024L
internal const val MAX_DOWNLOAD_SPACE_RESERVE_BYTES = 4L * 1024L * 1024L * 1024L
private const val DOWNLOAD_SPACE_RESERVE_DIVISOR = 20L
