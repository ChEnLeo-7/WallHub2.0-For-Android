package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountCollectionRefreshInvalidationTest {
    @Test
    fun `each cold refresh advances only its collection generation`() =
        runTest {
            val cache = SteamWorkshopCacheStore(clockMillis = { 1_000L })
            val accountId = 42L
            val oldEpoch = cache.accountEpoch.get()

            val firstSubscriptions =
                cache.beginAccountCollectionRefresh(accountId, AccountWorkshopCollection.SUBSCRIPTIONS)
            val favorites =
                cache.beginAccountCollectionRefresh(accountId, AccountWorkshopCollection.FAVORITES)
            val secondSubscriptions =
                cache.beginAccountCollectionRefresh(accountId, AccountWorkshopCollection.SUBSCRIPTIONS)

            assertEquals(1L, firstSubscriptions)
            assertEquals(1L, favorites)
            assertEquals(2L, secondSubscriptions)
            assertEquals(
                secondSubscriptions,
                cache.accountCollectionGeneration(accountId, AccountWorkshopCollection.SUBSCRIPTIONS),
            )
            assertTrue(cache.accountEpoch.get() > oldEpoch)
        }
}
