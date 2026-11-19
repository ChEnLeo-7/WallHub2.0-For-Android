package com.wallhub.android.data.steam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import io.steamcontent.sdk.WorkshopPersonalList
import io.steamcontent.sdk.WorkshopPersonalSort
import io.steamcontent.sdk.WorkshopTextField
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySortLiveTest {
    @Test
    fun serverSortsPersonalCollectionPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = EntryPointAccessors.fromApplication(context, LibrarySortProbeEntryPoint::class.java)
        val snapshot = requireNotNull(entry.sessions().awaitCurrentUsable(timeoutMs = 75_000L))
        val accountId = requireNotNull(entry.collections().activeAccountId())
        val output = File(context.filesDir, "library-sort-live.txt")
        output.writeText("Live read-only personal sorting\n")
        for (list in listOf(WorkshopPersonalList.SUBSCRIBED, WorkshopPersonalList.FAVORITED)) {
            for (sort in listOf(WorkshopPersonalSort.TITLE_ASC, WorkshopPersonalSort.UPDATED_DESC, WorkshopPersonalSort.CREATED_DESC)) {
                val rows = mutableListOf<Pair<Long, String>>()
                for (offset in listOf(0, 3)) {
                    snapshot.engine.workshopPersonalItems(accountId, list, 3, offset, 431960L, sort).use { page ->
                        repeat(page.size()) { index ->
                            val id = page.field(index, 1).value
                            val title = page.text(index, WorkshopTextField.TITLE).orEmpty()
                            rows += id to title
                        }
                    }
                }
                output.appendText("list=$list sort=$sort rows=${rows.size} ids=${rows.map { it.first }} titles=${rows.map { it.second }}\n")
                assertTrue(rows.map { it.first }.distinct().size == rows.size)
                if (sort == WorkshopPersonalSort.TITLE_ASC) {
                    assertTrue("Server titles must ascend: list=$list", rows.zipWithNext().all { (a, b) -> a.second.compareTo(b.second, ignoreCase = true) <= 0 })
                }
            }
        }
        snapshot.engine.workshopPersonalItems(accountId, WorkshopPersonalList.SUBSCRIBED, 6, 0, 431960L, WorkshopPersonalSort.RELATIONSHIP_DATE_DESC).use { page ->
            output.appendText("subscription-date rows=${page.size()}\n")
            assertTrue(page.size() > 0)
        }
    }
}
