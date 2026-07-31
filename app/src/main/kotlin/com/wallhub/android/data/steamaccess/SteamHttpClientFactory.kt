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
                    val useBridge =
                        uri.scheme.equals("https", ignoreCase = true) &&
                            SteamDomainPolicy.supports(host) &&
                            manager.shouldAccelerate(host)
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
            OkHttpClient
                .Builder()
                .dns(manager)
                .connectionPool(manager.connectionPool)
                .eventListenerFactory { manager.eventListener() }
                .proxySelector(proxySelector)
                .proxyAuthenticator { route, response ->
                    val address = route?.proxy?.address() as? InetSocketAddress
                    val bridgeAddress = bridge.proxy.address() as InetSocketAddress
                    if (address != bridgeAddress || response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request
                            .newBuilder()
                            .header("Proxy-Authorization", bridge.proxyAuthorization)
                            .build()
                    }
                }.sslSocketFactory(privateCa.clientSocketFactory, privateCa.clientTrustManager)
    }

internal fun ProxySelector?.safeSelect(uri: URI?): List<Proxy> =
    uri
        ?.let { target -> runCatching { this?.select(target) }.getOrNull() }
        .orEmpty()
        .ifEmpty { listOf(Proxy.NO_PROXY) }
