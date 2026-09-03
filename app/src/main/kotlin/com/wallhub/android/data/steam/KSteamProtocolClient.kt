package com.wallhub.android.data.steam

import android.content.Context
import bruhcollective.itaysonlab.ksteam.SteamClient
import bruhcollective.itaysonlab.ksteam.handlers.Account
import bruhcollective.itaysonlab.ksteam.handlers.Logger
import bruhcollective.itaysonlab.ksteam.kSteam
import bruhcollective.itaysonlab.ksteam.models.account.AuthorizationState
import bruhcollective.itaysonlab.ksteam.models.enums.EGamingDeviceType
import bruhcollective.itaysonlab.ksteam.models.enums.EOSType
import bruhcollective.itaysonlab.ksteam.persistence.KsteamPersistenceDriver
import bruhcollective.itaysonlab.ksteam.platform.DeviceInformation
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
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.security.AndroidKeystoreEncryptedStringStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toOkioPath

/**
 * kSteam-backed implementation of the engine-neutral [SteamProtocolClient] seam
 * (hybrid migration Phase B).
 *
 * Engine status, deliberately conservative:
 * - Session core (start/login/Steam Guard/refresh-token restore/lifecycle) is wired to
 *   kSteam's Account handler and persistence.
 * - Workshop browse, account collections, comments, interactions and playtime are not yet
 *   ported; they degrade gracefully (null/empty/no-op) with a diagnostic notice so this
 *   engine can run in parallel shadow mode without breaking feature contracts.
 *
 * The active production engine remains the JavaSteam-backed `SecureSteamSessionRepository`.
 */
