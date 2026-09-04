package com.wallhub.android.data.steam

import android.content.Context
import android.os.SystemClock
import bruhcollective.itaysonlab.ksteam.EnvironmentConstants
import bruhcollective.itaysonlab.ksteam.SteamClient
import bruhcollective.itaysonlab.ksteam.handlers.Logger
import bruhcollective.itaysonlab.ksteam.kSteam
import bruhcollective.itaysonlab.ksteam.messages.SteamPacket
import bruhcollective.itaysonlab.ksteam.messages.SteamPacketHeader
import bruhcollective.itaysonlab.ksteam.models.SteamId
import bruhcollective.itaysonlab.ksteam.models.account.AuthorizationState
import bruhcollective.itaysonlab.ksteam.models.account.SteamAccountAuthorization
import bruhcollective.itaysonlab.ksteam.models.enums.EGamingDeviceType
import bruhcollective.itaysonlab.ksteam.models.enums.EMsg
import bruhcollective.itaysonlab.ksteam.models.enums.EOSType
import bruhcollective.itaysonlab.ksteam.models.enums.EResult
import bruhcollective.itaysonlab.ksteam.network.CMClientState
import bruhcollective.itaysonlab.ksteam.persistence.MemoryPersistenceDriver
import bruhcollective.itaysonlab.ksteam.platform.DeviceInformation
import bruhcollective.itaysonlab.ksteam.util.executeSteam
import com.wallhub.android.R
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SteamAppPlaytime
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SteamProtocolClient
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.steam.wire.CMsgClientGetDepotDecryptionKey
import com.wallhub.android.data.steam.wire.CMsgClientGetDepotDecryptionKeyResponse
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import com.wallhub.android.data.steamaccess.shouldPrewarmSteamUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import steam.enums.EAuthTokenPlatformType
import steam.webui.common.CMsgClientLogon
import steam.webui.common.CMsgClientLogonResponse
import steam.webui.publishedfile.CPublishedFile_AddAppRelationship_Request
import steam.webui.publishedfile.CPublishedFile_AreFilesInSubscriptionList_Request
import steam.webui.publishedfile.CPublishedFile_GetAppRelationships_Request
import steam.webui.publishedfile.CPublishedFile_GetUserFiles_Request
import steam.webui.publishedfile.CPublishedFile_RemoveAppRelationship_Request
import steam.webui.publishedfile.CPublishedFile_Subscribe_Request
import steam.webui.publishedfile.CPublishedFile_Unsubscribe_Request
import steam.webui.player.CPlayer_GetOwnedGames_Request
import steam.webui.player.CPlayer_GetPlayerLinkDetails_Request

internal const val KSTEAM_DEVICE_FRIENDLY_NAME = "WallHub Android"
internal const val KSTEAM_CLIENT_PACKAGE_VERSION = 1_671_236_931

internal fun deviceInformation(): DeviceInformation =
    DeviceInformation(
        osType = EOSType.k_eAndroidUnknown,
        gamingDeviceType = EGamingDeviceType.k_EGamingDeviceType_Phone,
        deviceName = KSTEAM_DEVICE_FRIENDLY_NAME,
        platformType = EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient,
    )

internal fun isUsableAuthenticatedSteamClient(
    authorized: Boolean,
    connected: Boolean,
): Boolean = authorized && connected

private fun SteamClient.hasUsableAuthenticatedConnection(): Boolean =
    isUsableAuthenticatedSteamClient(
        authorized = account.clientAuthState.value is AuthorizationState.Success,
        connected = connectionStatus.value.hasActiveServerConnection,
    )

/**
 * kSteam-backed implementation of the engine-neutral [SteamProtocolClient] seam. After the
 * JavaSteam removal this is the sole Steam engine:
 *
 * - the main kSteam client owns the signed-in account session through kSteam's `Account`
 *   handler (password + Steam Guard + refresh-token restore + automatic reconnection);
 * - public Workshop browsing falls back to a dedicated anonymous CM session created with a
 *   raw `k_EMsgClientLogon` when no account is signed in;
 * - Workshop/Community/Player RPCs run through kSteam's Wire gRPC bridge;
 * - the depot pipeline uses the same clients via [steamContentClient] and
 *   [steamDepotDecryptionKey].
 *
 * Legacy JavaSteam-era credentials (encrypted refresh token + cached profile) are migrated
 * into kSteam per-account storage on the first restore attempt.
 */
