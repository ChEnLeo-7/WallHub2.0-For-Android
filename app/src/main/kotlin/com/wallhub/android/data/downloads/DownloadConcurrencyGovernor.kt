package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadConcurrencyGovernor
    @Inject
    constructor() {
        private val memoryBudget = GLOBAL_DOWNLOAD_MEMORY_BUDGET
        private val mutex = Mutex()
        private val waiters = ArrayDeque<Waiter>()
        private var activeDownloads = 0
        private val conversionMutex = Mutex()
        suspend fun <T> withSlot(
            taskId: String,
            priority: Long,
            limit: Int,
            block: suspend () -> T,
        ): T {
            val effectiveLimit = safeDownloadLimit(limit, Runtime.getRuntime().maxMemory())
            acquire(taskId, priority, effectiveLimit)
            return try {
                block()
            } finally {
                withContext(NonCancellable) { release() }
            }
        }

        suspend fun updatePriorities(taskIds: List<String>) {
            mutex.withLock {
                priorityOverrides =
                    taskIds.withIndex().associate { (index, taskId) ->
                        taskId to index.toLong()
                    }
            }
        }

        suspend fun <T> withConversionSlot(block: suspend () -> T): T {
            conversionMutex.lock()
            return try {
                memoryBudget.withExclusivePermit(block)
            } finally {
                conversionMutex.unlock()
            }
        }

        private suspend fun acquire(
            taskId: String,
            priority: Long,
            limit: Int,
        ) {
            val waiter =
                mutex.withLock {
                    if (activeDownloads < limit) {
                        activeDownloads += 1
                        null
                    } else {
                        Waiter(taskId, priority, limit, CompletableDeferred()).also(waiters::addLast)
                    }
                }
            if (waiter == null) return
            try {
                waiter.signal.await()
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    val removed = mutex.withLock { waiters.remove(waiter) }
                    if (!removed) release()
                }
                throw error
            }
        }

        private suspend fun release() {
            mutex.withLock {
                activeDownloads = (activeDownloads - 1).coerceAtLeast(0)
                while (true) {
                    val nextIndex =
                        waiters.indices
                            .filter { index -> activeDownloads < waiters[index].limit }
                            .minByOrNull { index ->
                                priorityOverrides[waiters[index].taskId] ?: waiters[index].priority
                            }
                            ?: -1
                    if (nextIndex < 0) return@withLock
                    val waiter = waiters.removeAt(nextIndex)
                    activeDownloads += 1
                    if (waiter.signal.complete(Unit)) return@withLock
                    activeDownloads -= 1
                }
            }
        }

        private data class Waiter(
            val taskId: String,
            val priority: Long,
            val limit: Int,
            val signal: CompletableDeferred<Unit>,
        )

        private var priorityOverrides: Map<String, Long> = emptyMap()
    }

internal fun safeDownloadLimit(
    configuredLimit: Int,
    @Suppress("UNUSED_PARAMETER")
    maxHeapBytes: Long,
): Int = configuredLimit.coerceAtLeast(1)

private val GLOBAL_DOWNLOAD_MEMORY_BUDGET = DownloadMemoryBudget(Runtime.getRuntime().maxMemory())

