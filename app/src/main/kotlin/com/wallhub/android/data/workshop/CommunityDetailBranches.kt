package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.WorkshopDetail
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Both responses have complementary fields; a fast HTML response cannot replace JSON metadata. */
internal suspend fun loadCommunityDetailBranches(
    loadHtml: suspend () -> Result<WorkshopDetail>,
    loadJson: suspend () -> Result<WorkshopDetail>,
): WorkshopDetail = coroutineScope {
    val html = async { loadHtml() }
    val json = async { loadJson() }
    val htmlResult = html.await()
    val jsonResult = json.await()
    val htmlDetail = htmlResult.getOrNull()
    val jsonDetail = jsonResult.getOrNull()
    when {
        htmlDetail != null && jsonDetail != null -> mergeCommunityDetail(htmlDetail, jsonDetail)
        jsonDetail != null -> jsonDetail
        htmlDetail != null -> htmlDetail
        else -> throw IOException(
            "Steam Community detail HTML and JSON requests failed",
            htmlResult.exceptionOrNull() ?: jsonResult.exceptionOrNull(),
        )
    }
}
