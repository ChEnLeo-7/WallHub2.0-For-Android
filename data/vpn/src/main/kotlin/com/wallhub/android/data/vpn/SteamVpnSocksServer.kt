package com.wallhub.android.data.vpn

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface ProtectedSocketFactory {
    fun connect(target: SocksTarget): Socket
}

data class SocksTarget(
    val host: String,
    val address: InetAddress?,
    val port: Int,
)

data class SocksRelayEvent(
    val fragmentedHost: String? = null,
    val failure: String? = null,
)

class SteamVpnSocksServer(
    private val socketFactory: ProtectedSocketFactory,
    private val fragmenter: TlsClientHelloRecordFragmenter = TlsClientHelloRecordFragmenter(),
    private val eventListener: (SocksRelayEvent) -> Unit = {},
    private val maxConcurrentFlows: Int = DEFAULT_MAX_CONCURRENT_FLOWS,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(maxConcurrentFlows)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = checkNotNull(serverSocket) { "SOCKS server has not started" }.localPort

    fun start() {
        check(running.compareAndSet(false, true)) { "SOCKS server is already running" }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ACCEPT_BACKLOG)
        }
        serverSocket = socket
        acceptJob = scope.launch {
            while (isActive && running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                if (!permits.tryAcquire()) {
                    client.close()
                    eventListener(SocksRelayEvent(failure = "flow_limit"))
                    continue
                }
                activeSockets += client
                launch {
                    try {
                        handle(client)
                    } finally {
                        activeSockets -= client
                        runCatching { client.close() }
                        permits.release()
                    }
                }
            }
        }
    }

    private suspend fun handle(client: Socket) {
        client.use { downstream ->
            downstream.tcpNoDelay = true
            downstream.soTimeout = HANDSHAKE_TIMEOUT_MS
            val target = runCatching {
                Socks5Protocol.negotiate(downstream.getInputStream(), downstream.getOutputStream())
            }.getOrElse { error ->
                eventListener(SocksRelayEvent(failure = error.javaClass.simpleName))
                return
            }

            val upstream = runCatching {
                socketFactory.connect(
                    SocksTarget(
                        host = target.host,
                        address = target.address,
                        port = target.port,
                    ),
                )
            }.getOrElse { error ->
                runCatching { Socks5Protocol.sendFailure(downstream.getOutputStream()) }
                eventListener(SocksRelayEvent(failure = error.javaClass.simpleName))
                return
            }

            activeSockets += upstream
            try {
                upstream.use { direct ->
                    direct.tcpNoDelay = true
                    downstream.soTimeout = FIRST_FLIGHT_TIMEOUT_MS
                    Socks5Protocol.sendSuccess(downstream.getOutputStream())
                    kotlinx.coroutines.coroutineScope {
                        val transfers = listOf(
                            async {
                                relayClientToUpstream(
                                    downstream = downstream,
                                    input = downstream.getInputStream(),
                                    output = direct.getOutputStream(),
                                    upstream = direct,
                                )
                            },
                            async {
                                relay(
                                    input = direct.getInputStream(),
                                    output = downstream.getOutputStream(),
                                    onEof = { runCatching { downstream.shutdownOutput() } },
                                )
                            },
                        )
                        transfers.forEach { transfer ->
                            transfer.invokeOnCompletion { failure ->
                                if (failure != null) {
                                    runCatching { downstream.close() }
                                    runCatching { direct.close() }
                                }
                            }
                        }
                        transfers.awaitAll()
                    }
                }
            } finally {
                activeSockets -= upstream
            }
        }
    }

    private fun relayClientToUpstream(
        downstream: Socket,
        input: InputStream,
        output: OutputStream,
        upstream: Socket,
    ) {
        val buffered = ByteArrayOutputStream()
        val chunk = ByteArray(STREAM_BUFFER_BYTES)
        while (buffered.size() <= TlsClientHelloRecordFragmenter.MAX_BUFFERED_BYTES) {
            val read = try {
                input.read(chunk)
            } catch (_: SocketTimeoutException) {
                downstream.soTimeout = 0
                if (buffered.size() > 0) {
                    output.write(buffered.toByteArray())
                    output.flush()
                }
                relay(input, output) { runCatching { upstream.shutdownOutput() } }
                return
            }
            if (read < 0) {
                if (buffered.size() > 0) output.write(buffered.toByteArray())
                runCatching { upstream.shutdownOutput() }
                return
            }
            buffered.write(chunk, 0, read)
            val bytes = buffered.toByteArray()
            when (val result = fragmenter.inspect(bytes)) {
                TlsClientHelloRecordFragmenter.Result.NeedMore -> continue
                is TlsClientHelloRecordFragmenter.Result.Passthrough -> {
                    downstream.soTimeout = 0
                    output.write(bytes)
                    output.flush()
                    relay(input, output) { runCatching { upstream.shutdownOutput() } }
                    return
                }

                is TlsClientHelloRecordFragmenter.Result.Fragmented -> {
                    downstream.soTimeout = 0
                    output.write(result.bytes)
                    output.flush()
                    eventListener(SocksRelayEvent(fragmentedHost = result.host))
                    relay(input, output) { runCatching { upstream.shutdownOutput() } }
                    return
                }
            }
        }
        downstream.soTimeout = 0
        output.write(buffered.toByteArray())
        output.flush()
        relay(input, output) { runCatching { upstream.shutdownOutput() } }
    }

    private fun relay(
        input: InputStream,
        output: OutputStream,
        onEof: () -> Unit,
    ) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                onEof()
                return
            }
            output.write(buffer, 0, read)
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        activeSockets.forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
        acceptJob?.cancel()
        scope.cancel()
        serverSocket = null
    }

    companion object {
        private const val ACCEPT_BACKLOG = 128
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val FIRST_FLIGHT_TIMEOUT_MS = 1_500
        private const val STREAM_BUFFER_BYTES = 16 * 1024
        private const val DEFAULT_MAX_CONCURRENT_FLOWS = 512
    }
}