@Singleton
class KSteamSessionRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        internal val diagnostics: DiagnosticRepository,
        private val steamHttpClientFactory: SteamHttpClientFactory,
    ) : SteamSessionRepository,
        SteamContentCredentialProvider,
        AccountWorkshopRepository,
        SteamUnifiedWorkshopRepository,
        SteamPlaytimeRepository,
        SteamProtocolClient {
        internal val applicationContext = context.applicationContext
        internal val credentialStore = EncryptedSteamCredentialStore(applicationContext)
        internal val steamProfiles = ConcurrentHashMap<Long, SteamProfile>()
        internal val authorDisplayNames = ConcurrentHashMap<Long, String>()
        internal val mutableSession = MutableStateFlow(SteamSessionState())

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val engineMutex = Mutex()
        private val anonymousMutex = Mutex()
        private val credentialMutex = Mutex()
        private val restoreLock = Any()
        private val restoreJobRef = AtomicReference<Job?>(null)
        private val loginInProgress = AtomicBoolean(false)
        private val expiredPublished = AtomicBoolean(false)
        private val anonymousEngineRef = AtomicReference<SteamClient?>(null)
        private val pendingAccountName = AtomicReference<String?>(null)

        private fun newKSteamHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                engine {
                    preconfigured = steamHttpClientFactory.newBuilder().build()
                }
                install("WallHubSteamRoutePrewarm") {
                    plugin(HttpSend).intercept { request ->
                        val port = request.url.port
                        if (shouldPrewarmSteamUrl(request.url.protocol.name, request.url.host, port)) {
                            steamHttpClientFactory.prewarmSteamEndpoint(request.url.host, port)
                        }
                        execute(request)
                    }
                }
            }

        @Volatile
        private var engine: SteamClient? = null

        @Volatile
        private var engineObserver: Job? = null

        @Volatile
        private var engineStarted: Boolean = false

        @Volatile
        private var backgroundedAtElapsedRealtime: Long? = null

        override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

        // ------------------------------------------------------------------
        // Engine lifecycle
        // ------------------------------------------------------------------

        private fun buildClient(rootDirectory: File): SteamClient =
            kSteam {
                rootFolder = rootDirectory.absolutePath.toPath()
                deviceInfo = deviceInformation()
                loggingVerbosity = Logger.Verbosity.Warning
                persistenceDriver = KsteamEncryptedPersistenceDriver(applicationContext)
                ktor(::newKSteamHttpClient)
            }

        private suspend fun obtainEngine(): SteamClient =
            engineMutex.withLock {
                engine?.let { return it }
                val created = buildClient(File(applicationContext.filesDir, "ksteam"))
                engine = created
                engineObserver?.cancel()
                engineObserver = observeEngine(created)
                created
            }

        private fun observeEngine(client: SteamClient): Job =
            scope.launch {
                combine(client.account.clientAuthState, client.connectionStatus) { auth, cmState -> auth to cmState }
                    .collect { (auth, cmState) -> reconcileState(client, auth, cmState) }
            }

        private suspend fun startEngine(client: SteamClient) {
            // start() refreshes the CM server list before connecting; resume() must never
            // run before it or CMList.getEndpoint() throws on the empty list.
            if (!engineStarted) {
                client.start()
                engineStarted = true
            }
            withTimeout(ANONYMOUS_CONNECT_TIMEOUT_MS) {
                client.connectionStatus.first { it.hasActiveServerConnection }
            }
        }

        // ------------------------------------------------------------------
        // Session state reconciliation
        // ------------------------------------------------------------------

        internal fun reconcileState(
            client: SteamClient,
            auth: AuthorizationState,
            cmState: CMClientState,
        ) {
            when {
                auth is AuthorizationState.Success && cmState.hasActiveServerConnection -> {
                    expiredPublished.set(false)
                    val previous = mutableSession.value
                    val accountName =
                        client.account.getCurrentAccount()?.accountName?.ifBlank { null }
                            ?: pendingAccountName.get()
                            ?: previous.accountName
                    if (
                        previous.phase == SteamSessionPhase.SIGNED_IN &&
                        previous.accountName == accountName
                    ) {
                        return
                    }
                    val next =
                        SteamSessionState(
                            phase = SteamSessionPhase.SIGNED_IN,
                            accountName = accountName,
                            personaName = previous.personaName,
                            avatarUrl = previous.avatarUrl,
                            message =
                                if (previous.phase == SteamSessionPhase.SIGNED_IN) {
                                    previous.message
                                } else {
                                    applicationContext.getString(R.string.backend_steam_login_success)
                                },
                            hasStoredSession = true,
                        )
                    mutableSession.value = next
                    recordState(next)
                    hydrateOwnProfile(client)
                }

                auth is AuthorizationState.Success -> {
                    // Signed in earlier, but the CM connection dropped or was rejected.
                    if (
                        cmState == CMClientState.AwaitingAuthorization &&
                        !client.account.hasSavedDataForAtLeastOneAccount() &&
                        expiredPublished.compareAndSet(false, true)
                    ) {
                        // Steam rejected the refresh token: kSteam wiped the saved account and
                        // reconnected unauthenticated. The stored credentials are unusable.
                        scope.launch {
                            credentialMutex.withLock { runCatching { credentialStore.clear() } }
                        }
                        val previous = mutableSession.value
                        val next =
                            SteamSessionState(
                                phase = SteamSessionPhase.EXPIRED,
                                accountName = previous.accountName,
                                personaName = previous.personaName,
                                avatarUrl = previous.avatarUrl,
                                message = applicationContext.getString(R.string.backend_steam_credentials_expired),
                                hasStoredSession = false,
                            )
                        mutableSession.value = next
                        recordState(next)
                        recordSessionEvent(0, "session_expired")
                    } else if (mutableSession.value.phase == SteamSessionPhase.SIGNED_IN) {
                        val previous = mutableSession.value
                        mutableSession.value =
                            previous.copy(
                                phase = SteamSessionPhase.SIGNING_IN,
                                message = applicationContext.getString(R.string.backend_steam_reconnecting),
                            )
                    }
                }

                auth is AuthorizationState.AwaitingTwoFactor -> {
                    val methods = auth.supportedConfirmationMethods
                    val phase = steamLoginPhaseForConfirmations(methods)
                    val previous = mutableSession.value
                    val next =
                        SteamSessionState(
                            phase = phase,
                            accountName = pendingAccountName.get() ?: previous.accountName,
                            personaName = previous.personaName,
                            avatarUrl = previous.avatarUrl,
                            message =
                                when (phase) {
                                    SteamSessionPhase.WAITING_FOR_CODE ->
                                        applicationContext.getString(R.string.backend_steam_enter_guard_code)
                                    SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION ->
                                        applicationContext.getString(R.string.backend_steam_confirm_mobile)
                                    SteamSessionPhase.FAILED ->
                                        applicationContext.getString(R.string.backend_steam_machine_token_unsupported)
                                    else ->
                                        applicationContext.getString(R.string.backend_steam_requesting_login)
                                },
                            requiresCode = phase == SteamSessionPhase.WAITING_FOR_CODE,
                            awaitingDeviceConfirmation = phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                            hasStoredSession = previous.hasStoredSession,
                        )
                    mutableSession.value = next
                    recordState(next)
                }

                else -> {
                    if (loginInProgress.get()) return
                    val hasStoredSession =
                        client.account.hasSavedDataForAtLeastOneAccount() || legacyCredentialExists()
                    val previous = mutableSession.value
                    when {
                        previous.phase == SteamSessionPhase.SIGNED_IN -> {
                            val next =
                                SteamSessionState(
                                    phase =
                                        if (hasStoredSession) {
                                            SteamSessionPhase.RESTORABLE
                                        } else {
                                            SteamSessionPhase.SIGNED_OUT
                                        },
                                    accountName = previous.accountName,
                                    personaName = previous.personaName,
                                    avatarUrl = previous.avatarUrl,
                                    message = applicationContext.getString(R.string.backend_steam_disconnected),
                                    hasStoredSession = hasStoredSession,
                                )
                            mutableSession.value = next
                            recordState(next)
                        }

                        previous.phase == SteamSessionPhase.EXPIRED && !hasStoredSession -> Unit
                        previous.phase == SteamSessionPhase.RESTORABLE && hasStoredSession -> Unit
                        previous.phase != SteamSessionPhase.SIGNED_OUT && !hasStoredSession -> {
                            val next =
                                previous.copy(
                                    phase = SteamSessionPhase.SIGNED_OUT,
                                    message = null,
                                    requiresCode = false,
                                    awaitingDeviceConfirmation = false,
                                )
                            mutableSession.value = next
                        }
                    }
                }
            }
        }

        private fun legacyCredentialExists(): Boolean =
            runCatching { credentialStore.read() is SteamCredentialReadResult.Value }.getOrDefault(false)

        // ------------------------------------------------------------------
        // Session transitions
        // ------------------------------------------------------------------

        override fun restorePersistedSession() {
            synchronized(restoreLock) {
                if (restoreJobRef.get()?.isActive == true) return
                loginInProgress.set(true)
                restoreJobRef.set(
                    scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val client = obtainEngine()
                            startEngine(client)
                            restoreInternal(client)
                        } catch (error: TimeoutCancellationException) {
                            publishPhase(
                                phase = SteamSessionPhase.RESTORABLE,
                                message = applicationContext.getString(R.string.backend_steam_restore_timeout),
                                hasStoredSession = true,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            publishPhase(
                                phase = SteamSessionPhase.FAILED,
                                message = applicationContext.getString(R.string.backend_steam_restore_failed),
                                hasStoredSession = error is KsteamSessionStorageException || legacyCredentialExists(),
                            )
                        } finally {
                            loginInProgress.set(false)
                        }
                    }.also { job -> job.start() },
                )
            }
        }

        private suspend fun restoreInternal(client: SteamClient) {
            if (mutableSession.value.phase == SteamSessionPhase.SIGNED_IN) return
            if (client.account.hasSavedDataForAtLeastOneAccount()) {
                publishPhase(
                    phase = SteamSessionPhase.SIGNING_IN,
                    message = applicationContext.getString(R.string.backend_steam_restoring_session),
                    hasStoredSession = true,
                )
                // Account registers its saved-account logon when CM enters AwaitingAuthorization.
                // Sending a second logon here races that callback and can replace a valid session.
                awaitRestorationOutcome(client)
                return
            }
            val credential =
                credentialMutex.withLock { credentialStore.read() }
            when (credential) {
                is SteamCredentialReadResult.Missing ->
                    publishPhase(phase = SteamSessionPhase.SIGNED_OUT, message = null)

                is SteamCredentialReadResult.Unreadable -> {
                    recordSessionEvent(0, "credential_read_failure", outcome = credential.cause.javaClass.simpleName)
                    publishPhase(
                        phase = SteamSessionPhase.FAILED,
                        message = applicationContext.getString(R.string.backend_steam_session_storage_unavailable),
                        hasStoredSession = true,
                    )
                }

                is SteamCredentialReadResult.Value -> {
                    val steamId = steamIdFromRefreshToken(credential.credential.refreshToken)
                    if (steamId == null || steamId.isEmpty) {
                        publishPhase(
                            phase = SteamSessionPhase.RESTORABLE,
                            message = applicationContext.getString(R.string.backend_steam_restore_rejected),
                            hasStoredSession = true,
                        )
                        return
                    }
                    publishPhase(
                        phase = SteamSessionPhase.SIGNING_IN,
                        message = applicationContext.getString(R.string.backend_steam_restoring_session),
                        accountName = credential.credential.accountName,
                        personaName = credential.credential.personaName,
                        avatarUrl = credential.credential.avatarUrl,
                        hasStoredSession = true,
                    )
                    seedKSteamAccount(client, steamId, credential.credential)
                    client.account.signInWithRefreshToken(steamId, credential.credential.refreshToken)
                    awaitRestorationOutcome(client)
                }
            }
        }

        private fun seedKSteamAccount(
            client: SteamClient,
            steamId: SteamId,
            credential: PersistedSteamCredential,
        ) {
            client.configuration.updateSecureAccount(
                steamId,
                SteamAccountAuthorization(
                    accountName = credential.accountName,
                    accessToken = MIGRATED_ACCESS_TOKEN_PLACEHOLDER,
                    refreshToken = credential.refreshToken,
                ),
            )
            client.configuration.autologinSteamId = steamId
        }

        /** Waits until kSteam's auth flow reaches a terminal app phase after a logon attempt. */
        private suspend fun awaitRestorationOutcome(client: SteamClient) {
            val outcome =
                withTimeoutOrNull(RESTORE_TOTAL_TIMEOUT_MS) {
                    client.account.clientAuthState.first { state -> state is AuthorizationState.Success }
                    mutableSession.first { it.phase != SteamSessionPhase.SIGNING_IN }
                    mutableSession.value.phase
                }
            when (outcome) {
                null ->
                    publishPhase(
                        phase = SteamSessionPhase.RESTORABLE,
                        message = applicationContext.getString(R.string.backend_steam_restore_timeout),
                        hasStoredSession = true,
                    )

                SteamSessionPhase.SIGNING_IN -> {
                    // kSteam wiped the account after a rejected refresh token; the connection
                    // observer publishes EXPIRED, so only handle the stuck case here.
                    if (!client.account.hasSavedDataForAtLeastOneAccount()) {
                        publishPhase(
                            phase = SteamSessionPhase.RESTORABLE,
                            message = applicationContext.getString(R.string.backend_steam_restore_rejected),
                            hasStoredSession = false,
                        )
                    }
                }

                else -> Unit
            }
        }

        override fun login(
            accountName: String,
            password: String,
        ) {
            if (accountName.isBlank() || password.isBlank()) {
                publishPhase(
                    phase = SteamSessionPhase.FAILED,
                    message = applicationContext.getString(R.string.backend_steam_enter_credentials),
                    accountName = accountName.trim().ifBlank { null },
                )
                return
            }
            loginInProgress.set(true)
            pendingAccountName.set(accountName.trim())
            publishPhase(
                phase = SteamSessionPhase.SIGNING_IN,
                message = applicationContext.getString(R.string.backend_steam_connecting),
                accountName = accountName.trim(),
            )
            scope.launch {
                try {
                    val client = obtainEngine()
                    startEngine(client)
                    publishPhase(
                        phase = SteamSessionPhase.SIGNING_IN,
                        message = applicationContext.getString(R.string.backend_steam_requesting_login),
                        accountName = accountName.trim(),
                    )
                    when (
                        client.account.signIn(
                            username = accountName.trim(),
                            password = password,
                            rememberSession = true,
                        )
                    ) {
                        bruhcollective.itaysonlab.ksteam.handlers.Account.AuthorizationResult.ProceedToTfa -> Unit
                        bruhcollective.itaysonlab.ksteam.handlers.Account.AuthorizationResult.InvalidPassword ->
                            publishPhase(
                                phase = SteamSessionPhase.FAILED,
                                message = applicationContext.getString(R.string.backend_steam_login_failed, "The password does not match"),
                                accountName = accountName.trim(),
                            )

                        bruhcollective.itaysonlab.ksteam.handlers.Account.AuthorizationResult.RpcError ->
                            publishPhase(
                                phase = SteamSessionPhase.FAILED,
                                message = applicationContext.getString(R.string.backend_steam_login_failed, "Steam network RPC error"),
                                accountName = accountName.trim(),
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    publishPhase(
                        phase = SteamSessionPhase.FAILED,
                        message =
                            applicationContext.getString(R.string.backend_steam_login_failed, error.displayMessage()),
                        accountName = pendingAccountName.get(),
                    )
                } finally {
                    loginInProgress.set(false)
                }
            }
        }

        override fun submitSteamGuardCode(code: String) {
            if (code.isBlank()) {
                publishPhase(
                    phase = SteamSessionPhase.FAILED,
                    message = applicationContext.getString(R.string.backend_steam_no_guard_request),
                )
                return
            }
            scope.launch {
                val accepted =
                    runCatching { engine?.account?.updateCurrentSessionWithCode(code.trim()) ?: false }.getOrDefault(false)
                if (accepted) {
                    publishPhase(
                        phase = SteamSessionPhase.SIGNING_IN,
                        message = applicationContext.getString(R.string.backend_steam_code_submitted),
                        accountName = pendingAccountName.get(),
                    )
                } else {
                    publishPhase(
                        phase = SteamSessionPhase.FAILED,
                        message = applicationContext.getString(R.string.backend_steam_no_guard_request),
                        accountName = pendingAccountName.get(),
                    )
                }
            }
        }

        override fun useManualSteamGuardFallback() {
            if (pendingAccountName.get() == null && !loginInProgress.get()) {
                publishPhase(
                    phase = SteamSessionPhase.FAILED,
                    message = applicationContext.getString(R.string.backend_steam_no_manual_login),
                )
                return
            }
            runCatching { engine?.account?.cancelPolling() }
            mutableSession.value =
                mutableSession.value.copy(
                    phase = SteamSessionPhase.WAITING_FOR_CODE,
                    requiresCode = true,
                    awaitingDeviceConfirmation = false,
                    message = applicationContext.getString(R.string.backend_steam_enter_guard_code),
                )
        }

        override fun logout() {
            scope.launch {
                loginInProgress.set(false)
                val client = engine
                if (client != null) {
                    val steamId = client.currentSessionSteamId
                    if (!steamId.isEmpty) {
                        runCatching { client.configuration.deleteSecureAccount(steamId) }
                    }
                    client.configuration.autologinSteamId = SteamId.Empty
                }
                credentialMutex.withLock { runCatching { credentialStore.clear() } }
                engineMutex.withLock {
                    engineObserver?.cancel()
                    engineObserver = null
                    if (engineStarted) {
                        runCatching { engine?.stop() }
                        engineStarted = false
                    }
                    engine = null
                }
                steamProfiles.clear()
                authorDisplayNames.clear()
                pendingAccountName.set(null)
                expiredPublished.set(false)
                publishPhase(
                    phase = SteamSessionPhase.SIGNED_OUT,
                    message = applicationContext.getString(R.string.backend_steam_signed_out),
                )
            }
        }

        override fun onAppBackgrounded() {
            synchronized(restoreLock) {
                if (backgroundedAtElapsedRealtime == null) {
                    backgroundedAtElapsedRealtime = SystemClock.elapsedRealtime()
                }
            }
            scope.launch {
                runCatching {
                    val client = engine ?: return@launch
                    if (engineStarted) client.pause()
                }
            }
        }

        override fun onAppForegrounded() {
            val backgroundedAt =
                synchronized(restoreLock) {
                    backgroundedAtElapsedRealtime.also { backgroundedAtElapsedRealtime = null }
                }
            scope.launch {
                runCatching {
                    val client = engine ?: return@launch
                    if (engineStarted) client.resume() else startEngine(client)
                    val stale =
                        backgroundedAt != null &&
                            SystemClock.elapsedRealtime() - backgroundedAt >= FOREGROUND_SESSION_REFRESH_AFTER_BACKGROUND_MS
                    if (stale &&
                        client.account.hasSavedDataForAtLeastOneAccount() &&
                        client.account.clientAuthState.value !is AuthorizationState.Success
                    ) {
                        restorePersistedSession()
                    }
                }
            }
        }
        // ------------------------------------------------------------------
        // Profiles
        // ------------------------------------------------------------------

        internal suspend fun resolveSteamProfiles(
            client: SteamClient,
            steamIds: Set<Long>,
        ): Map<Long, SteamProfile> {
            val validIds = steamIds.filterTo(linkedSetOf()) { steamId -> steamId > 0L }
            if (validIds.isEmpty()) return emptyMap()
            val cached = validIds.mapNotNull { steamId -> steamProfiles[steamId]?.let { steamId to it } }.toMap()
            val missing = validIds.filterNot(cached::containsKey)
            if (missing.isEmpty()) return cached
            val response =
                runCatching {
                    awaitSteamRpc("resolve_profiles") {
                        client.grpc.player.GetPlayerLinkDetails().executeSteam(
                            CPlayer_GetPlayerLinkDetails_Request(steamids = missing),
                        )
                    }
                }.getOrNull() ?: return cached
            response.accounts.forEach { account ->
                val publicData = account.public_data ?: return@forEach
                val steamId = publicData.steamid ?: 0L
                val displayName = publicData.persona_name.orEmpty().trim()
                if (steamId > 0L && displayName.isNotEmpty()) {
                    steamProfiles[steamId] =
                        SteamProfile(
                            displayName = displayName,
                            avatarUrl = publicData.sha_digest_avatar?.toByteArray()?.toSteamAvatarUrl(),
                        )
                }
            }
            return validIds.mapNotNull { steamId -> steamProfiles[steamId]?.let { steamId to it } }.toMap()
        }

        private fun hydrateOwnProfile(client: SteamClient) {
            scope.launch {
                val steamId = client.currentSessionSteamId
                if (steamId.isEmpty) return@launch
                val profile = resolveSteamProfiles(client, setOf(steamId.longId))[steamId.longId] ?: return@launch
                val previous = mutableSession.value
                if (previous.phase == SteamSessionPhase.SIGNED_IN &&
                    (previous.personaName != profile.displayName || previous.avatarUrl != profile.avatarUrl)
                ) {
                    mutableSession.value =
                        previous.copy(
                            personaName = profile.displayName,
                            avatarUrl = profile.avatarUrl,
                            updatedAt = System.currentTimeMillis(),
                        )
                }
                credentialMutex.withLock {
                    runCatching {
                        val stored = credentialStore.load() ?: return@withLock
                        if (steamIdFromRefreshToken(stored.refreshToken) == steamId) {
                            credentialStore.save(
                                stored.copy(
                                    personaName = profile.displayName,
                                    avatarUrl = profile.avatarUrl ?: stored.avatarUrl,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Content credentials (depot pipeline)
        // ------------------------------------------------------------------

        override suspend fun loadContentCredential(): SteamContentCredential? =
            withContext(Dispatchers.IO) {
                val client = engine ?: return@withContext null
                when (mutableSession.value.phase) {
                    SteamSessionPhase.SIGNED_OUT, SteamSessionPhase.EXPIRED -> return@withContext null
                    else -> Unit
                }
                val account = client.account.getCurrentAccount() ?: return@withContext null
                SteamContentCredential(
                    accountName = account.accountName.ifBlank { pendingAccountName.get().orEmpty() },
                    refreshToken = account.refreshToken,
                )
            }

        override suspend fun restoreContentCredential(): SteamContentCredential? {
            if (session.value.phase == SteamSessionPhase.EXPIRED) return null
            restorePersistedSession()
            val job = synchronized(restoreLock) { restoreJobRef.get() }
            withTimeoutOrNull(CONTENT_CREDENTIAL_RESTORE_TIMEOUT_MS) { job?.join() }
            return if (session.value.phase == SteamSessionPhase.SIGNED_IN) loadContentCredential() else null
        }

        /**
         * Content-session access for the depot pipeline: the signed-in engine when available,
         * otherwise a dedicated anonymous CM client (raw `k_EMsgClientLogon`). Mirrors the
         * JavaSteam-era anonymous logon downloads of public Workshop depots.
         */
        internal suspend fun steamContentClient(authenticated: Boolean): SteamClient? {
            if (authenticated) {
                engine?.takeIf(SteamClient::hasUsableAuthenticatedConnection)?.let { return it }
                // The signed-in engine may briefly leave the Success state while kSteam
                // transparently re-connects its CM transport; wait a short window for it
                // before falling back to the anonymous client, whose CM list is far less
                // reliable (no cell id -> arbitrary global endpoints).
                withTimeoutOrNull(CONTENT_SESSION_WAIT_TIMEOUT_MS) {
                    engine?.let { client ->
                        combine(client.account.clientAuthState, client.connectionStatus) { auth, connection ->
                            auth is AuthorizationState.Success && connection.hasActiveServerConnection
                        }.first { it }
                    }
                }
                engine?.takeIf(SteamClient::hasUsableAuthenticatedConnection)?.let { return it }
            }
            return anonymousClient()
        }

        private suspend fun anonymousClient(): SteamClient? =
            anonymousMutex.withLock {
                anonymousEngineRef.get()?.let { cached ->
                    if (cached.connectionStatus.value.hasActiveServerConnection) return cached
                    anonymousEngineRef.compareAndSet(cached, null)
                    stopAnonymousClient(cached)
                }
                // Reuse the signed-in engine's cell id so Steam's CM directory returns
                // nearby, reachable servers; a zero cell id yields arbitrary global
                // endpoints that are frequently unroutable (NoRouteToHost).
                val inheritedCellId = engine?.configuration?.cellId ?: 0
                val created =
                    runCatching {
                        kSteam {
                            rootFolder = File(applicationContext.cacheDir, "ksteam-anonymous").absolutePath.toPath()
                            deviceInfo = deviceInformation()
                            loggingVerbosity = Logger.Verbosity.Warning
                            persistenceDriver = MemoryPersistenceDriver
                            ktor(::newKSteamHttpClient)
                        }
                    }.getOrNull() ?: return null
                if (inheritedCellId != 0) {
                    runCatching { created.configuration.cellId = inheritedCellId }
                }
                repeat(ANONYMOUS_CONNECT_ATTEMPTS) { attempt ->
                    try {
                        withTimeout(ANONYMOUS_CONNECT_TIMEOUT_MS) {
                            created.start()
                            created.connectionStatus.first { it.hasActiveServerConnection }
                            sendAnonymousLogon(created)
                        }
                        anonymousEngineRef.set(created)
                        recordSessionEvent(0, "anonymous_logon_success")
                        return created
                    } catch (error: TimeoutCancellationException) {
                        stopAnonymousClient(created)
                        recordSessionEvent(0, "anonymous_logon_failure", outcome = error.javaClass.simpleName)
                        if (attempt == ANONYMOUS_CONNECT_ATTEMPTS - 1) return null
                        delay(ANONYMOUS_RETRY_DELAY_MS)
                    } catch (error: CancellationException) {
                        stopAnonymousClient(created)
                        throw error
                    } catch (error: Throwable) {
                        stopAnonymousClient(created)
                        recordSessionEvent(0, "anonymous_logon_failure", outcome = error.javaClass.simpleName)
                        if (attempt == ANONYMOUS_CONNECT_ATTEMPTS - 1) return null
                        delay(ANONYMOUS_RETRY_DELAY_MS)
                    }
                }
                null
            }

        private suspend fun stopAnonymousClient(client: SteamClient) {
            withContext(NonCancellable) {
                withTimeoutOrNull(ANONYMOUS_STOP_TIMEOUT_MS) {
                    runCatching { client.stop() }
                }
            }
        }

        /**
         * Performs an anonymous CM logon with a raw `k_EMsgClientLogon` packet. kSteam's
         * `Account` handler is account-centric, but its CM transport whitelists the logon
         * message and adopts the granted session id/steam id for any successful logon, so
         * the resulting client can run signed service calls against public depots.
         */
        private suspend fun sendAnonymousLogon(client: SteamClient) {
            if (client.configuration.machineId.isEmpty()) {
                client.configuration.machineId = Random.nextBytes(64).toByteString().hex()
            }
            val response =
                client.awaitPacket(
                    SteamPacket.newProto(
                        messageId = EMsg.k_EMsgClientLogon,
                        payload =
                            CMsgClientLogon(
                                protocol_version = EnvironmentConstants.PROTOCOL_VERSION,
                                client_package_version = KSTEAM_CLIENT_PACKAGE_VERSION,
                                client_language = client.language.vdfName,
                                client_os_type = EOSType.k_eAndroidUnknown.encoded,
                                should_remember_password = true,
                                qos_level = 2,
                                machine_id = client.configuration.machineId.decodeHex(),
                                machine_name = KSTEAM_DEVICE_FRIENDLY_NAME,
                                supports_rate_limit_response = true,
                                access_token = "",
                            ),
                    ).withHeader {
                        sessionId = 0
                        steamId = SteamId.Empty.id
                    },
                )
            val logonResponse = CMsgClientLogonResponse.ADAPTER.decode(response.payload)
            check(logonResponse.eresult == EResult.OK.encoded) {
                "Anonymous Steam logon failed: EResult ${logonResponse.eresult}"
            }
        }

        /**
         * Requests the depot decryption key over the legacy CM message pair
         * (k_EMsgClientGetDepotDecryptionKey / Response). Requires a logged-on session.
         */
        suspend fun steamDepotDecryptionKey(
            client: SteamClient,
            depotId: Int,
            appId: Int,
        ): ByteArray {
            val requestPacket =
                SteamPacket(
                    messageId = EMsg.k_EMsgClientGetDepotDecryptionKey,
                    header = SteamPacketHeader.Protobuf(),
                    payload =
                        CMsgClientGetDepotDecryptionKey.ADAPTER
                            .encodeByteString(CMsgClientGetDepotDecryptionKey(depot_id = depotId, app_id = appId))
                            .toByteArray(),
                )
            val response = client.awaitProto(requestPacket, CMsgClientGetDepotDecryptionKeyResponse.ADAPTER)
            check(response.eresult == EResult.OK.encoded) {
                "Steam did not provide depot key $depotId: EResult ${response.eresult}"
            }
            return response.depot_encryption_key?.toByteArray()
                ?: error("Steam returned an empty depot key for depot $depotId")
        }
        // ------------------------------------------------------------------
        // Public Workshop RPCs (signed-in or anonymous CM session)
        // ------------------------------------------------------------------

        private suspend fun acquireWorkshopClient(): SteamClient? {
            engine?.takeIf(SteamClient::hasUsableAuthenticatedConnection)?.let { return it }
            anonymousEngineRef
                .get()
                ?.takeIf { it.connectionStatus.value.hasActiveServerConnection }
                ?.let { return it }
            restorePersistedSession()
            synchronized(restoreLock) { restoreJobRef.get() }
                ?.takeIf(Job::isActive)
                ?.let { restoreJob ->
                    withTimeoutOrNull(CONTENT_SESSION_WAIT_TIMEOUT_MS) { restoreJob.join() }
                    engine?.takeIf(SteamClient::hasUsableAuthenticatedConnection)?.let { return it }
                }
            return anonymousClient()
        }

        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? =
            withContext(Dispatchers.IO) {
                val client = acquireWorkshopClient() ?: return@withContext null
                try {
                    val page =
                        if (query.creatorId != null) {
                            val response =
                                awaitSteamRpc("public_get_user_files") {
                                    client.grpc.publishedFile.GetUserFiles().executeSteam(
                                        buildUnifiedWorkshopAuthorRequest(query),
                                    )
                                }
                            mapUnifiedWorkshopAuthorResponse(query, response)
                        } else {
                            val response =
                                awaitSteamRpc("public_query_files") {
                                    client.grpc.publishedFile.QueryFiles().executeSteam(
                                        buildUnifiedWorkshopBrowseRequest(query),
                                    )
                                }
                            mapUnifiedWorkshopBrowseResponse(query, response)
                        }
                    val profiles =
                        resolveSteamProfiles(
                            client,
                            page.items.mapNotNull { item -> item.creatorId?.toLongOrNull() }.toSet(),
                        )
                    page.copy(items = page.items.map { item -> decorateWithProfile(item, profiles) })
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(0, "public_browse_failure", outcome = error.displayMessage())
                    null
                }
            }

        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? =
            withContext(Dispatchers.IO) {
                val client = acquireWorkshopClient() ?: return@withContext null
                try {
                    val response =
                        awaitSteamRpc("public_get_details") {
                            client.grpc.publishedFile.GetDetails().executeSteam(
                                buildUnifiedWorkshopDetailRequest(workshopId),
                            )
                        }
                    val detail =
                        response.publishedfiledetails
                            .firstOrNull { item ->
                                item.publishedfileid == workshopId && item.result == EResult.OK.encoded
                            } ?: return@withContext null
                    val profile = resolveSteamProfiles(client, setOfNotNull(detail.creator))[detail.creator]
                    mapUnifiedWorkshopDetail(detail, profile)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(0, "public_detail_failure", outcome = error.displayMessage())
                    null
                }
            }

        override suspend fun getPublicDetails(workshopIds: List<Long>): List<WorkshopDetail> =
            withContext(Dispatchers.IO) {
                val ids = workshopIds.filter { it > 0L }.distinct()
                if (ids.isEmpty()) return@withContext emptyList()
                val client = acquireWorkshopClient() ?: return@withContext emptyList()
                try {
                    val response =
                        awaitSteamRpc("public_get_details_batch") {
                            client.grpc.publishedFile.GetDetails().executeSteam(
                                buildUnifiedWorkshopDetailRequest(ids),
                            )
                        }
                    val details = response.publishedfiledetails.filter { it.result == EResult.OK.encoded }
                    val profiles = resolveSteamProfiles(client, details.mapNotNull { it.creator }.toSet())
                    details.map { detail -> mapUnifiedWorkshopDetail(detail, profiles[detail.creator]) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(0, "public_details_batch_failure", outcome = error.displayMessage())
                    emptyList()
                }
            }

        override suspend fun getPublicCollectionChildren(collectionId: Long): List<Long>? =
            withContext(Dispatchers.IO) {
                val client = acquireWorkshopClient() ?: return@withContext null
                try {
                    val response =
                        awaitSteamRpc("public_get_collection_children") {
                            client.grpc.publishedFile.GetDetails().executeSteam(
                                buildUnifiedWorkshopDetailRequest(collectionId),
                            )
                        }
                    mapUnifiedCollectionChildIds(collectionId, response)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(0, "collection_children_failure", outcome = error.displayMessage())
                    null
                }
            }

        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? =
            withContext(Dispatchers.IO) {
                val client =
                    engine?.takeIf { it.account.clientAuthState.value is AuthorizationState.Success }
                        ?: return@withContext null
                try {
                    val response =
                        awaitSteamRpc("community_get_comment_thread") {
                            client.grpc.community.GetCommentThread().executeSteam(
                                buildCommunityCommentRequest(workshopId, ownerId, start, count),
                            )
                        }
                    val profiles =
                        resolveSteamProfiles(
                            client,
                            response.comments.mapNotNull { comment -> comment.steamid }.toSet(),
                        )
                    mapCommunityComments(
                        response = response,
                        requestedStart = start,
                        requestedCount = count,
                        creatorId = ownerId,
                        profiles = profiles,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(0, "community_comments_failure", outcome = error.displayMessage())
                    null
                }
            }

        // ------------------------------------------------------------------
        // Account Workshop RPCs (signed-in only)
        // ------------------------------------------------------------------

        private suspend fun requireSignedInClient(): SteamClient {
            val client =
                engine?.takeIf { it.account.clientAuthState.value is AuthorizationState.Success }
            if (client != null) return client
            error(mutableSession.value.message ?: "Restore the Steam login before using the personal library")
        }

        override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage =
            withContext(Dispatchers.IO) {
                val client = requireSignedInClient()
                val normalized = query.normalized()
                val steamId = client.currentSessionSteamId.longId
                val service = client.grpc.publishedFile

                suspend fun loadSourcePage(
                    sourcePage: Int,
                    sourcePageSize: Int,
                ) = awaitSteamRpc("library_get_user_files") {
                    service.GetUserFiles().executeSteam(
                        CPublishedFile_GetUserFiles_Request(
                            steamid = steamId,
                            appid = WALLPAPER_ENGINE_APP_ID,
                            page = sourcePage,
                            numperpage = sourcePageSize,
                            type = normalized.collection.steamListType(),
                            sortmethod = "lastupdated",
                            return_tags = true,
                            return_previews = true,
                            return_short_description = true,
                            return_metadata = true,
                            return_vote_data = true,
                        ),
                    )
                }

                val hasClientFilter =
                    normalized.searchText.isNotEmpty() ||
                        normalized.type != null ||
                        normalized.types.isNotEmpty() ||
                        normalized.tags.isNotEmpty() ||
                        normalized.ratings.isNotEmpty() ||
                        (
                            normalized.genres.isNotEmpty() &&
                                normalized.genres.size < WorkshopFilterCatalog.genres.size
                        ) ||
                        normalized.officialTags.isNotEmpty() ||
                        normalized.excludedOfficialTags.isNotEmpty() ||
                        (
                            normalized.resolutions.isNotEmpty() &&
                                normalized.resolutions.size < WorkshopFilterCatalog.resolutions.size
                        )
                if (!hasClientFilter) {
                    val response = loadSourcePage(normalized.page, normalized.pageSize)
                    val summaries =
                        response.publishedfiledetails
                            .asSequence()
                            .filter { detail -> detail.result == EResult.OK.encoded }
                            .map { detail -> detail.toWorkshopSummary(normalized.collection) }
                            .toList()
                    return@withContext WorkshopPage(
                        items = summaries,
                        page = normalized.page,
                        hasNextPage =
                            (response.total ?: 0).toLong() >
                                normalized.page.toLong() * normalized.pageSize.toLong() ||
                                summaries.size >= normalized.pageSize,
                        totalCount = response.total?.takeIf { it > 0 },
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
                    val details = response.publishedfiledetails
                    matches +=
                        details
                            .asSequence()
                            .filter { detail -> detail.result == EResult.OK.encoded }
                            .map { detail -> detail.toWorkshopSummary(normalized.collection) }
                            .filter { summary -> normalized.matchesAccountCollectionItem(summary) }
                            .toList()
                    sourceExhausted =
                        (
                            (response.total ?: 0) > 0 &&
                                sourcePage.toLong() * MAX_ACCOUNT_WORKSHOP_PAGE_SIZE >= (response.total ?: 0).toLong()
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
            require(workshopId > 0L) { "Invalid Workshop item ID" }
            authorDisplayNames[workshopId]?.let { return it }
            return withContext(Dispatchers.IO) {
                try {
                    val client = requireSignedInClient()
                    val response =
                        awaitSteamRpc("author_get_workshop_details") {
                            client.grpc.publishedFile.GetDetails().executeSteam(
                                buildUnifiedWorkshopDetailRequest(workshopId),
                            )
                        }
                    val creatorId =
                        response.publishedfiledetails
                            .firstOrNull { detail ->
                                detail.publishedfileid == workshopId && detail.result == EResult.OK.encoded
                            }?.creator
                            ?.takeIf { it > 0L }
                            ?: return@withContext null
                    resolveSteamProfiles(client, setOf(creatorId))[creatorId]
                        ?.displayName
                        ?.also { displayName -> authorDisplayNames[workshopId] = displayName }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(0, "author_display_name_failure", outcome = error.displayMessage())
                    null
                }
            }
        }

        override suspend fun getInteraction(workshopId: Long): WorkshopInteraction =
            withContext(Dispatchers.IO) {
                readInteraction(requireSignedInClient(), workshopId)
            }

        override suspend fun setSubscribed(
            workshopId: Long,
            subscribed: Boolean,
        ): WorkshopInteraction =
            withContext(Dispatchers.IO) {
                require(workshopId > 0L) { "Invalid Workshop item ID" }
                val client = requireSignedInClient()
                if (subscribed) {
                    awaitSteamRpc("subscribe") {
                        client.grpc.publishedFile.Subscribe().executeSteam(
                            CPublishedFile_Subscribe_Request(
                                publishedfileid = workshopId,
                                list_type = SUBSCRIPTION_LIST_TYPE,
                                appid = WALLPAPER_ENGINE_APP_ID,
                                notify_client = true,
                                include_dependencies = true,
                            ),
                        )
                    }
                } else {
                    awaitSteamRpc("unsubscribe") {
                        client.grpc.publishedFile.Unsubscribe().executeSteam(
                            CPublishedFile_Unsubscribe_Request(
                                publishedfileid = workshopId,
                                list_type = SUBSCRIPTION_LIST_TYPE,
                                appid = WALLPAPER_ENGINE_APP_ID,
                                notify_client = true,
                            ),
                        )
                    }
                }
                readInteractionOrExpected(
                    client = client,
                    workshopId = workshopId,
                    expectedSubscription =
                        if (subscribed) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED,
                )
            }

        override suspend fun setFavorited(
            workshopId: Long,
            favorited: Boolean,
        ): WorkshopInteraction =
            withContext(Dispatchers.IO) {
                require(workshopId > 0L) { "Invalid Workshop item ID" }
                val client = requireSignedInClient()
                if (favorited) {
                    awaitSteamRpc("favorite") {
                        client.grpc.publishedFile.AddAppRelationship().executeSteam(
                            CPublishedFile_AddAppRelationship_Request(
                                publishedfileid = workshopId,
                                appid = WALLPAPER_ENGINE_APP_ID,
                                relationship = FAVORITE_RELATIONSHIP,
                            ),
                        )
                    }
                } else {
                    awaitSteamRpc("unfavorite") {
                        client.grpc.publishedFile.RemoveAppRelationship().executeSteam(
                            CPublishedFile_RemoveAppRelationship_Request(
                                publishedfileid = workshopId,
                                appid = WALLPAPER_ENGINE_APP_ID,
                                relationship = FAVORITE_RELATIONSHIP,
                            ),
                        )
                    }
                }
                readInteractionOrExpected(
                    client = client,
                    workshopId = workshopId,
                    expectedFavorite = if (favorited) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED,
                )
            }

        override suspend fun postComment(
            workshopId: Long,
            ownerId: String,
            text: String,
        ) {
            withContext(Dispatchers.IO) {
                val client = requireSignedInClient()
                val normalized = normalizeWorkshopCommentRequest(workshopId, ownerId, text)
                awaitSteamRpc("community_post_comment") {
                    client.grpc.community.PostCommentToThread().executeSteam(
                        buildCommunityPostRequest(normalized),
                    )
                }
                Unit
            }
        }

        override suspend fun getAppPlaytime(appId: Int): SteamAppPlaytime? =
            withContext(Dispatchers.IO) {
                try {
                    val client = requireSignedInClient()
                    val steamId = client.currentSessionSteamId.longId
                    val response =
                        awaitSteamRpc("player_get_owned_games") {
                            client.grpc.player.GetOwnedGames().executeSteam(
                                CPlayer_GetOwnedGames_Request(
                                    steamid = steamId,
                                    include_appinfo = false,
                                    include_played_free_games = true,
                                    appids_filter = listOf(appId),
                                ),
                            )
                        }
                    response.games
                        .firstOrNull { game -> game.appid == appId }
                        ?.let { game ->
                            SteamAppPlaytime(
                                appId = game.appid ?: appId,
                                totalMinutes = game.playtime_forever ?: 0,
                                recentMinutes = game.playtime_2weeks ?: 0,
                                lastPlayedEpochSeconds = game.rtime_last_played?.takeIf { it > 0 }?.toLong(),
                            )
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordSessionEvent(0, "playtime_failure", outcome = error.displayMessage())
                    null
                }
            }

        private suspend fun readInteraction(
            client: SteamClient,
            workshopId: Long,
        ): WorkshopInteraction {
            require(workshopId > 0L) { "Invalid Workshop item ID" }
            val subscriptionState =
                runCatching {
                    awaitSteamRpc("read_subscription") {
                        client.grpc.publishedFile.AreFilesInSubscriptionList().executeSteam(
                            CPublishedFile_AreFilesInSubscriptionList_Request(
                                appid = WALLPAPER_ENGINE_APP_ID,
                                publishedfileids = listOf(workshopId),
                                listtype = SUBSCRIPTION_LIST_TYPE,
                            ),
                        )
                    }.files
                        .firstOrNull { item -> item.publishedfileid == workshopId }
                        ?.inlist
                        ?.let { inList ->
                            if (inList) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED
                        }
                        ?: SubscriptionState.UNKNOWN
                }.getOrDefault(SubscriptionState.UNKNOWN)
            val favoriteState =
                runCatching {
                    awaitSteamRpc("read_favorite") {
                        client.grpc.publishedFile.GetAppRelationships().executeSteam(
                            CPublishedFile_GetAppRelationships_Request(publishedfileid = workshopId),
                        )
                    }.app_relationships
                        .any { relationship ->
                            relationship.appid == WALLPAPER_ENGINE_APP_ID &&
                                relationship.relationship == FAVORITE_RELATIONSHIP
                        }.let { favorited ->
                            if (favorited) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED
                        }
                }.getOrDefault(FavoriteState.UNKNOWN)
            return WorkshopInteraction(subscriptionState, favoriteState)
        }

        private suspend fun readInteractionOrExpected(
            client: SteamClient,
            workshopId: Long,
            expectedSubscription: SubscriptionState = SubscriptionState.UNKNOWN,
            expectedFavorite: FavoriteState = FavoriteState.UNKNOWN,
        ): WorkshopInteraction {
            val current =
                runCatching { readInteraction(client, workshopId) }
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

        // ------------------------------------------------------------------
        // Diagnostics
        // ------------------------------------------------------------------

        internal fun publishPhase(
            phase: SteamSessionPhase,
            message: String?,
            accountName: String? = null,
            personaName: String? = null,
            avatarUrl: String? = null,
            hasStoredSession: Boolean? = null,
        ) {
            val previous = mutableSession.value
            val next =
                SteamSessionState(
                    phase = phase,
                    accountName = accountName ?: previous.accountName,
                    personaName = personaName ?: previous.personaName,
                    avatarUrl = avatarUrl ?: previous.avatarUrl,
                    message = message,
                    requiresCode = phase == SteamSessionPhase.WAITING_FOR_CODE,
                    awaitingDeviceConfirmation = phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                    hasStoredSession = hasStoredSession ?: previous.hasStoredSession,
                )
            mutableSession.value = next
            recordState(next)
        }

        internal suspend fun <T> awaitSteamRpc(
            operation: String,
            block: suspend () -> T,
        ): T {
            val startedAt = System.nanoTime()
            recordSessionEvent(0, "rpc_start", outcome = operation)
            return try {
                withTimeout(STEAM_RPC_TIMEOUT_MS) { block() }
            } catch (error: TimeoutCancellationException) {
                recordSessionEvent(0, "rpc_timeout", outcome = operation)
                throw IllegalStateException("Steam request timed out; try again later")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordSessionEvent(0, "rpc_failure", outcome = "$operation:${error.javaClass.simpleName}")
                throw error
            }.also {
                recordSessionEvent(0, "rpc_success", outcome = operation)
            }
        }

        internal fun recordSessionEvent(
            generation: Long,
            stage: String,
            outcome: String? = null,
        ) {
            scope.launch {
                runCatching {
                    diagnostics.record(
                        DiagnosticEvent(
                            source = "steam-session",
                            level =
                                if (
                                    stage.contains("failure") ||
                                    stage.contains("timeout") ||
                                    stage.contains("disconnect") ||
                                    stage.contains("expired")
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
                                    outcome?.let { put("outcome", it) }
                                },
                        ),
                    )
                }
            }
        }

        internal fun recordState(state: SteamSessionState) {
            scope.launch {
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
                            attributes = mapOf("phase" to state.phase.name),
                        ),
                    )
                }
            }
        }

        internal companion object {
            const val ANONYMOUS_CONNECT_TIMEOUT_MS = 20_000L
            const val ANONYMOUS_STOP_TIMEOUT_MS = 2_000L
            const val ANONYMOUS_CONNECT_ATTEMPTS = 3
            const val ANONYMOUS_RETRY_DELAY_MS = 2_000L
            const val CONTENT_SESSION_WAIT_TIMEOUT_MS = 12_000L
            const val RESTORE_TOTAL_TIMEOUT_MS = 60_000L
            const val CONTENT_CREDENTIAL_RESTORE_TIMEOUT_MS = 30_000L
            const val STEAM_RPC_TIMEOUT_MS = 25_000L
            const val FOREGROUND_SESSION_REFRESH_AFTER_BACKGROUND_MS = 2 * 60_000L
            const val MIGRATED_ACCESS_TOKEN_PLACEHOLDER = "wallhub-migrated"
        }
    }

internal fun Throwable.displayMessage(): String =
    message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

internal fun steamLoginPhaseForConfirmations(
    methods: Collection<AuthorizationState.AwaitingTwoFactor.ConfirmationMethod>,
): SteamSessionPhase {
    val requiresCode =
        methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.DeviceCode) ||
            methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.EmailCode)
    val awaitingDevice =
        methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.DeviceConfirmation) ||
            methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.EmailConfirmation)
    val requiresMachineToken =
        methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.MachineToken)
    return when {
        requiresCode -> SteamSessionPhase.WAITING_FOR_CODE
        awaitingDevice -> SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
        requiresMachineToken -> SteamSessionPhase.FAILED
        else -> SteamSessionPhase.SIGNING_IN
    }
}
