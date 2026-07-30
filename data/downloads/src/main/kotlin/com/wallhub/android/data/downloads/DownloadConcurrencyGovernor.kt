package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadConcurrencyGovernor
    @Inject
    constructor() {
        private val mutex = Mutex()
        private val waiters = ArrayDeque<Waiter>()
        private var activeDownloads = 0

        suspend fun <T> withSlot(
            taskId: String,
            priority: Long,
            limit: Int,
            block: suspend () -> T,
        ): T {
            val effectiveLimit = limit.coerceIn(1, 4)
            acquire(taskId, priority, effectiveLimit)
            return try {
                block()
            } finally {
                release()
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
                mutex.withLock { waiters.remove(waiter) }
                throw error
            }
        }

        private suspend fun release() {
            val next =
                mutex.withLock {
                    activeDownloads = (activeDownloads - 1).coerceAtLeast(0)
                    val nextIndex =
                        waiters.indices
                            .filter { index -> activeDownloads < waiters[index].limit }
                            .minByOrNull { index ->
                                priorityOverrides[waiters[index].taskId] ?: waiters[index].priority
                            }
                            ?: -1
                    if (nextIndex < 0) return@withLock null
                    val waiter = waiters.removeAt(nextIndex)
                    activeDownloads += 1
                    waiter.signal
                }
            next?.complete(Unit)
        }

        private data class Waiter(
            val taskId: String,
            val priority: Long,
            val limit: Int,
            val signal: CompletableDeferred<Unit>,
        )

        private var priorityOverrides: Map<String, Long> = emptyMap()
    }
