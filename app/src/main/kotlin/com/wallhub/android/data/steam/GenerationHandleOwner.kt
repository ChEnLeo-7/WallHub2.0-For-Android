package com.wallhub.android.data.steam

import java.io.Closeable

/**
 * Owns one replaceable native handle without letting an older wrapper close a newer generation.
 *
 * Every [Handle] closes the exact value it was issued for. Closing the current handle also clears the
 * owner's current slot; closing an obsolete handle cannot mutate that slot. This is deliberately small:
 * the SDK engine remains process-wide, while session wrappers get precise, idempotent ownership.
 */
internal class GenerationHandleOwner<T : Closeable> {
    private var nextGeneration = 0L
    private var current: Handle<T>? = null

    @Synchronized
    fun replace(value: T): Handle<T> {
        val previous = current
        val opened = Handle(this, ++nextGeneration, value)
        current = opened
        previous?.close()
        return opened
    }

    @Synchronized
    fun currentValue(): T? = currentHandle()?.value

    @Synchronized
    fun currentHandle(): Handle<T>? = current?.takeUnless { it.isClosed }

    @Synchronized
    fun closeCurrent() {
        current?.close()
    }

    @Synchronized
    private fun close(handle: Handle<T>) {
        if (!handle.markClosed()) return
        if (current === handle) current = null
        runCatching { handle.value.close() }
    }

    internal class Handle<T : Closeable> internal constructor(
        private val owner: GenerationHandleOwner<T>,
        internal val generation: Long,
        internal val value: T,
    ) : Closeable {
        @Volatile
        internal var isClosed: Boolean = false
            private set

        @Synchronized
        internal fun markClosed(): Boolean {
            if (isClosed) return false
            isClosed = true
            return true
        }

        override fun close() = owner.close(this)
    }
}
