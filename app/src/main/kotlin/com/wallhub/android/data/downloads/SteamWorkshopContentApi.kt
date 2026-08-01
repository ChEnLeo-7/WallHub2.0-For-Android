package com.wallhub.android.data.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class WorkshopContentTarget(
    val publishedFileId: Long,
    val title: String,
    val appId: Int,
    val contentManifestId: Long,
    val expectedSize: Long,
    val contentTypeHint: String?,
) {
    val depotId: Int
        get() = appId
}

internal class SteamWorkshopContentApi(
    clientBuilder: OkHttpClient.Builder,
) {
    private val client =
        clientBuilder
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    suspend fun fetchContentTarget(publishedFileId: Long): WorkshopContentTarget =
        withContext(Dispatchers.IO) {
            require(publishedFileId > 0L) { "Invalid Workshop item ID" }
            val form =
                FormBody
                    .Builder()
                    .add("itemcount", "1")
                    .add("publishedfileids[0]", publishedFileId.toString())
                    .add("includetags", "true")
                    .build()
            val request =
                Request
                    .Builder()
                    .url(PUBLISHED_FILE_DETAILS_URL)
                    .post(form)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
            val body =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Steam public details request failed: HTTP ${response.code}")
                    }
                    response.body.string()
                }
            parseTarget(body, publishedFileId)
        }

    private fun parseTarget(
        body: String,
        publishedFileId: Long,
    ): WorkshopContentTarget {
        val details =
            JSONObject(body)
                .optJSONObject("response")
                ?.optJSONArray("publishedfiledetails")
                ?: error("Steam returned no Workshop details")
        val detail = details.optJSONObject(0) ?: error("Steam returned no Workshop details")
        check(detail.jsonLong("result") == RESULT_OK) {
            "Steam returned an item error: ${detail.jsonLong("result")}"
        }
        val appId =
            detail
                .jsonLong("consumer_app_id")
                .takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?: error("Steam returned no valid Wallpaper Engine App ID")
        val manifestId =
            detail
                .jsonLong("hcontent_file")
                .takeIf { it > 0L }
                ?: error("This Workshop item has no downloadable Steam content manifest")
        return WorkshopContentTarget(
            publishedFileId = publishedFileId,
            title = detail.jsonString("title").ifBlank { "Workshop $publishedFileId" },
            appId = appId,
            contentManifestId = manifestId,
            expectedSize = detail.jsonLong("file_size").coerceAtLeast(0L),
            contentTypeHint =
                detail.jsonTags().let { tags ->
                    when {
                        "video" in tags -> "video"
                        "web" in tags || "website" in tags || "application" in tags -> "web"
                        "scene" in tags -> "scene"
                        else -> null
                    }
                },
        )
    }

    private fun JSONObject.jsonLong(name: String): Long =
        when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun JSONObject.jsonString(name: String): String = opt(name)?.toString()?.trim().orEmpty()

    private fun JSONObject.jsonTags(): Set<String> {
        val values = optJSONArray("tags") ?: return emptySet()
        return buildSet {
            for (index in 0 until values.length()) {
                values
                    .optJSONObject(index)
                    ?.optString("tag")
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }

    private companion object {
        const val RESULT_OK = 1L
        const val USER_AGENT = "WallHub-Android/0.6 (Workshop Downloader)"
        const val PUBLISHED_FILE_DETAILS_URL =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
    }
}
