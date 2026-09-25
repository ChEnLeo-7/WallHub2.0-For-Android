package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SteamAppPlaytime
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.effectiveWorkshopTypes
import com.wallhub.android.core.model.steamSearchText
import com.wallhub.android.core.model.workshopAuthorSearchOrNull
import com.wallhub.android.core.model.workshopDetailTagSearch
import com.wallhub.android.di.SteamSessionSdkEngine
import io.steamcontent.sdk.PlayerField
import io.steamcontent.sdk.PlayerProfileField
import io.steamcontent.sdk.PlayerProfiles
import io.steamcontent.sdk.SteamContentEngine
import io.steamcontent.sdk.WorkshopCommentField
import io.steamcontent.sdk.WorkshopCommentSort
import io.steamcontent.sdk.WorkshopContentType
import io.steamcontent.sdk.WorkshopField
import io.steamcontent.sdk.WorkshopItemField
import io.steamcontent.sdk.WorkshopPage as SdkWorkshopPage
import io.steamcontent.sdk.WorkshopPersonalList
import io.steamcontent.sdk.WorkshopResult
import io.steamcontent.sdk.WorkshopTextField
import io.steamcontent.sdk.WorkshopTrendWindow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One Workshop summary row as the SDK reports it: a field number per ordinal, in a fixed slot order.
 *
 * The slots exist because the SDK reads a page item one field at a time while a details handle reads the same
 * rows through a different entry point; both then build the app model through one mapping. Every ordinal in
 * [ORDINALS] is one the SDK answers on a page row *and* on a details result, which is why `VIEWS` and
 * `FILE_SIZE` are here and the interaction rows are not.
 */
private class SdkSummaryFields(
    private val numbers: LongArray,
) {
    /** The number at one ordinal slot. */
    fun at(slot: Int): Long = numbers[slot]

    /** The author's account id, or null when the row reported none. */
    fun authorIdOrNull(): Long? = at(AUTHOR).takeIf { it > 0L }

    /** How many tag strings the row states, clamped to the bound this adapter copies. */
    fun tagCount(): Int = at(TAG_COUNT).coerceIn(0L, MAX_SDK_ITEM_TAGS.toLong()).toInt()

    companion object {
        const val ITEM = 0
        const val AUTHOR = 1
        const val CONTENT_TYPE = 2
        const val SUBSCRIPTIONS = 3
        const val FAVOURITES = 4
        const val TAG_COUNT = 5
        const val VIEWS = 6
        const val FILE_SIZE = 7

        /** The summary rows a resolved item and a page row can both answer. */
        val ORDINALS: IntArray =
            intArrayOf(
                WorkshopItemField.ITEM,
                WorkshopItemField.AUTHOR,
                WorkshopItemField.CONTENT_TYPE,
                WorkshopItemField.SUBSCRIPTIONS,
                WorkshopItemField.FAVOURITES,
                WorkshopItemField.TAG_COUNT,
                WorkshopItemField.VIEWS,
                WorkshopItemField.FILE_SIZE,
            )
    }
}

/** One resolved Steam profile as the player family reports it, after the avatar URL is built app-side. */
private data class SdkPlayerProfile(
    val personaName: String,
    val avatarUrl: String?,
)

/** One comment row as the page reports it, before the persona read decorates it. */
private data class SdkCommentRow(
    val text: String,
    val authorId: Long?,
    val createdAt: Long?,
)

/**
 * The Steam Content SDK as this app's public and account Workshop reader.
 *
 * It serves the contracts [SteamUnifiedWorkshopRepository], [AccountWorkshopRepository], and
 * [SteamPlaytimeRepository], and it is now their only implementation: the kSteam mapper this class was
 * written to match field for field has been deleted, and the equality it was built for is what the device
 * parity readings compare. Every value is built here the way that mapper built it for the same data,
 * including the enum and state mapping and the epoch-**seconds** timestamps, because the SDK reports
 * milliseconds.
 *
 * The SDK reports "no Workshop provider", "no session", and "this operation is not implemented yet" as an
 * `IllegalStateException` rather than a nullable answer. Every read below translates that into the
 * contract's own unavailable answer: null for a method the contract already marks nullable, an empty page or
 * interaction for one it does not, and no throw ever crosses this class but the caller's own invalid input.
 *
 * The acting account comes from the SDK session (ADR 0034), so this class holds no credential store and
 * parses no token: it reads through the engine the session domain authenticated. That engine is the one
 * injected here, and it is the reason for the qualifier - the process also owns an application-wide engine
 * for the download path, and a read issued on *that* one finds no session and answers the empty page this
 * class would otherwise report as "no items".
 *
 * `RepositoryModule` binds all three contracts to this class, so the Workshop reads the product performs are
 * served by the SDK rather than by kSteam; the last adapter-level capability gaps it inherited (the thread
 * owner, the comment ordering, and the Voted collection) are now recorded as parity rather than as gaps at
 * their call sites.
 */
