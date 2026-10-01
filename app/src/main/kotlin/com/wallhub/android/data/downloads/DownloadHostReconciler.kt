package com.wallhub.android.data.downloads

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Process-local edge notification layered over the durable command table.
 *
 * The value is only a wake hint: correctness remains in Room and every signal is also backed by a WorkManager
 * request. A generation rather than a one-shot event closes the observe/query race without polling.
 */
internal interface DownloadHostWakeSignal {
    val generation: Long

    fun signal()

    suspend fun awaitAfter(generation: Long): Long
}

@Singleton
internal class ProcessDownloadHostWakeSignal @Inject constructor() : DownloadHostWakeSignal {
    private val generations = MutableStateFlow(0L)

    override val generation: Long
        get() = generations.value

    override fun signal() {
        generations.update { generation -> Math.addExact(generation, 1L) }
    }

    override suspend fun awaitAfter(generation: Long): Long = generations.first { it != generation }
}

/**
 * Keeps one OS-granted execution window responsive to durable commands accepted while another task is running.
 * It deduplicates active task IDs, sleeps on either task completion or an explicit wake edge, and returns only when
 * the durable queue is observed idle. WorkManager remains responsible for starting and recovering that window.
 */
internal class DownloadHostReconciler(
    private val pendingTaskIds: suspend () -> List<String>,
    private val host: DownloadHostCoordinator,
    private val signal: DownloadHostWakeSignal,
    private val launchTask: suspend (String) -> DownloadHostRunResult,
) {
    suspend fun reconcile(): DownloadHostRunResult = coroutineScope {
        val completions = Channel<Pair<String, DownloadHostRunResult>>(Channel.UNLIMITED)
        val active = mutableSetOf<String>()
        var observedGeneration = signal.generation
        var retryRequested = false

        while (true) {
            if (!retryRequested) {
                pendingTaskIds().distinct().filterNot(active::contains).forEach { taskId ->
                    active += taskId
                    launch {
                        val result = host.reconcile(taskId) { launchTask(taskId) }
                        completions.send(taskId to result)
                    }
                }
            }

            if (active.isEmpty()) {
                if (retryRequested) return@coroutineScope DownloadHostRunResult.RETRY
                val afterQuery = signal.generation
                if (afterQuery == observedGeneration) return@coroutineScope DownloadHostRunResult.SUCCESS
                observedGeneration = afterQuery
                continue
            }

            if (retryRequested) {
                val (taskId, _) = completions.receive()
                active -= taskId
                continue
            }

            val wake = async(start = CoroutineStart.UNDISPATCHED) { signal.awaitAfter(observedGeneration) }
            select<Unit> {
                completions.onReceive { (taskId, result) ->
                    active -= taskId
                    retryRequested = result == DownloadHostRunResult.RETRY
                }
                wake.onAwait { generation -> observedGeneration = generation }
            }
            if (wake.isActive) wake.cancelAndJoin()
        }
        @Suppress("UNREACHABLE_CODE")
        DownloadHostRunResult.SUCCESS
    }
}
