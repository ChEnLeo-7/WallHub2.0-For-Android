package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.WorkshopSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountCollectionMutationPersistenceTest {
    @Test
    fun `failed snapshot patch drops all snapshots for the acting account`() {
        val store = RecordingSnapshotStore(updateSucceeds = false)

        assertTrue(
            persistAccountCollectionMutation(
                snapshots = store,
                accountId = 10L,
                collection = AccountWorkshopCollection.FAVORITES,
                itemId = 20L,
                present = true,
                summary = WorkshopSummary(20L, "item", "author"),
            ),
        )

        assertEquals(listOf(10L), store.removedAccounts)
    }

    private class RecordingSnapshotStore(
        private val updateSucceeds: Boolean,
    ) : AccountWorkshopPageSnapshotStore {
        val removedAccounts = mutableListOf<Long>()

        override fun read(key: AccountWorkshopPageSnapshotKey): AccountWorkshopPageSnapshot? = null

        override fun write(
            key: AccountWorkshopPageSnapshotKey,
            snapshot: AccountWorkshopPageSnapshot,
        ): Boolean = true

        override fun updateCollection(
            accountId: Long,
            collection: AccountWorkshopCollection,
            itemId: Long,
            present: Boolean,
            summary: WorkshopSummary?,
        ): Boolean = updateSucceeds

        override fun removeAccount(accountId: Long): Boolean {
            removedAccounts += accountId
            return true
        }
    }
}
