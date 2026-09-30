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

        suspend fun <T> withConversionSlot(block: suspend () -> T): T = conversionMutex.withLock { block() }

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
): Int = configuredLimit.coerceIn(1, 3)
