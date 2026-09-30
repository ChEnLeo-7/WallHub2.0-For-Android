package com.wallhub.android.data.downloads

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide owner for one live execution of a persisted download task.
 *
 * WorkManager owns the execution window. Concurrent wake-ups for the same durable task join the one run created by
 * the winning worker; cancelling a joiner cannot cancel that run, while stopping the owning worker does cancel its
 * transfer so the runner can project an interruption and return the command to reconciliation.
 */
internal interface DownloadHostCoordinator {
    suspend fun reconcile(
        taskId: String,
        launch: suspend () -> DownloadHostRunResult,
    ): DownloadHostRunResult
}

@Singleton
internal class SingleProcessDownloadHostCoordinator internal constructor(
    private val commandStore: DownloadCommandStore?,
    @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
) : DownloadHostCoordinator {
    @Inject
    constructor(commandStore: DownloadCommandStore) : this(commandStore, false)

    private var epoch: Long? = null
    private val epochMutex = Mutex()
    private val active = ConcurrentHashMap<String, Deferred<DownloadHostRunResult>>()

    override suspend fun reconcile(
        taskId: String,
        launch: suspend () -> DownloadHostRunResult,
    ): DownloadHostRunResult = coroutineScope {
        require(taskId.isNotBlank()) { "Download task ID must not be blank" }
        val candidate =
            async(start = CoroutineStart.LAZY) {
                val currentEpoch = currentEpoch()
                val before = commandStore?.claimTask(taskId, currentEpoch).orEmpty()
                Log.i(HOST_LOG_TAG, "host reconcile taskId=$taskId epoch=$currentEpoch commands=${before.map { it.revision }}")
                val result = launch()
                if (!result.settlesCommand) return@async result
                // Only commands claimed before this run are represented by its result. Commands accepted while
                // it was running stay pending for the successor wake; acknowledging opposing late intent with
                // this result would violate revision semantics (for example PAUSE followed by RUN).
                var sequence = commandStore?.receiptSequenceBase(taskId, currentEpoch) ?: 0L
                before.sortedBy { it.revision }.forEach { command ->
                    sequence = Math.addExact(sequence, 1L)
                    val acknowledged =
                        commandStore?.acknowledge(
                            command = command,
                            epoch = currentEpoch,
                            receiptSequence = sequence,
                            result = result.toString(),
                            quiesced = command.command != "RUN",
                        ) ?: true
                    check(acknowledged) { "Command receipt was rejected for taskId=$taskId sequence=$sequence" }
                    Log.i(
                        HOST_LOG_TAG,
                        "host receipt taskId=$taskId epoch=$currentEpoch revision=${command.revision} sequence=$sequence",
                    )
                }
                result
            }
        val owned = active.putIfAbsent(taskId, candidate) ?: candidate
        if (owned === candidate) {
            candidate.invokeOnCompletion { active.remove(taskId, candidate) }
            candidate.start()
        } else {
            candidate.cancel()
        }
        owned.await()
    }

    private suspend fun currentEpoch(): Long =
        epoch ?: epochMutex.withLock {
            epoch ?: (commandStore?.acquireEngineEpoch() ?: 0L).also { acquired -> epoch = acquired }
        }

    private companion object {
        const val HOST_LOG_TAG = "WallHubDownloadHost"
    }
}
