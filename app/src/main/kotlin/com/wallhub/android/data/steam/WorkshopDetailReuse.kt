package com.wallhub.android.data.steam

import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopInteraction
import kotlinx.coroutines.CancellationException

/**
 * Holds the interaction fields read with the detail body until the detail screen asks for them.
 *
 * Entries are scoped to one SDK session generation and consumed once, so a later refresh still performs an
 * authoritative read while the immediate detail/interaction pair does not resolve the same item twice.
 */
internal class WorkshopDetailInteractionCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val freshMillis: Long = FRESH_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0) { "Workshop detail interaction cache size must be positive" }
        require(freshMillis > 0L) { "Workshop detail interaction freshness must be positive" }
    }

    private val entries = LinkedHashMap<Long, Entry>(maxEntries, 0.75f, true)

    @Synchronized
    fun remember(
        workshopId: Long,
        sessionGeneration: Long,
        interaction: WorkshopInteraction,
    ) {
        entries[workshopId] = Entry(sessionGeneration, interaction, clockMillis())
        while (entries.size > maxEntries) {
            entries.entries.iterator().run {
                next()
                remove()
            }
        }
    }

    @Synchronized
    fun take(
        workshopId: Long,
        sessionGeneration: Long,
    ): WorkshopInteraction? {
        val entry = entries.remove(workshopId) ?: return null
        val age = (clockMillis() - entry.loadedAtMillis).coerceAtLeast(0L)
        return entry.interaction.takeIf {
            entry.sessionGeneration == sessionGeneration && age < freshMillis
        }
    }

    @Synchronized
    fun invalidateAll() {
        entries.clear()
    }

    private data class Entry(
        val sessionGeneration: Long,
        val interaction: WorkshopInteraction,
        val loadedAtMillis: Long,
    )

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        const val FRESH_MILLIS = 10_000L
    }
}

/** Keeps the already-read comment page when the optional player-persona plane fails. */
internal inline fun WorkshopCommentPage.withOptionalPersonaEnrichment(
    enrich: (WorkshopCommentPage) -> WorkshopCommentPage,
): WorkshopCommentPage =
    try {
        enrich(this)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        this
    }
