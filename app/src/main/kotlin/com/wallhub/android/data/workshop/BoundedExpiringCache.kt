package com.wallhub.android.data.workshop

internal sealed interface CacheLookup<out V> {
    data object Missing : CacheLookup<Nothing>
    data object Negative : CacheLookup<Nothing>
    data class Present<V>(val value: V) : CacheLookup<V>
}

/** Thread-safe access-ordered cache with separate positive and negative lifetimes. */
internal class BoundedExpiringCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val positiveTtlMs: Long,
    private val negativeTtlMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0)
        require(positiveTtlMs > 0L)
        require(negativeTtlMs > 0L)
    }

    private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(key: K): CacheLookup<V> {
        val entry = entries[key] ?: return CacheLookup.Missing
        if (nowMs() >= entry.expiresAtMs) {
            entries.remove(key)
            return CacheLookup.Missing
        }
        return entry.value?.let { CacheLookup.Present(it) } ?: CacheLookup.Negative
    }

    @Synchronized
    fun put(key: K, value: V?) {
        entries[key] =
            Entry(
                value = value,
                expiresAtMs = nowMs().saturatingAdd(if (value == null) negativeTtlMs else positiveTtlMs),
            )
        while (entries.size > maxEntries) {
            entries.entries.iterator().run {
                next()
                remove()
            }
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    internal fun size(): Int = synchronized(this) { entries.size }

    private data class Entry<V>(val value: V?, val expiresAtMs: Long)

    private fun Long.saturatingAdd(delta: Long): Long =
        if (this > Long.MAX_VALUE - delta) Long.MAX_VALUE else this + delta
}
