package com.wallhub.android.data.steamaccess

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SteamHttpClientFactory
    @Inject
    internal constructor(
        private val manager: SteamAccessManager,
        private val bridge: SteamLoopbackTlsBridge,
        private val privateCa: WallHubPrivateCa,
    ) {
        private val systemProxySelector = ProxySelector.getDefault()

        private val proxySelector =
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> {
                    val host = uri?.host ?: return systemProxies(uri)
                    val port = uri.port.takeIf { it > 0 } ?: STEAM_HTTPS_PORT
                    val useBridge =
                        isSteamSecureScheme(uri.scheme) &&
                            SteamDomainPolicy.supportsEndpoint(host, port) &&
                            SteamDomainPolicy.accelerates(host) &&
                            manager.enhancedConnectionEnabled()
                    return if (useBridge) listOf(bridge.proxy) else systemProxies(uri)
                }

                override fun connectFailed(
                    uri: URI?,
                    socketAddress: SocketAddress?,
                    error: IOException?,
                ) {
                    if (socketAddress == bridge.proxy.address()) {
                        manager.recordBridgeFailure(error ?: IOException("Loopback proxy connection failed"))
                    } else {
                        systemProxySelector?.connectFailed(uri, socketAddress, error)
                    }
                }
            }

        private fun systemProxies(uri: URI?): List<Proxy> = systemProxySelector.safeSelect(uri)

        fun newBuilder(): OkHttpClient.Builder =
            newBuilder(
                proxyAuthorization = bridge.proxyAuthorization,
                diagnosticMode = "standard",
            )

        internal fun enhancedConnectionEnabled(): Boolean = manager.enhancedConnectionEnabled()

        fun newAnonymousReadBuilder(): OkHttpClient.Builder =
            newBuilder(
                proxyAuthorization = bridge.anonymousWebProxyAuthorization,
                diagnosticMode = "anonymous_read",
            ).addInterceptor { chain ->
                val request = chain.request()
                val routedRequest =
                    if (SteamAccessRoutes.webUpstreams(request.url.host).size > 1) {
                        request.newBuilder().header("Connection", "close").build()
                    } else {
                        request
                    }
                chain.proceed(routedRequest)
            }

        private fun newBuilder(
            proxyAuthorization: String,
            diagnosticMode: String,
        ): OkHttpClient.Builder =
            OkHttpClient
                .Builder()
                .dns(manager)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    SteamNetworkDiagnosticInterceptor(
                        mode = diagnosticMode,
                        enhancedConnectionEnabled = manager::enhancedConnectionEnabled,
                        record = manager::recordHttpDiagnostic,
                    ),
                ).addInterceptor(SteamAnonymousReadRetry(manager::enhancedConnectionEnabled))
                .followSslRedirects(false)
                .connectionPool(manager.connectionPool)
                .proxySelector(proxySelector)
                .proxyAuthenticator { route, response ->
                    val address = route?.proxy?.address() as? InetSocketAddress
                    val bridgeAddress = bridge.proxy.address() as InetSocketAddress
                    if (address != bridgeAddress || response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request
                            .newBuilder()
                            .header("Proxy-Authorization", proxyAuthorization)
                            .build()
                    }
                }.sslSocketFactory(privateCa.clientSocketFactory, privateCa.clientTrustManager)

        internal suspend fun builderForSteamEndpoint(
            hostname: String,
            port: Int,
        ): OkHttpClient.Builder {
            if (!manager.enhancedConnectionEnabled()) return newBuilder()
            SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
            return newBuilder().proxy(bridge.proxy)
        }
    }

internal fun isSteamSecureScheme(scheme: String?): Boolean =
    scheme.equals("https", ignoreCase = true) || scheme.equals("wss", ignoreCase = true)

internal fun ProxySelector?.safeSelect(uri: URI?): List<Proxy> =
    uri
        ?.let { target -> runCatching { this?.select(target) }.getOrNull() }
        .orEmpty()
        .ifEmpty { listOf(Proxy.NO_PROXY) }
