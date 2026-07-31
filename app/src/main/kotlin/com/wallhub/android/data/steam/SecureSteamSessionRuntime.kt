package com.wallhub.android.data.steam

import android.content.Context
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WORKSHOP_COMMENT_MAX_LENGTH
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.data.downloads.applyDownloadProxy
import com.wallhub.android.data.security.AndroidKeystoreEncryptedStringStore
import com.wallhub.android.data.security.EncryptedStringReadResult
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSession
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.SteamID
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal suspend fun <T> SecureSteamSessionRepository.withPublicSteamSession(block: suspend (SteamClientSession) -> T): T? =
    withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val steamSession = acquirePublicSteamSession() ?: return@withLock null
            block(steamSession)
        }
    }

internal suspend fun SecureSteamSessionRepository.acquirePublicSteamSession(): SteamClientSession? {
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
        val candidate = createSteamSession(generation, createCurrentSteamConfiguration(generation))
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

internal suspend fun SecureSteamSessionRepository.resolveSteamProfiles(
    steamSession: SteamClientSession,
    steamIds: Set<Long>,
): Map<Long, SteamProfile> {
    val validIds = steamIds.filterTo(linkedSetOf()) { steamId -> steamId > 0L }
    if (validIds.isEmpty()) return emptyMap()
    val missingIds = validIds.filterNot(steamProfiles::containsKey)
    if (missingIds.isNotEmpty()) {
        val service = steamSession.unified.createService(Player::class.java)
        missingIds.chunked(MAX_PROFILE_BATCH_SIZE).forEach { batch ->
            val response =
                try {
                    withTimeout(PROFILE_RPC_TIMEOUT_MS) {
                        awaitSteamRpc(steamSession, "player_get_link_details") {
                            val rpcResponse =
                                service
                                    .getPlayerLinkDetails(
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
                steamProfiles[publicData.steamid] =
                    SteamProfile(
                        displayName = displayName,
                        avatarUrl = publicData.shaDigestAvatar.toByteArray().toSteamAvatarUrl(),
                    )
            }
        }
    }
    if (steamSession.isAuthenticated) {
        resolvePersonaProfiles(
            steamSession = steamSession,
            steamIds = validIds.filterNot(steamProfiles::containsKey).toSet(),
        )
    }
    return validIds.mapNotNull { steamId -> steamProfiles[steamId]?.let { steamId to it } }.toMap()
}

internal suspend fun SecureSteamSessionRepository.resolvePersonaProfiles(
    steamSession: SteamClientSession,
    steamIds: Set<Long>,
) {
    if (steamIds.isEmpty()) return
    val ownedRequests = linkedMapOf<Long, CompletableDeferred<SteamProfile>>()
    val requests =
        steamIds.associateWith { steamId ->
            val candidate = CompletableDeferred<SteamProfile>()
            pendingPersonaProfiles.putIfAbsent(steamId, candidate)?.also {
                candidate.cancel()
            } ?: candidate.also { ownedRequests[steamId] = it }
        }
    try {
        steamSession.friends.requestFriendInfo(steamIds.map { steamId -> SteamID(steamId) })
        withTimeoutOrNull(PERSONA_RPC_TIMEOUT_MS) {
            requests.values.forEach { request -> request.await() }
        }
    } finally {
        ownedRequests.forEach { (steamId, request) ->
            pendingPersonaProfiles.remove(steamId, request)
        }
    }
}

internal suspend fun <T> SecureSteamSessionRepository.withAuthenticatedSteamSession(block: suspend (SteamClientSession) -> T): T =
    withContext(Dispatchers.IO) {
        requestMutex.withLock {
            block(requireAuthenticatedSteamSession())
        }
    }

internal fun SecureSteamSessionRepository.requireAuthenticatedSteamSession(): SteamClientSession {
    val activeSession = authenticatedSession
    if (activeSession?.isUsable == true) return activeSession
    if (activeSession != null) {
        handleUnexpectedDisconnect(activeSession.id, userInitiated = false)
    }
    error(mutableSession.value.message ?: "请先恢复 Steam 登录后再使用个人资料库")
}

internal suspend fun SecureSteamSessionRepository.readInteraction(
    steamSession: SteamClientSession,
    workshopId: Long,
): WorkshopInteraction {
    require(workshopId > 0L) { "创意工坊项目 ID 无效" }
    val service = steamSession.unified.createService(PublishedFile::class.java)
    val subscriptionState =
        runCatching {
            awaitSteamRpc(steamSession, "read_subscription") {
                service
                    .areFilesInSubscriptionList(
                        SteammessagesPublishedfileSteamclient.CPublishedFile_AreFilesInSubscriptionList_Request
                            .newBuilder()
                            .setAppid(WALLPAPER_ENGINE_APP_ID)
                            .addPublishedfileids(workshopId)
                            .setListtype(SUBSCRIPTION_LIST_TYPE)
                            .build(),
                    ).await()
                    .body.filesList
                    .firstOrNull { item -> item.publishedfileid == workshopId }
                    ?.inlist
                    ?.let { inList ->
                        if (inList) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED
                    }
                    ?: SubscriptionState.UNKNOWN
            }
        }.getOrDefault(SubscriptionState.UNKNOWN)
    val favoriteState =
        runCatching {
            awaitSteamRpc(steamSession, "read_favorite") {
                service
                    .getAppRelationships(
                        SteammessagesPublishedfileSteamclient.CPublishedFile_GetAppRelationships_Request
                            .newBuilder()
                            .setPublishedfileid(workshopId)
                            .build(),
                    ).await()
                    .body.appRelationshipsList
                    .any { relationship ->
                        relationship.appid == WALLPAPER_ENGINE_APP_ID &&
                            relationship.relationship == FAVORITE_RELATIONSHIP
                    }.let { favorited ->
                        if (favorited) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED
                    }
            }
        }.getOrDefault(FavoriteState.UNKNOWN)
    return WorkshopInteraction(subscriptionState, favoriteState)
}

internal suspend fun SecureSteamSessionRepository.readInteractionOrExpected(
    steamSession: SteamClientSession,
    workshopId: Long,
    expectedSubscription: SubscriptionState = SubscriptionState.UNKNOWN,
    expectedFavorite: FavoriteState = FavoriteState.UNKNOWN,
): WorkshopInteraction {
    val current =
        runCatching { readInteraction(steamSession, workshopId) }
            .getOrDefault(WorkshopInteraction())
    return current.copy(
        subscriptionState =
            expectedSubscription.takeUnless { it == SubscriptionState.UNKNOWN }
                ?: current.subscriptionState,
        favoriteState =
            expectedFavorite.takeUnless { it == FavoriteState.UNKNOWN }
                ?: current.favoriteState,
    )
}

internal fun AccountWorkshopQuery.normalized(): AccountWorkshopQuery =
    copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_ACCOUNT_WORKSHOP_PAGE_SIZE),
        searchText = searchText.trim().take(MAX_ACCOUNT_WORKSHOP_SEARCH_LENGTH),
        tags =
            tags
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_ACCOUNT_WORKSHOP_TAGS)
                .toSet(),
    )

internal fun AccountWorkshopCollection.steamListType(): String =
    when (this) {
        AccountWorkshopCollection.SUBSCRIPTIONS -> "mysubscriptions"
        AccountWorkshopCollection.FAVORITES -> "myfavorites"
        AccountWorkshopCollection.VOTED -> "myvotes"
    }

internal fun SecureSteamSessionRepository.startLogin(login: PendingLogin) {
    val previousJob: Job?
    val job: Job
    synchronized(lifecycleLock) {
        previousJob = authenticationJob
        val generation = ++sessionGeneration
        pendingLogin = login
        job =
            serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    loginInternal(generation, login)
                } finally {
                    val clearPendingLogin =
                        synchronized(lifecycleLock) {
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

internal suspend fun SecureSteamSessionRepository.loginInternal(
    generation: Long,
    login: PendingLogin,
) {
    detachAuthenticatedSession(generation)?.close()
    pendingCode.getAndSet(null)?.cancel(true)
    val steamSession = createSteamSession(generation, createCurrentSteamConfiguration(generation))
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
        val authDetails =
            AuthSessionDetails().apply {
                username = login.accountName
                password = login.password
                persistentSession = true
                authenticator =
                    createAuthenticator(
                        preferManualCode = login.preferManualCode,
                        accountName = login.accountName,
                        generation = generation,
                    )
                deviceFriendlyName = DEVICE_FRIENDLY_NAME
                clientOSType = EOSType.AndroidUnknown
            }
        val authSession =
            withTimeout(AUTH_SESSION_BEGIN_TIMEOUT_MS) {
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

internal suspend fun SecureSteamSessionRepository.restorePersistedSessionInternal(generation: Long) {
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
    val configuration = createCurrentSteamConfiguration(generation)
    try {
        val restoredSession =
            withTimeout(RESTORE_TOTAL_TIMEOUT_MS) {
                var lastFailure: Throwable? = null
                repeat(RESTORE_ATTEMPTS) { attempt ->
                    ensureCurrentGeneration(generation)
                    setStateIfCurrent(
                        generation = generation,
                        phase = SteamSessionPhase.SIGNING_IN,
                        message =
                            if (attempt == 0) {
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
            phase =
                if (error.result.isCredentialRejection()) {
                    SteamSessionPhase.EXPIRED
                } else {
                    SteamSessionPhase.RESTORABLE
                },
            message =
                if (error.result.isCredentialRejection()) {
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

internal suspend fun SecureSteamSessionRepository.logOn(
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

internal suspend fun SecureSteamSessionRepository.awaitAuthenticationResult(authSession: AuthSession): AuthPollResult {
    return withTimeout(AUTH_POLL_TIMEOUT_MS) {
        supervisorScope {
            val result = CompletableDeferred<AuthPollResult>()
            val automaticPolling =
                launch {
                    runCatching { authSession.pollingWaitForResult().await() }
                        .onSuccess(result::complete)
                }
            val directPolling =
                launch {
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

internal fun SecureSteamSessionRepository.createAuthenticator(
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
                message =
                    if (previousCodeWasIncorrect) {
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
        ): CompletableFuture<String> =
            requestCode(
                message =
                    if (previousCodeWasIncorrect) {
                        "邮件验证码错误，请重新输入"
                    } else {
                        "请输入 Steam 邮件验证码${email?.let { "（$it）" }.orEmpty()}"
                    },
                accountName = accountName,
                generation = generation,
            )
    }
}

internal fun SecureSteamSessionRepository.requestCode(
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

internal suspend fun SecureSteamSessionRepository.connect(
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

internal suspend fun SecureSteamSessionRepository.createCurrentSteamConfiguration(generation: Long): SteamConfiguration {
    val preferences = preferencesStore.preferences.first()
    val proxyUrl =
        preferences.downloadProxyUrl
            .takeIf {
                preferences.downloadProxyEnabled
            }.orEmpty()
    val directoryClient =
        createSteamDirectoryClient(
            clientFactory
                .newBuilder()
                .applyDownloadProxy(proxyUrl),
        )
    return createSteamConfiguration(
        directoryClient = directoryClient,
        serverListProvider = steamServerListProvider,
        onWebSocketFailure = { endpoint, error ->
            recordTransportFailure(generation, endpoint, error)
        },
    )
}

internal fun SecureSteamSessionRepository.recordTransportFailure(
    generation: Long,
    endpoint: java.net.InetSocketAddress?,
    error: Throwable,
) {
    serviceScope.launch {
        diagnostics.record(
            DiagnosticEvent(
                source = "steam-session",
                level = DiagnosticLevel.WARNING,
                message = "Steam CM WebSocket transport failed",
                attributes =
                    mapOf(
                        "generation" to generation.toString(),
                        "endpointHost" to endpoint?.hostString.orEmpty(),
                        "endpointPort" to endpoint?.port?.toString().orEmpty(),
                        "error" to error.javaClass.simpleName,
                    ),
            ),
        )
    }
}

internal fun SecureSteamSessionRepository.createSteamSession(
    generation: Long,
    configuration: SteamConfiguration,
): SteamClientSession {
    val client = SteamClient(configuration)
    val callbackManager = CallbackManager(client)
    val user = client.getHandler(SteamUser::class.java) ?: error("SteamUser handler unavailable")
    val friends = client.getHandler(SteamFriends::class.java) ?: error("SteamFriends handler unavailable")
    val unified =
        client.getHandler(SteamUnifiedMessages::class.java)
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
    subscriptions +=
        callbackManager.subscribe(ConnectedCallback::class.java) {
            connected.complete(Unit)
        }
    subscriptions +=
        callbackManager.subscribe(DisconnectedCallback::class.java) { callback ->
            disconnected.set(true)
            val error = IllegalStateException("Steam connection closed")
            if (!connected.isCompleted) connected.completeExceptionally(error)
            if (!loggedOn.isCompleted) loggedOn.completeExceptionally(error)
            if (!accountSteamId.isCompleted) accountSteamId.completeExceptionally(error)
            if (authenticated.get() && !expectedClose.get()) {
                handleUnexpectedDisconnect(sessionId, callback.isUserInitiated)
            }
        }
    subscriptions +=
        callbackManager.subscribe(LoggedOnCallback::class.java) { callback ->
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
    subscriptions +=
        callbackManager.subscribe(PersonaStateCallback::class.java) { callback ->
            val steamId = callback.friendId.convertToUInt64()
            val displayName = callback.playerName.trim()
            if (steamId <= 0L || displayName.isBlank()) return@subscribe
            val profile =
                SteamProfile(
                    displayName = displayName,
                    avatarUrl = callback.avatarHash.toSteamAvatarUrl(),
                )
            steamProfiles[steamId] = profile
            pendingPersonaProfiles.remove(steamId)?.complete(profile)
        }
    val callbackJob = launchCallbackPump(callbackScope, callbackManager, generation)
    return SteamClientSession(
        id = sessionId,
        generation = generation,
        client = client,
        user = user,
        friends = friends,
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

internal fun SecureSteamSessionRepository.launchCallbackPump(
    callbackScope: CoroutineScope,
    callbackManager: CallbackManager,
    generation: Long,
): Job =
    callbackScope.launch {
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

internal suspend fun <T> SecureSteamSessionRepository.awaitSteamRpc(
    steamSession: SteamClientSession,
    operation: String,
    block: suspend () -> T,
): T {
    val startedAt = System.nanoTime()
    recordSessionEvent(steamSession.generation, "rpc_start", outcome = operation)
    val result =
        try {
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

internal fun SecureSteamSessionRepository.clearAuthenticationJob(generation: Long) {
    synchronized(lifecycleLock) {
        if (generation == sessionGeneration) authenticationJob = null
    }
}

internal fun SecureSteamSessionRepository.isCurrentGeneration(generation: Long): Boolean =
    synchronized(lifecycleLock) {
        generation == sessionGeneration
    }

internal fun SecureSteamSessionRepository.ensureCurrentGeneration(generation: Long) {
    if (!isCurrentGeneration(generation)) {
        throw CancellationException("Steam session operation was superseded")
    }
}

internal fun SecureSteamSessionRepository.detachAuthenticatedSession(generation: Long): SteamClientSession? =
    synchronized(lifecycleLock) {
        if (generation != sessionGeneration) return@synchronized null
        authenticatedSession.also { authenticatedSession = null }
    }

internal fun SecureSteamSessionRepository.promoteAuthenticatedSession(
    generation: Long,
    steamSession: SteamClientSession,
): Boolean {
    var previousSession: SteamClientSession? = null
    val promoted =
        synchronized(lifecycleLock) {
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

internal fun SecureSteamSessionRepository.handleUnexpectedDisconnect(
    sessionId: Long,
    userInitiated: Boolean,
) {
    serviceScope.launch {
        val invalidation =
            synchronized(lifecycleLock) {
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

internal fun SecureSteamSessionRepository.setStateIfCurrent(
    generation: Long,
    phase: SteamSessionPhase,
    message: String,
    accountName: String? = null,
    requiresCode: Boolean = false,
    awaitingDeviceConfirmation: Boolean = false,
    hasStoredSession: Boolean = false,
): Boolean {
    val state =
        synchronized(lifecycleLock) {
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

internal fun SecureSteamSessionRepository.setState(
    phase: SteamSessionPhase,
    message: String,
    accountName: String? = null,
    requiresCode: Boolean = false,
    awaitingDeviceConfirmation: Boolean = false,
    hasStoredSession: Boolean = false,
) {
    val state =
        SteamSessionState(
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

internal fun SecureSteamSessionRepository.recordState(
    state: SteamSessionState,
    generation: Long?,
) {
    serviceScope.launch {
        runCatching {
            diagnostics.record(
                DiagnosticEvent(
                    source = "steam-session",
                    level =
                        if (
                            state.phase == SteamSessionPhase.FAILED ||
                            state.phase == SteamSessionPhase.EXPIRED ||
                            state.phase == SteamSessionPhase.RESTORABLE
                        ) {
                            DiagnosticLevel.WARNING
                        } else {
                            DiagnosticLevel.INFO
                        },
                    message = state.message.orEmpty(),
                    attributes =
                        buildMap {
                            put("phase", state.phase.name)
                            generation?.let { put("generation", it.toString()) }
                        },
                ),
            )
        }
    }
}

internal fun SecureSteamSessionRepository.recordSessionEvent(
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
                    level =
                        if (
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
                    attributes =
                        buildMap {
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

internal fun Throwable.displayMessage(): String =
    message?.takeIf(String::isNotBlank)
        ?: javaClass.simpleName

internal data class PendingLogin(
    val accountName: String,
    val password: String,
    val preferManualCode: Boolean = false,
)

internal class SteamClientSession(
    val id: Long,
    val generation: Long,
    val client: SteamClient,
    val user: SteamUser,
    val friends: SteamFriends,
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

internal const val DEVICE_FRIENDLY_NAME = "WallHub Android"
internal const val CONNECT_TIMEOUT_MS = 15_000L
internal const val LOGON_TIMEOUT_MS = 30_000L
internal const val ANONYMOUS_LOGON_TIMEOUT_MS = 20_000L
internal const val RESTORE_TOTAL_TIMEOUT_MS = 60_000L
internal const val RESTORE_ATTEMPTS = 2
internal const val RESTORE_RETRY_DELAY_MS = 1_000L
internal const val AUTH_SESSION_BEGIN_TIMEOUT_MS = 30_000L
internal const val AUTH_POLL_TIMEOUT_MS = 5 * 60_000L
internal const val AUTH_STATUS_POLL_INTERVAL_MS = 2_000L
internal const val PUBLIC_BROWSE_SESSION_WAIT_MS = 12_000L
internal const val PUBLIC_BROWSE_SESSION_POLL_MS = 100L
internal const val CALLBACK_WAIT_MS = 1_000L
internal const val SUBSCRIPTION_LIST_TYPE = 1
internal const val FAVORITE_RELATIONSHIP = 1
internal const val MAX_ACCOUNT_WORKSHOP_PAGE_SIZE = 50
internal const val MAX_ACCOUNT_WORKSHOP_TAGS = 6
internal const val MAX_ACCOUNT_WORKSHOP_SEARCH_LENGTH = 120
internal const val MAX_ACCOUNT_COLLECTION_FILTER_SOURCE_PAGES = 400
internal const val MAX_PROFILE_BATCH_SIZE = 100
internal const val PROFILE_RPC_TIMEOUT_MS = 5_000L
internal const val PERSONA_RPC_TIMEOUT_MS = 5_000L

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

internal fun ByteArray.toSteamAvatarUrl(): String? {
    if (isEmpty() || all { byte -> byte.toInt() == 0 }) return null
    val hash = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "https://avatars.fastly.steamstatic.com/${hash}_medium.jpg"
}

internal class EncryptedSteamCredentialStore(
    context: Context,
) {
    private val encryptedStore =
        AndroidKeystoreEncryptedStringStore(
            context = context,
            preferencesName = PREFERENCES_NAME,
            keyAlias = KEY_ALIAS,
        )

    @Synchronized
    fun load(): PersistedSteamCredential? {
        val payload =
            when (val result = encryptedStore.read()) {
                EncryptedStringReadResult.Missing -> return null
                is EncryptedStringReadResult.Value -> result.value
                is EncryptedStringReadResult.Unreadable -> {
                    encryptedStore.clear()
                    return null
                }
            }
        return try {
            val fields = payload.split(RECORD_SEPARATOR, limit = 3)
            require(fields.size == 3 && fields[0] == RECORD_VERSION)
            require(fields[1].isNotBlank() && fields[2].isNotBlank())
            PersistedSteamCredential(accountName = fields[1], refreshToken = fields[2])
        } catch (_: IllegalArgumentException) {
            clear()
            null
        }
    }

    @Synchronized
    fun save(credential: PersistedSteamCredential) {
        require(credential.accountName.isNotBlank()) { "Steam account name is required" }
        require(credential.refreshToken.isNotBlank()) { "Steam refresh token is required" }
        val payload =
            listOf(
                RECORD_VERSION,
                credential.accountName,
                credential.refreshToken,
            ).joinToString(RECORD_SEPARATOR)
        encryptedStore.write(payload)
    }

    @Synchronized
    fun clear() {
        encryptedStore.clear()
    }

    private companion object {
        const val PREFERENCES_NAME = "wallhub_formal_steam_session"
        const val KEY_ALIAS = "wallhub_formal_steam_refresh_token"
        const val RECORD_VERSION = "v1"
        const val RECORD_SEPARATOR = "\u001F"
    }
}
