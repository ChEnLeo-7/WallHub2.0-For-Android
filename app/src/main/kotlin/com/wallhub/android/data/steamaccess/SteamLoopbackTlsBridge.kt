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
                val route = accessManager.acceleratedRoute(host, request.port)
                selectedRoute = route
                val upstream =
                    noSniTlsDialer.connect(
                        hostname = host,
                        candidates = route.addresses,
                        port = request.port,
                        onFailure = { address, error ->
                            accessManager.recordAcceleratedFailure(
                                hostname = host,
                                selectedNetworkType = route.networkType,
                                generation = route.generation,
                                port = request.port,
                                address = address,
                                error = error,
                            )
                        },
                    )
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
            const val RELAY_BUFFER_BYTES = 32 * 1024
            const val LOG_TAG = "WallHubSteamAccess"
            val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        }
    }

internal data class ConnectRequest(
    val host: String,
    val port: Int,
    val authorization: String?,
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
    )
}

private const val MAX_CONNECT_PORT = 65535
