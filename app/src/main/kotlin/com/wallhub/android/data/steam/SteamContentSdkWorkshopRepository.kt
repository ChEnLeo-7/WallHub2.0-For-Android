package com.wallhub.android.data.steam

import android.content.Context
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SteamAppPlaytime
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SubscriptionState
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
import io.steamcontent.sdk.SteamContentEngine
import io.steamcontent.sdk.WorkshopCommentField
import io.steamcontent.sdk.WorkshopCommentSort
import io.steamcontent.sdk.WorkshopContentType
import io.steamcontent.sdk.WorkshopField
import io.steamcontent.sdk.WorkshopItemField
import io.steamcontent.sdk.WorkshopPage as SdkWorkshopPage
import io.steamcontent.sdk.WorkshopPersonalList
import io.steamcontent.sdk.WorkshopResult
import io.steamcontent.sdk.WorkshopTrendWindow
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One Workshop summary row as the SDK reports it: a field number per ordinal, in a fixed slot order.
 *
 * The slots exist because the SDK reads a page item one field at a time while a details handle reads the same
 * rows through a different entry point; both then build the app model through one mapping.
 */
private class SdkSummaryFields(
    private val numbers: LongArray,
) {
    /** The number at one ordinal slot. */
    fun at(slot: Int): Long = numbers[slot]

    companion object {
        const val ITEM = 0
        const val AUTHOR = 1
        const val CONTENT_TYPE = 2
        const val SUBSCRIPTIONS = 3
        const val FAVOURITES = 4
        const val SIZE = 5

        /** The summary rows a resolved item and a page row can both answer. */
        val ORDINALS: IntArray =
            intArrayOf(
                WorkshopItemField.ITEM,
                WorkshopItemField.AUTHOR,
                WorkshopItemField.CONTENT_TYPE,
                WorkshopItemField.SUBSCRIPTIONS,
                WorkshopItemField.FAVOURITES,
            )
    }
}

/**
 * The Steam Content SDK as this app's public and account Workshop reader.
 *
 * It serves the same contracts the kSteam implementation serves - [SteamUnifiedWorkshopRepository],
 * [AccountWorkshopRepository], and [SteamPlaytimeRepository] - so repointing a binding is a wiring change
 * rather than a domain change. Every value is built here exactly as
 * [mapUnifiedWorkshopDetail] and `PublishedFileDetails.toWorkshopSummary` build it for the same data,
 * including the enum and state mapping and the epoch-**seconds** timestamps, because the SDK reports
 * milliseconds.
 *
 * The SDK reports "no Workshop provider", "no session", and "this operation is not implemented yet" as an
 * `IllegalStateException` rather than a nullable answer. Every read below translates that into the
 * contract's own unavailable answer: null for a method the contract already marks nullable, an empty page or
 * interaction for one it does not, and no throw ever crosses this class.
 *
 * This class is deliberately not bound into the dependency graph yet. The contracts still resolve to kSteam,
 * so nothing in the app behaves differently while this unit is reviewed.
 */
