package com.wallhub.android.data.steam

import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bounded read-through cache that can preserve stale data when a Steam refresh fails. */
internal class SteamReadCache<K : Any, V : Any>(
    private val maxEntries: Int = 32,
    private val freshMillis: Long,
    private val staleMillis: Long,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0)
        require(freshMillis > 0)
        require(staleMillis >= freshMillis)
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)

    suspend fun getFresh(key: K): V? = mutex.withLock {
        val entry = entries[key] ?: return@withLock null
        entry.value.takeIf { clockMillis() - entry.writtenAtMillis in 0 until freshMillis }
    }

    suspend fun getStale(key: K): V? = mutex.withLock {
        val entry = entries[key] ?: return@withLock null
        val age = clockMillis() - entry.writtenAtMillis
        if (age !in 0 until staleMillis) {
            entries.remove(key)
            null
        } else {
            entry.value
        }
    }

    suspend fun put(key: K, value: V) = mutex.withLock {
        entries[key] = Entry(value, clockMillis())
        while (entries.size > maxEntries) entries.entries.iterator().run { next(); remove() }
    }

    /** Patches matching cached projections after a confirmed account mutation. */
    suspend fun updateWhere(
        predicate: (K) -> Boolean,
        transform: (K, V) -> V,
    ) = mutex.withLock {
        entries.entries
            .filter { predicate(it.key) }
            .forEach { (key, entry) -> entries[key] = Entry(transform(key, entry.value), clockMillis()) }
    }

    suspend fun removeWhere(predicate: (K) -> Boolean) = mutex.withLock {
        entries.keys.removeAll(predicate)
    }

    suspend fun invalidateAll() = mutex.withLock { entries.clear() }

    private data class Entry<V>(val value: V, val writtenAtMillis: Long)
}
