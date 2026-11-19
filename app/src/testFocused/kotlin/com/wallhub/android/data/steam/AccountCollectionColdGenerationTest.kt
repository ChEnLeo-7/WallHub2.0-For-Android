package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import org.junit.Test
import kotlin.test.assertNotEquals

class AccountCollectionColdGenerationTest {
    @Test
    fun `count reads use a key outside every full list cold generation`() {
        val count =
            accountCollectionCountReadKey(
                accountId = 42L,
                sessionGeneration = 7L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
            )
        val list =
            accountCollectionReadKey(
                accountId = 42L,
                sessionGeneration = 7L,
                collectionGeneration = 1L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                sourcePage = 1,
                sourcePageSize = 1,
            )

        assertNotEquals(count, list)
    }

    @Test
    fun `cold generations cannot coalesce the same collection page flight`() {
        val first =
            accountCollectionReadKey(
                accountId = 42L,
                sessionGeneration = 7L,
                collectionGeneration = 1L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                sourcePage = 1,
                sourcePageSize = 16,
            )
        val second =
            accountCollectionReadKey(
                accountId = 42L,
                sessionGeneration = 7L,
                collectionGeneration = 2L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                sourcePage = 1,
                sourcePageSize = 16,
            )

        assertNotEquals(first, second)
    }
}