@Singleton
internal class SteamContentSdkWorkshopRepository
    @Inject
    constructor(
        @SteamSessionSdkEngine private val engine: SteamContentSdkEngine,
    ) : SteamUnifiedWorkshopRepository,
        AccountWorkshopRepository,
        SteamPlaytimeRepository {
        /** Serializes Workshop reads so the engine is never asked for two handles at once. */
        private val mutex = Mutex()

        // ------------------------------------------------------------------
        // SteamUnifiedWorkshopRepository
        // ------------------------------------------------------------------

        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? =
            withContext(Dispatchers.IO) {
                runCatchingUnavailable {
                    val normalized = query.normalizedForSdk()
                    val creatorId = normalized.creatorId?.toLongOrNull()?.takeIf { it > 0L }
                    mutex.withLock {
                        val page =
                            if (creatorId == null) {
                                engine.workshopSearch(
                                    appId = WALLPAPER_ENGINE_APP_ID.toLong(),
                                    sort = normalized.sort.sdkSort(),
                                    // Only DEFAULT is accepted by this release, so the search's own trend
                                    // window is what answers; the app's `days` has no SDK parameter.
                                    trendWindow = WorkshopTrendWindow.DEFAULT,
                                    pageSize = normalized.pageSize,
                                    pageOffset = normalized.pageOffset(),
                                    queryText = normalized.steamSearchText().takeIf(String::isNotBlank),
                                    contentTypes = normalized.sdkContentTypes(),
                                    tags = normalized.sdkTags(),
                                )
                            } else {
                                engine.workshopAuthorItems(
                                    authorId = creatorId,
                                    pageSize = normalized.pageSize,
                                    pageOffset = normalized.pageOffset(),
                                    contentTypes = normalized.sdkContentTypes(),
                                )
                            }
                        page.use { readWorkshopPage(it, normalized.page) }
                    }
                }
            }

        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? =
            withContext(Dispatchers.IO) { loadWorkshopDetail(workshopId) }

        override suspend fun getPublicDetails(workshopIds: List<Long>): List<WorkshopDetail> =
            withContext(Dispatchers.IO) {
                // The AAR has no batch details entry point, so one single-item read replaces kSteam's batch.
                workshopIds.distinct().mapNotNull { id -> loadWorkshopDetail(id) }
            }

        /**
         * The ordered direct children of a collection (ADR 0033).
         *
         * The details response the item was resolved from now carries them, ordered by its own `sortorder`
         * ascending, so this is a pass-through over the result handle. Null is the unavailable answer the
         * contract allows; a resolved item with no children answers an empty list.
         */
        override suspend fun getPublicCollectionChildren(collectionId: Long): List<Long>? =
            withContext(Dispatchers.IO) {
                runCatchingUnavailable {
                    require(collectionId > 0L) { "Invalid Workshop collection ID" }
                    mutex.withLock {
                        engine.openWorkshopItem(collectionId).use { result -> readCollectionChildren(result) }
                    }
                }
            }

        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? =
            withContext(Dispatchers.IO) {
                // The app's existing comment-request rules are applied outside the translation below, so bad
                // caller input stays a caller error while only SDK unavailability becomes a null answer.
                require(workshopId > 0L) { "Invalid Workshop item ID" }
                require(ownerId.toULongOrNull()?.let { it > 0uL } == true) { "Invalid Workshop author ID" }
                val pageStart = start.coerceAtLeast(0)
                val pageCount = count.coerceIn(1, MAX_SDK_COMMENT_PAGE_SIZE)
                // The thread owner is not a caller input here, and it does not need to be: the SDK's
                // `CommunityGetCommentThreadRequest.steam_id` is documented as "the account that owns the
                // thread; for a Workshop item, its author", and the session provider fills it from the item's
                // own details before it reads the thread. kSteam had to be handed the same value by the
                // caller; both end up sending the item's author as the thread key.
                runCatchingUnavailable {
                    mutex.withLock {
                        val page =
                            engine.workshopComments(
                                itemId = workshopId,
                                // kSteam's request carries no ordering field, so the service's newest-first
                                // default answered it. The SDK names that ordering explicitly instead -
                                // `CommentSort::NewestFirst` clears the request's `oldest_first` flag - so the
                                // two paths ask for the same page.
                                sort = WorkshopCommentSort.NEWEST_FIRST,
                                pageSize = pageCount,
                                pageOffset = pageStart,
                            )
                        page.use { readWorkshopComments(it, pageStart, pageCount, ownerId) }
                    }
                }
            }

        // ------------------------------------------------------------------
        // AccountWorkshopRepository
        // ------------------------------------------------------------------

        override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage =
            withContext(Dispatchers.IO) {
                // The contract has no null answer here, so an unavailable read is an empty page rather than a
                // throw, which is the only "nothing to show" this signature can carry.
                val loaded =
                    runCatchingUnavailable {
                        val accountId =
                            signedInAccountId() ?: error("No signed-in Steam account is available")
                        // Every app collection has an SDK list, so this mapping has no absent case: a
                        // collection the product shows is never answered by producing another list's items.
                        val list = query.collection.sdkPersonalList()
                        val normalized = query.normalizedForSdk()
                        mutex.withLock {
                            val page =
                                engine.workshopPersonalItems(
                                    accountId = accountId,
                                    list = list,
                                    pageSize = normalized.pageSize,
                                    pageOffset = normalized.pageOffset(),
                                )
                            // A personal row's own state is the list it came from, which is how the removed
                            // kSteam mapper read it.
                            page.use { readWorkshopPage(it, normalized.page, query.collection) }
                        }
                    }
                loaded ?: emptyWorkshopPage(query)
            }

        /**
         * Resolves the creator's public display name through the player family (ADR 0032).
         *
         * The item's `AUTHOR` field is an account id, not a name; the persona read is the only name-bearing
         * reading. The id-bearing placeholder the old adapter answered here is now a real name whenever the
         * player plane answers, and null when it does not.
         */
        override suspend fun resolveAuthorDisplayName(workshopId: Long): String? =
            withContext(Dispatchers.IO) {
                require(workshopId > 0L) { "Invalid Workshop item ID" }
                runCatchingUnavailable {
                    mutex.withLock {
                        engine.openWorkshopItem(workshopId).use { result ->
                            val authorId = result.field(WorkshopItemField.AUTHOR).valueOrZero().takeIf { it > 0L }
                            if (authorId == null) {
                                null
                            } else {
                                readPlayerProfiles(setOf(authorId))[authorId]?.personaName
                            }
                        }
                    }
                }
            }

        override suspend fun getInteraction(workshopId: Long): WorkshopInteraction =
            withContext(Dispatchers.IO) {
                runCatchingUnavailable {
                    mutex.withLock { readInteraction(workshopId) }
                } ?: WorkshopInteraction()
            }

        override suspend fun setSubscribed(
            workshopId: Long,
            subscribed: Boolean,
        ): WorkshopInteraction =
            withContext(Dispatchers.IO) {
                val confirmed =
                    runCatchingUnavailable {
                        val accountId = signedInAccountId() ?: error("No signed-in Steam account is available")
                        mutex.withLock { engine.setSubscribed(workshopId, accountId, subscribed) }
                    }
                if (confirmed == null) {
                    WorkshopInteraction()
                } else {
                    // The confirmed half is the SDK's own answer; the favourite half is not part of it and
                    // stays unknown rather than being guessed or re-read.
                    WorkshopInteraction(
                        subscriptionState =
                            if (confirmed.enabled) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED,
                    )
                }
            }

        override suspend fun setFavorited(
            workshopId: Long,
            favorited: Boolean,
        ): WorkshopInteraction =
            withContext(Dispatchers.IO) {
                val confirmed =
                    runCatchingUnavailable {
                        val accountId = signedInAccountId() ?: error("No signed-in Steam account is available")
                        mutex.withLock { engine.setFavorited(workshopId, accountId, favorited) }
                    }
                if (confirmed == null) {
                    WorkshopInteraction()
                } else {
                    // Mirror of the subscription case: the confirmed favourite is reported, the subscription
                    // half is not carried by this answer.
                    WorkshopInteraction(
                        favoriteState =
                            if (confirmed.enabled) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED,
                    )
                }
            }

        /**
         * Writes one comment to an item's public thread (ADR 0033).
         *
         * The AAR's own second argument is the account that owns the thread - the contract's `ownerId`, which
         * the caller already holds - while the write itself is authorized as the session's signed-in account;
         * no acting-account number is sent. The app's comment rules stay the caller error they are today, and
         * only SDK unavailability is swallowed, because this signature cannot carry a null.
         */
        override suspend fun postComment(
            workshopId: Long,
            ownerId: String,
            text: String,
        ) = withContext(Dispatchers.IO) {
            val request = normalizeWorkshopCommentRequest(workshopId, ownerId, text)
            runCatchingUnavailable {
                mutex.withLock {
                    engine.postComment(request.workshopId, request.ownerId.toLong(), request.text)
                }
            }
            Unit
        }

        // ------------------------------------------------------------------
        // SteamPlaytimeRepository
        // ------------------------------------------------------------------

        /**
         * Reads the signed-in account's playtime for one App through the player family (ADR 0032).
         *
         * The SDK separates "no record" from "played zero minutes" with its own `present` flag, so this maps
         * a record that is not present to the contract's null and otherwise keeps the three counters the
         * kSteam path returned. An engine without a player plane answers null rather than throwing.
         */
        override suspend fun getAppPlaytime(appId: Int): SteamAppPlaytime? =
            withContext(Dispatchers.IO) {
                val playtime = runCatchingUnavailable { mutex.withLock { engine.playerPlaytime(appId.toLong()) } }
                playtime
                    ?.takeIf { it.present }
                    ?.let { row ->
                        SteamAppPlaytime(
                            appId = row.appId.toInt(),
                            totalMinutes = row.playtimeForeverMinutes.toInt(),
                            recentMinutes = row.playtime2WeeksMinutes.toInt(),
                            lastPlayedEpochSeconds = row.lastPlayedUnixSeconds.takeIf { it > 0L },
                        )
                    }
            }

        // ------------------------------------------------------------------
        // Reads
        // ------------------------------------------------------------------

        /** Resolves one item's detail, or null when the SDK cannot serve the read. */
        private suspend fun loadWorkshopDetail(workshopId: Long): WorkshopDetail? =
            runCatchingUnavailable {
                require(workshopId > 0L) { "Invalid Workshop item ID" }
                mutex.withLock {
                    engine.openWorkshopItem(workshopId).use { result -> readWorkshopDetail(result) }
                }
            }

        /** Builds one detail from a resolved item, reading each field the app model needs once. */
        private fun readWorkshopDetail(result: WorkshopResult): WorkshopDetail {
            val fields = summaryFieldsOf(result)
            val authorId = fields.authorIdOrNull()
            val profile = authorId?.let { readPlayerProfiles(setOf(it))[it] }
            val summary =
                summaryOf(
                    fields = fields,
                    title = result.text(WorkshopTextField.TITLE).orEmpty(),
                    previewUrl = result.text(WorkshopTextField.PREVIEW_URL),
                    tags = readResultTags(result, fields),
                    profile = profile,
                    // kSteam's details path labels an unresolved creator, not an unresolved user.
                    unresolvedPlaceholder = WorkshopAuthorPlaceholder.CREATOR,
                )
            return WorkshopDetail(
                summary = summary,
                description = result.text(WorkshopTextField.DESCRIPTION).orEmpty().trim(),
                fileSizeBytes = fields.at(SdkSummaryFields.FILE_SIZE).takeIf { it > 0L },
                // The SDK publishes one preview URL and no additional-preview list, so the summary's URL is
                // the only media the app can name.
                previewMediaUrl = summary.previewUrl,
                // No ordinal reports playability.
                canPlay = false,
                createdAt = result.field(WorkshopItemField.CREATED_AT).epochSeconds(),
                updatedAt = result.field(WorkshopItemField.UPDATED_AT).epochSeconds(),
                subscriptions = summary.subscriptions,
                creatorId = summary.creatorId,
            )
        }

        /** The ordered child ids a resolved collection states; the SDK orders them by its own sortorder. */
        private fun readCollectionChildren(result: WorkshopResult): List<Long> {
            val count = result.childCount()
            return (0 until count).map { index -> result.child(index) }.filter { it > 0L }
        }

        /** Reads the summary rows a resolved item reports. */
        private fun summaryFieldsOf(result: WorkshopResult): SdkSummaryFields =
            SdkSummaryFields(
                LongArray(SdkSummaryFields.ORDINALS.size).also { values ->
                    SdkSummaryFields.ORDINALS.forEachIndexed { slot, ordinal ->
                        values[slot] = result.field(ordinal).value
                    }
                }
            )

        /** Reads the summary rows of one page row. */
        private fun summaryFieldsOf(
            page: SdkWorkshopPage,
            index: Int,
        ): SdkSummaryFields =
            SdkSummaryFields(
                LongArray(SdkSummaryFields.ORDINALS.size).also { values ->
                    SdkSummaryFields.ORDINALS.forEachIndexed { slot, ordinal ->
                        values[slot] = page.field(index, ordinal).value
                    }
                }
            )

        /** Builds the app summary of one page row, closing over the page it reads text and tags from. */
        private fun readWorkshopPage(
            page: SdkWorkshopPage,
            requestedPage: Int,
            collection: AccountWorkshopCollection? = null,
        ): WorkshopPage {
            val size = page.size()
            val state = page.state()
            val rows = (0 until size).map { index -> summaryFieldsOf(page, index) }
            val profiles = readPlayerProfiles(rows.mapNotNull { it.authorIdOrNull() }.toSet())
            val items =
                rows.mapIndexed { index, fields ->
                    summaryOf(
                        fields = fields,
                        title = page.text(index, WorkshopTextField.TITLE).orEmpty(),
                        previewUrl = page.text(index, WorkshopTextField.PREVIEW_URL),
                        tags = readPageTags(page, index, fields),
                        profile = fields.authorIdOrNull()?.let(profiles::get),
                        // A page row has no resolved creator behind it, so the id-bearing placeholder stands
                        // in, exactly as the removed kSteam summary mapper left it.
                        unresolvedPlaceholder = WorkshopAuthorPlaceholder.USER_WITH_ID,
                        collection = collection,
                    )
                }
            // One line per non-empty page, so a device reading can name what the SDK actually put on its
            // rows (tags, the mapped content type, and the size) without exposing anything but public item
            // metadata: the same evidence convention the session, depot, and playback paths follow.
            if (items.isNotEmpty()) {
                val first = items.first()
                Log.i(
                    TAG,
                    "workshop page rows=${items.size} collection=${collection?.name ?: "public"} " +
                        "first-id=${first.id} first-type=${first.type} first-size=${first.fileSizeBytes ?: 0} " +
                        "first-tags=${first.tags.take(MAX_LOGGED_TAGS)}",
                )
            }
            val total = state.total.takeIf { it > 0 }
            return WorkshopPage(
                items = items,
                page = requestedPage,
                // The cursor is zero when the provider reported no further page.
                hasNextPage = state.nextOffset > 0,
                totalCount = total,
                totalPages = total?.toMaximumPage(state.count),
            )
        }

        /**
         * One summary in the app's model, from rows both the detail and page paths report the same way.
         *
         * [collection] is the personal list a page row came from, when it came from one: the service reports
         * counts on such a row, not this account's own state, so the collection is what says the row is
         * subscribed or favourited - exactly the reading the removed kSteam mapper took.
         */
        private fun summaryOf(
            fields: SdkSummaryFields,
            title: String,
            previewUrl: String?,
            tags: List<String>,
            profile: SdkPlayerProfile?,
            unresolvedPlaceholder: WorkshopAuthorPlaceholder,
            collection: AccountWorkshopCollection? = null,
        ): WorkshopSummary {
            val trimmedTitle = title.trim()
            val creatorId = fields.authorIdOrNull()?.toString()
            return WorkshopSummary(
                id = fields.at(SdkSummaryFields.ITEM),
                title = trimmedTitle,
                // The player family's persona fills the name (ADR 0032); an unresolved author keeps the
                // placeholder the kSteam path produces before its own profile resolution.
                author = profile?.personaName.orEmpty(),
                creatorId = creatorId,
                authorAvatarUrl = profile?.avatarUrl,
                previewUrl = previewUrl?.trim()?.takeIf(String::isNotBlank),
                // The type comes from the item's own tags, which is how the removed kSteam mapper read it
                // and what the Library's badge and its video play action are gated on; the SDK's mapped
                // content type is the fallback for a row whose tags the service did not report.
                type = tags.toWorkshopType().takeIf { it != WorkshopType.UNKNOWN }
                    ?: fields.at(SdkSummaryFields.CONTENT_TYPE).toWorkshopType(),
                // The tag text copy ordinals complete TAG_COUNT, so the app's client-side tag filtering sees
                // the same tags the old path put on the model.
                tags = tags,
                subscriptions = fields.at(SdkSummaryFields.SUBSCRIPTIONS).takeIf { it > 0L },
                favorites = fields.at(SdkSummaryFields.FAVOURITES).takeIf { it > 0L },
                // VIEWS is the one row past UPDATED_AT that a page row can answer too (ADR 0033).
                views = fields.at(SdkSummaryFields.VIEWS).takeIf { it > 0L },
                // The size travels on the summary now that every read asks the service for the item's
                // metadata.
                fileSizeBytes = fields.at(SdkSummaryFields.FILE_SIZE).takeIf { it > 0L },
                subscriptionState =
                    if (collection == AccountWorkshopCollection.SUBSCRIPTIONS) {
                        SubscriptionState.SUBSCRIBED
                    } else {
                        SubscriptionState.UNKNOWN
                    },
                favoriteState =
                    if (collection == AccountWorkshopCollection.FAVORITES) {
                        FavoriteState.FAVORITED
                    } else {
                        FavoriteState.UNKNOWN
                    },
                isTitlePlaceholder = trimmedTitle.isEmpty(),
                authorPlaceholder =
                    when {
                        profile != null -> WorkshopAuthorPlaceholder.NONE
                        creatorId == null -> WorkshopAuthorPlaceholder.NONE
                        else -> unresolvedPlaceholder
                    },
            )
        }

        /** Maps one comment page on and reports it in the app's page model. */
        private fun readWorkshopComments(
            page: SdkWorkshopPage,
            requestedStart: Int,
            requestedCount: Int,
            ownerId: String,
        ): WorkshopCommentPage {
            val size = page.size()
            val state = page.state()
            val creatorId = ownerId.toLongOrNull()?.takeIf { it > 0L }
            val rows =
                (0 until size).mapNotNull { index ->
                    val text = page.text(index, WorkshopCommentField.BODY).orEmpty().trim()
                    if (text.isEmpty()) return@mapNotNull null
                    SdkCommentRow(
                        text = text,
                        authorId = page.field(index, WorkshopCommentField.AUTHOR).valueOrZero().takeIf { it > 0L },
                        createdAt = page.field(index, WorkshopCommentField.CREATED_AT).epochSeconds(),
                    )
                }
            val profiles = readPlayerProfiles(rows.mapNotNull { it.authorId }.toSet())
            val comments =
                rows.map { row ->
                    val profile = row.authorId?.let(profiles::get)
                    WorkshopComment(
                        // AUTHOR is an account id; the persona read is what names the comment (ADR 0032).
                        author = profile?.personaName.orEmpty(),
                        text = row.text,
                        authorId = row.authorId?.toString(),
                        avatarUrl = profile?.avatarUrl,
                        // The one field this adapter can derive: the comment's author is the thread owner.
                        isCreator = row.authorId != null && row.authorId == creatorId,
                        timestamp = row.createdAt,
                        dateLabel = null,
                        // A resolved author with no persona behind it is exactly the placeholder reading.
                        isAuthorPlaceholder = profile == null,
                    )
                }
            val nextStart = state.nextOffset
            val total = state.total.takeIf { it > 0 }
            return WorkshopCommentPage(
                comments = comments,
                start = requestedStart,
                count = requestedCount,
                nextStart = nextStart,
                total = total,
                hasMore = nextStart > 0,
                ownerId = ownerId.takeIf(String::isNotBlank),
            )
        }

        /** Reads both interaction halves once, or reports both as unknown. */
        private fun readInteraction(workshopId: Long): WorkshopInteraction {
            require(workshopId > 0L) { "Invalid Workshop item ID" }
            return engine.openWorkshopItem(workshopId).use { result ->
                WorkshopInteraction(
                    subscriptionState = subscriptionStateOf(result.field(WorkshopItemField.SUBSCRIBED)),
                    favoriteState = favoriteStateOf(result.field(WorkshopItemField.FAVOURITED)),
                )
            }
        }

        // ------------------------------------------------------------------
        // Player profiles and account identity
        // ------------------------------------------------------------------

        /**
         * Resolves a bounded set of account ids into persona names and avatar URLs (ADR 0032).
         *
         * Callers already hold [mutex], so this never takes it again; the SDK's profile handle is a second
         * child handle of the same engine and is closed here. An engine without a player plane, or a provider
         * that refuses the read, answers an empty map so every caller keeps its own placeholder reading.
         */
        private fun readPlayerProfiles(accountIds: Set<Long>): Map<Long, SdkPlayerProfile> {
            val ids = accountIds.filter { it > 0L }.distinct().toLongArray()
            if (ids.isEmpty()) return emptyMap()
            return runCatchingUnavailable {
                engine.openPlayerProfiles(ids).use { profiles -> readProfiles(profiles) }
            } ?: emptyMap()
        }

        /**
         * Reads every profile of one resolved set.
         *
         * Only a profile the service named is kept, exactly as the kSteam path keeps one, so an account with
         * no persona keeps the placeholder reading rather than reporting an empty name as resolved. The
         * avatar crosses the AAR as a hash, never a URL, so the URL is built on this side.
         */
        private fun readProfiles(profiles: PlayerProfiles): Map<Long, SdkPlayerProfile> {
            val resolved = LinkedHashMap<Long, SdkPlayerProfile>()
            for (index in 0 until profiles.count()) {
                val account = profiles.field(index, PlayerProfileField.ACCOUNT_ID)
                val accountId =
                    account.value.takeIf { account.state == PlayerField.VALUE.toLong() && it > 0L } ?: continue
                val personaName =
                    profiles.text(index, PlayerProfileField.PERSONA_NAME)?.trim()?.takeIf(String::isNotBlank)
                        ?: continue
                resolved[accountId] =
                    SdkPlayerProfile(
                        personaName = personaName,
                        avatarUrl = profiles.text(index, PlayerProfileField.AVATAR_HASH).toSteamAvatarUrl(),
                    )
            }
            return resolved
        }

        /**
         * The signed-in account id from the SDK session, or null when nobody is signed in.
         *
         * The session names the account it is authorized as (ADR 0034) and reports zero when no account is
         * signed in, so a zero never reaches an account-scoped read: the caller reports the read unavailable
         * instead. No stored token is parsed and no credential crosses this helper.
         */
        private fun signedInAccountId(): Long? =
            runCatching { engine.currentSession()?.accountId() }.getOrNull()?.takeIf { it > 0L }
    }

