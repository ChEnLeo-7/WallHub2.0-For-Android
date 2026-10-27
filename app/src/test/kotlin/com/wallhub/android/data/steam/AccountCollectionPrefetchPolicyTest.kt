package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountCollectionPrefetchPolicyTest {
    @Test
    fun `account sign in warms both first pages and both count probes once`() {
        val queries = accountCollectionPrefetchQueries(pageSize = 16)

        assertEquals(4, queries.size)
        AccountWorkshopCollection.entries.forEach { collection ->
            val scoped = queries.filter { it.collection == collection }
            assertEquals(2, scoped.size)
            assertTrue(scoped.all { it.page == 1 })
            assertEquals(setOf(1, 16), scoped.map { it.pageSize }.toSet())
            assertTrue(scoped.single { it.pageSize == 1 }.resolveTotalCount)
            assertTrue(!scoped.single { it.pageSize == 16 }.resolveTotalCount)
        }
    }

    @Test
    fun `count probes remain background while visible pages retain foreground priority`() {
        val queries = accountCollectionPrefetchQueries(pageSize = 16)
        val count = queries.first { it.pageSize == 1 }
        val page = queries.first { it.pageSize == 16 }

        assertEquals(WorkshopReadPriority.BACKGROUND, accountCollectionPriority(count, count.pageSize))
        assertEquals(WorkshopReadPriority.FOREGROUND, accountCollectionPriority(page, page.pageSize))
    }
}
