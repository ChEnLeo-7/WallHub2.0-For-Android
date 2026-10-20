package com.wallhub.android.data.steamaccess

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SteamLoopbackTlsBridge
    @Inject
    constructor(
        private val privateCa: WallHubPrivateCa,
        private val accessManager: SteamAccessManager,
        private val noSniTlsDialer: NoSniTlsDialer,
    ) {
        private val executor = Executors.newCachedThreadPool()
        private val authorization = createAuthorization()
        private val cdnAuthorization = createAuthorization()
        private val serverSocket =
            ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), ACCEPT_BACKLOG)
            }

        val proxy: Proxy =
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), serverSocket.localPort),
            )

        val proxyAuthorization: String
            get() = authorization

        val cdnProxyAuthorization: String
            get() = cdnAuthorization

        /** Authenticated loopback CONNECT route used only by the SDK's Steam CM channel. */
        val cmProxyAuthorization: String
            get() = authorization

        /** Trust is scoped to the SDK route that terminates TLS inside this process. */
        val routeTrustRootPem: String by lazy {
            val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateCa.rootCertificate().encoded)
            "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
        }

        suspend fun prepareCmEndpoint(
            hostname: String,
            port: Int,
        ): Boolean = accessManager.prewarmSteamEndpoint(hostname, port)

        init {
            executor.execute(::acceptLoop)
        }

        private fun acceptLoop() {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                executor.execute { handle(socket) }
            }
        }

        private fun handle(clientSocket: Socket) {
            var upstreamSocket: Socket? = null
            var securedClientSocket: Socket? = null
            var selectedRoute: SteamAccessManager.AcceleratedRoute? = null
            var tunnelEstablished = false
            try {
                clientSocket.soTimeout = HEADER_TIMEOUT_MS
                clientSocket.tcpNoDelay = true
                val request = readConnectRequest(clientSocket)
                if (request.authorization == cdnAuthorization) {
                    val host = SteamDomainPolicy.requireAuthenticatedCdnEndpoint(request.host, request.port)
                    if (!accessManager.enhancedConnectionEnabled()) {
                        Log.i(LOG_TAG, "SDK bridge plane=cdn mode=direct")
                        val upstream = connectDirect(host, request.port)
                        upstreamSocket = upstream
                        writeResponse(clientSocket, "HTTP/1.1 200 Connection Established\r\n\r\n")
                        clientSocket.soTimeout = 0
                        tunnelEstablished = true
                        relay(clientSocket, upstream)
                        return
                    }
                    Log.i(LOG_TAG, "SDK bridge plane=cdn mode=enhanced")
                    val tlsHost =
                        SteamDomainPolicy.requireAuthenticatedCdnEndpoint(
                            request.tlsServerName ?: host,
                            request.port,
                        )
                    val addresses =
                        kotlinx.coroutines
                            .runBlocking { accessManager.enhancedAddresses(host) }
                            .filter(::isAuthenticatedCdnAddress)
                    val upstream =
                        runCatching {
                            noSniTlsDialer.connectAuthenticatedCdn(
                                tlsHostname = tlsHost,
                                candidates = addresses,
                                port = request.port,
                                hideSni = true,
                            )
                        }.getOrElse {
                            // Some SteamPipe providers require their ordinary ClientHello behavior. Preserve the
                            // SDK's own strict rustls verification on this fallback instead of duplicating the
                            // provider's virtual-host semantics in the Android bridge.
                            Log.i(LOG_TAG, "SDK bridge plane=cdn upstream_sni=transparent_fallback")
                            val transparent = connectDirect(host, request.port, addresses)
                            upstreamSocket = transparent
                            writeResponse(clientSocket, "HTTP/1.1 200 Connection Established\r\n\r\n")
                            clientSocket.soTimeout = 0
                            tunnelEstablished = true
                            relay(clientSocket, transparent)
                            return
                        }
                    upstreamSocket = upstream.socket
                    writeResponse(clientSocket, "HTTP/1.1 200 Connection Established\r\n\r\n")
                    val securedClient =
                        privateCa.createAuthenticatedCdnServerSocket(clientSocket, tlsHost).apply {
                            soTimeout = 0
                            startHandshake()
                        }
                    securedClientSocket = securedClient
                    tunnelEstablished = true
                    relay(securedClient, upstream.socket)
                    return
                }
                if (request.authorization != authorization) {
                    writeResponse(
                        clientSocket,
                        "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=\"WallHub\"\r\n" +
                            "Connection: close\r\n\r\n",
                    )
                    return
                }
                val host = SteamDomainPolicy.requireSupportedEndpoint(request.host, request.port)
                if (SteamDomainPolicy.isCmHost(host) && !accessManager.enhancedConnectionEnabled()) {
                    Log.i(LOG_TAG, "SDK bridge plane=cm mode=direct")
                    val upstream = connectDirect(host, request.port)
                    upstreamSocket = upstream
                    writeResponse(clientSocket, "HTTP/1.1 200 Connection Established\r\n\r\n")
                    clientSocket.soTimeout = 0
                    tunnelEstablished = true
                    relay(clientSocket, upstream)
                    return
                }
                if (SteamDomainPolicy.isCmHost(host)) Log.i(LOG_TAG, "SDK bridge plane=cm mode=enhanced")
                val route =
                    runCatching { accessManager.acceleratedRoute(host, request.port) }.getOrElse {
                        val prepared =
                            kotlinx.coroutines.runBlocking {
                                accessManager.prewarmSteamEndpoint(host, request.port)
                            }
                        if (!prepared) throw it
                        accessManager.acceleratedRoute(host, request.port)
                    }
                selectedRoute = route
                val recordFailure: (InetAddress, Throwable) -> Unit = { address, error ->
                    accessManager.recordAcceleratedFailure(
                        hostname = host,
                        selectedNetworkType = route.networkType,
                        generation = route.generation,
                        port = request.port,
                        address = address,
                        error = error,
                    )
                }
                val upstream =
                    if (SteamDomainPolicy.isCmHost(host)) {
                        // Steam CM serves the same strictly verified per-host certificate without SNI. Hiding
                        // the ClientHello name is the part that prevents mainland mobile paths from admitting
                        // login and then repeatedly terminating the long-lived authenticated channel.
                        noSniTlsDialer.connect(host, route.addresses, request.port, recordFailure)
                    } else {
                        noSniTlsDialer.connect(host, route.addresses, request.port, recordFailure)
                    }
                upstreamSocket = upstream.socket
                val committed =
                    accessManager.commitAcceleratedRoute(
                        route = route,
                        hostname = host,
                        port = request.port,
                        address = upstream.address,
                        elapsedMs = upstream.elapsedMs,
                        commitTunnel = {
                            writeResponse(clientSocket, "HTTP/1.1 200 Connection Established\r\n\r\n")
                        },
                    )
                if (!committed) throw IOException("Steam route changed during upstream connection")

                val securedClient =
                    privateCa.createServerSocket(clientSocket, host).apply {
                        soTimeout = 0
                        startHandshake()
                    }
                securedClientSocket = securedClient
                tunnelEstablished = true
                relay(securedClient, upstream.socket)
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Loopback TLS bridge failed", error)
                val routeIsCurrent = selectedRoute?.let(accessManager::isRouteCurrent) != false
                if (!tunnelEstablished && routeIsCurrent) runCatching { accessManager.recordBridgeFailure(error) }
                if (securedClientSocket == null && !clientSocket.isClosed) {
                    runCatching {
                        writeResponse(
                            clientSocket,
                            "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n",
                        )
                    }
                }
            } finally {
                securedClientSocket.closeQuietly()
                upstreamSocket.closeQuietly()
                clientSocket.closeQuietly()
            }
        }

        private fun connectDirect(
            hostname: String,
            port: Int,
            preparedAddresses: List<InetAddress>? = null,
        ): Socket {
            var lastError: IOException? = null
            (preparedAddresses ?: InetAddress.getAllByName(hostname).toList())
                .asSequence()
                .filter(::isAuthenticatedCdnAddress)
                .distinctBy(InetAddress::getHostAddress)
                .take(MAX_CDN_ADDRESSES)
                .forEach { address ->
                    val socket = Socket()
                    try {
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(address, port), CDN_CONNECT_TIMEOUT_MS)
                        return socket
                    } catch (error: IOException) {
                        lastError = error
                        socket.closeQuietly()
                    }
                }
            throw IOException("No reachable CDN route for $hostname", lastError)
        }

        private fun relay(
            client: Socket,
            upstream: Socket,
        ) {
            val completion = ExecutorCompletionService<Unit>(executor)
            val clientToUpstream =
                completion.submit(
                    Callable {
                        client.getInputStream().copyTo(upstream.getOutputStream(), RELAY_BUFFER_BYTES)
                        upstream.getOutputStream().flush()
                    },
                )
            val upstreamToClient =
                completion.submit(
                    Callable {
                        upstream.getInputStream().copyTo(client.getOutputStream(), RELAY_BUFFER_BYTES)
                        client.getOutputStream().flush()
                    },
                )
            try {
                completion.take().get()
            } finally {
                client.closeQuietly()
                upstream.closeQuietly()
                clientToUpstream.cancel(true)
                upstreamToClient.cancel(true)
            }
        }

        private fun readConnectRequest(socket: Socket): ConnectRequest {
            val output = ByteArrayOutputStream()
            val input = socket.getInputStream()
            var matched = 0
            while (output.size() < MAX_HEADER_BYTES && matched < HEADER_TERMINATOR.size) {
                val value = input.read()
                if (value < 0) throw IOException("Proxy client closed before CONNECT headers")
                output.write(value)
                matched =
                    if (value.toByte() == HEADER_TERMINATOR[matched]) {
                        matched + 1
                    } else if (value == '\r'.code) {
                        1
                    } else {
                        0
                    }
            }
            if (matched != HEADER_TERMINATOR.size) throw IOException("CONNECT headers exceed limit")
            return parseSteamConnectRequest(output.toString(StandardCharsets.ISO_8859_1.name()))
        }

        private fun writeResponse(
            socket: Socket,
            response: String,
        ) {
            socket.getOutputStream().apply {
                write(response.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
        }

        private fun createAuthorization(): String {
            val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
            val password = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
            val credentials = "wallhub:$password".toByteArray(StandardCharsets.ISO_8859_1)
            return "Basic ${Base64.getEncoder().encodeToString(credentials)}"
        }

        private fun Socket?.closeQuietly() {
            if (this != null) runCatching { close() }
        }

        private companion object {
            const val LOOPBACK_HOST = "127.0.0.1"
            const val ACCEPT_BACKLOG = 16
            const val TOKEN_BYTES = 32
            const val MAX_HEADER_BYTES = 16 * 1024
            const val HEADER_TIMEOUT_MS = 5_000
            const val CDN_CONNECT_TIMEOUT_MS = 8_000
            const val MAX_CDN_ADDRESSES = 4
            const val RELAY_BUFFER_BYTES = 32 * 1024
            const val LOG_TAG = "WallHubSteamAccess"
            val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        }
    }

internal fun isAuthenticatedCdnAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return false
    val bytes = address.address.map { byte -> byte.toInt() and 0xff }
    return when (bytes.size) {
        4 -> isAuthenticatedCdnIpv4(bytes)
        16 -> {
            val mappedIpv4 = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
            when {
                mappedIpv4 -> isAuthenticatedCdnIpv4(bytes.takeLast(4))
                bytes[0] and 0xfe == 0xfc -> false
                bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8 -> false
                else -> true
            }
        }
        else -> false
    }
}