/** Runs an SDK call and answers null when the engine has no provider, no session, or no such operation. */
private inline fun <T> runCatchingUnavailable(block: () -> T): T? =
    try {
        block()
    } catch (error: IllegalStateException) {
        null
    }

/** The page an unavailable account read answers with, because the contract cannot express null there. */
private fun emptyWorkshopPage(query: AccountWorkshopQuery): WorkshopPage =
    WorkshopPage(
        items = emptyList(),
        page = query.page.coerceAtLeast(1),
        hasNextPage = false,
    )

/**
 * The tag texts one page row states, in the response's own order.
 *
 * The tag copies are never joined into one string by the SDK, so this reads the count `TAG_COUNT` reports and
 * copies each index; a copy that produced no bytes or only blanks is absent from the list.
 */
private fun readPageTags(
    page: SdkWorkshopPage,
    index: Int,
    fields: SdkSummaryFields,
): List<String> = (0 until fields.tagCount()).mapNotNull { tagIndex -> page.tagText(index, tagIndex).tagOrNull() }

/** The tag texts one resolved item states, the details half of the same copy ordinals. */
private fun readResultTags(
    result: WorkshopResult,
    fields: SdkSummaryFields,
): List<String> = (0 until fields.tagCount()).mapNotNull { tagIndex -> result.tagText(tagIndex).tagOrNull() }

