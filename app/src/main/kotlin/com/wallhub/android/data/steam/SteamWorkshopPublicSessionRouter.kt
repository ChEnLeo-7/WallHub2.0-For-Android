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

/**
 * Owns the foreground anonymous CM WebSocket session used by every public Workshop read.
 *
 * The session is bound to the egress it was opened on. The route is read from the one plan, so when that
 * plan names a different egress the next read closes this session first instead of continuing on the
 * previous route - otherwise browsing would keep the old proxy while the account and download planes had
 * already moved, which is the split egress this app exists to avoid.
 */
@Singleton
internal class SteamWorkshopPublicSessionRouter
    @Inject
    constructor(
        @SteamAnonymousWorkshopEngine private val anonymousEngine: SteamContentSdkEngine,
        private val routes: SteamContentSdkRouteFactory,
    ) {
        private val anonymousMutex = Mutex()

        /** The egress the live anonymous session was opened on; null when no session is open. */
        private var openedEgress: String? = null

        suspend fun acquire(): SteamContentSdkEngine = acquireAnonymous()

        suspend fun acquireAnonymous(): SteamContentSdkEngine =
            anonymousMutex.withLock {
                if (anonymousEngine.state() == SessionState.AUTHENTICATED) {
                    if (openedEgress == routes.currentEgressIdentity()) return@withLock anonymousEngine
                    anonymousEngine.closeSession()
                    openedEgress = null
                }
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
            // Recorded after the route was built, so it names the plan the session's route came from.
            openedEgress = routes.currentEgressIdentity()
            return anonymousEngine
        }

        suspend fun onAccountAuthenticated() {
            anonymousMutex.withLock {
                anonymousEngine.closeSession()
                openedEgress = null
            }
        }

        suspend fun onAppBackgrounded() {
            anonymousMutex.withLock {
                anonymousEngine.closeSession()
                openedEgress = null
            }
        }

        private companion object {
            const val ANONYMOUS_READY_TIMEOUT_MS = 8_000L
            const val ANONYMOUS_POLL_MS = 50L
        }
    }
