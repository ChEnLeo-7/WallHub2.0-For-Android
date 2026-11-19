package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommunityDetailBranchesTest {
    @Test
    fun `fast html waits for json metadata and merges both responses`() = runTest {
        val json = CompletableDeferred<Result<WorkshopDetail>>()
        val htmlDetail = detail(title = "HTML title")
        val jsonDetail = detail(
            title = "JSON title",
            subscriptions = 34_000L,
            updatedAt = 1_712_345_678L,
            fileSize = 165_500_000L,
        )

        val loading = async {
            loadCommunityDetailBranches(
                loadHtml = { Result.success(htmlDetail) },
                loadJson = { json.await() },
            )
        }
        runCurrent()
        assertEquals(false, loading.isCompleted)

        json.complete(Result.success(jsonDetail))
        val merged = loading.await()

        assertEquals("HTML title", merged.summary.title)
        assertEquals(34_000L, merged.subscriptions)
        assertEquals(1_712_345_678L, merged.updatedAt)
        assertEquals(165_500_000L, merged.fileSizeBytes)
    }

    @Test
    fun `one successful detail remains usable when complementary branch fails`() = runTest {
        val htmlDetail = detail(title = "HTML title")

        val loaded =
            loadCommunityDetailBranches(
                loadHtml = { Result.success(htmlDetail) },
                loadJson = { Result.failure(IllegalStateException("JSON unavailable")) },
            )

        assertEquals(htmlDetail, loaded)
    }

    @Test
    fun `both detail branches failing is reported`() = runTest {
        assertFailsWith<java.io.IOException> {
            loadCommunityDetailBranches(
                loadHtml = { Result.failure(IllegalStateException("HTML unavailable")) },
                loadJson = { Result.failure(IllegalStateException("JSON unavailable")) },
            )
        }
    }

    private fun detail(
        title: String,
        subscriptions: Long? = null,
        updatedAt: Long? = null,
        fileSize: Long? = null,
    ): WorkshopDetail =
        WorkshopDetail(
            summary = WorkshopSummary(
                id = 42L,
                title = title,
                author = "Creator",
                subscriptions = subscriptions,
                updatedAt = updatedAt,
                fileSizeBytes = fileSize,
            ),
            subscriptions = subscriptions,
            updatedAt = updatedAt,
            fileSizeBytes = fileSize,
        )
}
