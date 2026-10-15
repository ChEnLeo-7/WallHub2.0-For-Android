package com.wallhub.android.data.downloads

internal data class WorkshopSourceCacheEntry(
    val taskId: String,
    val retainedBytes: Long,
    val lastAccessedAt: Long,
    val active: Boolean,
) {
    init {
        require(taskId.isNotBlank()) { "Source-cache task ID is blank" }
        require(retainedBytes >= 0L) { "Source-cache size is negative" }
        require(lastAccessedAt >= 0L) { "Source-cache access time is negative" }
    }
}

internal data class WorkshopSourceCacheDecision(
    val budgetBytes: Long,
    val retainedBytesBefore: Long,
    val retainedBytesAfter: Long,
    val evictTaskIds: List<String>,
)

/** Pure policy behind the private Workshop source-cache seam. File and Room adapters apply this decision. */
internal object WorkshopSourceCachePolicy {
    const val DEFAULT_USER_LIMIT_BYTES: Long = 5L * 1024L * 1024L * 1024L

    fun effectiveBudget(
        enabled: Boolean,
        userLimitBytes: Long,
        usableSpaceBytes: Long,
    ): Long {
        require(userLimitBytes >= 0L) { "Source-cache user limit is negative" }
        require(usableSpaceBytes >= 0L) { "Usable space is negative" }
        if (!enabled) return 0L
        return minOf(userLimitBytes, DEFAULT_USER_LIMIT_BYTES, usableSpaceBytes / 10L)
    }

    fun decide(
        entries: List<WorkshopSourceCacheEntry>,
        enabled: Boolean,
        userLimitBytes: Long,
        usableSpaceBytes: Long,
    ): WorkshopSourceCacheDecision {
        val budget = effectiveBudget(enabled, userLimitBytes, usableSpaceBytes)
        val before = entries.fold(0L) { total, entry -> Math.addExact(total, entry.retainedBytes) }
        var retained = before
        val evictions = mutableListOf<String>()
        entries
            .asSequence()
            .filterNot(WorkshopSourceCacheEntry::active)
            .sortedWith(compareBy<WorkshopSourceCacheEntry> { it.lastAccessedAt }.thenBy { it.taskId })
            .forEach { entry ->
                if (retained > budget) {
                    evictions += entry.taskId
                    retained -= entry.retainedBytes
                }
            }
        return WorkshopSourceCacheDecision(
            budgetBytes = budget,
            retainedBytesBefore = before,
            retainedBytesAfter = retained,
            evictTaskIds = evictions,
        )
    }
}
