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

/** Owns the foreground anonymous CM WebSocket session used by every public Workshop read. */
@Singleton
internal class SteamWorkshopPublicSessionRouter
    @Inject
    constructor(
        @SteamAnonymousWorkshopEngine private val anonymousEngine: SteamContentSdkEngine,
        private val routes: SteamContentSdkRouteFactory,
    ) {
        private val anonymousMutex = Mutex()

        suspend fun acquire(): SteamContentSdkEngine = acquireAnonymous()

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
                    cmTransport = SessionTransportKind.WEBSOCKET,
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

        suspend fun onAccountAuthenticated() {
            anonymousMutex.withLock { anonymousEngine.closeSession() }
        }

        suspend fun onAppBackgrounded() {
            anonymousMutex.withLock { anonymousEngine.closeSession() }
        }

        private companion object {
            const val ANONYMOUS_READY_TIMEOUT_MS = 8_000L
            const val ANONYMOUS_POLL_MS = 50L
        }
    }