/** One tag text as the app model keeps it; a copy with no bytes or only blanks is absent. */
private fun String?.tagOrNull(): String? = this?.trim()?.takeIf(String::isNotBlank)

/**
 * The Steam CDN URL for one avatar hash (ADR 0032).
 *
 * The player family copies the avatar as the lowercase hexadecimal hash the service reported and never as a
 * URL, so constructing the URL stays the product rule it already is on the kSteam path. The old path
 * hex-encoded the digest bytes; the SDK has already done that step, so only the URL is left to build here.
 */
private fun String?.toSteamAvatarUrl(): String? =
    this?.trim()?.takeIf(String::isNotBlank)?.let { hash ->
        "https://avatars.fastly.steamstatic.com/${hash}_medium.jpg"
    }

/**
 * Normalises a public query the way the kSteam path does before either transport sees it, so the page, size,
 * search text, author form, and tag bounds stay the same and the SDK's own request preconditions hold.
 */
private fun WorkshopBrowseQuery.normalizedForSdk(): WorkshopBrowseQuery {
    val mergedTags =
        (tags + searchText.workshopDetailTagSearch())
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_SDK_TAGS)
            .toSet()
    return copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_SDK_PAGE_SIZE),
        searchText = searchText.trim().take(MAX_SDK_SEARCH_LENGTH),
        // kSteam turns an `author:` search into the author request; that extraction has to survive here or
        // the same text would be sent as a free-text query instead.
        creatorId = (creatorId?.trim()?.takeIf(String::isNotBlank)) ?: searchText.workshopAuthorSearchOrNull(),
        types = types.filter { it != WorkshopType.UNKNOWN }.toSet(),
        tags = mergedTags,
    )
}