internal class SteamContentSdkWorkshopRepository
    @Inject
    constructor(
        private val engine: SteamContentSdkEngine,
        private val credentialStore: EncryptedSteamCredentialStore,
    ) : SteamUnifiedWorkshopRepository,
        AccountWorkshopRepository,
        SteamPlaytimeRepository {
        /**
         * Constructs the adapter and the one engine and credential store it reads from the platform context.
         *
         * This is the no-graph form of the constructor above: it builds the same engine the download path
         * builds, so the adapter is usable before it has a binding.
         */
        constructor(context: Context) : this(
            engine = SteamContentSdkEngine(context, SteamDeviceSeedStore(context)),
            credentialStore = EncryptedSteamCredentialStore(context),
        )

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
         * GAP(getPublicCollectionChildren): the SDK exposes no collection-children read.
         *
         * kSteam reads `children[].publishedfileid`; the SDK's item field table ends at `FAVOURITED=16` and
         * the core summary and detail structs carry no children at all.
         */
        override suspend fun getPublicCollectionChildren(collectionId: Long): List<Long>? = null

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
                // GAP(comment owner): the SDK request carries no owner at all - "comments take no
                // authorization of their own" - so the owner id is only the caller-side thread label kSteam
                // sends as the request's steamid.
                runCatchingUnavailable {
                    mutex.withLock {
                        val page =
                            engine.workshopComments(
                                itemId = workshopId,
                                // GAP(comments order): the SDK request carries no caller order, so its default
                                // NEWEST_FIRST is the only reading; kSteam passes no order either.
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
                        // GAP(browseCollection list): the SDK has no "voted" personal list, so that collection
                        // answers the empty page below rather than another account's list.
                        val list = query.collection.sdkPersonalList()
                            ?: error("The Steam Content SDK has no personal list for this collection")
                        val normalized = query.normalizedForSdk()
                        mutex.withLock {
                            val page =
                                engine.workshopPersonalItems(
                                    accountId = accountId,
                                    list = list,
                                    pageSize = normalized.pageSize,
                                    pageOffset = normalized.pageOffset(),
                                )
                            page.use { readWorkshopPage(it, normalized.page) }
                        }
                    }
                loaded ?: emptyWorkshopPage(query)
            }

        /**
         * GAP(resolveAuthorDisplayName): the SDK's AUTHOR field is an account id, not a name.
         *
         * kSteam resolves the creator's persona through `player.GetPlayerLinkDetails`; the SDK has no persona
         * read and no name-bearing copy ordinal for a Workshop item.
         */
        override suspend fun resolveAuthorDisplayName(workshopId: Long): String? = null

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
         * GAP(postComment): the SDK has no comment-write entry point.
         *
         * Every Workshop operation the AAR exposes is a read or an interaction toggle and there is no
         * comment-write bit in its capability mask, so this answers without doing anything.
         */
        override suspend fun postComment(
            workshopId: Long,
            ownerId: String,
            text: String,
        ) = Unit

        // ------------------------------------------------------------------
        // SteamPlaytimeRepository
        // ------------------------------------------------------------------

        /**
         * GAP(getAppPlaytime): the SDK exposes no player or owned-games read.
         *
         * kSteam reads `player.GetOwnedGames`; the SDK's surface is session, Workshop, download, manifest, and
         * chunk only, so there is no playtime to report.
         */
        override suspend fun getAppPlaytime(appId: Int): SteamAppPlaytime? = null

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
            val summary = readWorkshopSummary(summaryFieldsOf(result), result)
            val fileSize = result.field(WorkshopItemField.FILE_SIZE)
            return WorkshopDetail(
                summary = summary,
                description = result.text(WorkshopTextField.DESCRIPTION).orEmpty().trim(),
                fileSizeBytes = fileSize.valueOrZero().takeIf { it > 0L },
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

        /** Reads the summary rows a resolved item reports. */
        private fun summaryFieldsOf(result: WorkshopResult): SdkSummaryFields =
            SdkSummaryFields(
                LongArray(SdkSummaryFields.SIZE).also { values ->
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
                LongArray(SdkSummaryFields.SIZE).also { values ->
                    SdkSummaryFields.ORDINALS.forEachIndexed { slot, ordinal ->
                        values[slot] = page.field(index, ordinal).value
                    }
                }
            )

        /** Builds the app summary shared by the detail and page paths. */
        private fun readWorkshopSummary(
            fields: SdkSummaryFields,
            result: WorkshopResult,
        ): WorkshopSummary =
            summaryOf(
                fields = fields,
                title = result.text(WorkshopTextField.TITLE).orEmpty(),
                previewUrl = result.text(WorkshopTextField.PREVIEW_URL),
            )

        /** Builds the app summary of one page row, closing over the page it reads text from. */
        private fun readWorkshopPage(
            page: SdkWorkshopPage,
            requestedPage: Int,
        ): WorkshopPage {
            val size = page.size()
            val state = page.state()
            val items =
                (0 until size).map { index ->
                    val fields = summaryFieldsOf(page, index)
                    summaryOf(
                        fields = fields,
                        title = page.text(index, WorkshopTextField.TITLE).orEmpty(),
                        previewUrl = page.text(index, WorkshopTextField.PREVIEW_URL),
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

        /** One summary in the app's model, from rows both the detail and page paths report the same way. */
        private fun summaryOf(
            fields: SdkSummaryFields,
            title: String,
            previewUrl: String?,
        ): WorkshopSummary {
            val trimmedTitle = title.trim()
            val creatorId = fields.at(SdkSummaryFields.AUTHOR).takeIf { it > 0L }?.toString()
            return WorkshopSummary(
                id = fields.at(SdkSummaryFields.ITEM),
                title = trimmedTitle,
                // AUTHOR is an account id and the SDK has no persona read, so no name can be filled in; the
                // placeholder is the reading the kSteam path itself produces before profile resolution.
                author = "",
                creatorId = creatorId,
                authorAvatarUrl = null,
                previewUrl = previewUrl?.trim()?.takeIf(String::isNotBlank),
                type = fields.at(SdkSummaryFields.CONTENT_TYPE).toWorkshopType(),
                // Only TAG_COUNT exists; no copy ordinal carries tag text.
                tags = emptyList(),
                subscriptions = fields.at(SdkSummaryFields.SUBSCRIPTIONS).takeIf { it > 0L },
                favorites = fields.at(SdkSummaryFields.FAVOURITES).takeIf { it > 0L },
                // No views field exists in the SDK's ordinal table.
                views = null,
                // FILE_SIZE is a details-only row, so a page row leaves the size absent.
                fileSizeBytes = null,
                // SUBSCRIBED/FAVOURITED are details-only rows too, so a page row cannot report either state.
                subscriptionState = SubscriptionState.UNKNOWN,
                favoriteState = FavoriteState.UNKNOWN,
                isTitlePlaceholder = trimmedTitle.isEmpty(),
                authorPlaceholder =
                    if (creatorId == null) {
                        WorkshopAuthorPlaceholder.NONE
                    } else {
                        WorkshopAuthorPlaceholder.USER_WITH_ID
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
            val comments =
                (0 until size).mapNotNull { index ->
                    val text = page.text(index, WorkshopCommentField.BODY).orEmpty().trim()
                    if (text.isEmpty()) return@mapNotNull null
                    val authorId = page.field(index, WorkshopCommentField.AUTHOR).value.takeIf { it > 0L }
                    WorkshopComment(
                        // AUTHOR is an account id and no persona read exists, so the label stays blank.
                        author = "",
                        text = text,
                        authorId = authorId?.toString(),
                        avatarUrl = null,
                        // The one field this adapter can derive: the comment's author is the thread owner.
                        isCreator = authorId != null && authorId == creatorId,
                        timestamp = page.field(index, WorkshopCommentField.CREATED_AT).epochSeconds(),
                        dateLabel = null,
                        // A resolved author id with no persona behind it is exactly the placeholder reading.
                        isAuthorPlaceholder = authorId != null,
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
        // Account identity
        // ------------------------------------------------------------------

        /**
         * The signed-in account id, derived from the stored refresh token the SDK session persisted.
         *
         * A personal read and a mutation both need the acting account as an identifier, and the app's session
         * state carries none, so it is read from the one place the app keeps it: the same token whose `sub`
         * claim the legacy-credential migration already reads. An unreadable or unparseable token answers null
         * and the caller reports the operation as unavailable.
         */
        private fun signedInAccountId(): Long? {
            val token = credentialStore.load()?.refreshToken?.takeIf(String::isNotBlank) ?: return null
            return steamIdFromRefreshToken(token)?.longId?.takeIf { it > 0L }
        }
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

/** Maps the app's sort onto the one SDK ranking it has; an unmapped sort falls back to relevance. */
private fun WorkshopSort.sdkSort(): Int =
    when (this) {
        WorkshopSort.TRENDING -> io.steamcontent.sdk.WorkshopSort.MOST_POPULAR
        WorkshopSort.MOST_RECENT -> io.steamcontent.sdk.WorkshopSort.MOST_RECENT
        WorkshopSort.TOP_RATED -> io.steamcontent.sdk.WorkshopSort.RELEVANCE
        WorkshopSort.MOST_SUBSCRIBERS -> io.steamcontent.sdk.WorkshopSort.MOST_SUBSCRIBED
        // No SDK ranking exists for votes, friends' favourites, or friends' creations.
        WorkshopSort.MOST_VOTES,
        WorkshopSort.FRIENDS_FAVORITES,
        WorkshopSort.FRIENDS_CREATED,
        -> io.steamcontent.sdk.WorkshopSort.RELEVANCE
    }

/** Maps the SDK's content-type ordinal onto the app's type; only the video ordinal names one. */
private fun Long.toWorkshopType(): WorkshopType =
    if (this == WorkshopContentType.VIDEO.toLong()) WorkshopType.VIDEO else WorkshopType.UNKNOWN

/** Maps the app's collection onto the SDK's personal list; the voted list has no SDK ordinal. */
private fun AccountWorkshopCollection.sdkPersonalList(): Int? =
    when (this) {
        AccountWorkshopCollection.SUBSCRIPTIONS -> WorkshopPersonalList.SUBSCRIBED
        AccountWorkshopCollection.FAVORITES -> WorkshopPersonalList.FAVORITED
        // kSteam reads `type="myvotes"`; the SDK publishes no matching ordinal, and answering with another
        // list would report the wrong items rather than none.
        AccountWorkshopCollection.VOTED -> null
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

/** The Wallpaper Engine App the app's Workshop reads are scoped to, as the kSteam path scopes them. */
private const val WALLPAPER_ENGINE_APP_ID = 431_960
private const val MAX_SDK_COMMENT_PAGE_SIZE = 50
private const val MAX_SDK_PAGE_SIZE = 50
private const val MAX_SDK_SEARCH_LENGTH = 128
private const val MAX_SDK_TAGS = 48
private const val MAX_SDK_DIRECT_BROWSE_PAGE = 1_000
