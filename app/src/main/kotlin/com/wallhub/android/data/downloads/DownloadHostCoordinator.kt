package com.wallhub.android.data.downloads

import androidx.work.ListenableWorker
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Process-wide owner for one live execution of a persisted download task.
 *
 * WorkManager, UI and future OS adapters are wake-up sources, not execution owners. Concurrent wake-ups for the
 * same durable task join one coordinator-owned run; a finished run is removed so reconciliation may launch it
 * again after process restart, retry or another durable command.
 */
internal interface DownloadHostCoordinator {
    suspend fun reconcile(
        taskId: String,
        launch: suspend () -> ListenableWorker.Result,
    ): ListenableWorker.Result

    fun isActive(taskId: String): Boolean
}

@Singleton
internal class SingleProcessDownloadHostCoordinator internal constructor(
    private val scope: CoroutineScope,
) : DownloadHostCoordinator {
    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val active = ConcurrentHashMap<String, Deferred<ListenableWorker.Result>>()

    override suspend fun reconcile(
        taskId: String,
        launch: suspend () -> ListenableWorker.Result,
    ): ListenableWorker.Result {
        require(taskId.isNotBlank()) { "Download task ID must not be blank" }
        val candidate = scope.async(start = CoroutineStart.LAZY) { launch() }
        val owned = active.putIfAbsent(taskId, candidate) ?: candidate
        if (owned === candidate) candidate.start() else candidate.cancel()
        return try {
            owned.await()
        } finally {
            active.remove(taskId, owned)
        }
    }

    override fun isActive(taskId: String): Boolean = active.containsKey(taskId)
}
