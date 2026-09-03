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
    private val promotedRequests = mutableSetOf<Long>()
    private var availableBytes = capacityBytes

    constructor(maxHeapBytes: Long) : this(downloadMemoryCapacityBytes(maxHeapBytes), true)

    suspend fun <T> withPermit(
        requestedBytes: Long,
        priority: SteamStreamChunkPriority = SteamStreamChunkPriority.PREFETCH,
        requestId: Long? = null,
        order: Long = Long.MAX_VALUE,
        block: suspend () -> T,
    ): T {
        val reservation =
            try {
                acquire(requestedBytes, priority, requestId, order)
            } catch (error: Throwable) {
                requestId?.let { id -> mutex.withLock { promotedRequests.remove(id) } }
                throw error
            }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                reservation.release()
                requestId?.let { id -> mutex.withLock { promotedRequests.remove(id) } }
            }
        }
    }

    /** Promotes an in-flight stream request across the memory/decode queue as well as the network queue. */
    suspend fun promote(requestId: Long) {
        val signals = mutableListOf<CompletableDeferred<Unit>>()
        mutex.withLock {
            promotedRequests += requestId
            waiters.firstOrNull { waiter -> waiter.requestId == requestId }?.priority = SteamStreamChunkPriority.FOREGROUND
            dispatchWaitersLocked(signals)
        }
        signals.forEach { signal -> signal.complete(Unit) }
    }

    suspend fun <T> withExclusivePermit(block: suspend () -> T): T = withPermit(capacityBytes, block = block)

    private suspend fun acquire(
        requestedBytes: Long,
        priority: SteamStreamChunkPriority,
        requestId: Long?,
        order: Long,
    ): Reservation {
        require(requestedBytes > 0L) { "Requested memory must be positive" }
        // Match the stream worker: a single oversized chunk may run, but only
        // while it owns the complete budget so the pipeline cannot deadlock.
        val bytes = requestedBytes.coerceAtMost(capacityBytes)
        val waiter =
            mutex.withLock {
                val effectivePriority =
                    if (requestId != null && requestId in promotedRequests) {
                        SteamStreamChunkPriority.FOREGROUND
                    } else {
                        priority
                    }
                if (waiters.isEmpty() && availableBytes >= bytes) {
                    availableBytes -= bytes
                    null
                } else {
                    Waiter(bytes, effectivePriority, requestId, order, CompletableDeferred()).also(waiters::addLast)
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
            dispatchWaitersLocked(signals)
        }
        signals.forEach { it.complete(Unit) }
    }

    private fun dispatchWaitersLocked(signals: MutableList<CompletableDeferred<Unit>>) {
        while (waiters.isNotEmpty()) {
            val next =
                waiters
                    .withIndex()
                    .minWithOrNull(
                        compareBy<IndexedValue<Waiter>> { it.value.priority }
                            .thenBy { it.value.order }
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
        var priority: SteamStreamChunkPriority,
        val requestId: Long?,
        val order: Long,
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

/** Accounts for managed and native buffers retained while a depot chunk is decoded and verified. */
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
