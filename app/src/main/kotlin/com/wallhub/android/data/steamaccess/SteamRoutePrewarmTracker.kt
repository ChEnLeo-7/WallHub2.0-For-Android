package com.wallhub.android.data.steamaccess

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

internal data class SteamRoutePrewarmKey(
    val networkType: String,
    val generation: Long,
    val hostname: String,
    val port: Int,
)

internal class SteamRoutePrewarmTracker(
    initialGeneration: Long = 0L,
) {
    private val completed = ConcurrentHashMap<SteamRoutePrewarmKey, Boolean>()
    private val changeVersion = MutableStateFlow(0L)
    private var currentGeneration = initialGeneration

    fun result(key: SteamRoutePrewarmKey): Boolean? =
        synchronized(this) {
            if (key.generation != currentGeneration) return@synchronized null
            completed[key]?.also { available ->
                if (!available) completed.remove(key)
            }
        }

    suspend fun awaitCompletionOrInvalidation(
        key: SteamRoutePrewarmKey,
        requestRefresh: () -> Unit,
    ): Boolean? {
        result(key)?.let { return it }
        val observedVersion = changeVersion.value
        requestRefresh()
        result(key)?.let { return it }
        changeVersion.first { version -> version != observedVersion }
        return result(key)
    }

    @Synchronized
    fun complete(
        key: SteamRoutePrewarmKey,
        available: Boolean,
    ) {
        if (key.generation != currentGeneration) return
        completed[key] = available
        changeVersion.value += 1L
    }

    @Synchronized
    fun invalidate(generation: Long) {
        currentGeneration = generation
        completed.clear()
        changeVersion.value += 1L
    }
}
