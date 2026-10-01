package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DEFAULT_MAX_CONCURRENT_DOWNLOADS
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.safeMaxConcurrentDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadConcurrencyGovernor private constructor(
    initialLimit: Int,
    initialized: Boolean,
) {
    @Inject
    constructor(settingsRepository: SettingsRepository) : this(
        initialLimit = DEFAULT_MAX_CONCURRENT_DOWNLOADS,
        initialized = false,
    ) {
        startLimitCollection(
            limits = settingsRepository.preferences.map { preferences -> preferences.maxConcurrentDownloads },
            scope = applicationScope,
        )
    }

    internal constructor(initialLimit: Int) : this(initialLimit, initialized = true)

    internal constructor() : this(DEFAULT_MAX_CONCURRENT_DOWNLOADS)

    private val mutex = Mutex()
    private val waiters = ArrayDeque<Waiter>()
    private var activeDownloads = 0
    private var configuredLimit = safeMaxConcurrentDownloads(initialLimit)
    private val initialization =
        CompletableDeferred<Unit>().apply {
            if (initialized) complete(Unit)
        }
    private val conversionMutex = Mutex()
    private var priorityOverrides: Map<String, Long> = emptyMap()

    // This governor is a singleton, so both the scope and its collector remain strongly referenced for app lifetime.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    suspend fun <T> withSlot(
        taskId: String,
        priority: Long,
        @Suppress("UNUSED_PARAMETER") limit: Int,
        whileWaiting: suspend () -> Boolean = { true },
        block: suspend () -> T,
    ): T {
        if (!acquire(taskId, priority, whileWaiting)) {
            throw DownloadAdmissionWithdrawnException()
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    /**
     * Changes the one process-wide download limit.
     *
     * An increase admits queued work immediately. A decrease never cancels work that already owns a slot;
     * queued work resumes only after the active count falls below the new limit.
     */
    suspend fun updateLimit(limit: Int) {
        mutex.withLock {
            configuredLimit = safeDownloadLimit(limit, Runtime.getRuntime().maxMemory())
            admitWaiters()
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

    private fun startLimitCollection(
        limits: Flow<Int>,
        scope: CoroutineScope,
    ) {
        check(collectorJob == null)
        collectorJob =
            scope.launch {
                try {
                    limits.distinctUntilChanged().collect { limit ->
                        updateLimit(limit)
                        initialization.complete(Unit)
                    }
                } catch (failure: Throwable) {
                    if (!initialization.isCompleted) initialization.completeExceptionally(failure)
                } finally {
                    if (!initialization.isCompleted) {
                        initialization.completeExceptionally(
                            CancellationException("Download limit source completed before initialization"),
                        )
                    }
                }
            }
    }

    private suspend fun acquire(
        taskId: String,
        priority: Long,
        whileWaiting: suspend () -> Boolean,
    ): Boolean {
        initialization.await()
        val waiter =
            mutex.withLock {
                if (activeDownloads < configuredLimit) {
                    activeDownloads += 1
                    null
                } else {
                    Waiter(taskId, priority, CompletableDeferred()).also(waiters::addLast)
                }
            }
        if (waiter == null) return true
        try {
            while (true) {
                if (withTimeoutOrNull(CONTROL_POLL_MILLIS) { waiter.signal.await() } != null) return true
                if (!whileWaiting()) {
                    withContext(NonCancellable) {
                        val removed = mutex.withLock { waiters.remove(waiter) }
                        if (!removed) release()
                    }
                    return false
                }
            }
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
            admitWaiters()
        }
    }

    private fun admitWaiters() {
        while (activeDownloads < configuredLimit) {
            val nextIndex =
                waiters.indices.minByOrNull { index ->
                    priorityOverrides[waiters[index].taskId] ?: waiters[index].priority
                } ?: return
            val waiter = waiters.removeAt(nextIndex)
            activeDownloads += 1
            if (!waiter.signal.complete(Unit)) activeDownloads -= 1
        }
    }

    private data class Waiter(
        val taskId: String,
        val priority: Long,
        val signal: CompletableDeferred<Unit>,
    )

    private companion object {
        const val CONTROL_POLL_MILLIS = 250L
    }
}

internal class DownloadAdmissionWithdrawnException : RuntimeException()

internal fun safeDownloadLimit(
    configuredLimit: Int,
    @Suppress("UNUSED_PARAMETER")
    maxHeapBytes: Long,
): Int = safeMaxConcurrentDownloads(configuredLimit)
