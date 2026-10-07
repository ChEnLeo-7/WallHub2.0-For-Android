package com.wallhub.android.data.steam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun interface SteamPersonaLoader {
    suspend fun load(accountIds: Set<Long>): Map<Long, SteamProfile>
}

/**
 * A bounded, process-local persona cache with one shared load for each account currently being resolved.
 *
 * Present profiles remain fresh longer than misses. Concurrent batches share overlapping account IDs without
 * coupling the lifetime of one caller to another: cancelling a waiter only cancels loader work once no waiter
 * remains for any account in that loader batch.
 */
internal class SteamPersonaCache(
    private val loader: SteamPersonaLoader,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val positiveTtlMillis: Long = POSITIVE_TTL_MILLIS,
    private val negativeTtlMillis: Long = NEGATIVE_TTL_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    init {
        require(maxEntries > 0) { "Steam persona cache size must be positive" }
        require(positiveTtlMillis > 0) { "Steam persona positive TTL must be positive" }
        require(negativeTtlMillis > 0) { "Steam persona negative TTL must be positive" }
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Long, CacheEntry>(maxEntries, 0.75f, true)
    private val flights = mutableMapOf<Long, Flight>()

    suspend fun get(accountId: Long): SteamProfile? = getAll(listOf(accountId))[accountId]

    suspend fun getAll(accountIds: Collection<Long>): Map<Long, SteamProfile> {
        val requestedIds = accountIds.toSet()
        if (requestedIds.isEmpty()) return emptyMap()

        val plan =
            mutex.withLock {
                val now = clockMillis()
                val cached = linkedMapOf<Long, SteamProfile>()
                val awaited = linkedMapOf<Long, Flight>()
                val missing = linkedSetOf<Long>()

                requestedIds.forEach { accountId ->
                    val entry = entries[accountId]
                    if (entry != null && entry.isFreshAt(now)) {
                        entry.profile?.let { cached[accountId] = it }
                    } else {
                        if (entry != null) entries.remove(accountId)
                        val flight = flights[accountId]
                        if (flight == null) {
                            missing += accountId
                        } else {
                            flight.waiters += 1
                            awaited[accountId] = flight
                        }
                    }
                }

                if (missing.isNotEmpty()) {
                    val flight = Flight(accountIds = missing, waiters = missing.size)
                    missing.forEach { accountId ->
                        flights[accountId] = flight
                        awaited[accountId] = flight
                    }
                    flight.job =
                        scope.launch(start = CoroutineStart.LAZY) {
                            execute(flight)
                        }
                }
                LoadPlan(cached = cached, awaited = awaited)
            }

        plan.awaited.values.mapNotNull(Flight::job).distinct().forEach(Job::start)
        return try {
            buildMap {
                putAll(plan.cached)
                plan.awaited.forEach { (accountId, flight) ->
                    flight.result.await()[accountId]?.let { put(accountId, it) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                releaseWaiters(plan.awaited)
            }
        }
    }

    private suspend fun execute(flight: Flight) {
        try {
            val loaded = loader.load(flight.accountIds).filterKeys(flight.accountIds::contains)
            val loadedAt = clockMillis()
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.accountIds.forEach { accountId ->
                        val profile = loaded[accountId]
                        putEntry(
                            accountId,
                            CacheEntry(
                                profile = profile,
                                expiresAtMillis =
                                    loadedAt.saturatingAdd(
                                        if (profile == null) negativeTtlMillis else positiveTtlMillis,
                                    ),
                            ),
                        )
                        flights.remove(accountId, flight)
                    }
                }
            }
            flight.result.complete(loaded)
        } catch (throwable: Throwable) {
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.accountIds.forEach { accountId -> flights.remove(accountId, flight) }
                }
            }
            flight.result.completeExceptionally(throwable)
        }
    }

    private suspend fun releaseWaiters(awaited: Map<Long, Flight>) {
        val jobsToCancel = mutableSetOf<Job>()
        mutex.withLock {
            awaited.values.groupingBy { it }.eachCount().forEach { (flight, releasedCount) ->
                flight.waiters -= releasedCount
                check(flight.waiters >= 0) { "Steam persona waiter count became negative" }
                if (flight.waiters == 0 && !flight.result.isCompleted) {
                    flight.accountIds.forEach { accountId -> flights.remove(accountId, flight) }
                    flight.job?.let(jobsToCancel::add)
                }
            }
        }
        jobsToCancel.forEach(Job::cancel)
    }

    private fun putEntry(
        accountId: Long,
        entry: CacheEntry,
    ) {
        entries[accountId] = entry
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    private data class LoadPlan(
        val cached: Map<Long, SteamProfile>,
        val awaited: Map<Long, Flight>,
    )

    private data class CacheEntry(
        val profile: SteamProfile?,
        val expiresAtMillis: Long,
    ) {
        fun isFreshAt(nowMillis: Long): Boolean = nowMillis < expiresAtMillis
    }

    private class Flight(
        val accountIds: Set<Long>,
        var waiters: Int,
        val result: CompletableDeferred<Map<Long, SteamProfile>> = CompletableDeferred(),
        var job: Job? = null,
    )

    private fun Long.saturatingAdd(delta: Long): Long =
        if (this > Long.MAX_VALUE - delta) Long.MAX_VALUE else this + delta

    internal companion object {
        const val POSITIVE_TTL_MILLIS = 20L * 60L * 1_000L
        const val NEGATIVE_TTL_MILLIS = 90L * 1_000L
        const val DEFAULT_MAX_ENTRIES = 512
    }
}