@Singleton
class KSteamProtocolClient
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val diagnostics: DiagnosticRepository,
    ) : SteamSessionRepository,
        SteamContentCredentialProvider,
        SteamUnifiedWorkshopRepository,
        AccountWorkshopRepository,
        SteamPlaytimeRepository,
        SteamProtocolClient {
        private val applicationContext = context.applicationContext
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val engineMutex = Mutex()
        private val mutableSession = MutableStateFlow(SteamSessionState())
        private val pendingAccountName = AtomicReference<String?>(null)

        private var engine: SteamClient? = null
        private var authCollector: Job? = null

        @Volatile
        private var engineConnected: Boolean = false

        @Volatile
        private var degradationNoticed: Boolean = false

        override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

        private suspend fun obtainEngine(): SteamClient =
            engineMutex.withLock {
                engine?.let { return it }
                val created =
                    kSteam {
                        rootFolder = File(applicationContext.filesDir, "ksteam").toOkioPath()
                        deviceInfo =
                            DeviceInformation(
                                osType = EOSType.k_eAndroidUnknown,
                                gamingDeviceType = EGamingDeviceType.k_EGamingDeviceType_Phone,
                                deviceName = "WallHub",
                                platformType = steam.enums.EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient,
                            )
                        loggingVerbosity = Logger.Verbosity.Warning
                        persistenceDriver = ksteamPersistenceDriver(applicationContext)
                    }
                authCollector =
                    scope.launch {
                        created.account.clientAuthState.collect { state ->
                            onAuthorizationState(state, created.account.hasSavedDataForAtLeastOneAccount())
                        }
                    }
                engine = created
                created
            }

        private fun ksteamPersistenceDriver(context: Context): KsteamPersistenceDriver =
            KsteamEncryptedPersistenceDriver(context)

        private suspend fun ensureConnected(client: SteamClient) {
            if (engineConnected) return
            client.start()
            engineConnected = true
        }

        private fun onAuthorizationState(
            state: AuthorizationState,
            hasStoredSession: Boolean,
        ) {
            val accountName = pendingAccountName.get()
            val nextState =
                when (state) {
                    AuthorizationState.Unauthorized ->
                        SteamSessionState(
                            phase = SteamSessionPhase.SIGNED_OUT,
                            hasStoredSession = hasStoredSession,
                        )

                    AuthorizationState.Success ->
                        SteamSessionState(phase = SteamSessionPhase.SIGNED_IN, accountName = accountName)

                    is AuthorizationState.AwaitingTwoFactor -> {
                        val methods = state.supportedConfirmationMethods
                        SteamSessionState(
                            phase = SteamSessionPhase.WAITING_FOR_CODE,
                            accountName = accountName,
                            requiresCode =
                                methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.DeviceCode) ||
                                    methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.EmailCode),
                            awaitingDeviceConfirmation =
                                methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.DeviceConfirmation) ||
                                    methods.contains(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.EmailConfirmation),
                        )
                    }
                }
            mutableSession.value = nextState
        }

        override fun restorePersistedSession() {
            scope.launch {
                try {
                    val client = obtainEngine()
                    ensureConnected(client)
                    if (client.account.hasSavedDataForAtLeastOneAccount()) {
                        mutableSession.value = mutableSession.value.copy(phase = SteamSessionPhase.SIGNING_IN)
                        val restored = client.account.trySignInSavedDefault()
                        if (!restored) {
                            mutableSession.value =
                                SteamSessionState(
                                    phase = SteamSessionPhase.RESTORABLE,
                                    hasStoredSession = true,
                                    message = "Saved kSteam session could not be restored",
                                )
                        }
                    } else {
                        mutableSession.value = SteamSessionState(phase = SteamSessionPhase.SIGNED_OUT)
                    }
                } catch (error: Throwable) {
                    mutableSession.value =
                        SteamSessionState(
                            phase = SteamSessionPhase.FAILED,
                            message = error.message ?: error.javaClass.simpleName,
                        )
                }
            }
        }

        override fun login(
            accountName: String,
            password: String,
        ) {
            if (accountName.isBlank() || password.isBlank()) {
                mutableSession.value =
                    SteamSessionState(phase = SteamSessionPhase.FAILED, message = "Enter an account name and password")
                return
            }
            scope.launch {
                try {
                    pendingAccountName.set(accountName.trim())
                    mutableSession.value =
                        SteamSessionState(phase = SteamSessionPhase.SIGNING_IN, accountName = accountName.trim())
                    val client = obtainEngine()
                    ensureConnected(client)
                    when (client.account.signIn(username = accountName.trim(), password = password, rememberSession = true)) {
                        Account.AuthorizationResult.ProceedToTfa -> Unit
                        // The authorization state flow publishes the confirmation UI state.
                        Account.AuthorizationResult.InvalidPassword ->
                            mutableSession.value =
                                SteamSessionState(
                                    phase = SteamSessionPhase.FAILED,
                                    accountName = accountName.trim(),
                                    message = "The password does not match",
                                )

                        Account.AuthorizationResult.RpcError ->
                            mutableSession.value =
                                SteamSessionState(
                                    phase = SteamSessionPhase.FAILED,
                                    accountName = accountName.trim(),
                                    message = "Steam network RPC error",
                                )
                    }
                } catch (error: Throwable) {
                    mutableSession.value =
                        SteamSessionState(
                            phase = SteamSessionPhase.FAILED,
                            accountName = pendingAccountName.get(),
                            message = error.message ?: error.javaClass.simpleName,
                        )
                }
            }
        }

        override fun submitSteamGuardCode(code: String) {
            if (code.isBlank()) return
            scope.launch {
                val accepted =
                    runCatching { engine?.account?.updateCurrentSessionWithCode(code.trim()) }.getOrDefault(false)
                if (!accepted) {
                    mutableSession.value = mutableSession.value.copy(message = "Steam Guard code was not accepted")
                }
            }
        }

        override fun useManualSteamGuardFallback() {
            runCatching { engine?.account?.cancelPolling() }
            mutableSession.value = mutableSession.value.copy(phase = SteamSessionPhase.WAITING_FOR_CODE, requiresCode = true)
        }

        override fun logout() {
            scope.launch {
                engineMutex.withLock {
                    runCatching { engine?.stop() }
                    authCollector?.cancel()
                    authCollector = null
                    engine = null
                    engineConnected = false
                }
                pendingAccountName.set(null)
                mutableSession.value = SteamSessionState(phase = SteamSessionPhase.SIGNED_OUT)
            }
        }

        override fun onAppForegrounded() {
            scope.launch {
                runCatching {
                    if (engine != null && engineConnected) engine?.resume()
                }
            }
        }

        override fun onAppBackgrounded() {
            scope.launch {
                runCatching {
                    if (engine != null && engineConnected) engine?.pause()
                }
            }
        }

        override suspend fun loadContentCredential(): SteamContentCredential? {
            // Depot credentials remain owned by the JavaSteam session until the depot
            // pipeline migrates to the Rust engine; anonymous downloads are unaffected.
            return null
        }

        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? {
            noticeDegraded("browsePublic")
            return null
        }

        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? {
            noticeDegraded("getPublicDetail")
            return null
        }

        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? {
            noticeDegraded("getAuthenticatedComments")
            return null
        }

        override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage {
            noticeDegraded("browseCollection")
            return WorkshopPage(items = emptyList<WorkshopSummary>(), page = query.page, hasNextPage = false)
        }

        override suspend fun resolveAuthorDisplayName(workshopId: Long): String? {
            noticeDegraded("resolveAuthorDisplayName")
            return null
        }

        override suspend fun getInteraction(workshopId: Long): WorkshopInteraction {
            noticeDegraded("getInteraction")
            return WorkshopInteraction(subscriptionState = SubscriptionState.UNKNOWN, favoriteState = FavoriteState.UNKNOWN)
        }

        override suspend fun setSubscribed(
            workshopId: Long,
            subscribed: Boolean,
        ): WorkshopInteraction {
            noticeDegraded("setSubscribed")
            return getInteraction(workshopId)
        }

        override suspend fun setFavorited(
            workshopId: Long,
            favorited: Boolean,
        ): WorkshopInteraction {
            noticeDegraded("setFavorited")
            return getInteraction(workshopId)
        }

        override suspend fun postComment(
            workshopId: Long,
            ownerId: String,
            text: String,
        ) {
            noticeDegraded("postComment")
        }

        override suspend fun getAppPlaytime(appId: Int): SteamAppPlaytime? {
            noticeDegraded("getAppPlaytime")
            return null
        }

        private suspend fun noticeDegraded(operation: String) {
            if (degradationNoticed) return
            degradationNoticed = true
            diagnostics.record(
                DiagnosticEvent(
                    source = "KSteamProtocolClient",
                    level = DiagnosticLevel.WARNING,
                    message = "kSteam engine capability not yet ported: $operation (engine runs in shadow mode)",
                ),
            )
        }
    }
