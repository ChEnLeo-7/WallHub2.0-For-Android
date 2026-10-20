package com.wallhub.android.data.steam

import com.wallhub.android.di.SteamAnonymousWorkshopEngine
import io.steamcontent.sdk.SessionState
import io.steamcontent.sdk.SessionTransportKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Selects the authenticated CM session when ready, otherwise owns one foreground anonymous public session. */
@Singleton
internal class SteamWorkshopPublicSessionRouter
    @Inject
    constructor(
        private val authenticatedSessions: SteamContentSdkAuthenticatedSessionRegistry,
        @SteamAnonymousWorkshopEngine private val anonymousEngine: SteamContentSdkEngine,
        private val routes: SteamContentSdkRouteFactory,
    ) {
        private val anonymousMutex = Mutex()

        suspend fun acquire(): SteamContentSdkEngine {
            authenticatedSessions.currentAuthenticated()?.let { return it }
            withTimeoutOrNull(ACCOUNT_FAST_PATH_WAIT_MS) {
                while (authenticatedSessions.currentAuthenticated() == null) delay(ACCOUNT_POLL_MS)
            }
            authenticatedSessions.currentAuthenticated()?.let { return it }
            return acquireAnonymous()
        }

        suspend fun acquireAnonymous(): SteamContentSdkEngine =
            anonymousMutex.withLock {
                if (anonymousEngine.state() == SessionState.AUTHENTICATED) return@withLock anonymousEngine
                runCatching { openAnonymous(refreshLive = false) }
                    .getOrElse {
                        anonymousEngine.closeSession()
                        openAnonymous(refreshLive = true)
                    }
            }

        private suspend fun openAnonymous(refreshLive: Boolean): SteamContentSdkEngine {
            val route =
                routes.platformRoute(
                    refreshLive = refreshLive,
                    cmTransport = SessionTransportKind.STEAM3,
                )
                ?: error("The platform's Steam route is unavailable for anonymous public reads")
            val session = anonymousEngine.open(route)
                ?: error("The anonymous Steam Content SDK session is unavailable")
            session.beginAnonymousLogon()
            withTimeout(ANONYMOUS_READY_TIMEOUT_MS) {
                while (session.state() != SessionState.AUTHENTICATED) {
                    session.pollEvent(ANONYMOUS_POLL_MS.toInt())
                    delay(ANONYMOUS_POLL_MS)
                }
            }
            return anonymousEngine
        }

        suspend fun awaitAuthenticated(timeoutMs: Long = PUBLIC_FAILURE_DEADLINE_MS): SteamContentSdkEngine? =
            withTimeoutOrNull(timeoutMs) {
                var authenticated = authenticatedSessions.currentAuthenticated()
                while (authenticated == null) {
                    delay(ACCOUNT_POLL_MS)
                    authenticated = authenticatedSessions.currentAuthenticated()
                }
                authenticated
            }

        suspend fun onAccountAuthenticated() {
            anonymousMutex.withLock { anonymousEngine.closeSession() }
        }

        suspend fun onAppBackgrounded() {
            anonymousMutex.withLock { anonymousEngine.closeSession() }
        }

        private companion object {
            const val ACCOUNT_FAST_PATH_WAIT_MS = 300L
            const val ACCOUNT_POLL_MS = 25L
            const val ANONYMOUS_READY_TIMEOUT_MS = 8_000L
            const val ANONYMOUS_POLL_MS = 50L
            const val PUBLIC_FAILURE_DEADLINE_MS = 8_000L
        }
    }