/** Normalises an account query to the bounds a personal-items request requires. */
private fun AccountWorkshopQuery.normalizedForSdk(): AccountWorkshopQuery =
    copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_SDK_PAGE_SIZE),
    )

/** The absolute cursor the SDK request takes, which the app expresses as a one-based page. */
private fun WorkshopBrowseQuery.pageOffset(): Int = pageOffsetOf(page, pageSize)

private fun AccountWorkshopQuery.pageOffset(): Int = pageOffsetOf(page, pageSize)

private fun pageOffsetOf(
    page: Int,
    pageSize: Int,
): Int {
    val offset = (page.coerceAtLeast(1).toLong() - 1L) * pageSize.coerceAtLeast(1).toLong()
    return offset.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** The SDK content-type filter for the query's effective types; only the video ordinal maps. */
private fun WorkshopBrowseQuery.sdkContentTypes(): List<Int> {
    // SCENE and WEB have no SDK content-type ordinal; kSteam expresses them as tags instead.
    val mapped: List<Int> =
        effectiveWorkshopTypes().mapNotNull { type ->
            when (type) {
                WorkshopType.VIDEO -> WorkshopContentType.VIDEO
                WorkshopType.SCENE,
                WorkshopType.WEB,
                WorkshopType.UNKNOWN,
                -> null
            }
        }
    return mapped
}

/** The tag filter the SDK request takes; its empty list means no tag filter at all. */
private fun WorkshopBrowseQuery.sdkTags(): List<String> =
    (tags + searchText.workshopDetailTagSearch())
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_SDK_TAGS)

