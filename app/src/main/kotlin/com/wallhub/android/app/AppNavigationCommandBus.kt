package com.wallhub.android.app

import android.content.Intent
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One external navigation command, retained only until the active app shell consumes it. */
internal data class AppNavigationCommand(
    val id: Long,
    val target: Target,
) {
    sealed interface Target {
        data object Downloads : Target

        data class Workshop(
            val workshopId: Long,
        ) : Target
    }
}

internal fun parseAppNavigationTarget(uri: String?): AppNavigationCommand.Target? {
    val parsed = uri?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (!parsed.scheme.equals("wallhub", ignoreCase = true)) return null
    return when (parsed.host?.lowercase()) {
        "downloads" -> AppNavigationCommand.Target.Downloads
        "workshop" -> {
            val workshopId = parsed.path.orEmpty().trim('/').substringBefore('/').toLongOrNull()
            workshopId?.takeIf { it > 0L }?.let(AppNavigationCommand.Target::Workshop)
        }
        else -> null
    }
}

/** Process-local navigation command seam for cold and warm external intents. */
internal object AppNavigationCommandBus {
    private val sequence = AtomicLong()
    private val mutableCommand = MutableStateFlow<AppNavigationCommand?>(null)
    val command = mutableCommand.asStateFlow()

    fun accept(intent: Intent?) {
        accept(intent?.dataString)
    }

    internal fun accept(uri: String?) {
        val target = parseAppNavigationTarget(uri) ?: return
        mutableCommand.value = AppNavigationCommand(sequence.incrementAndGet(), target)
    }

    fun consume(id: Long) {
        mutableCommand.compareAndSet(mutableCommand.value?.takeIf { it.id == id }, null)
    }

    internal fun clearForTest() {
        mutableCommand.value = null
    }
}