private fun isAuthenticatedCdnIpv4(bytes: List<Int>): Boolean =
    when {
        bytes.size != 4 -> false
        bytes[0] == 0 || bytes[0] == 10 || bytes[0] == 127 || bytes[0] >= 224 -> false
        bytes[0] == 100 && bytes[1] in 64..127 -> false
        bytes[0] == 169 && bytes[1] == 254 -> false
        bytes[0] == 172 && bytes[1] in 16..31 -> false
        bytes[0] == 192 && bytes[1] == 168 -> false
        bytes[0] == 192 && bytes[1] == 0 && bytes[2] in 0..2 -> false
        bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100 -> false
        bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113 -> false
        else -> true // Includes Android VPN fake-IP 198.18/15 on this authenticated CDN-only route.
    }

internal data class ConnectRequest(
    val host: String,
    val port: Int,
    val authorization: String?,
    val tlsServerName: String?,
)

internal fun parseSteamConnectRequest(headersText: String): ConnectRequest {
    val lines = headersText.split("\r\n")
    val requestParts = lines.firstOrNull()?.split(' ') ?: throw IOException("Missing CONNECT request")
    if (requestParts.size != 3 || requestParts[0] != "CONNECT" || requestParts[2] != "HTTP/1.1") {
        throw IOException("Only HTTP/1.1 CONNECT is supported")
    }
    val authority = requestParts[1]
    if (authority.count { it == ':' } != 1) throw IOException("Invalid CONNECT authority")
    val host = authority.substringBefore(':').lowercase().trimEnd('.')
    val port =
        authority
            .substringAfter(':')
            .toIntOrNull()
            ?.takeIf { it in 1..MAX_CONNECT_PORT }
            ?: throw IOException("Invalid CONNECT port")
    val headers =
        lines
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim()
                }
            }.toMap()
    return ConnectRequest(
        host = host,
        port = port,
        authorization = headers["proxy-authorization"],
        tlsServerName = headers["x-steam-content-tls-server-name"]?.lowercase()?.trimEnd('.'),
    )
}

private const val MAX_CONNECT_PORT = 65535