/**
 * The app's seven sorts onto the AAR's published ranking vocabulary, one to one.
 *
 * `WorkshopSort.TOTAL_VOTES`, `FRIENDS_FAVORITED`, and `FRIENDS_CREATED` were the three rankings the AAR
 * did not publish, which is why those sorts used to fall back to relevance; every one now names a real
 * query type. `LAST_UPDATED` and `MOST_FAVORITED` are SDK members with no app sort, so they are unused.
 */
private fun WorkshopSort.sdkSort(): Int =
    when (this) {
        WorkshopSort.TRENDING -> io.steamcontent.sdk.WorkshopSort.MOST_POPULAR
        WorkshopSort.MOST_RECENT -> io.steamcontent.sdk.WorkshopSort.MOST_RECENT
        WorkshopSort.TOP_RATED -> io.steamcontent.sdk.WorkshopSort.RELEVANCE
        WorkshopSort.MOST_VOTES -> io.steamcontent.sdk.WorkshopSort.TOTAL_VOTES
        WorkshopSort.MOST_SUBSCRIBERS -> io.steamcontent.sdk.WorkshopSort.MOST_SUBSCRIBED
        WorkshopSort.FRIENDS_FAVORITES -> io.steamcontent.sdk.WorkshopSort.FRIENDS_FAVORITED
        WorkshopSort.FRIENDS_CREATED -> io.steamcontent.sdk.WorkshopSort.FRIENDS_CREATED
    }

