package com.wallhub.android.data.discover

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wallhub.android.core.model.DiscoverFeedbackRepository
import com.wallhub.android.core.model.DiscoverFeedbackWeight
import com.wallhub.android.core.model.DiscoverRailFeedback
import com.wallhub.android.core.model.DiscoverSavedQuery
import com.wallhub.android.core.model.DiscoverSavedQueryCategory
import com.wallhub.android.core.model.DiscoverSavedQueryRepository
import com.wallhub.android.core.model.DiscoverSavedQuerySource
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.toDiscoverFeedbackWeight
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private const val DISCOVER_FEEDBACK_FILE_NAME = "wallhub_discover_feedback"
private const val MAX_RAIL_NAME_LENGTH = 512
private val Context.discoverFeedbackDataStore by preferencesDataStore(
    name = DISCOVER_FEEDBACK_FILE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class DataStoreDiscoverFeedbackRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : DiscoverFeedbackRepository,
        DiscoverSavedQueryRepository {
        private val dataStore = context.applicationContext.discoverFeedbackDataStore

        override val feedback: Flow<Map<String, DiscoverRailFeedback>> =
            dataStore.data
                .catch { error ->
                    if (error is IOException) {
                        Log.w(TAG, "Discover feedback could not be read; using an empty snapshot", error)
                        emit(emptyPreferences())
                    } else {
                        throw error
                    }
                }.map(::toFeedbackSnapshot)

        override val queries: Flow<List<DiscoverSavedQuery>> =
            dataStore.data
                .catch { error ->
                    if (error is IOException) emit(emptyPreferences()) else throw error
                }.map { preferences ->
                    preferences[DiscoverFeedbackKeys.savedQueries]
                        .orEmpty()
                        .mapNotNull(::decodeSavedQuery)
                        .sortedByDescending(DiscoverSavedQuery::createdAtMillis)
                }

        override suspend fun feedbackFor(railName: String): DiscoverRailFeedback {
            val normalizedRailName = railName.normalizedRailName()
            return feedback.first()[normalizedRailName] ?: DiscoverRailFeedback(normalizedRailName)
        }

        override suspend fun setLiked(
            railName: String,
            liked: Boolean,
        ) {
            updateFeedback(railName) { current ->
                current.copy(
                    liked = liked,
                    disliked = if (liked) false else current.disliked,
                )
            }
        }

        override suspend fun setDisliked(
            railName: String,
            disliked: Boolean,
        ) {
            updateFeedback(railName) { current ->
                current.copy(
                    liked = if (disliked) false else current.liked,
                    disliked = disliked,
                )
            }
        }

        override suspend fun setFavorited(
            railName: String,
            favorited: Boolean,
        ) {
            updateFeedback(railName) { current -> current.copy(favorited = favorited) }
        }

        override suspend fun weightFor(railName: String): DiscoverFeedbackWeight =
            feedbackFor(railName).toDiscoverFeedbackWeight()

        override suspend fun weightsFor(railNames: Collection<String>): Map<String, DiscoverFeedbackWeight> {
            if (railNames.isEmpty()) return emptyMap()
            val normalizedNames = railNames.mapTo(linkedSetOf(), String::normalizedRailName)
            val snapshot = feedback.first()
            return normalizedNames.associateWith { railName ->
                snapshot[railName]
                    ?.toDiscoverFeedbackWeight()
                    ?: DiscoverFeedbackWeight()
            }
        }

        override suspend fun clearFeedback(railName: String) {
            val normalizedRailName = railName.normalizedRailName()
            dataStore.edit { preferences ->
                preferences.removeFromSet(DiscoverFeedbackKeys.likedRailNames, normalizedRailName)
                preferences.removeFromSet(DiscoverFeedbackKeys.dislikedRailNames, normalizedRailName)
                preferences.removeFromSet(DiscoverFeedbackKeys.favoritedRailNames, normalizedRailName)
            }
        }

        override suspend fun clearAllFeedback() {
            dataStore.edit { preferences ->
                preferences.remove(DiscoverFeedbackKeys.likedRailNames)
                preferences.remove(DiscoverFeedbackKeys.dislikedRailNames)
                preferences.remove(DiscoverFeedbackKeys.favoritedRailNames)
            }
        }

        override suspend fun upsert(query: DiscoverSavedQuery) {
            dataStore.edit { preferences ->
                val existing = preferences[DiscoverFeedbackKeys.savedQueries].orEmpty()
                val updated = existing.filterNot { encoded -> decodeSavedQuery(encoded)?.id == query.id }.toMutableSet()
                updated += encodeSavedQuery(query)
                preferences[DiscoverFeedbackKeys.savedQueries] = updated
            }
        }

        override suspend fun remove(queryId: String) {
            dataStore.edit { preferences ->
                val updated =
                    preferences[DiscoverFeedbackKeys.savedQueries]
                        .orEmpty()
                        .filterNot { encoded -> decodeSavedQuery(encoded)?.id == queryId }
                        .toSet()
                if (updated.isEmpty()) preferences.remove(DiscoverFeedbackKeys.savedQueries) else preferences[DiscoverFeedbackKeys.savedQueries] = updated
            }
        }

        private suspend fun updateFeedback(
            railName: String,
            transform: (DiscoverRailFeedback) -> DiscoverRailFeedback,
        ) {
            val normalizedRailName = railName.normalizedRailName()
            dataStore.edit { preferences ->
                val current = preferences.toFeedback(normalizedRailName)
                val updated = transform(current)
                preferences.updateSetMembership(DiscoverFeedbackKeys.likedRailNames, normalizedRailName, updated.liked)
                preferences.updateSetMembership(DiscoverFeedbackKeys.dislikedRailNames, normalizedRailName, updated.disliked)
                preferences.updateSetMembership(DiscoverFeedbackKeys.favoritedRailNames, normalizedRailName, updated.favorited)
            }
        }

        private companion object {
            const val TAG = "DiscoverFeedback"
        }
    }

private object DiscoverFeedbackKeys {
    val likedRailNames = stringSetPreferencesKey("liked_rail_names")
    val dislikedRailNames = stringSetPreferencesKey("disliked_rail_names")
    val favoritedRailNames = stringSetPreferencesKey("favorited_rail_names")
    val savedQueries = stringSetPreferencesKey("saved_queries_v1")
}

private fun encodeSavedQuery(query: DiscoverSavedQuery): String =
    JSONObject()
        .put("id", query.id)
        .put("title", query.title)
        .put("category", query.category.name)
        .put("source", query.source.name)
        .put("semantic", query.semantic)
        .put("searchText", query.searchText)
        .put("creatorId", query.creatorId)
        .put("collectionId", query.collectionId)
        .put("tags", JSONArray(query.tags.toList()))
        .put("excludedTags", JSONArray(query.excludedTags.toList()))
        .put("officialTags", JSONArray(query.officialTags.toList()))
        .put("excludedOfficialTags", JSONArray(query.excludedOfficialTags.toList()))
        .put("requiredTagGroups", JSONArray(query.requiredTagGroups.map { JSONArray(it.toList()) }))
        .put("types", JSONArray(query.types.map(WorkshopType::name)))
        .put("sort", query.sort.name)
        .put("days", query.days)
        .put("exactPhrase", query.exactPhrase)
        .put("allowNsfw", query.allowNsfw)
        .put("mobileCompatibleOnly", query.mobileCompatibleOnly)
        .put("previewUrl", query.previewUrl)
        .put("createdAtMillis", query.createdAtMillis)
        .toString()

private fun decodeSavedQuery(encoded: String): DiscoverSavedQuery? =
    runCatching {
        val json = JSONObject(encoded)
        DiscoverSavedQuery(
            id = json.getString("id"),
            title = json.getString("title"),
            category = DiscoverSavedQueryCategory.valueOf(json.getString("category")),
            source = DiscoverSavedQuerySource.valueOf(json.getString("source")),
            semantic = json.optString("semantic", "top_rated"),
            searchText = json.optString("searchText"),
            creatorId = json.optNullableString("creatorId"),
            collectionId = json.optNullableString("collectionId")?.toLongOrNull(),
            tags = json.optStringSet("tags"),
            excludedTags = json.optStringSet("excludedTags"),
            officialTags = json.optStringSet("officialTags"),
            excludedOfficialTags = json.optStringSet("excludedOfficialTags"),
            requiredTagGroups = json.optJSONArray("requiredTagGroups").toStringSetList(),
            types = json.optStringSet("types").mapNotNullTo(linkedSetOf()) { runCatching { WorkshopType.valueOf(it) }.getOrNull() },
            sort = runCatching { WorkshopSort.valueOf(json.optString("sort")) }.getOrDefault(WorkshopSort.TRENDING),
            days = json.optInt("days", 30),
            exactPhrase = json.optBoolean("exactPhrase"),
            allowNsfw = json.optBoolean("allowNsfw"),
            mobileCompatibleOnly = json.optBoolean("mobileCompatibleOnly"),
            previewUrl = json.optNullableString("previewUrl"),
            createdAtMillis = json.optLong("createdAtMillis", 0L),
        )
    }.getOrNull()

private fun JSONObject.optNullableString(name: String): String? =
    optString(name).trim().takeIf(String::isNotEmpty)?.takeUnless { it == "null" }

private fun JSONObject.optStringSet(name: String): Set<String> =
    optJSONArray(name).toStringSetList().firstOrNull().orEmpty()

private fun JSONArray?.toStringSetList(): List<Set<String>> {
    if (this == null) return emptyList()
    if (length() == 0) return listOf(emptySet())
    val nested = opt(0) is JSONArray
    return if (nested) {
        buildList {
            repeat(length()) { index ->
                val array = optJSONArray(index) ?: return@repeat
                add(buildSet { repeat(array.length()) { item -> array.optString(item).takeIf(String::isNotBlank)?.let(::add) } })
            }
        }
    } else {
        listOf(buildSet { repeat(length()) { index -> optString(index).takeIf(String::isNotBlank)?.let(::add) } })
    }
}

private fun toFeedbackSnapshot(preferences: Preferences): Map<String, DiscoverRailFeedback> {
    val liked = preferences[DiscoverFeedbackKeys.likedRailNames].orEmpty()
    val disliked = preferences[DiscoverFeedbackKeys.dislikedRailNames].orEmpty()
    val favorited = preferences[DiscoverFeedbackKeys.favoritedRailNames].orEmpty()
    return (liked + disliked + favorited).associateWith { railName ->
        preferences.toFeedback(railName)
    }
}

private fun Preferences.toFeedback(railName: String): DiscoverRailFeedback {
    val disliked = railName in this[DiscoverFeedbackKeys.dislikedRailNames].orEmpty()
    return DiscoverRailFeedback(
        railName = railName,
        liked = !disliked && railName in this[DiscoverFeedbackKeys.likedRailNames].orEmpty(),
        disliked = disliked,
        favorited = railName in this[DiscoverFeedbackKeys.favoritedRailNames].orEmpty(),
    )
}

private fun MutablePreferences.updateSetMembership(
    key: Preferences.Key<Set<String>>,
    value: String,
    included: Boolean,
) {
    val updated = this[key].orEmpty().toMutableSet()
    if (included) updated += value else updated -= value
    if (updated.isEmpty()) remove(key) else this[key] = updated
}

private fun MutablePreferences.removeFromSet(
    key: Preferences.Key<Set<String>>,
    value: String,
) = updateSetMembership(key, value, included = false)

private fun String.normalizedRailName(): String =
    trim().also { normalized ->
        require(normalized.isNotEmpty()) { "Discover rail name must not be blank" }
        require(normalized.length <= MAX_RAIL_NAME_LENGTH) {
            "Discover rail name must be at most $MAX_RAIL_NAME_LENGTH characters"
        }
    }
