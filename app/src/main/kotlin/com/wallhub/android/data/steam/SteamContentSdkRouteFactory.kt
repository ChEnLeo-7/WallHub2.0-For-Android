package com.wallhub.android.data.steam

import io.steamcontent.sdk.SessionTransportKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Steam Content SDK's own route description from what this platform already has.
 *
 * The SDK deliberately takes route inputs and never guesses one. This class is the only place that knows both
 * vocabularies, and it exists so the adapter above it deals in SDK types only:
 *
 * - **the CM channel** is reached directly, with the endpoint set the platform read from Steam's directory.
 *   That is how the platform's own signed-in transport reaches it, and the engine's own discovery cannot
 *   reach the directory on a network that only passes the platform's bridge.
 * - **the directory fetch** is the platform's job, through the same bridged client its other Web API calls
 *   use, so the endpoint set it hands over is one the platform already proved reachable.
 * - **trust** stays the platform store. The bridge's own root is only needed by a route that terminates TLS
 *   locally, and this route does not.
 */
@Singleton
internal class SteamContentSdkRouteFactory
    @Inject
    constructor(
        private val cmEndpoints: SteamCmEndpointSource,
    ) {
        /**
         * The route this platform actually has.
         *
         * The channel is reached the way the platform's own transport reaches it: directly, with the endpoint
         * set the platform read from Steam's directory, because the engine's own discovery cannot reach that
         * directory on a network that only passes the platform's bridge. The directory fetch itself goes
         * through the bridge, like every other Web API call the platform makes, and so does the trust the
         * CDN needs if a caller supplies one.
         *
         * The loopback bridge is deliberately **not** used for the channel. It only tunnels a route the
         * platform has already discovered, and it never discovers a CM endpoint: `shouldAccelerate` and
         * `requestRouteRefresh` both cover the Web API and community hosts only, so a bridged CM channel
         * cannot come up at all. The platform's own signed-in transport proves the direct channel works here.
         *
         * Returns null when the platform has no endpoint set to offer, which means the platform route is
         * unavailable rather than "connect directly": the caller must not silently fall back to the engine's
         * own discovery on a network that blocks it.
         */
        suspend fun platformRoute(cdnProxy: String? = null): SteamContentSdkEngine.Route? {
            val candidates = cmEndpoints.candidates() ?: return null
            return SteamContentSdkEngine.Route(
                cmCandidates = candidates,
                transport = SessionTransportKind.WEBSOCKET,
                cdnProxy = cdnProxy,
            )
        }

        /** The route for a network that passes Steam directly. No extra root is trusted here. */
        fun direct(cdnProxy: String? = null): SteamContentSdkEngine.Route =
            SteamContentSdkEngine.Route(cdnProxy = cdnProxy)
    }
