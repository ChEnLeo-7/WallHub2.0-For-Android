package com.wallhub.android.data.steam

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSession
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.SteamID
import com.wallhub.android.core.model.AccountWorkshopCollection
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
import com.wallhub.android.core.model.WORKSHOP_COMMENT_MAX_LENGTH
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal data class PersistedSteamCredential(
    val accountName: String,
    val refreshToken: String,
)

/**
 * Owns the live JavaSteam CM session. Passwords and Steam Guard codes only exist in the
 * active authentication request; the encrypted store contains a refresh token only.
 */
@Singleton
class SecureSteamSessionRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val diagnostics: DiagnosticRepository,
    clientFactory: SteamHttpClientFactory,
) : SteamSessionRepository, SteamContentCredentialProvider, AccountWorkshopRepository, SteamUnifiedWorkshopRepository {
    private val credentialStore = EncryptedSteamCredentialStore(context.applicationContext)
    private val steamDirectoryClient = createSteamDirectoryClient(clientFactory)
    private val steamServerListProvider = SteamWebSocketServerListProvider()
    private val authorDisplayNames = ConcurrentHashMap<Long, String>()
    private val steamProfiles = ConcurrentHashMap<Long, SteamProfile>()
    private val mutableSession = MutableStateFlow(SteamSessionState())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val requestMutex = Mutex()
    private val anonymousSessionMutex = Mutex()
    private val credentialMutex = Mutex()
    private val pendingCode = AtomicReference<CompletableFuture<String>?>(null)
    private val nextSessionId = AtomicLong(0L)

    private var sessionGeneration = 0L

    @Volatile
    private var authenticatedSession: SteamClientSession? = null

    @Volatile
    private var anonymousSession: SteamClientSession? = null

    @Volatile
    private var authenticationJob: Job? = null

    @Volatile
    private var pendingLogin: PendingLogin? = null

    override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

    override fun restorePersistedSession() {
        val job = synchronized(lifecycleLock) {
            if (authenticatedSession?.isUsable == true || authenticationJob?.isActive == true) return
            val generation = ++sessionGeneration
            serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    restorePersistedSessionInternal(generation)
                } finally {
                    clearAuthenticationJob(generation)
                }
            }.also { authenticationJob = it }
        }
        job.start()
    }

    override fun login(accountName: String, password: String) {
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
        val (generation, authentication, activeSession) = synchronized(lifecycleLock) {
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

    override suspend fun loadContentCredential(): SteamContentCredential? = withContext(Dispatchers.IO) {
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
            val response = awaitSteamRpc(
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
            val profiles = resolveSteamProfiles(
                steamSession = steamSession,
                steamIds = page.items.mapNotNull { item -> item.creatorId?.toLongOrNull() }.toSet(),
            )
            page.copy(
                items = page.items.map { item ->
                    item.copy(author = item.creatorId?.toLongOrNull()?.let(profiles::get)?.displayName ?: item.author)
                },
            )
        }

    override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? =
        withPublicSteamSession { steamSession ->
            val service = steamSession.unified.createService(PublishedFile::class.java)
            val response = awaitSteamRpc(steamSession, "public_get_details") {
                val rpcResponse = service.getDetails(buildUnifiedWorkshopDetailRequest(workshopId)).await()
                check(rpcResponse.result == EResult.OK) {
                    "Steam PublishedFile.GetDetails returned ${rpcResponse.result}"
                }
                rpcResponse.body.build()
            }
            val detail = response.publishedfiledetailsList
                .firstOrNull { item ->
                    item.publishedfileid == workshopId && item.result == EResult.OK.code()
                }
                ?: return@withPublicSteamSession null
            val profile = if (steamSession.isAuthenticated) {
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
    ): WorkshopCommentPage? = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val steamSession = authenticatedSession?.takeIf { session ->
                session.isUsable && session.isAuthenticated
            } ?: return@withLock null
            val service = steamSession.unified.createService(CommunityUnifiedService::class.java)
            val response = awaitSteamRpc(steamSession, "community_get_comment_thread") {
                val rpcResponse = service.getCommentThread(
                    buildCommunityCommentRequest(workshopId, ownerId, start, count),
                ).await()
                check(rpcResponse.result == EResult.OK) {
                    "Steam Community.GetCommentThread returned ${rpcResponse.result}"
                }
                rpcResponse.body.build()
            }
            val profiles = resolveSteamProfiles(
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
            suspend fun loadSourcePage(sourcePage: Int, sourcePageSize: Int) = awaitSteamRpc(
                steamSession = steamSession,
                operation = "library_get_user_files",
            ) {
                service.getUserFiles(
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
                        }
                        .build(),
                ).await().body
            }

            val hasClientFilter = normalized.searchText.isNotEmpty() ||
                normalized.type != null || normalized.tags.isNotEmpty()
            if (!hasClientFilter) {
                val response = loadSourcePage(normalized.page, normalized.pageSize)
                val summaries = response.publishedfiledetailsList
                    .asSequence()
                    .filter { detail -> detail.result == EResult.OK.code() }
                    .map { detail -> detail.toWorkshopSummary(normalized.collection) }
                    .toList()
                return@withAuthenticatedSteamSession WorkshopPage(
                    items = summaries,
                    page = normalized.page,
                    hasNextPage = response.total.toLong() >
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
                matches += details.asSequence()
                    .filter { detail -> detail.result == EResult.OK.code() }
                    .map { detail -> detail.toWorkshopSummary(normalized.collection) }
                    .filter { summary -> normalized.matchesAccountCollectionItem(summary) }
                    .toList()
                sourceExhausted =
                    (response.total > 0 &&
                        sourcePage.toLong() * MAX_ACCOUNT_WORKSHOP_PAGE_SIZE >= response.total.toLong()) ||
                        details.size < MAX_ACCOUNT_WORKSHOP_PAGE_SIZE
                sourcePage += 1
            }
            val selection = selectAccountCollectionPage(
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
            val response = awaitSteamRpc(steamSession, "author_get_workshop_details") {
                val rpcResponse = service.getDetails(buildUnifiedWorkshopDetailRequest(workshopId)).await()
                check(rpcResponse.result == EResult.OK) {
                    "Steam PublishedFile.GetDetails returned ${rpcResponse.result}"
                }
                rpcResponse.body.build()
            }
            val creatorId = response.publishedfiledetailsList
                .firstOrNull { detail ->
                    detail.publishedfileid == workshopId && detail.result == EResult.OK.code()
                }
                ?.creator
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
    ): WorkshopInteraction = withAuthenticatedSteamSession { steamSession ->
        require(workshopId > 0L) { "创意工坊项目 ID 无效" }
        val service = steamSession.unified.createService(PublishedFile::class.java)
        if (subscribed) {
            awaitSteamRpc(steamSession, "subscribe") {
                service.subscribe(
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
                service.unsubscribe(
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
            expectedSubscription = if (subscribed) {
                SubscriptionState.SUBSCRIBED
            } else {
                SubscriptionState.NOT_SUBSCRIBED
            },
        )
    }

    override suspend fun setFavorited(
        workshopId: Long,
        favorited: Boolean,
    ): WorkshopInteraction = withAuthenticatedSteamSession { steamSession ->
        require(workshopId > 0L) { "创意工坊项目 ID 无效" }
        val service = steamSession.unified.createService(PublishedFile::class.java)
        if (favorited) {
            awaitSteamRpc(steamSession, "favorite") {
                service.addAppRelationship(
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
                service.removeAppRelationship(
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
    }

    private suspend fun <T> withPublicSteamSession(
        block: suspend (SteamClientSession) -> T,
    ): T? = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val steamSession = acquirePublicSteamSession() ?: return@withLock null
            block(steamSession)
        }
    }

    private suspend fun acquirePublicSteamSession(): SteamClientSession? {
        authenticatedSession?.takeIf { session -> session.isUsable }?.let { return it }
        if (authenticationJob?.isActive == true) {
            withTimeoutOrNull(PUBLIC_BROWSE_SESSION_WAIT_MS) {
                while (authenticatedSession?.isUsable != true && authenticationJob?.isActive == true) {
                    delay(PUBLIC_BROWSE_SESSION_POLL_MS)
                }
            }
            authenticatedSession?.takeIf { session -> session.isUsable }?.let { return it }
        }
        return anonymousSessionMutex.withLock {
            authenticatedSession?.takeIf { session -> session.isUsable }?.let { return@withLock it }
            anonymousSession?.takeIf { session -> session.isUsable }?.let { return@withLock it }
            anonymousSession?.close()
            anonymousSession = null
            val generation = synchronized(lifecycleLock) { sessionGeneration }
            val candidate = createSteamSession(generation)
            try {
                connect(candidate, generation)
                withTimeout(ANONYMOUS_LOGON_TIMEOUT_MS) {
                    candidate.user.logOnAnonymous()
                    candidate.loggedOn.await()
                    candidate.accountSteamId.await()
                }
                anonymousSession = candidate
                recordSessionEvent(generation, "anonymous_logon_success")
                candidate
            } catch (error: CancellationException) {
                candidate.close()
                throw error
            } catch (error: Throwable) {
                candidate.close()
                recordSessionEvent(
                    generation = generation,
                    stage = "anonymous_logon_failure",
                    outcome = error.javaClass.simpleName,
                )
                null
            }
        }
    }

    private suspend fun resolveSteamProfiles(
        steamSession: SteamClientSession,
        steamIds: Set<Long>,
    ): Map<Long, SteamProfile> {
        val validIds = steamIds.filterTo(linkedSetOf()) { steamId -> steamId > 0L }
        if (validIds.isEmpty()) return emptyMap()
        val missingIds = validIds.filterNot(steamProfiles::containsKey)
        if (missingIds.isNotEmpty()) {
            val service = steamSession.unified.createService(Player::class.java)
            missingIds.chunked(MAX_PROFILE_BATCH_SIZE).forEach { batch ->
                val response = try {
                    withTimeout(PROFILE_RPC_TIMEOUT_MS) {
                        awaitSteamRpc(steamSession, "player_get_link_details") {
                            val rpcResponse = service.getPlayerLinkDetails(
                                `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient
                                    .CPlayer_GetPlayerLinkDetails_Request
                                    .newBuilder()
                                    .addAllSteamids(batch)
                                    .build(),
                            ).await()
                            check(rpcResponse.result == EResult.OK) {
                                "Steam Player.GetPlayerLinkDetails returned ${rpcResponse.result}"
                            }
                            rpcResponse.body.build()
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    return@forEach
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@forEach
                }
                response.accountsList.forEach { account ->
                    if (!account.hasPublicData()) return@forEach
                    val publicData = account.publicData
                    val displayName = publicData.personaName.trim()
                    if (publicData.steamid <= 0L || displayName.isBlank()) return@forEach
                    val avatarHash = publicData.shaDigestAvatar.toByteArray()
                        .takeIf { hash -> hash.isNotEmpty() && hash.any { byte -> byte.toInt() != 0 } }
                        ?.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
                    steamProfiles[publicData.steamid] = SteamProfile(
                        displayName = displayName,
                        avatarUrl = avatarHash?.let { hash ->
                            "https://avatars.fastly.steamstatic.com/${hash}_medium.jpg"
                        },
                    )
                }
            }
        }
        return validIds.mapNotNull { steamId -> steamProfiles[steamId]?.let { steamId to it } }.toMap()
    }

    private suspend fun <T> withAuthenticatedSteamSession(
        block: suspend (SteamClientSession) -> T,
    ): T = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            block(requireAuthenticatedSteamSession())
        }
    }

    private fun requireAuthenticatedSteamSession(): SteamClientSession {
        val activeSession = authenticatedSession
        if (activeSession?.isUsable == true) return activeSession
        if (activeSession != null) {
            handleUnexpectedDisconnect(activeSession.id, userInitiated = false)
        }
        error(mutableSession.value.message ?: "请先恢复 Steam 登录后再使用个人资料库")
    }

    private suspend fun readInteraction(
        steamSession: SteamClientSession,
        workshopId: Long,
    ): WorkshopInteraction {
        require(workshopId > 0L) { "创意工坊项目 ID 无效" }
        val service = steamSession.unified.createService(PublishedFile::class.java)
        val subscriptionState = runCatching {
            awaitSteamRpc(steamSession, "read_subscription") {
                service.areFilesInSubscriptionList(
                    SteammessagesPublishedfileSteamclient.CPublishedFile_AreFilesInSubscriptionList_Request
                        .newBuilder()
                        .setAppid(WALLPAPER_ENGINE_APP_ID)
                        .addPublishedfileids(workshopId)
                        .setListtype(SUBSCRIPTION_LIST_TYPE)
                        .build(),
                ).await().body.filesList
                    .firstOrNull { item -> item.publishedfileid == workshopId }
                    ?.inlist
                    ?.let { inList ->
                        if (inList) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED
                    }
                    ?: SubscriptionState.UNKNOWN
            }
        }.getOrDefault(SubscriptionState.UNKNOWN)
        val favoriteState = runCatching {
            awaitSteamRpc(steamSession, "read_favorite") {
                service.getAppRelationships(
                    SteammessagesPublishedfileSteamclient.CPublishedFile_GetAppRelationships_Request
                        .newBuilder()
                        .setPublishedfileid(workshopId)
                        .build(),
                ).await().body.appRelationshipsList
                    .any { relationship ->
                        relationship.appid == WALLPAPER_ENGINE_APP_ID &&
                            relationship.relationship == FAVORITE_RELATIONSHIP
                    }
                    .let { favorited ->
                        if (favorited) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED
                    }
            }
        }.getOrDefault(FavoriteState.UNKNOWN)
        return WorkshopInteraction(subscriptionState, favoriteState)
    }

    private suspend fun readInteractionOrExpected(
        steamSession: SteamClientSession,
        workshopId: Long,
        expectedSubscription: SubscriptionState = SubscriptionState.UNKNOWN,
        expectedFavorite: FavoriteState = FavoriteState.UNKNOWN,
    ): WorkshopInteraction {
        val current = runCatching { readInteraction(steamSession, workshopId) }
            .getOrDefault(WorkshopInteraction())
        return current.copy(
            subscriptionState = expectedSubscription.takeUnless { it == SubscriptionState.UNKNOWN }
                ?: current.subscriptionState,
            favoriteState = expectedFavorite.takeUnless { it == FavoriteState.UNKNOWN }
                ?: current.favoriteState,
        )
    }

    private fun AccountWorkshopQuery.normalized(): AccountWorkshopQuery = copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_ACCOUNT_WORKSHOP_PAGE_SIZE),
        searchText = searchText.trim().take(MAX_ACCOUNT_WORKSHOP_SEARCH_LENGTH),
        tags = tags.map(String::trim).filter(String::isNotBlank).take(MAX_ACCOUNT_WORKSHOP_TAGS).toSet(),
    )

    private fun AccountWorkshopCollection.steamListType(): String = when (this) {
        AccountWorkshopCollection.SUBSCRIPTIONS -> "mysubscriptions"
        AccountWorkshopCollection.FAVORITES -> "myfavorites"
        AccountWorkshopCollection.VOTED -> "myvotes"
    }

    private fun startLogin(login: PendingLogin) {
        val previousJob: Job?
        val job: Job
        synchronized(lifecycleLock) {
            previousJob = authenticationJob
            val generation = ++sessionGeneration
            pendingLogin = login
            job = serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    loginInternal(generation, login)
                } finally {
                    val clearPendingLogin = synchronized(lifecycleLock) {
                        if (pendingLogin === login) {
                            pendingLogin = null
                            true
                        } else {
                            false
                        }
                    }
                    if (clearPendingLogin) pendingCode.getAndSet(null)?.cancel(true)
                    clearAuthenticationJob(generation)
                }
            }
            authenticationJob = job
        }
        previousJob?.cancel()
        job.start()
    }

    private suspend fun loginInternal(
        generation: Long,
        login: PendingLogin,
    ) {
        detachAuthenticatedSession(generation)?.close()
        pendingCode.getAndSet(null)?.cancel(true)
        val steamSession = createSteamSession(generation)
        var promoted = false
        try {
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.SIGNING_IN,
                message = "正在连接 Steam，随后可能要求手机客户端确认或 Steam Guard 验证…",
                accountName = login.accountName,
            )
            connect(steamSession, generation)
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.SIGNING_IN,
                message = "正在请求 Steam 登录会话…",
                accountName = login.accountName,
            )
            val authDetails = AuthSessionDetails().apply {
                username = login.accountName
                password = login.password
                persistentSession = true
                authenticator = createAuthenticator(
                    preferManualCode = login.preferManualCode,
                    accountName = login.accountName,
                    generation = generation,
                )
                deviceFriendlyName = DEVICE_FRIENDLY_NAME
                clientOSType = EOSType.AndroidUnknown
            }
            val authSession = withTimeout(AUTH_SESSION_BEGIN_TIMEOUT_MS) {
                steamSession.client.authentication
                    .beginAuthSessionViaCredentials(authDetails)
                    .await()
            }
            val pollingResult = awaitAuthenticationResult(authSession)
            check(pollingResult.refreshToken.isNotBlank()) { "Steam 未返回 refresh token" }

            val authenticatedName = pollingResult.accountName.ifBlank { login.accountName }
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.SIGNING_IN,
                message = "账户凭据已验证，正在建立 Steam 登录会话…",
                accountName = authenticatedName,
            )
            logOn(steamSession, authenticatedName, pollingResult.refreshToken, generation)
            credentialMutex.withLock {
                ensureCurrentGeneration(generation)
                credentialStore.save(
                    PersistedSteamCredential(
                        accountName = authenticatedName,
                        refreshToken = pollingResult.refreshToken,
                    ),
                )
            }
            promoted = promoteAuthenticatedSession(generation, steamSession)
            if (!promoted) throw CancellationException("Steam login was superseded")
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.SIGNED_IN,
                message = "Steam 登录成功，已加密保存本机登录状态。",
                accountName = authenticatedName,
                hasStoredSession = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.FAILED,
                message = "Steam 登录验证失败：${error.displayMessage()}",
            )
        } finally {
            if (!promoted) steamSession.close()
        }
    }

    private suspend fun restorePersistedSessionInternal(generation: Long) {
        val credential = credentialMutex.withLock { credentialStore.load() }
        if (credential == null) {
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.SIGNED_OUT,
                message = "未检测到已保存的 Steam 登录状态",
            )
            return
        }
        val currentSession = authenticatedSession
        if (currentSession?.isUsable == true) {
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.SIGNED_IN,
                message = "Steam 登录状态已恢复",
                accountName = credential.accountName,
                hasStoredSession = true,
            )
            return
        }
        detachAuthenticatedSession(generation)?.close()

        recordSessionEvent(generation, "restore_start")
        val configuration = createSteamConfiguration(
            directoryClient = steamDirectoryClient,
            serverListProvider = steamServerListProvider,
        )
        try {
            val restoredSession = withTimeout(RESTORE_TOTAL_TIMEOUT_MS) {
                var lastFailure: Throwable? = null
                repeat(RESTORE_ATTEMPTS) { attempt ->
                    ensureCurrentGeneration(generation)
                    setStateIfCurrent(
                        generation = generation,
                        phase = SteamSessionPhase.SIGNING_IN,
                        message = if (attempt == 0) {
                            "正在恢复已保存的 Steam 登录状态…"
                        } else {
                            "首次连接未成功，正在重新连接 Steam…"
                        },
                        accountName = credential.accountName,
                        hasStoredSession = true,
                    )
                    val candidate = createSteamSession(generation, configuration)
                    try {
                        recordSessionEvent(generation, "restore_attempt", attempt + 1)
                        connect(candidate, generation)
                        logOn(candidate, credential.accountName, credential.refreshToken, generation)
                        return@withTimeout candidate
                    } catch (error: SteamLogonRejectedException) {
                        candidate.close()
                        if (error.result.isCredentialRejection()) throw error
                        lastFailure = error
                    } catch (error: TimeoutCancellationException) {
                        candidate.close()
                        lastFailure = error
                    } catch (error: CancellationException) {
                        candidate.close()
                        throw error
                    } catch (error: Throwable) {
                        candidate.close()
                        lastFailure = error
                    }
                    if (attempt + 1 < RESTORE_ATTEMPTS) delay(RESTORE_RETRY_DELAY_MS)
                }
                throw lastFailure ?: IllegalStateException("Steam session restore failed")
            }
            val promoted = promoteAuthenticatedSession(generation, restoredSession)
            if (!promoted) {
                restoredSession.close()
                throw CancellationException("Steam restore was superseded")
            }
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.SIGNED_IN,
                message = "已恢复 Steam 登录状态。",
                accountName = credential.accountName,
                hasStoredSession = true,
            )
            recordSessionEvent(generation, "restore_success")
        } catch (error: SteamLogonRejectedException) {
            setStateIfCurrent(
                generation = generation,
                phase = if (error.result.isCredentialRejection()) {
                    SteamSessionPhase.EXPIRED
                } else {
                    SteamSessionPhase.RESTORABLE
                },
                message = if (error.result.isCredentialRejection()) {
                    "保存的 Steam 登录凭据已失效，请重新登录。"
                } else {
                    "Steam 暂时拒绝了会话恢复，请稍后重试。"
                },
                accountName = credential.accountName,
                hasStoredSession = true,
            )
            recordSessionEvent(generation, "restore_rejected", outcome = error.result.name)
        } catch (error: TimeoutCancellationException) {
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.RESTORABLE,
                message = "恢复 Steam 会话超时，请检查网络后重试。",
                accountName = credential.accountName,
                hasStoredSession = true,
            )
            recordSessionEvent(generation, "restore_timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            setStateIfCurrent(
                generation = generation,
                phase = SteamSessionPhase.RESTORABLE,
                message = "暂时无法恢复 Steam 会话，请检查网络后重试。",
                accountName = credential.accountName,
                hasStoredSession = true,
            )
            recordSessionEvent(
                generation = generation,
                stage = "restore_failure",
                outcome = error.javaClass.simpleName,
            )
        }
    }

    private suspend fun logOn(
        steamSession: SteamClientSession,
        accountName: String,
        refreshToken: String,
        generation: Long,
    ) {
        recordSessionEvent(generation, "logon_start")
        withTimeout(LOGON_TIMEOUT_MS) {
            steamSession.user.logOn(
                LogOnDetails(
                    username = accountName,
                    accessToken = refreshToken,
                    shouldRememberPassword = true,
                    loginID = WALLPAPER_ENGINE_APP_ID,
                    machineName = DEVICE_FRIENDLY_NAME,
                    chatMode = ChatMode.NEW_STEAM_CHAT,
                ),
            )
            steamSession.loggedOn.await()
            steamSession.accountSteamId.await()
        }
        recordSessionEvent(generation, "logon_success")
    }

    private suspend fun awaitAuthenticationResult(authSession: AuthSession): AuthPollResult {
        return withTimeout(AUTH_POLL_TIMEOUT_MS) {
            supervisorScope {
                val result = CompletableDeferred<AuthPollResult>()
                val automaticPolling = launch {
                    runCatching { authSession.pollingWaitForResult().await() }
                        .onSuccess(result::complete)
                }
                val directPolling = launch {
                    while (isActive && !result.isCompleted) {
                        runCatching { authSession.pollAuthSessionStatus().await() }
                            .getOrNull()
                            ?.let { pollingResult ->
                                result.complete(pollingResult)
                                return@launch
                            }
                        delay(AUTH_STATUS_POLL_INTERVAL_MS)
                    }
                }
                try {
                    result.await()
                } finally {
                    automaticPolling.cancel()
                    directPolling.cancel()
                }
            }
        }
    }

    private fun createAuthenticator(
        preferManualCode: Boolean,
        accountName: String,
        generation: Long,
    ): IAuthenticator {
        return object : IAuthenticator {
            override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
                if (preferManualCode) {
                    setStateIfCurrent(
                        generation = generation,
                        phase = SteamSessionPhase.SIGNING_IN,
                        message = "正在切换为 Steam Guard 令牌验证码…",
                        accountName = accountName,
                    )
                    return CompletableFuture.completedFuture(false)
                }
                setStateIfCurrent(
                    generation = generation,
                    phase = SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                    message = "请在 Steam 手机客户端确认此次登录，确认后会自动继续。",
                    accountName = accountName,
                    awaitingDeviceConfirmation = true,
                )
                return CompletableFuture.completedFuture(true)
            }

            override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
                requestCode(
                    message = if (previousCodeWasIncorrect) {
                        "Steam Guard 验证码错误，请重新查看手机"
                    } else {
                        "请输入 Steam Guard 手机验证码"
                    },
                    accountName = accountName,
                    generation = generation,
                )

            override fun getEmailCode(
                email: String?,
                previousCodeWasIncorrect: Boolean,
            ): CompletableFuture<String> = requestCode(
                message = if (previousCodeWasIncorrect) {
                    "邮件验证码错误，请重新输入"
                } else {
                    "请输入 Steam 邮件验证码${email?.let { "（$it）" }.orEmpty()}"
                },
                accountName = accountName,
                generation = generation,
            )
        }
    }

    private fun requestCode(
        message: String,
        accountName: String,
        generation: Long,
    ): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        if (!isCurrentGeneration(generation)) {
            future.cancel(true)
            return future
        }
        pendingCode.getAndSet(future)?.cancel(true)
        setStateIfCurrent(
            generation = generation,
            phase = SteamSessionPhase.WAITING_FOR_CODE,
            message = message,
            accountName = accountName,
            requiresCode = true,
        )
        return future
    }

    private suspend fun connect(
        steamSession: SteamClientSession,
        generation: Long,
    ) {
        recordSessionEvent(generation, "connect_start")
        withTimeout(CONNECT_TIMEOUT_MS) {
            steamSession.client.connect()
            steamSession.connected.await()
        }
        recordSessionEvent(generation, "connect_success")
    }

    private fun createSteamSession(
        generation: Long,
        configuration: SteamConfiguration = createSteamConfiguration(
            directoryClient = steamDirectoryClient,
            serverListProvider = steamServerListProvider,
        ),
    ): SteamClientSession {
        val client = SteamClient(configuration)
        val callbackManager = CallbackManager(client)
        val user = client.getHandler(SteamUser::class.java) ?: error("SteamUser handler unavailable")
        val unified = client.getHandler(SteamUnifiedMessages::class.java)
            ?: error("SteamUnifiedMessages handler unavailable")
        val connected = CompletableDeferred<Unit>()
        val loggedOn = CompletableDeferred<Unit>()
        val accountSteamId = CompletableDeferred<SteamID>()
        val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val subscriptions = mutableListOf<Closeable>()
        val sessionId = nextSessionId.incrementAndGet()
        val disconnected = AtomicBoolean(false)
        val expectedClose = AtomicBoolean(false)
        val authenticated = AtomicBoolean(false)
        subscriptions += callbackManager.subscribe(ConnectedCallback::class.java) {
            connected.complete(Unit)
        }
        subscriptions += callbackManager.subscribe(DisconnectedCallback::class.java) { callback ->
            disconnected.set(true)
            val error = IllegalStateException("Steam connection closed")
            if (!connected.isCompleted) connected.completeExceptionally(error)
            if (!loggedOn.isCompleted) loggedOn.completeExceptionally(error)
            if (!accountSteamId.isCompleted) accountSteamId.completeExceptionally(error)
            if (authenticated.get() && !expectedClose.get()) {
                handleUnexpectedDisconnect(sessionId, callback.isUserInitiated)
            }
        }
        subscriptions += callbackManager.subscribe(LoggedOnCallback::class.java) { callback ->
            if (callback.result == EResult.OK) {
                val steamId = callback.clientSteamID
                if (steamId == null) {
                    val error = IllegalStateException("Steam 登录成功但未返回账户 ID")
                    accountSteamId.completeExceptionally(error)
                    loggedOn.completeExceptionally(error)
                } else {
                    accountSteamId.complete(steamId)
                    loggedOn.complete(Unit)
                }
            } else {
                val error = SteamLogonRejectedException(callback.result)
                accountSteamId.completeExceptionally(error)
                loggedOn.completeExceptionally(error)
            }
        }
        val callbackJob = callbackScope.launch {
            while (isActive) {
                try {
                    callbackManager.runWaitCallbacks(CALLBACK_WAIT_MS)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(
                        generation = generation,
                        stage = "callback_failure",
                        outcome = error.javaClass.simpleName,
                    )
                }
                delay(1)
            }
        }
        return SteamClientSession(
            id = sessionId,
            generation = generation,
            client = client,
            user = user,
            unified = unified,
            connected = connected,
            loggedOn = loggedOn,
            accountSteamId = accountSteamId,
            callbackScope = callbackScope,
            callbackJob = callbackJob,
            subscriptions = subscriptions,
            disconnected = disconnected,
            expectedClose = expectedClose,
            authenticated = authenticated,
        )
    }

    private suspend fun <T> awaitSteamRpc(
        steamSession: SteamClientSession,
        operation: String,
        block: suspend () -> T,
    ): T {
        val startedAt = System.nanoTime()
        recordSessionEvent(steamSession.generation, "rpc_start", outcome = operation)
        val result = try {
            withTimeout(STEAM_RPC_TIMEOUT_MS) { block() }
        } catch (error: TimeoutCancellationException) {
            recordSessionEvent(
                generation = steamSession.generation,
                stage = "rpc_timeout",
                outcome = operation,
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L,
            )
            if (!steamSession.isUsable) {
                handleUnexpectedDisconnect(steamSession.id, userInitiated = false)
            }
            throw IllegalStateException("Steam 请求超时，请稍后重试")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            recordSessionEvent(
                generation = steamSession.generation,
                stage = "rpc_failure",
                outcome = "$operation:${error.javaClass.simpleName}",
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L,
            )
            if (!steamSession.isUsable) {
                handleUnexpectedDisconnect(steamSession.id, userInitiated = false)
            }
            throw error
        }
        recordSessionEvent(
            generation = steamSession.generation,
            stage = "rpc_success",
            outcome = operation,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L,
        )
        return result
    }

    private fun clearAuthenticationJob(generation: Long) {
        synchronized(lifecycleLock) {
            if (generation == sessionGeneration) authenticationJob = null
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean = synchronized(lifecycleLock) {
        generation == sessionGeneration
    }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("Steam session operation was superseded")
        }
    }

    private fun detachAuthenticatedSession(generation: Long): SteamClientSession? =
        synchronized(lifecycleLock) {
            if (generation != sessionGeneration) return@synchronized null
            authenticatedSession.also { authenticatedSession = null }
        }

    private fun promoteAuthenticatedSession(
        generation: Long,
        steamSession: SteamClientSession,
    ): Boolean {
        var previousSession: SteamClientSession? = null
        val promoted = synchronized(lifecycleLock) {
            if (generation != sessionGeneration || !steamSession.tryMarkAuthenticated()) {
                false
            } else {
                previousSession = authenticatedSession
                authenticatedSession = steamSession
                true
            }
        }
        if (promoted && previousSession !== steamSession) previousSession?.close()
        return promoted
    }

    private fun handleUnexpectedDisconnect(
        sessionId: Long,
        userInitiated: Boolean,
    ) {
        serviceScope.launch {
            val invalidation = synchronized(lifecycleLock) {
                val active = authenticatedSession
                if (active?.id != sessionId || active.isExpectedClose) return@synchronized null
                authenticatedSession = null
                val generation = ++sessionGeneration
                generation to active
            } ?: return@launch
            invalidation.second.close()
            setStateIfCurrent(
                generation = invalidation.first,
                phase = SteamSessionPhase.RESTORABLE,
                message = "Steam 连接已中断，请重试恢复会话。",
                accountName = mutableSession.value.accountName,
                hasStoredSession = true,
            )
            recordSessionEvent(
                generation = invalidation.first,
                stage = "unexpected_disconnect",
                outcome = if (userInitiated) "client" else "network",
            )
        }
    }

    private fun setStateIfCurrent(
        generation: Long,
        phase: SteamSessionPhase,
        message: String,
        accountName: String? = null,
        requiresCode: Boolean = false,
        awaitingDeviceConfirmation: Boolean = false,
        hasStoredSession: Boolean = false,
    ): Boolean {
        val state = synchronized(lifecycleLock) {
            if (generation != sessionGeneration) return@synchronized null
            SteamSessionState(
                phase = phase,
                accountName = accountName,
                message = message,
                requiresCode = requiresCode,
                awaitingDeviceConfirmation = awaitingDeviceConfirmation,
                hasStoredSession = hasStoredSession,
            ).also { mutableSession.value = it }
        } ?: return false
        recordState(state, generation)
        return true
    }

    private fun setState(
        phase: SteamSessionPhase,
        message: String,
        accountName: String? = null,
        requiresCode: Boolean = false,
        awaitingDeviceConfirmation: Boolean = false,
        hasStoredSession: Boolean = false,
    ) {
        val state = SteamSessionState(
            phase = phase,
            accountName = accountName,
            message = message,
            requiresCode = requiresCode,
            awaitingDeviceConfirmation = awaitingDeviceConfirmation,
            hasStoredSession = hasStoredSession,
        )
        mutableSession.value = state
        recordState(state, generation = null)
    }

    private fun recordState(state: SteamSessionState, generation: Long?) {
        serviceScope.launch {
            runCatching {
                diagnostics.record(
                    DiagnosticEvent(
                        source = "steam-session",
                        level = if (
                            state.phase == SteamSessionPhase.FAILED ||
                            state.phase == SteamSessionPhase.EXPIRED ||
                            state.phase == SteamSessionPhase.RESTORABLE
                        ) {
                            DiagnosticLevel.WARNING
                        } else {
                            DiagnosticLevel.INFO
                        },
                        message = state.message.orEmpty(),
                        attributes = buildMap {
                            put("phase", state.phase.name)
                            generation?.let { put("generation", it.toString()) }
                        },
                    ),
                )
            }
        }
    }

    private fun recordSessionEvent(
        generation: Long,
        stage: String,
        attempt: Int? = null,
        outcome: String? = null,
        elapsedMillis: Long? = null,
    ) {
        serviceScope.launch {
            runCatching {
                diagnostics.record(
                    DiagnosticEvent(
                        source = "steam-session",
                        level = if (
                            stage.contains("failure") ||
                            stage.contains("timeout") ||
                            stage.contains("disconnect") ||
                            stage.contains("rejected")
                        ) {
                            DiagnosticLevel.WARNING
                        } else {
                            DiagnosticLevel.INFO
                        },
                        message = "Steam session stage: $stage",
                        attributes = buildMap {
                            put("generation", generation.toString())
                            put("stage", stage)
                            attempt?.let { put("attempt", it.toString()) }
                            outcome?.let { put("outcome", it) }
                            elapsedMillis?.let { put("elapsed_ms", it.toString()) }
                        },
                    ),
                )
            }
        }
    }

    private fun Throwable.displayMessage(): String = message?.takeIf(String::isNotBlank)
        ?: javaClass.simpleName

    private data class PendingLogin(
        val accountName: String,
        val password: String,
        val preferManualCode: Boolean = false,
    )

    private class SteamClientSession(
        val id: Long,
        val generation: Long,
        val client: SteamClient,
        val user: SteamUser,
        val unified: SteamUnifiedMessages,
        val connected: CompletableDeferred<Unit>,
        val loggedOn: CompletableDeferred<Unit>,
        val accountSteamId: CompletableDeferred<SteamID>,
        val callbackScope: CoroutineScope,
        val callbackJob: Job,
        val subscriptions: List<Closeable>,
        private val disconnected: AtomicBoolean,
        private val expectedClose: AtomicBoolean,
        private val authenticated: AtomicBoolean,
    ) {
        private val closed = AtomicBoolean(false)

        val isExpectedClose: Boolean
            get() = expectedClose.get()

        val isUsable: Boolean
            get() = !closed.get() && !disconnected.get() && client.isConnected

        val isAuthenticated: Boolean
            get() = authenticated.get()

        fun tryMarkAuthenticated(): Boolean {
            if (closed.get() || disconnected.get() || !client.isConnected) return false
            authenticated.set(true)
            if (disconnected.get() || !client.isConnected) {
                authenticated.set(false)
                return false
            }
            return true
        }

        fun close() {
            expectedClose.set(true)
            if (!closed.compareAndSet(false, true)) return
            callbackJob.cancel()
            subscriptions.forEach { subscription -> runCatching { subscription.close() } }
            runCatching { user.logOff() }
            runCatching { client.disconnect() }
            callbackScope.cancel()
        }
    }

    private companion object {
        const val WALLPAPER_ENGINE_APP_ID = 431960
        const val DEVICE_FRIENDLY_NAME = "WallHub Android"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val LOGON_TIMEOUT_MS = 30_000L
        const val ANONYMOUS_LOGON_TIMEOUT_MS = 20_000L
        const val RESTORE_TOTAL_TIMEOUT_MS = 60_000L
        const val RESTORE_ATTEMPTS = 2
        const val RESTORE_RETRY_DELAY_MS = 1_000L
        const val AUTH_SESSION_BEGIN_TIMEOUT_MS = 30_000L
        const val AUTH_POLL_TIMEOUT_MS = 5 * 60_000L
        const val AUTH_STATUS_POLL_INTERVAL_MS = 2_000L
        const val PUBLIC_BROWSE_SESSION_WAIT_MS = 12_000L
        const val PUBLIC_BROWSE_SESSION_POLL_MS = 100L
        const val CALLBACK_WAIT_MS = 1_000L
        const val SUBSCRIPTION_LIST_TYPE = 1
        const val FAVORITE_RELATIONSHIP = 1
        const val MAX_ACCOUNT_WORKSHOP_PAGE_SIZE = 50
        const val MAX_ACCOUNT_WORKSHOP_TAGS = 6
        const val MAX_ACCOUNT_WORKSHOP_SEARCH_LENGTH = 120
        const val MAX_ACCOUNT_COLLECTION_FILTER_SOURCE_PAGES = 400
        const val MAX_PROFILE_BATCH_SIZE = 100
        const val PROFILE_RPC_TIMEOUT_MS = 5_000L
    }
}

internal data class NormalizedWorkshopCommentRequest(
    val workshopId: Long,
    val ownerId: String,
    val text: String,
)

internal fun normalizeWorkshopCommentRequest(
    workshopId: Long,
    ownerId: String,
    text: String,
): NormalizedWorkshopCommentRequest {
    require(workshopId > 0L) { "创意工坊项目 ID 无效" }
    val normalizedOwnerId = ownerId.trim()
    require(normalizedOwnerId.toULongOrNull()?.let { it > 0uL } == true) {
        "创意工坊作者 ID 无效"
    }
    val normalizedText = text.trim()
    require(normalizedText.isNotEmpty()) { "评论不能为空" }
    require(normalizedText.length <= WORKSHOP_COMMENT_MAX_LENGTH) {
        "评论不能超过 $WORKSHOP_COMMENT_MAX_LENGTH 个字符"
    }
    return NormalizedWorkshopCommentRequest(
        workshopId = workshopId,
        ownerId = normalizedOwnerId,
        text = normalizedText,
    )
}

private class EncryptedSteamCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(): PersistedSteamCredential? {
        val encryptedPayload = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val initializationVector = preferences.getString(KEY_INITIALIZATION_VECTOR, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(
                    GCM_TAG_LENGTH_BITS,
                    Base64.decode(initializationVector, Base64.NO_WRAP),
                ),
            )
            val payload = String(
                cipher.doFinal(Base64.decode(encryptedPayload, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
            val fields = payload.split(RECORD_SEPARATOR, limit = 3)
            require(fields.size == 3 && fields[0] == RECORD_VERSION)
            require(fields[1].isNotBlank() && fields[2].isNotBlank())
            PersistedSteamCredential(accountName = fields[1], refreshToken = fields[2])
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun save(credential: PersistedSteamCredential) {
        require(credential.accountName.isNotBlank()) { "Steam account name is required" }
        require(credential.refreshToken.isNotBlank()) { "Steam refresh token is required" }
        val payload = listOf(
            RECORD_VERSION,
            credential.accountName,
            credential.refreshToken,
        ).joinToString(RECORD_SEPARATOR)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encryptedPayload = cipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encryptedPayload, Base64.NO_WRAP))
                .putString(
                    KEY_INITIALIZATION_VECTOR,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                )
                .commit(),
        ) { "无法保存 Steam 登录状态" }
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "wallhub_formal_steam_session"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_INITIALIZATION_VECTOR = "initialization_vector"
        const val KEY_ALIAS = "wallhub_formal_steam_refresh_token"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val RECORD_VERSION = "v1"
        const val RECORD_SEPARATOR = "\u001F"
    }
}