/**
 * The type an item's own tags name, taking the same three tags the removed kSteam mapper took.
 *
 * Wallpaper Engine publishes one of `Video`, `Scene`, or `Web`/`Website` on every item, and the product's
 * Library badge and its video play action are gated on this reading rather than on the service's file-type
 * number, which does not distinguish the three.
 */
private fun List<String>.toWorkshopType(): WorkshopType =
    when {
        any { tag -> tag.equals("Video", ignoreCase = true) } -> WorkshopType.VIDEO
        any { tag -> tag.equals("Scene", ignoreCase = true) } -> WorkshopType.SCENE
        any { tag -> tag.equals("Web", ignoreCase = true) || tag.equals("Website", ignoreCase = true) } ->
            WorkshopType.WEB
        else -> WorkshopType.UNKNOWN
    }

/** Maps the SDK's content-type ordinal onto the app's type. Only the video ordinal names one. */
private fun Long.toWorkshopType(): WorkshopType =
    if (this == WorkshopContentType.VIDEO.toLong()) WorkshopType.VIDEO else WorkshopType.UNKNOWN

/** Maps the app's collection onto the SDK's personal list, one to one. */
private fun AccountWorkshopCollection.sdkPersonalList(): Int =
    when (this) {
        AccountWorkshopCollection.SUBSCRIPTIONS -> WorkshopPersonalList.SUBSCRIBED
        AccountWorkshopCollection.FAVORITES -> WorkshopPersonalList.FAVORITED
        // The SDK's ordinal is sent as `type = "myvotes"`, which is the selector kSteam used for this same
        // collection, so the two paths ask the service the same question.
        AccountWorkshopCollection.VOTED -> WorkshopPersonalList.VOTED
    }

