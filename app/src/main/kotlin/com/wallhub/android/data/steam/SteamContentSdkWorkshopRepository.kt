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
import com.wallhub.android.core.model.matchesSteamWallpaperPage
import com.wallhub.android.core.model.normalizedCreatedRange
import com.wallhub.android.core.model.normalizedRequiredTagGroups
import com.wallhub.android.core.model.steamTagCriteria
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * `IllegalStateException` rather than a nullable answer. Nullable reads and interaction fallbacks retain their
 * contract-specific unavailable answers; account collection browsing propagates the failure so Library retry
 * and error state can distinguish it from an authoritative empty collection.
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
        /** Native handles remain single-lane, but foreground reads now jump queued background discovery work. */
        private val mutex = Mutex()
        private val reads =
            SteamWorkshopReadCoordinator(
                maxConcurrent = 1,
                onTiming = { timing ->
                    Log.i(
                        TAG,
                        "read kind=${timing.kind} priority=${timing.priority} outcome=${timing.outcome} " +
                            "queueMs=${timing.queueMillis} runMs=${timing.runMillis}",
                    )
                },
            )
        private val recovery =
            SteamSessionRequestRecovery(
                object : RecoverableSteamSession {
                    override fun generation(): SteamSessionGeneration? =
                        engine.currentSessionGeneration()?.let(::SteamSessionGeneration)

                    override suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean =
                        engine.reconnectSessionIfCurrent(expected.value)
                },
            )
        private val accountCollectionPager = AccountCollectionPager()
        private val publicPageCache =
            SteamReadCache<WorkshopBrowseQuery, WorkshopPage>(freshMillis = 120_000L, staleMillis = 600_000L)
        private val accountPageCache =
            SteamReadCache<AccountWorkshopQuery, WorkshopPage>(freshMillis = 120_000L, staleMillis = 300_000L)
        private val personaCache by lazy {
            SteamPersonaCache(
                loader = SteamPersonaLoader { accountIds ->
                    sdkRead(
                        key = "profiles:${accountIds.sorted().joinToString(",")}",
                        kind = WorkshopReadKind.PROFILE,
                        priority = WorkshopReadPriority.BACKGROUND,
                    ) {
                        readPlayerProfiles(accountIds).mapValues { (_, profile) ->
                            SteamProfile(profile.personaName, profile.avatarUrl)
                        }
                    }
                },
            )
        }

        // ------------------------------------------------------------------
        // SteamUnifiedWorkshopRepository
        // ------------------------------------------------------------------

        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? =
            withContext(Dispatchers.IO) {
                val normalized = query.normalizedForSdk()
                publicPageCache.getFresh(normalized)?.let { return@withContext it }
                val loaded = runCatchingUnavailable {
                    awaitSignedInSession()
                    val creatorId = normalized.creatorId?.toLongOrNull()?.takeIf { it > 0L }
                    sdkRead(
                        key = "browse:${normalized.hashCode()}",
                        kind = WorkshopReadKind.SEARCH,
                        priority =
                            if (normalized.sort == WorkshopSort.FRIENDS_FAVORITES ||
                                normalized.sort == WorkshopSort.FRIENDS_CREATED
                            ) {
                                WorkshopReadPriority.FOREGROUND
                            } else {
                                WorkshopReadPriority.BACKGROUND
                            },
                    ) {
                        val page =
                            if (creatorId == null) {
                                engine.workshopSearch(
                                    appId = WALLPAPER_ENGINE_APP_ID.toLong(),
                                    sort = normalized.sort.sdkSort(),
                                    // Only DEFAULT is accepted by this release, so the search's own trend
                                    // window is what answers; the app's `days` has no SDK parameter.
                                    trendWindow = normalized.days.toSdkTrendWindow(),
                                    pageSize = normalized.pageSize,
                                    pageOffset = normalized.pageOffset(),
                                    queryText = normalized.steamSearchText().takeIf(String::isNotBlank),
                                    // The type is sent as tags and never as a content-type filter. The query's
                                    // own type selection already reaches the service that way, because
                                    // `sdkExcludedTags()` excludes every type tag the user did not select, and
                                    // "Video" is a real Steam tag on these items - the same tags the Library
                                    // types its rows from. The CM provider refuses a content-type filter on
                                    // purpose, since the enum naming the service's file-type numbers is outside
                                    // the approved schema closure, so sending the video ordinal made every
                                    // video-narrowed browse fail outright instead of narrowing it. An empty list
                                    // is "no content-type filter", which is what the service answers with.
                                    contentTypes = emptyList(),
                                    tags = normalized.sdkTags(),
                                    // Both halves of the product's filter travel: the service excludes what
                                    // the user did not select, so a page arrives already narrowed and stays
                                    // full. The client-side gate below is the backstop, not the mechanism.
                                    excludedTags = normalized.sdkExcludedTags(),
                                    // The window the product's filter names, the language its own catalogue
                                    // asks for, and the trend window its day selection means: three fields the
                                    // service answers with rather than dimensions the page has to be trimmed by.
                                    createdAfter = normalized.sdkCreatedAfter(),
                                    createdBefore = normalized.sdkCreatedBefore(),
                                    // The query's own language, not the phone's: the field is the one the
                                    // caller states, and its default is already the locale's. Passing the
                                    // locale here would silently ignore a query that names another language.
                                    language = normalized.language.coerceAtLeast(0),
                                    // Tag groups are alternatives per group, which is how a discover route
                                    // states its genres; the service satisfies at least one tag of each.
                                    tagGroups = normalized.normalizedRequiredTagGroups(),
                                )
                            } else {
                                engine.workshopAuthorItems(
                                    authorId = creatorId,
                                    pageSize = normalized.pageSize,
                                    pageOffset = normalized.pageOffset(),
                                    // The author read is `GetUserFiles`, which has no content-type dimension at
                                    // all, so the ordinal that used to be named here was silently ignored. None
                                    // is sent, and the type still narrows the page through the tag criteria.
                                    contentTypes = emptyList(),
                                )
                            }
                        page.use { readWorkshopPage(it, normalized.page, filter = normalized) }
                    }
                }
                if (loaded != null) {
                    publicPageCache.put(normalized, loaded)
                    loaded
                } else {
                    publicPageCache.getStale(normalized)
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
                    sdkRead(
                        key = "collection:$collectionId",
                        kind = WorkshopReadKind.DETAILS,
                        priority = WorkshopReadPriority.FOREGROUND,
                    ) {
                        engine
                            .openWorkshopItem(collectionId, WALLPAPER_ENGINE_APP_ID.toLong())
                            .use { result ->
                                val children = readCollectionChildren(result)
                                // A collection's children travel on the details response, so the reading this
                                // owes is the count and the ids the service put there - the same one-line shape
                                // the interaction reads use. Null is the unavailable answer, not an empty list.
                                Log.i(
                                    TAG,
                                    "workshop collection children id=$collectionId count=${children?.size} " +
                                        "ids=${children?.take(MAX_LOGGED_CHILDREN)}",
                                )
                                children
                            }
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
                    sdkRead(
                        key = "comments:$workshopId:$pageStart:$pageCount",
                        kind = WorkshopReadKind.COMMENTS,
                        priority = WorkshopReadPriority.FOREGROUND,
                    ) {
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
                val normalized = query.normalized()
                accountPageCache.getFresh(normalized)?.let { return@withContext it }
                try {
                    awaitSignedInSession()
                    val accountId = signedInAccountId() ?: error("No signed-in Steam account is available")
                val list = normalized.collection.sdkPersonalList()
                val loaded = if (!normalized.hasInteractiveFilters()) {
                    val source =
                        loadPersonalCollectionPage(accountId, list, normalized, normalized.page, normalized.pageSize)
                    source.copy(items = source.items.filter(normalized::matchesAccountCollectionItem))
                } else {
                    val generation =
                        engine.currentSessionGeneration()
                            ?: error("No signed-in Steam session generation is available")
                    accountCollectionPager.page(
                        scope = AccountCollectionScope(accountId, generation),
                        query = normalized,
                    ) { sourcePage, sourcePageSize ->
                        loadPersonalCollectionPage(accountId, list, normalized, sourcePage, sourcePageSize)
                    }
                }
                    accountPageCache.put(normalized, loaded)
                    loaded
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    accountPageCache.getStale(normalized) ?: throw failure
                }
            }

        /** One personal-list operation; SDK unavailability remains a failure for Library retry/UI. */
        private suspend fun loadPersonalCollectionPage(
            accountId: Long,
            list: Int,
            query: AccountWorkshopQuery,
            sourcePage: Int,
            sourcePageSize: Int,
        ): WorkshopPage =
            sdkRead(
                key = "account:${query.collection}:$sourcePage:$sourcePageSize",
                kind = WorkshopReadKind.ACCOUNT_COLLECTION,
                priority = WorkshopReadPriority.FOREGROUND,
            ) {
                val page =
                    engine.workshopPersonalItems(
                        accountId = accountId,
                        list = list,
                        pageSize = sourcePageSize,
                        pageOffset = pageOffsetOf(sourcePage, sourcePageSize),
                        // A personal read is answered within one App's Workshop: without the scope the
                        // service omits each row's own tags and file type.
                        appId = WALLPAPER_ENGINE_APP_ID.toLong(),
                    )
                page.use { readWorkshopPage(it, sourcePage, query.collection) }
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
                    val authorId =
                        sdkRead(
                            key = "author-id:$workshopId",
                            kind = WorkshopReadKind.DETAILS,
                            priority = WorkshopReadPriority.BACKGROUND,
                        ) {
                            engine.openWorkshopItem(workshopId, WALLPAPER_ENGINE_APP_ID.toLong()).use { result ->
                                result.field(WorkshopItemField.AUTHOR).valueOrZero().takeIf { it > 0L }
                            }
                        }
                    authorId?.let { personaCache.get(it)?.displayName }
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
                // The write is a mutation of the operator's own Steam state, so the line names the item, the
                // direction asked for, and what the service confirmed - a refused write must not read as one.
                Log.i(
                    TAG,
                    "workshop subscribe write id=$workshopId ask=$subscribed " +
                        "confirmed=${confirmed?.enabled}",
                )
                if (confirmed == null) {
                    WorkshopInteraction()
                } else {
                    accountPageCache.invalidateAll()
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
                Log.i(
                    TAG,
                    "workshop favorite write id=$workshopId ask=$favorited " +
                        "confirmed=${confirmed?.enabled}",
                )
                if (confirmed == null) {
                    WorkshopInteraction()
                } else {
                    accountPageCache.invalidateAll()
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
                val playtime =
                    runCatchingUnavailable {
                        sdkRead(
                            key = "playtime:$appId",
                            kind = WorkshopReadKind.PLAYTIME,
                            priority = WorkshopReadPriority.FOREGROUND,
                        ) { engine.playerPlaytime(appId.toLong()) }
                    }
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

        /**
         * Resolves one item's detail, or null when the SDK cannot serve the read.
         *
         * The item is named on the failure line: the contract's null answer carries no reason, and the SDK's
         * typed failure is logged with this id so a device reading can tell which item a refusal was about.
         */
        private suspend fun loadWorkshopDetail(workshopId: Long): WorkshopDetail? {
            val detail =
                runCatchingUnavailable {
                    awaitSignedInSession()
                    require(workshopId > 0L) { "Invalid Workshop item ID" }
                    sdkRead(
                        key = "detail:$workshopId",
                        kind = WorkshopReadKind.DETAILS,
                        priority = WorkshopReadPriority.FOREGROUND,
                    ) {
                        engine.openWorkshopItem(workshopId, WALLPAPER_ENGINE_APP_ID.toLong()).use { result ->
                            readWorkshopDetail(result)
                        }
                    }
                }
            if (detail == null) {
                Log.w(TAG, "workshop detail unavailable id=$workshopId")
            }
            return detail
        }

        /** Builds one detail from a resolved item, reading each field the app model needs once. */
        private fun readWorkshopDetail(result: WorkshopResult): WorkshopDetail {
            val fields = summaryFieldsOf(result)
            val summary =
                summaryOf(
                    fields = fields,
                    title = result.text(WorkshopTextField.TITLE).orEmpty(),
                    previewUrl = result.text(WorkshopTextField.PREVIEW_URL),
                    tags = readResultTags(result, fields),
                    // Persona enrichment is loaded independently after the detail body is visible.
                    profile = null,
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
            filter: WorkshopBrowseQuery? = null,
        ): WorkshopPage {
            val size = page.size()
            val state = page.state()
            val rows = (0 until size).map { index -> summaryFieldsOf(page, index) }
            val items =
                rows.mapIndexed { index, fields ->
                    summaryOf(
                        fields = fields,
                        title = page.text(index, WorkshopTextField.TITLE).orEmpty(),
                        previewUrl = page.text(index, WorkshopTextField.PREVIEW_URL),
                        tags = readPageTags(page, index, fields),
                        // Persona enrichment is intentionally deferred; page rows become visible immediately.
                        profile = null,
                        // A page row has no resolved creator behind it, so the id-bearing placeholder stands
                        // in, exactly as the removed kSteam summary mapper left it.
                        unresolvedPlaceholder = WorkshopAuthorPlaceholder.USER_WITH_ID,
                        collection = collection,
                    )
                }
            // The service answers the tags a request names, not a product's whole filter vocabulary, so the same
            // client-side gate the community and Web API sources apply decides what this page may show: an
            // `asset` row, a rating the account did not allow, or a genre outside the selection is not a
            // wallpaper this query asked for. Filtering after the page is exactly what those sources do.
            val visible = filter?.let { query -> items.filter { item -> query.matchesSteamWallpaperPage(item) } } ?: items
            // A page the gate shortened is the only case a reader cannot see from the row count alone, so the
            // first rejected row's own reading is on the line: which row, which type, which tags.
            val rejected = items.firstOrNull { item -> item !in visible }
            // One line per non-empty page, so a device reading can name what the SDK actually put on its
            // rows (tags, the mapped content type, and the size) without exposing anything but public item
            // metadata: the same evidence convention the session, depot, and playback paths follow. The raw
            // tag count and content-type ordinal are on the line too, so an untyped row says whether the
            // service sent no tags or sent tags this adapter could not read.
            if (visible.isNotEmpty()) {
                val first = visible.first()
                Log.i(
                    TAG,
                    "workshop page rows=${visible.size} of ${items.size} collection=" +
                        "${collection?.name ?: "public"} " +
                        rejected?.let { row -> "rejected-id=${row.id} rejected-type=${row.type} " +
                            "rejected-tags=${row.tags.take(MAX_LOGGED_TAGS)} " }.orEmpty() +
                        "first-id=${first.id} first-type=${first.type} first-size=${first.fileSizeBytes ?: 0} " +
                        "first-tag-count=${rows.first().tagCount()} " +
                        "first-content-type=${rows.first().at(SdkSummaryFields.CONTENT_TYPE)} " +
                        "first-tags=${first.tags.take(MAX_LOGGED_TAGS)}",
                )
            }
            val total = state.total.takeIf { it > 0 }
            return WorkshopPage(
                items = visible,
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
            return engine.openWorkshopItem(workshopId, WALLPAPER_ENGINE_APP_ID.toLong()).use { result ->
                val subscribed = subscriptionStateOf(result.field(WorkshopItemField.SUBSCRIBED))
                val favorited = favoriteStateOf(result.field(WorkshopItemField.FAVOURITED))
                // The detail screen draws a hollow star for both "not favourited" and "the read did not
                // answer", so a screen alone cannot say which. One line per read makes the two states - and
                // the field states behind them - readable, and the ids are the public catalog numbers a
                // reading already logs elsewhere.
                Log.i(
                    TAG,
                    "workshop interaction id=$workshopId subscribed=$subscribed favorited=$favorited " +
                        "subscribed-field=${result.field(WorkshopItemField.SUBSCRIBED).state} " +
                        "subscribed-value=${result.field(WorkshopItemField.SUBSCRIBED).value} " +
                        "favorited-field=${result.field(WorkshopItemField.FAVOURITED).state} " +
                        "favorited-value=${result.field(WorkshopItemField.FAVOURITED).value}",
                )
                WorkshopInteraction(
                    subscriptionState = subscribed,
                    favoriteState = favorited,
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
            val ids = accountIds.filter { it > 0L }.distinct()
            if (ids.isEmpty()) return emptyMap()
            // The SDK refuses a read of more than its own ceiling, and the refusal aborts whatever asked for the
            // profiles - on a browse page that is the whole page. One page holds more authors than the ceiling,
            // so the set is read in bounded slices and the answers are merged; a slice that fails contributes
            // nothing, which is the same placeholder reading a failed single read leaves.
            return ids.chunked(MAX_SDK_PROFILE_ACCOUNTS).flatMap { slice ->
                runCatchingUnavailable {
                    engine.openPlayerProfiles(slice.toLongArray()).use { profiles -> readProfiles(profiles) }
                }.orEmpty().entries
            }.associate { entry -> entry.key to entry.value }
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

        private suspend fun <T> sdkRead(
            key: String,
            kind: WorkshopReadKind,
            priority: WorkshopReadPriority,
            operation: suspend () -> T,
        ): T =
            reads.read(key, kind, priority) {
                recovery.execute { mutex.withLock { operation() } }
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

        /**
         * Waits, briefly, for the engine's own session to be signed in.
         *
         * The engine restores its session while a screen asks for its first page, and a workshop read that
         * arrives before it is ready is refused *before it is sent* - a typed local refusal, not a wait, so the
         * page it was serving reads as a failure and a screen that does not retry stays empty. This is the one
         * bounded wait that turns that race into a page; it returns at once once the session answers.
         */
        private suspend fun awaitSignedInSession() {
            if (signedInAccountId() != null) return
            repeat(SESSION_READY_ATTEMPTS) {
                delay(SESSION_READY_DELAY_MS)
                if (signedInAccountId() != null) return
            }
        }
    }

/**
 * Runs an SDK call and answers null when the engine has no provider, no session, or no such operation.
 *
 * The typed failure is logged before it becomes null: the contract's null/empty answers carry no reason, so
 * without this line a failed read is indistinguishable from an absent capability on the device. The text is
 * the SDK's own redacted error, and it names no credential, token, or URL.
 *
 * A cancelled read is not an unavailable read. `JobCancellationException` is itself an
 * `IllegalStateException`, so catching that alone swallowed every cancellation: it stopped the cancellation
 * from propagating, and it logged a failure for a read the caller had simply abandoned, with a minified job
 * class name as the reason (`ne5 was cancelled`). Cancellation is rethrown before the handler below.
 */
private inline fun <T> runCatchingUnavailable(block: () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalStateException) {
        Log.w(TAG, "workshop read unavailable: ${error.message}")
        null
    }

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

/** The absolute cursor the SDK request takes, which the app expresses as a one-based page. */
private fun WorkshopBrowseQuery.pageOffset(): Int = pageOffsetOf(page, pageSize)

private fun pageOffsetOf(
    page: Int,
    pageSize: Int,
): Int {
    val offset = (page.coerceAtLeast(1).toLong() - 1L) * pageSize.coerceAtLeast(1).toLong()
    return offset.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** The tag filter the SDK request takes; its empty list means no tag filter at all. */
private fun WorkshopBrowseQuery.sdkTags(): List<String> = steamTagCriteria().requiredTags

/** The tags the filter vocabulary names as excluded, which the service now excludes for us. */
private fun WorkshopBrowseQuery.sdkExcludedTags(): List<String> = steamTagCriteria().excludedTags

/** The creation window's lower bound in epoch seconds, or zero for no bound. */
private fun WorkshopBrowseQuery.sdkCreatedAfter(): Long =
    normalizedCreatedRange()?.first?.coerceAtLeast(0L) ?: 0L

/** The creation window's upper bound in epoch seconds, or zero for no bound. */
private fun WorkshopBrowseQuery.sdkCreatedBefore(): Long =
    normalizedCreatedRange()?.last?.coerceAtLeast(0L) ?: 0L

/**
 * The trend window the product's day selection means, as the ABI's own vocabulary.
 *
 * The ABI names day, week, month, and year rather than a count of days, so a selection that is not exactly one
 * of those maps onto the nearest window it does not exceed; zero days is every time the service holds.
 */
private fun Int.toSdkTrendWindow(): Int =
    when {
        this <= 0 -> WorkshopTrendWindow.ALL_TIME
        this == 1 -> WorkshopTrendWindow.DAY
        this <= 7 -> WorkshopTrendWindow.WEEK
        this <= 30 -> WorkshopTrendWindow.MONTH
        else -> WorkshopTrendWindow.YEAR
    }

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

/** Most child ids one reading logs; a collection can hold more than a line should carry. */
private const val MAX_LOGGED_CHILDREN = 12

/** How many tag names one diagnostic page line may carry: enough to name the type, no more. */
private const val MAX_LOGGED_TAGS = 8

/**
 * How many accounts one SDK profile read may name.
 *
 * The SDK's own ceiling is sixteen (`sc_content::player::MAX_PLAYER_ACCOUNT_IDS`), and the binding refuses a
 * longer list before the call leaves the process, so this mirrors it rather than guessing.
 */
private const val MAX_SDK_PROFILE_ACCOUNTS = 16

/** How long a workshop read waits for the engine's session to be signed in, and how often it looks. */
private const val SESSION_READY_ATTEMPTS = 20
private const val SESSION_READY_DELAY_MS = 250L

private const val MAX_SDK_COMMENT_PAGE_SIZE = 50
private const val MAX_SDK_PAGE_SIZE = 50
private const val MAX_SDK_SEARCH_LENGTH = 128
private const val MAX_SDK_TAGS = 48

/** The most tag copies this adapter reads from one item, well past any list the service reports. */
private const val MAX_SDK_ITEM_TAGS = 256
private const val MAX_SDK_DIRECT_BROWSE_PAGE = 1_000
