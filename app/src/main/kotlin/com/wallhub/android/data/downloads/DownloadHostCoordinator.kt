package com.wallhub.android.data.downloads

import androidx.work.ListenableWorker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    private val commandStore: DownloadCommandStore? = null,
) : DownloadHostCoordinator {
    @Inject
    constructor(commandStore: DownloadCommandStore) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        commandStore,
    )

    private val epoch = scope.async(start = CoroutineStart.LAZY) {
        commandStore?.acquireEngineEpoch() ?: 0L
    }
    private val active = ConcurrentHashMap<String, Deferred<ListenableWorker.Result>>()
    private val receiptSequence = AtomicLong()

    override suspend fun reconcile(
        taskId: String,
        launch: suspend () -> ListenableWorker.Result,
    ): ListenableWorker.Result {
        require(taskId.isNotBlank()) { "Download task ID must not be blank" }
        val candidate =
            scope.async(start = CoroutineStart.LAZY) {
                val currentEpoch = epoch.await()
                val commands = commandStore?.claimTask(taskId, currentEpoch).orEmpty()
                val result = launch()
                commands.forEach { command ->
                    commandStore?.acknowledge(
                        command = command,
                        epoch = currentEpoch,
                        receiptSequence = receiptSequence.incrementAndGet(),
                        result = result.toString(),
                        quiesced = command.command != "RUN",
                    )
                }
                result
            }
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
