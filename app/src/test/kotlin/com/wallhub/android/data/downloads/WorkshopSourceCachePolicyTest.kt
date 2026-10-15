package com.wallhub.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkshopSourceCachePolicyTest {
    @Test
    fun `budget is the minimum of user choice five GiB and ten percent usable space`() {
        val gib = 1024L * 1024L * 1024L

        assertEquals(2L * gib, WorkshopSourceCachePolicy.effectiveBudget(true, 8L * gib, 20L * gib))
        assertEquals(3L * gib, WorkshopSourceCachePolicy.effectiveBudget(true, 3L * gib, 100L * gib))
        assertEquals(5L * gib, WorkshopSourceCachePolicy.effectiveBudget(true, 8L * gib, 100L * gib))
        assertEquals(0L, WorkshopSourceCachePolicy.effectiveBudget(false, 8L * gib, 100L * gib))
    }

    @Test
    fun `eviction is deterministic LRU and never selects an active task`() {
        val entries =
            listOf(
                entry("new", bytes = 40, accessed = 30),
                entry("active-old", bytes = 80, accessed = 1, active = true),
                entry("old-b", bytes = 30, accessed = 10),
                entry("old-a", bytes = 30, accessed = 10),
            )

        val decision = WorkshopSourceCachePolicy.decide(entries, enabled = true, userLimitBytes = 120, usableSpaceBytes = 1_200)

        assertEquals(120, decision.budgetBytes)
        assertEquals(180, decision.retainedBytesBefore)
        assertEquals(listOf("old-a", "old-b"), decision.evictTaskIds)
        assertEquals(120, decision.retainedBytesAfter)
    }

    @Test
    fun `active bytes may exceed budget without evicting active source`() {
        val decision =
            WorkshopSourceCachePolicy.decide(
                listOf(entry("active", bytes = 200, accessed = 1, active = true)),
                enabled = false,
                userLimitBytes = 0,
                usableSpaceBytes = 0,
            )

        assertEquals(emptyList<String>(), decision.evictTaskIds)
        assertEquals(200, decision.retainedBytesAfter)
    }

    @Test
    fun `retained byte overflow is rejected`() {
        assertThrows(ArithmeticException::class.java) {
            WorkshopSourceCachePolicy.decide(
                listOf(entry("a", Long.MAX_VALUE, 1), entry("b", 1, 2)),
                enabled = true,
                userLimitBytes = Long.MAX_VALUE,
                usableSpaceBytes = Long.MAX_VALUE,
            )
        }
    }

    private fun entry(
        id: String,
        bytes: Long,
        accessed: Long,
        active: Boolean = false,
    ) = WorkshopSourceCacheEntry(id, bytes, accessed, active)
}
