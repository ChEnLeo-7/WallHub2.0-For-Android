package com.wallhub.android.feature.library

/** Small access-ordered cache used for process-local Library query projections. */
internal class BoundedLruCache<K : Any, V : Any>(
    private val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "LRU cache size must be positive" }
    }

    private val entries = LinkedHashMap<K, V>(maxEntries, 0.75f, true)

    operator fun get(key: K): V? = entries[key]

    operator fun set(key: K, value: V) {
        entries[key] = value
        while (entries.size > maxEntries) {
            entries.entries.iterator().run {
                next()
                remove()
            }
        }
    }

    fun remove(key: K): V? = entries.remove(key)

    fun removeWhere(predicate: (K) -> Boolean) {
        entries.keys.removeAll(predicate)
    }

    fun updateWhere(predicate: (K) -> Boolean, transform: (V) -> V) {
        entries.keys.filter(predicate).forEach { key -> entries[key] = transform(requireNotNull(entries[key])) }
    }

    fun clear() = entries.clear()

    internal fun keysInLruOrder(): List<K> = entries.keys.toList()
}
