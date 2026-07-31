package com.wallhub.android.data.steam

import android.content.Context
import com.wallhub.android.core.database.AppPreferencesStore
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val JAVA_STEAM_CM_COMPONENT = "CMClient"

internal data class PersistedSteamCredential(
    val accountName: String,
    val refreshToken: String,
)

/**
 * Owns the live JavaSteam CM session. Passwords and Steam Guard codes only exist in the
 * active authentication request; the encrypted store contains a refresh token only.
 */
@Singleton
class SecureSteamSessionRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        internal val diagnostics: DiagnosticRepository,
        internal val clientFactory: SteamHttpClientFactory,
        internal val preferencesStore: AppPreferencesStore,
    ) : SteamSessionRepository,
        SteamContentCredentialProvider,
        AccountWorkshopRepository,
        SteamUnifiedWorkshopRepository {
        internal val credentialStore = EncryptedSteamCredentialStore(context.applicationContext)
        internal val steamServerListProvider = SteamWebSocketServerListProvider()
        internal val authorDisplayNames = ConcurrentHashMap<Long, String>()
        internal val steamProfiles = ConcurrentHashMap<Long, SteamProfile>()
        internal val pendingPersonaProfiles = ConcurrentHashMap<Long, CompletableDeferred<SteamProfile>>()
        internal val mutableSession = MutableStateFlow(SteamSessionState())
        internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        internal val lifecycleLock = Any()
        internal val requestMutex = Mutex()
        internal val anonymousSessionMutex = Mutex()
        internal val credentialMutex = Mutex()
        internal val pendingCode = AtomicReference<CompletableFuture<String>?>(null)
        internal val nextSessionId = AtomicLong(0L)
        private val javaSteamLogListener = createJavaSteamLogListener()

        internal var sessionGeneration = 0L

        @Volatile
        internal var authenticatedSession: SteamClientSession? = null

        @Volatile
        internal var anonymousSession: SteamClientSession? = null

        @Volatile
        internal var authenticationJob: Job? = null

        @Volatile
        internal var pendingLogin: PendingLogin? = null

        init {
            LogManager.addListener(javaSteamLogListener)
        }

        override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

        private fun createJavaSteamLogListener(): LogListener =
            object : LogListener {
                override fun onLog(
                    clazz: Class<*>,
                    message: String?,
                    throwable: Throwable?,
                ) = Unit

                override fun onError(
                    clazz: Class<*>,
                    message: String?,
                    throwable: Throwable?,
                ) {
                    if (clazz.simpleName != JAVA_STEAM_CM_COMPONENT) return
                    serviceScope.launch {
                        diagnostics.record(
                            DiagnosticEvent(
                                source = "steam-session",
                                level = DiagnosticLevel.WARNING,
                                message = "JavaSteam CM error",
                                attributes =
                                    mapOf(
                                        "component" to clazz.simpleName,
                                        "operation" to message.orEmpty(),
                                        "error" to throwable?.javaClass?.simpleName.orEmpty(),
                                        "detail" to throwable?.message.orEmpty(),
                                    ),
                            ),
                        )
                    }
                }
            }

        override fun restorePersistedSession() {
            val job =
                synchronized(lifecycleLock) {
                    if (authenticatedSession?.isUsable == true || authenticationJob?.isActive == true) return
                    val generation = ++sessionGeneration
                    serviceScope
                        .launch(start = CoroutineStart.LAZY) {
                            try {
                                restorePersistedSessionInternal(generation)
                            } finally {
                                clearAuthenticationJob(generation)
                            }
                        }.also { authenticationJob = it }
                }
            job.start()
        }

        override fun login(
            accountName: String,
            password: String,
        ) {
            if (accountName.isBlank() || password.isBlank()) {
                setState(
                    phase = SteamSessionPhase.FAILED,
                    message = "请输入 Steam 用户名和密码",
                )
                return
            }
            startLogin(PendingLogin(accountName.trim(), password))
        }

        override fun submitSteamGuardCode(code: String) {
            val future = pendingCode.getAndSet(null)
            if (code.isBlank() || future == null || !future.complete(code.trim())) {
                setState(
                    phase = SteamSessionPhase.FAILED,
                    message = "当前没有等待中的 Steam Guard 验证请求",
                )
                return
            }
            setState(
                phase = SteamSessionPhase.SIGNING_IN,
                message = "已提交验证码，正在等待 Steam 确认…",
            )
        }

        override fun useManualSteamGuardFallback() {
            val activeLogin = pendingLogin
            if (activeLogin == null) {
                setState(
                    phase = SteamSessionPhase.FAILED,
                    message = "当前没有可切换为令牌码的登录请求，请重新输入账号和密码。",
                )
                return
            }
            startLogin(activeLogin.copy(preferManualCode = true))
        }

        override fun logout() {
            val (generation, authentication, activeSession) =
                synchronized(lifecycleLock) {
                    val nextGeneration = ++sessionGeneration
                    val activeAuthentication = authenticationJob
                    authenticationJob = null
                    pendingLogin = null
                    val active = authenticatedSession
                    authenticatedSession = null
                    Triple(nextGeneration, activeAuthentication, active)
                }
            authentication?.cancel()
            pendingCode.getAndSet(null)?.cancel(true)
            activeSession?.close()
            serviceScope.launch {
                credentialMutex.withLock {
                    if (isCurrentGeneration(generation)) credentialStore.clear()
                }
                setStateIfCurrent(
                    generation = generation,
                    phase = SteamSessionPhase.SIGNED_OUT,
                    message = "已退出 Steam 登录，并清除本机保存的登录状态。",
                )
            }
        }

        override suspend fun loadContentCredential(): SteamContentCredential? =
            withContext(Dispatchers.IO) {
                credentialMutex.withLock { credentialStore.load() }?.let { credential ->
                    SteamContentCredential(
                        accountName = credential.accountName,
                        refreshToken = credential.refreshToken,
                    )
                }
            }

        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? =
            withPublicSteamSession { steamSession ->
                val service = steamSession.unified.createService(PublishedFile::class.java)
                val response =
                    awaitSteamRpc(
                        steamSession = steamSession,
                        operation = "public_query_files",
                    ) {
                        val rpcResponse = service.queryFiles(buildUnifiedWorkshopBrowseRequest(query)).await()
                        check(rpcResponse.result == EResult.OK) {
                            "Steam PublishedFile.QueryFiles returned ${rpcResponse.result}"
                        }
                        rpcResponse.body.build()
                    }
                val page = mapUnifiedWorkshopBrowseResponse(query, response)
                if (!steamSession.isAuthenticated) return@withPublicSteamSession page
                val profiles =
                    resolveSteamProfiles(
                        steamSession = steamSession,
                        steamIds = page.items.mapNotNull { item -> item.creatorId?.toLongOrNull() }.toSet(),
                    )
                page.copy(
                    items =
                        page.items.map { item ->
                            item.copy(
                                author =
                                    item.creatorId
                                        ?.toLongOrNull()
                                        ?.let(profiles::get)
                                        ?.displayName ?: item.author,
                            )
                        },
                )
            }

        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? =
            withPublicSteamSession { steamSession ->
                val service = steamSession.unified.createService(PublishedFile::class.java)
                val response =
                    awaitSteamRpc(steamSession, "public_get_details") {
                        val rpcResponse = service.getDetails(buildUnifiedWorkshopDetailRequest(workshopId)).await()
                        check(rpcResponse.result == EResult.OK) {
                            "Steam PublishedFile.GetDetails returned ${rpcResponse.result}"
                        }
                        rpcResponse.body.build()
                    }
                val detail =
                    response.publishedfiledetailsList
                        .firstOrNull { item ->
                            item.publishedfileid == workshopId && item.result == EResult.OK.code()
                        }
                        ?: return@withPublicSteamSession null
                val profile =
                    if (steamSession.isAuthenticated) {
                        resolveSteamProfiles(steamSession, setOf(detail.creator))[detail.creator]
                    } else {
                        steamProfiles[detail.creator]
                    }
                mapUnifiedWorkshopDetail(detail, profile)
            }

        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? =
            withContext(Dispatchers.IO) {
                requestMutex.withLock {
                    val steamSession =
                        authenticatedSession?.takeIf { session ->
                            session.isUsable && session.isAuthenticated
                        } ?: return@withLock null
                    val service = steamSession.unified.createService(CommunityUnifiedService::class.java)
                    val response =
                        awaitSteamRpc(steamSession, "community_get_comment_thread") {
                            val rpcResponse =
                                service
                                    .getCommentThread(
                                        buildCommunityCommentRequest(workshopId, ownerId, start, count),
                                    ).await()
                            check(rpcResponse.result == EResult.OK) {
                                "Steam Community.GetCommentThread returned ${rpcResponse.result}"
                            }
                            rpcResponse.body.build()
                        }
                    val profiles =
                        resolveSteamProfiles(
                            steamSession = steamSession,
                            steamIds = response.commentsList.map { comment -> comment.steamid }.toSet(),
                        )
                    mapCommunityComments(
                        response = response,
                        requestedStart = start,
                        requestedCount = count,
                        creatorId = ownerId,
                        profiles = profiles,
                    )
                }
            }

        override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage =
            withAuthenticatedSteamSession { steamSession ->
                val normalized = query.normalized()
                val service = steamSession.unified.createService(PublishedFile::class.java)
                val steamId = steamSession.accountSteamId.await().convertToUInt64()

                suspend fun loadSourcePage(
                    sourcePage: Int,
                    sourcePageSize: Int,
                ) = awaitSteamRpc(
                    steamSession = steamSession,
                    operation = "library_get_user_files",
                ) {
                    service
                        .getUserFiles(
                            SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
                                .newBuilder()
                                .apply {
                                    steamid = steamId
                                    appid = WALLPAPER_ENGINE_APP_ID
                                    page = sourcePage
                                    numperpage = sourcePageSize
                                    type = normalized.collection.steamListType()
                                    sortmethod = "lastupdated"
                                    setReturnTags(true)
                                    setReturnPreviews(true)
                                    setReturnShortDescription(true)
                                }.build(),
                        ).await()
                        .body
                }

                val hasClientFilter =
                    normalized.searchText.isNotEmpty() ||
                        normalized.type != null ||
                        normalized.tags.isNotEmpty()
                if (!hasClientFilter) {
                    val response = loadSourcePage(normalized.page, normalized.pageSize)
                    val summaries =
                        response.publishedfiledetailsList
                            .asSequence()
                            .filter { detail -> detail.result == EResult.OK.code() }
                            .map { detail -> detail.toWorkshopSummary(normalized.collection) }
                            .toList()
                    return@withAuthenticatedSteamSession WorkshopPage(
                        items = summaries,
                        page = normalized.page,
                        hasNextPage =
                            response.total.toLong() >
                                normalized.page.toLong() * normalized.pageSize.toLong() ||
                                summaries.size >= normalized.pageSize,
                        totalCount = response.total.takeIf { it > 0 },
                    )
                }

                val requestedEndIndex = normalized.page.toLong() * normalized.pageSize.toLong()
                val matches = mutableListOf<WorkshopSummary>()
                var sourcePage = 1
                var sourceExhausted = false
                while (
                    !sourceExhausted &&
                    (normalized.resolveTotalCount || matches.size.toLong() <= requestedEndIndex) &&
                    sourcePage <= MAX_ACCOUNT_COLLECTION_FILTER_SOURCE_PAGES
                ) {
                    val response = loadSourcePage(sourcePage, MAX_ACCOUNT_WORKSHOP_PAGE_SIZE)
                    val details = response.publishedfiledetailsList
                    matches +=
                        details
                            .asSequence()
                            .filter { detail -> detail.result == EResult.OK.code() }
                            .map { detail -> detail.toWorkshopSummary(normalized.collection) }
                            .filter { summary -> normalized.matchesAccountCollectionItem(summary) }
                            .toList()
                    sourceExhausted =
                        (
                            response.total > 0 &&
                                sourcePage.toLong() * MAX_ACCOUNT_WORKSHOP_PAGE_SIZE >= response.total.toLong()
                        ) ||
                        details.size < MAX_ACCOUNT_WORKSHOP_PAGE_SIZE
                    sourcePage += 1
                }
                val selection =
                    selectAccountCollectionPage(
                        matches = matches,
                        page = normalized.page,
                        pageSize = normalized.pageSize,
                        sourceExhausted = sourceExhausted,
                    )
                WorkshopPage(
                    items = selection.items,
                    page = normalized.page,
                    hasNextPage = selection.hasNextPage,
                    totalCount = matches.size.takeIf { sourceExhausted },
                )
            }

        override suspend fun resolveAuthorDisplayName(workshopId: Long): String? {
            require(workshopId > 0L) { "创意工坊项目 ID 无效" }
            authorDisplayNames[workshopId]?.let { return it }
            return withAuthenticatedSteamSession { steamSession ->
                val service = steamSession.unified.createService(PublishedFile::class.java)
                val response =
                    awaitSteamRpc(steamSession, "author_get_workshop_details") {
                        val rpcResponse = service.getDetails(buildUnifiedWorkshopDetailRequest(workshopId)).await()
                        check(rpcResponse.result == EResult.OK) {
                            "Steam PublishedFile.GetDetails returned ${rpcResponse.result}"
                        }
                        rpcResponse.body.build()
                    }
                val creatorId =
                    response.publishedfiledetailsList
                        .firstOrNull { detail ->
                            detail.publishedfileid == workshopId && detail.result == EResult.OK.code()
                        }?.creator
                        ?.takeIf { it > 0L }
                        ?: return@withAuthenticatedSteamSession null
                resolveSteamProfiles(steamSession, setOf(creatorId))[creatorId]
                    ?.displayName
                    ?.also { displayName -> authorDisplayNames.putIfAbsent(workshopId, displayName) }
            }
        }

        override suspend fun getInteraction(workshopId: Long): WorkshopInteraction =
            withAuthenticatedSteamSession { steamSession ->
                readInteraction(steamSession, workshopId)
            }

        override suspend fun setSubscribed(
            workshopId: Long,
            subscribed: Boolean,
        ): WorkshopInteraction =
            withAuthenticatedSteamSession { steamSession ->
                require(workshopId > 0L) { "创意工坊项目 ID 无效" }
                val service = steamSession.unified.createService(PublishedFile::class.java)
                if (subscribed) {
                    awaitSteamRpc(steamSession, "subscribe") {
                        service
                            .subscribe(
                                SteammessagesPublishedfileSteamclient.CPublishedFile_Subscribe_Request
                                    .newBuilder()
                                    .setPublishedfileid(workshopId)
                                    .setAppid(WALLPAPER_ENGINE_APP_ID)
                                    .setListType(SUBSCRIPTION_LIST_TYPE)
                                    .setNotifyClient(true)
                                    .setIncludeDependencies(true)
                                    .build(),
                            ).await()
                    }
                } else {
                    awaitSteamRpc(steamSession, "unsubscribe") {
                        service
                            .unsubscribe(
                                SteammessagesPublishedfileSteamclient.CPublishedFile_Unsubscribe_Request
                                    .newBuilder()
                                    .setPublishedfileid(workshopId)
                                    .setAppid(WALLPAPER_ENGINE_APP_ID)
                                    .setListType(SUBSCRIPTION_LIST_TYPE)
                                    .setNotifyClient(true)
                                    .build(),
                            ).await()
                    }
                }
                readInteractionOrExpected(
                    steamSession = steamSession,
                    workshopId = workshopId,
                    expectedSubscription =
                        if (subscribed) {
                            SubscriptionState.SUBSCRIBED
                        } else {
                            SubscriptionState.NOT_SUBSCRIBED
                        },
                )
            }

        override suspend fun setFavorited(
            workshopId: Long,
            favorited: Boolean,
        ): WorkshopInteraction =
            withAuthenticatedSteamSession { steamSession ->
                require(workshopId > 0L) { "创意工坊项目 ID 无效" }
                val service = steamSession.unified.createService(PublishedFile::class.java)
                if (favorited) {
                    awaitSteamRpc(steamSession, "favorite") {
                        service
                            .addAppRelationship(
                                SteammessagesPublishedfileSteamclient.CPublishedFile_AddAppRelationship_Request
                                    .newBuilder()
                                    .setPublishedfileid(workshopId)
                                    .setAppid(WALLPAPER_ENGINE_APP_ID)
                                    .setRelationship(FAVORITE_RELATIONSHIP)
                                    .build(),
                            ).await()
                    }
                } else {
                    awaitSteamRpc(steamSession, "unfavorite") {
                        service
                            .removeAppRelationship(
                                SteammessagesPublishedfileSteamclient.CPublishedFile_RemoveAppRelationship_Request
                                    .newBuilder()
                                    .setPublishedfileid(workshopId)
                                    .setAppid(WALLPAPER_ENGINE_APP_ID)
                                    .setRelationship(FAVORITE_RELATIONSHIP)
                                    .build(),
                            ).await()
                    }
                }
                readInteractionOrExpected(
                    steamSession = steamSession,
                    workshopId = workshopId,
                    expectedFavorite = if (favorited) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED,
                )
            }

        override suspend fun postComment(
            workshopId: Long,
            ownerId: String,
            text: String,
        ) = withAuthenticatedSteamSession { steamSession ->
            val normalized = normalizeWorkshopCommentRequest(workshopId, ownerId, text)
            val service = steamSession.unified.createService(CommunityUnifiedService::class.java)
            awaitSteamRpc(steamSession, "community_post_comment") {
                val response = service.postCommentToThread(buildCommunityPostRequest(normalized)).await()
                check(response.result == EResult.OK) {
                    "Steam Community.PostCommentToThread returned ${response.result}"
                }
                response.body.build()
            }
            Unit
        }
    }
