package com.wallhub.android.data.steam

import com.wallhub.android.data.steamaccess.SteamLoopbackTlsBridge
import com.wallhub.android.data.steamaccess.WallHubPrivateCa
import io.steamcontent.sdk.SessionTransportKind
import java.net.InetSocketAddress
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Steam Content SDK's own route description from what this platform already has.
 *
 * The SDK deliberately takes route inputs and never guesses one. This class is the only place that knows both
 * vocabularies, and it exists so the adapter above it deals in SDK types only:
 *
 * - **the CM route** is the platform's loopback bridge, which is the only path this network passes. The SDK is
 *   therefore told to use the WebSocket transport - the bridge terminates TLS with a platform-owned root - and
 *   is given the bridge's endpoint and its authorization value, which the platform generated in-process.
 * - **the trust** stays the platform store plus exactly one extra root, the bridge's own certificate authority,
 *   and that root is scoped to a route that needs it. A direct route trusts the system store alone.
 * - **discovery** is the platform's job here. The engine's own directory request does not go through the CM
 *   proxy, so on a network that only passes the bridge it returns no usable endpoint set; the platform reads
 *   the CM directory itself, over the same route its other Steam traffic takes, and hands the endpoints over.
 */
@Singleton
internal class SteamContentSdkRouteFactory
    @Inject
    constructor(
        private val privateCa: WallHubPrivateCa,
        private val bridge: SteamLoopbackTlsBridge,
        private val cmEndpoints: SteamCmEndpointSource,
    ) {
        /**
         * The route this platform actually has, for a network that passes no Steam CM traffic directly.
         *
         * Returns null when the bridge has no loopback endpoint, which means the platform route is unavailable
         * rather than "connect directly": the caller must not silently fall back on a blocked path.
         *
         * A directory that yields no endpoint leaves the endpoint set empty and the engine to its own
         * discovery, rather than inventing one.
         */
        suspend fun throughPlatformBridge(cdnProxy: String? = null): SteamContentSdkEngine.Route? {
            val address = bridge.proxy.address() as? InetSocketAddress ?: return null
            val host = address.address?.hostAddress ?: address.hostString
            if (host.isNullOrEmpty() || address.port <= 0) return null
            return SteamContentSdkEngine.Route(
                cmCandidates = cmEndpoints.candidates(),
                cmProxy = "$host:${address.port}",
                cmProxyAuthorization = bridge.proxyAuthorization,
                transport = SessionTransportKind.WEBSOCKET,
                extraTrustRootPem = privateCa.rootCertificate().toPemBundle(),
                cdnProxy = cdnProxy,
            )
        }

        /** The route for a network that passes Steam directly. No extra root is trusted here. */
        fun direct(cdnProxy: String? = null): SteamContentSdkEngine.Route =
            SteamContentSdkEngine.Route(cdnProxy = cdnProxy)

        /**
         * Renders the platform's interception root as one PEM certificate.
         *
         * The SDK takes a PEM bundle because Android keeps the system store as one PEM file per certificate,
         * and appending a root is what an application does with a store, not a special API.
         */
        private fun java.security.cert.X509Certificate.toPemBundle(): String {
            val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(encoded)
            return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
        }
    }