internal class DownloadMemoryBudget private constructor(
    private val capacityBytes: Long,
    @Suppress("UNUSED_PARAMETER") fixedCapacity: Boolean,
) {
    private val mutex = Mutex()
    private val waiters = ArrayDeque<Waiter>()
    private var availableBytes = capacityBytes

    constructor(maxHeapBytes: Long) : this(downloadMemoryCapacityBytes(maxHeapBytes), true)

    suspend fun <T> withPermit(
        requestedBytes: Long,
        priority: SteamStreamChunkPriority = SteamStreamChunkPriority.PREFETCH,
        block: suspend () -> T,
    ): T {
        val reservation = acquire(requestedBytes, priority)
        return try {
            block()
        } finally {
            withContext(NonCancellable) { reservation.release() }
        }
    }

    suspend fun <T> withExclusivePermit(block: suspend () -> T): T = withPermit(capacityBytes, block = block)

    private suspend fun acquire(
        requestedBytes: Long,
        priority: SteamStreamChunkPriority,
    ): Reservation {
        require(requestedBytes in 1L..capacityBytes) {
            "Requested memory $requestedBytes exceeds budget capacity $capacityBytes"
        }
        val bytes = requestedBytes
        val waiter =
            mutex.withLock {
                if (waiters.isEmpty() && availableBytes >= bytes) {
                    availableBytes -= bytes
                    null
                } else {
                    Waiter(bytes, priority, CompletableDeferred()).also(waiters::addLast)
                }
            }
        if (waiter == null) return Reservation(this, bytes)
        try {
            waiter.signal.await()
            return Reservation(this, bytes)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val removed = mutex.withLock { waiters.remove(waiter) }
                if (!removed) release(bytes)
            }
            throw error
        }
    }

    private suspend fun release(bytes: Long) {
        val signals = mutableListOf<CompletableDeferred<Unit>>()
        mutex.withLock {
            availableBytes = (availableBytes + bytes).coerceAtMost(capacityBytes)
            while (waiters.isNotEmpty()) {
                val next =
                    waiters
                        .withIndex()
                        .minWithOrNull(
                            compareBy<IndexedValue<Waiter>> { it.value.priority }
                                .thenBy { it.index },
                        )
                        ?.value
                        ?: break
                if (availableBytes < next.bytes) break
                waiters.remove(next)
                availableBytes -= next.bytes
                signals += next.signal
            }
        }
        signals.forEach { it.complete(Unit) }
    }

    internal class Reservation(
        private val owner: DownloadMemoryBudget,
        private val bytes: Long,
    ) {
        private var released = false

        suspend fun release() {
            if (released) return
            released = true
            owner.release(bytes)
        }
    }

    private data class Waiter(
        val bytes: Long,
        val priority: SteamStreamChunkPriority,
        val signal: CompletableDeferred<Unit>,
    )

    companion object {
        fun withFixedCapacity(capacityBytes: Long): DownloadMemoryBudget {
            require(capacityBytes > 0L) { "Memory budget capacity must be positive" }
            return DownloadMemoryBudget(capacityBytes, true)
        }
    }
}

internal fun downloadMemoryCapacityBytes(maxHeapBytes: Long): Long =
    (maxHeapBytes / DOWNLOAD_MEMORY_HEAP_DIVISOR).coerceIn(
        minimumValue = DOWNLOAD_MEMORY_MIN_BYTES,
        maximumValue = DOWNLOAD_MEMORY_MAX_BYTES,
    )

/**
 * JavaSteam temporarily retains the destination array, ResponseBody byte array, Okio
 * segments and the decompressed result for a depot chunk.
 */
internal fun estimatedSteamChunkPeakMemoryBytes(
    compressedBytes: Int,
    uncompressedBytes: Int,
): Long {
    require(compressedBytes >= 0) { "Compressed Steam chunk size must not be negative" }
    require(uncompressedBytes >= 0) { "Uncompressed Steam chunk size must not be negative" }
    val networkAndDecodePeak =
        compressedBytes.toLong() * STEAM_CHUNK_COMPRESSED_BUFFER_COPIES + uncompressedBytes.toLong()
    val checksumPeak =
        compressedBytes.toLong() * STEAM_CHUNK_CHECKSUM_COMPRESSED_COPIES +
            uncompressedBytes.toLong() * STEAM_CHUNK_UNCOMPRESSED_BUFFER_COPIES
    return maxOf(networkAndDecodePeak, checksumPeak)
}

private const val DOWNLOAD_MEMORY_HEAP_DIVISOR = 8L
private const val DOWNLOAD_MEMORY_MIN_BYTES = 16L * 1024L * 1024L
private const val DOWNLOAD_MEMORY_MAX_BYTES = 32L * 1024L * 1024L
private const val STEAM_CHUNK_COMPRESSED_BUFFER_COPIES = 3L
private const val STEAM_CHUNK_CHECKSUM_COMPRESSED_COPIES = 2L
private const val STEAM_CHUNK_UNCOMPRESSED_BUFFER_COPIES = 2L
