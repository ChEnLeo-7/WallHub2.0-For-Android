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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal internal fun steamWebFirstResponseTimeoutMillis(
    connectAuthority: String,
    requestHead: String,
): Int =
    if (isAnonymousSteamDirectoryRequest(connectAuthority, requestHead)) {
        DIRECTORY_FIRST_RESPONSE_TIMEOUT_MS
    } else {
        DEFAULT_FIRST_RESPONSE_TIMEOUT_MS
    }

private const val DIRECTORY_FIRST_RESPONSE_TIMEOUT_MS = 2_500
private const val DEFAULT_FIRST_RESPONSE_TIMEOUT_MS = 20_000

class SteamLoopbackTlsBridge
    @Inject
    constructor(
        private val privateCa: WallHubPrivateCa,
        private val accessManager: SteamAccessManager,
        private val noSniTlsDialer: NoSniTlsDialer,
    ) {
        private val acceptExecutor = Executors.newSingleThreadExecutor()
        private val connectionExecutor = newBridgeConnectionExecutor()
        // Relays and upstream address races use a separate pool: connection handlers synchronously wait for
        // them, so sharing the bounded admission pool would deadlock when every admitted connection relays.
        private val ioExecutor = Executors.newCachedThreadPool()
        private val authorization = createAuthorization()
        private val anonymousWebAuthorization = createAuthorization()
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

        val anonymousWebProxyAuthorization: String
            get() = anonymousWebAuthorization

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

        init {
            acceptExecutor.execute(::acceptLoop)
        }

        private fun acceptLoop() {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                try {
                    connectionExecutor.execute { handle(socket) }
                } catch (_: RejectedExecutionException) {
                    socket.closeQuietly()
                }
            }
        }

        private fun handle(clientSocket: Socket) {
            var upstreamSocket: Socket? = null
            var securedClientSocket: Socket? = null
            var selectedRoute: SteamAccessManager.AcceleratedRoute? = null
            var selectedWebRoute: SteamAccessManager.WebAcceleratedRoute? = null
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
                    val tlsHost =
                        SteamDomainPolicy.requireAuthenticatedCdnEndpoint(
                            request.tlsServerName ?: host,
                            request.port,
                        )
                    val addresses =
                        kotlinx.coroutines
                            .runBlocking { accessManager.enhancedAddresses(host) }
                            .filter(::isAuthenticatedCdnAddress)
                    Log.i(
                        LOG_TAG,
                        "SDK bridge plane=cdn mode=enhanced route_host=$host tls_host=$tlsHost " +
                            "port=${request.port} candidates=${addresses.joinToString(",") { it.hostAddress.orEmpty() }}",
                    )
                    val upstream =
                        runCatching {
                            noSniTlsDialer.connectAuthenticatedCdn(
                                tlsHostname = tlsHost,
                                candidates = addresses,
                                port = request.port,
                                hideSni = true,
                            )
                        }.getOrElse { noSniFailure ->
                            // Some SteamPipe providers require their ordinary ClientHello behavior. Preserve the
                            // SDK's own strict rustls verification on this fallback instead of duplicating the
                            // provider's virtual-host semantics in the Android bridge.
                            Log.i(
                                LOG_TAG,
                                "SDK bridge plane=cdn upstream_sni=transparent_fallback " +
                                    "failure=${sanitizedFailure(noSniFailure)}",
                            )
                            val transparent = connectDirect(host, request.port, addresses)
                            Log.i(
                                LOG_TAG,
                                "SDK bridge plane=cdn transparent_connected " +
                                    "route_host=$host address=${transparent.inetAddress.hostAddress.orEmpty()}",
                            )
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
                if (request.authorization == anonymousWebAuthorization) {
                    val host = SteamDomainPolicy.requireSupportedEndpoint(request.host, request.port)
                    require(request.port == STEAM_HTTPS_PORT && !SteamDomainPolicy.isCmHost(host)) {
                        "Anonymous alias route is restricted to Steam Web HTTPS"
                    }
                    writeResponse(clientSocket, "HTTP/1.1 200 Connection Established\r\n\r\n")
                    val securedClient =
                        privateCa.createServerSocket(clientSocket, host).apply {
                            soTimeout = HEADER_TIMEOUT_MS
                            startHandshake()
                        }
                    securedClientSocket = securedClient
                    val requestHead = readHttpRequestHead(securedClient)
                    val requestHeadText = String(requestHead, StandardCharsets.ISO_8859_1)
                    val route = kotlinx.coroutines.runBlocking { accessManager.acceleratedWebRoute(host, request.port) }
                    selectedWebRoute = route
                    val eligibleTargets = steamWebTargetsForRequest(host, requestHeadText, route.targets)
                    if (eligibleTargets.isEmpty()) throw IOException("No eligible Steam Web authority target")
                    Log.i(
                        LOG_TAG,
                        "SDK bridge plane=web mode=authority_separated aliases=${eligibleTargets.count { it.rule.tlsAuthority != host }}",
                    )
                    val upstream =
                        noSniTlsDialer.connectWebAuthorities(eligibleTargets, request.port) { target, error ->
                            accessManager.recordWebFailure(route, target, error)
                        }
                    val target = requireNotNull(upstream.webTarget)
                    Log.i(
                        LOG_TAG,
                        "SDK bridge plane=web selected_tls_authority=${target.rule.tlsAuthority} " +
                            "http_authority=${target.rule.httpAuthority} address=${target.address.hostAddress.orEmpty()}",
                    )
                    upstreamSocket = upstream.socket
                    if (!accessManager.commitWebRoute(route) {}) {
                        throw IOException("Steam Web route changed during upstream connection")
                    }
                    upstream.socket.getOutputStream().apply {
                        write(requestHead)
                        flush()
                    }
                    securedClient.soTimeout = 0
                    tunnelEstablished = true
                    val responseStatus = SteamResponseStatusObserver {
                        upstream.socket.soTimeout = 0
                        accessManager.recordWebResponse(route, target, upstream.elapsedMs)
                    }
                    upstream.socket.soTimeout = steamWebFirstResponseTimeoutMillis(host, requestHeadText)
                    relay(
                        securedClient,
                        upstream.socket,
                        onUpstreamBytes = responseStatus::accept,
                        onUpstreamEof = {
                            if (responseStatus.receivedResponse) null else IOException("EOF before HTTP response")
                        },
                        onUpstreamReadFailure = { error ->
                            accessManager.recordWebFailure(route, target, error, phase = "response")
                        },
                    )
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
                val plane = if (SteamDomainPolicy.isCmHost(host)) "cm" else "web"
                Log.i(LOG_TAG, "SDK bridge plane=$plane mode=enhanced")
                val route = kotlinx.coroutines.runBlocking {
                    accessManager.acceleratedOrWebCandidateRoute(host, request.port)
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
                // Hidden-SNI failure alone is not address failure: this edge may require SNI.
                // Only failures observed again in the original-SNI pass enter route cooldown.
                val hiddenSniFailures = mutableSetOf<InetAddress>()
                val upstream =
                    runCatching {
                        noSniTlsDialer.connect(host, route.addresses, request.port) { address, _ ->
                            hiddenSniFailures += address
                        }
                    }.getOrElse { noSniFailure ->
                        Log.i(
                            LOG_TAG,
                            "SDK bridge plane=$plane upstream_sni=original_fallback " +
                                "failure=${sanitizedFailure(noSniFailure)}",
                        )
                        noSniTlsDialer.connectWithSni(host, route.addresses, request.port) { address, error ->
                            if (address in hiddenSniFailures) recordFailure(address, error)
                        }
                    }
                upstreamSocket = upstream.socket
                val committed = accessManager.commitAcceleratedRoute(
                    route = route,
                    commitTunnel = {
                        writeResponse(clientSocket, "HTTP/1.1 200 Connection Established\r\n\r\n")
                    },
                )
                if (!committed) throw IOException("Steam route changed during upstream connection")

                val securedClient =
                    privateCa.createServerSocket(clientSocket, host).apply {
                        soTimeout = HEADER_TIMEOUT_MS
                        startHandshake()
                        soTimeout = 0
                    }
                securedClientSocket = securedClient
                tunnelEstablished = true
                val responseStatus = SteamResponseStatusObserver {
                    upstream.socket.soTimeout = 0
                    accessManager.recordResponse(route, host, upstream.address, upstream.elapsedMs)
                }
                upstream.socket.soTimeout = FIRST_RESPONSE_TIMEOUT_MS
                relay(securedClient, upstream.socket,
                    onUpstreamBytes = responseStatus::accept,
                    onUpstreamEof = {
                        if (responseStatus.receivedResponse) null else IOException("EOF before HTTP response")
                    },
                    onUpstreamReadFailure = { error ->
                        accessManager.recordAcceleratedFailure(host, route.networkType, route.generation,
                            request.port, upstream.address, error, phase = "response")
                    },
                )
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Loopback TLS bridge failed", error)
                val routeIsCurrent =
                    selectedRoute?.let(accessManager::isRouteCurrent) != false &&
                        selectedWebRoute?.let(accessManager::isRouteCurrent) != false
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
            val addresses =
                (preparedAddresses ?: InetAddress.getAllByName(hostname).toList())
                    .asSequence()
                    .filter(::isAuthenticatedCdnAddress)
                    .distinctBy(InetAddress::getHostAddress)
                    .take(MAX_CDN_ADDRESSES)
                    .toList()
            if (addresses.isEmpty()) throw IOException("No public route for $hostname")
            val opened = ConcurrentLinkedQueue<Socket>()
            val completion = ExecutorCompletionService<Result<Socket>>(ioExecutor)
            val futures =
                addresses.mapIndexed { index, address ->
                    completion.submit(
                        Callable {
                            if (index > 0) Thread.sleep(index * DIRECT_RACE_DELAY_MS)
                            runCatching {
                                Socket().also { socket ->
                                    opened += socket
                                    socket.tcpNoDelay = true
                                    socket.connect(InetSocketAddress(address, port), CDN_CONNECT_TIMEOUT_MS)
                                }
                            }
                        },
                    )
                }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DIRECT_RACE_BUDGET_MS)
            var winner: Socket? = null
            var lastError: Throwable? = null
            try {
                repeat(addresses.size) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return@repeat
                    val completed = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
                    completed.get().onSuccess { socket ->
                        winner = socket
                        return socket
                    }.onFailure { error -> lastError = error }
                }
                throw IOException("No reachable route for $hostname", lastError)
            } finally {
                futures.forEach { it.cancel(true) }
                opened.filter { it !== winner }.forEach { socket -> socket.closeQuietly() }
            }
        }

        private fun relay(
            client: Socket,
            upstream: Socket,
            onUpstreamBytes: (ByteArray, Int) -> Unit = { _, _ -> },
            onUpstreamReadFailure: (IOException) -> Unit = {},
            onUpstreamEof: () -> IOException? = { null },
        ) {
            val clientEnded = java.util.concurrent.atomic.AtomicBoolean(false)
            val completion = ExecutorCompletionService<IOException?>(ioExecutor)
            val clientToUpstream = completion.submit(Callable<IOException?> {
                // Client EOF, cancellation and either write failure are not upstream route evidence.
                try {
                    forwardSteamBytes(client.getInputStream(), upstream.getOutputStream())
                } finally { clientEnded.set(true) }
                null
            })
            val upstreamToClient = completion.submit(Callable<IOException?> {
                forwardSteamBytes(upstream.getInputStream(), client.getOutputStream(), onUpstreamBytes, onUpstreamEof)
            })
            try {
                // Only the first terminating direction can report failure. Closing the other socket
                // below must never manufacture a route penalty for normal EOF or user cancellation.
                completion.take().get()?.takeUnless { clientEnded.get() }?.let(onUpstreamReadFailure)
            } finally {
                client.closeQuietly()
                upstream.closeQuietly()
                clientToUpstream.cancel(true)
                upstreamToClient.cancel(true)
            }
        }

        private fun readHttpRequestHead(socket: Socket): ByteArray {
            val output = ByteArrayOutputStream()
            val input = socket.getInputStream()
            var matched = 0
            while (output.size() < MAX_HEADER_BYTES && matched < HEADER_TERMINATOR.size) {
                val value = input.read()
                if (value < 0) throw IOException("Proxy client closed before HTTP request headers")
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
            if (matched != HEADER_TERMINATOR.size) throw IOException("HTTP request headers exceed limit")
            return output.toByteArray()
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

        private fun sanitizedFailure(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(4).toList()
            return chain.joinToString("<-") { it::class.java.simpleName.take(48) }
        }

        private companion object {
            const val LOOPBACK_HOST = "127.0.0.1"
            const val ACCEPT_BACKLOG = 16
            const val TOKEN_BYTES = 32
            const val MAX_HEADER_BYTES = 16 * 1024
            const val HEADER_TIMEOUT_MS = 5_000
            const val CDN_CONNECT_TIMEOUT_MS = 5_000
            const val DIRECT_RACE_DELAY_MS = 150L
            const val DIRECT_RACE_BUDGET_MS = 7_000L
            const val MAX_CDN_ADDRESSES = 4
            const val FIRST_RESPONSE_TIMEOUT_MS = 20_000
            const val LOG_TAG = "WallHubSteamAccess"
            val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        }
    }

internal fun newBridgeConnectionExecutor(): ThreadPoolExecutor =
    ThreadPoolExecutor(
        MAX_ACTIVE_BRIDGE_CONNECTIONS,
        MAX_ACTIVE_BRIDGE_CONNECTIONS,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        ThreadPoolExecutor.AbortPolicy(),
    )

private const val MAX_ACTIVE_BRIDGE_CONNECTIONS = 16

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
