package com.wallhub.android.data.downloads

import android.util.Log
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
        launch: suspend () -> DownloadHostRunResult,
    ): DownloadHostRunResult

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
    private val active = ConcurrentHashMap<String, Deferred<DownloadHostRunResult>>()
    private val receiptSequences = ConcurrentHashMap<String, Long>()

    override suspend fun reconcile(
        taskId: String,
        launch: suspend () -> DownloadHostRunResult,
    ): DownloadHostRunResult {
        require(taskId.isNotBlank()) { "Download task ID must not be blank" }
        val candidate =
            scope.async(start = CoroutineStart.LAZY) {
                val currentEpoch = epoch.await()
                val before = commandStore?.claimTask(taskId, currentEpoch).orEmpty()
                Log.i(HOST_LOG_TAG, "host reconcile taskId=$taskId epoch=$currentEpoch commands=${before.map { it.revision }}")
                val result = launch()
                if (!result.settlesCommand) return@async result
                // Controls may arrive while the transfer is running. The runner observes the compatibility
                // projection; claiming again binds those late commands to this same settled outcome.
                val commands =
                    (before + commandStore?.claimTask(taskId, currentEpoch).orEmpty())
                        .distinctBy { it.commandId }
                        .sortedBy { it.revision }
                commands.forEach { command ->
                    val sequence = (receiptSequences[taskId] ?: 0L) + 1L
                    val acknowledged =
                        commandStore?.acknowledge(
                            command = command,
                            epoch = currentEpoch,
                            receiptSequence = sequence,
                            result = result.toString(),
                            quiesced = command.command != "RUN",
                        ) ?: true
                    check(acknowledged) { "Command receipt was rejected for taskId=$taskId sequence=$sequence" }
                    receiptSequences[taskId] = sequence
                    Log.i(
                        HOST_LOG_TAG,
                        "host receipt taskId=$taskId epoch=$currentEpoch revision=${command.revision} sequence=$sequence",
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

    private companion object {
        const val HOST_LOG_TAG = "WallHubDownloadHost"
    }
}