/** A field's number, or zero when the provider could not answer it. */
private fun SteamContentEngine.WorkshopFieldValue.valueOrZero(): Long =
    if (state == WorkshopField.VALUE) value else 0L

/**
 * A field's number as epoch **seconds**.
 *
 * The SDK reports milliseconds and the app model is seconds, so this is the one conversion and it happens
 * nowhere else. An absent field, or one that reports the epoch itself, answers null.
 */
private fun SteamContentEngine.WorkshopFieldValue.epochSeconds(): Long? =
    (valueOrZero() / 1_000L).takeIf { it > 0L }

/** The subscription half of an interaction: a present value is the answer, anything else stays unknown. */
private fun subscriptionStateOf(field: SteamContentEngine.WorkshopFieldValue): SubscriptionState =
    when {
        field.state != WorkshopField.VALUE -> SubscriptionState.UNKNOWN
        field.value != 0L -> SubscriptionState.SUBSCRIBED
        else -> SubscriptionState.NOT_SUBSCRIBED
    }

/** The favourite half of an interaction, with the same present/absent reading. */
private fun favoriteStateOf(field: SteamContentEngine.WorkshopFieldValue): FavoriteState =
    when {
        field.state != WorkshopField.VALUE -> FavoriteState.UNKNOWN
        field.value != 0L -> FavoriteState.FAVORITED
        else -> FavoriteState.NOT_FAVORITED
    }

/** The page count for a reported total, mirroring the kSteam page mapping's own ceiling. */
private fun Int.toMaximumPage(pageSize: Int): Int {
    val safePageSize = pageSize.coerceAtLeast(1).toLong()
    return ((coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
        .coerceIn(1L, MAX_SDK_DIRECT_BROWSE_PAGE.toLong())
        .toInt()
}

private const val TAG = "WallHubSdkWorkshop"

/** How many tag names one diagnostic page line may carry: enough to name the type, no more. */
private const val MAX_LOGGED_TAGS = 8

private const val MAX_SDK_COMMENT_PAGE_SIZE = 50
private const val MAX_SDK_PAGE_SIZE = 50
private const val MAX_SDK_SEARCH_LENGTH = 128
private const val MAX_SDK_TAGS = 48

/** The most tag copies this adapter reads from one item, well past any list the service reports. */
private const val MAX_SDK_ITEM_TAGS = 256
private const val MAX_SDK_DIRECT_BROWSE_PAGE = 1_000
