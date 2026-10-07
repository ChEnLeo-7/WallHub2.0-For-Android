package com.wallhub.android.data.steam

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Broad operation families used for scheduling and sanitized timing data. */
internal enum class WorkshopReadKind {
    SEARCH,
    DETAILS,
    COMMENTS,
    ACCOUNT_COLLECTION,
    PROFILE,
    PLAYTIME,
    INTERACTION,
    SET_SUBSCRIBED,
    SET_FAVORITED,
    POST_COMMENT,
}

/** User-visible work is admitted before queued speculative or refresh work. */
internal enum class WorkshopReadPriority {
    FOREGROUND,
    BACKGROUND,
}

internal enum class WorkshopReadOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/**
 * Non-sensitive timing data. It deliberately contains no request key, account, query, item ID, or error text.
 */
internal data class WorkshopReadTiming(
    val kind: WorkshopReadKind,
    val priority: WorkshopReadPriority,
    val outcome: WorkshopReadOutcome,
    val queueNanos: Long,
    val runNanos: Long,
) {
    val queueMillis: Long
        get() = TimeUnit.NANOSECONDS.toMillis(queueNanos)

    val runMillis: Long
        get() = TimeUnit.NANOSECONDS.toMillis(runNanos)
}

/**
 * Bounds access to the session-backed Workshop engine behind one small read seam.
 *
 * Reads with the same [kind] and [key] share one execution. The key stays internal and is never included in
 * timing data. Cancelling one caller only stops shared work when no caller remains. Queued foreground reads
 * are admitted before queued background reads; reads already running are never preempted.
 *
 * Callers must use a key whose result type and meaning are stable for the operation's lifetime.
 */
internal class SteamWorkshopReadCoordinator(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT_READS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val onTiming: (WorkshopReadTiming) -> Unit = {},
) {
    init {
        require(maxConcurrent > 0) { "Workshop read concurrency must be positive" }
    }

    private val mutex = Mutex()
    private val flights = mutableMapOf<FlightKey, Flight>()
    private val queue = mutableListOf<Flight>()
    private val retiringKeys = mutableMapOf<FlightKey, CompletableDeferred<Unit>>()
    private var activeCount = 0
    private var nextSequence = 0L

    suspend fun <T> read(
        key: String,
        kind: WorkshopReadKind,
        priority: WorkshopReadPriority,
        operation: suspend () -> T,
    ): T {
        require(key.isNotBlank()) { "Workshop read key must not be blank" }
        currentCoroutineContext().ensureActive()

        val flightKey = FlightKey(kind, key)
        val (flight, jobsToStart) = acquireFlight(flightKey, kind, priority, operation)
        jobsToStart.forEach(Job::start)

        return try {
            @Suppress("UNCHECKED_CAST")
            flight.result.await() as T
        } finally {
            withContext(NonCancellable) { releaseCaller(flight) }
        }
    }

    private suspend fun <T> acquireFlight(
        key: FlightKey,
        kind: WorkshopReadKind,
        priority: WorkshopReadPriority,
        operation: suspend () -> T,
    ): Pair<Flight, List<Job>> {
        while (true) {
            currentCoroutineContext().ensureActive()
            var retiring: CompletableDeferred<Unit>? = null
            val admission =
                mutex.withLock {
                    retiring = retiringKeys[key]
                    if (retiring != null) {
                        null
                    } else {
                        val selected =
                            flights[key]?.also { existing ->
                                existing.callers += 1
                                if (priority == WorkshopReadPriority.FOREGROUND) {
                                    existing.priority = WorkshopReadPriority.FOREGROUND
                                }
                            } ?: Flight(
                                key = key,
                                kind = kind,
                                priority = priority,
                                sequence = nextSequence++,
                                queuedAtNanos = monotonicNanos(),
                                operation = operation,
                            ).also { created ->
                                flights[key] = created
                                queue += created
                            }
                        selected to prepareAdmissionsLocked()
                    }
                }
            if (admission != null) return admission
            checkNotNull(retiring).await()
        }
    }

    private suspend fun releaseCaller(flight: Flight) {
        var cancelJob: Job? = null
        mutex.withLock {
            flight.callers -= 1
            check(flight.callers >= 0) { "Workshop read caller count became negative" }
            if (flight.callers == 0 && !flight.result.isCompleted) {
                if (queue.remove(flight)) {
                    flights.remove(flight.key, flight)
                    flight.result.cancel()
                } else {
                    retiringKeys[flight.key] = CompletableDeferred()
                    cancelJob = flight.job
                }
            }
        }
        cancelJob?.cancel()
    }

    private fun prepareAdmissionsLocked(): List<Job> {
        val jobs = mutableListOf<Job>()
        while (activeCount < maxConcurrent && queue.isNotEmpty()) {
            val next =
                queue.minWithOrNull(
                    compareBy<Flight>({ it.priority.ordinal }, { it.sequence }),
                ) ?: break
            queue.remove(next)
            activeCount += 1
            next.job =
                scope.launch(start = CoroutineStart.LAZY) {
                    execute(next)
                }.also { job ->
                    job.invokeOnCompletion {
                        if (next.finished.compareAndSet(false, true)) {
                            scope.launch(NonCancellable) {
                                next.result.cancel()
                                finish(next)
                            }
                        }
                    }
                }
            jobs += checkNotNull(next.job)
        }
        return jobs
    }

    private suspend fun execute(flight: Flight) {
        val startedAt = monotonicNanos()
        var outcome = WorkshopReadOutcome.SUCCEEDED
        var value: Any? = null
        var failure: Throwable? = null
        try {
            value = flight.operation()
        } catch (caught: Throwable) {
            failure = caught
            outcome =
                if (caught is kotlinx.coroutines.CancellationException) {
                    WorkshopReadOutcome.CANCELLED
                } else {
                    WorkshopReadOutcome.FAILED
                }
        } finally {
            val finishedAt = monotonicNanos()
            runCatching {
                onTiming(
                    WorkshopReadTiming(
                        kind = flight.kind,
                        priority = flight.priority,
                        outcome = outcome,
                        queueNanos = (startedAt - flight.queuedAtNanos).coerceAtLeast(0L),
                        runNanos = (finishedAt - startedAt).coerceAtLeast(0L),
                    ),
                )
            }
            withContext(NonCancellable) {
                if (flight.finished.compareAndSet(false, true)) {
                    finish(flight)
                }
            }
        }
        failure?.let(flight.result::completeExceptionally) ?: flight.result.complete(value)
    }

    private suspend fun finish(flight: Flight) {
        var retired: CompletableDeferred<Unit>? = null
        val jobsToStart =
            mutex.withLock {
                activeCount -= 1
                flights.remove(flight.key, flight)
                retired = retiringKeys.remove(flight.key)
                prepareAdmissionsLocked()
            }
        retired?.complete(Unit)
        jobsToStart.forEach(Job::start)
    }

    private data class FlightKey(
        val kind: WorkshopReadKind,
        val value: String,
    )

    private class Flight(
        val key: FlightKey,
        val kind: WorkshopReadKind,
        var priority: WorkshopReadPriority,
        val sequence: Long,
        val queuedAtNanos: Long,
        val operation: suspend () -> Any?,
    ) {
        var callers: Int = 1
        val result = CompletableDeferred<Any?>()
        val finished = AtomicBoolean()
        var job: Job? = null
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_READS = 2
    }
}
