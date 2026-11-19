package com.wallhub.android.data.steamaccess

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A real HTTP CONNECT proxy on the loopback interface, used to drive OkHttp's own tunnel code.
 *
 * The proxy-authentication defect lives in how OkHttp reports a 407 for an HTTPS target: it closes the
 * tunnel with a bare `IOException` instead of handing back the status. A test that synthesised that
 * exception would only restate the fix, so this fixture speaks the protocol instead: it accepts
 * CONNECT, records the request, and answers with the configured status. Nothing leaves the machine
 * and no credential is ever accepted or produced.
 */
internal class FakeHttpConnectProxy(
    private val status: Int,
    private val reason: String = REASON_FOR_STATUS[status] ?: "Proxy Response",
    private val responseHeaders: List<String> = DEFAULT_HEADERS_FOR_STATUS[status].orEmpty(),
) : Closeable {
    /** One CONNECT as it arrived, with its header lines verbatim. */
    internal data class Recorded(
        val method: String,
        val authority: String,
        val headers: List<String>,
    )

    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val lock = Any()
    private val recorded = mutableListOf<Recorded>()
    private var failure: Throwable? = null

    val port: Int get() = server.localPort

    val requests: List<Recorded>
        get() = synchronized(lock) { recorded.toList() }

    /** Anything the acceptor thread could not handle, surfaced to the test instead of being swallowed. */
    fun failureOrNull(): Throwable? = synchronized(lock) { failure }

    private val acceptor =
        Thread(
            {
                while (!server.isClosed) {
                    val accepted = runCatching { server.accept() }.getOrElse { break }
                    runCatching { serve(accepted) }.onFailure { error ->
                        synchronized(lock) { failure = error }
                    }
                }
            },
            "fake-http-connect-proxy",
        ).apply {
            isDaemon = true
            start()
        }

    private fun serve(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = SOCKET_TIMEOUT_MS
            val lines = readHead(connection.getInputStream())
            val requestLine = lines.firstOrNull().orEmpty().split(' ')
            synchronized(lock) {
                recorded +=
                    Recorded(
                        method = requestLine.getOrNull(0).orEmpty(),
                        authority = requestLine.getOrNull(1).orEmpty(),
                        headers = lines.drop(1),
                    )
            }
            val response =
                buildString {
                    append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                    responseHeaders.forEach { header -> append(header).append("\r\n") }
                    append("Content-Length: 0\r\nConnection: close\r\n\r\n")
                }
            connection.getOutputStream().apply {
                write(response.toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
    }

    private fun readHead(input: InputStream): List<String> {
        val head = StringBuilder()
        while (head.length < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) break
            head.append(next.toChar())
            if (head.endsWith(HEAD_TERMINATOR)) break
        }
        return head.toString().split("\r\n").filter(String::isNotEmpty)
    }

    override fun close() {
        runCatching { server.close() }
        acceptor.join(SOCKET_TIMEOUT_MS.toLong())
    }

    companion object {
        const val OK: Int = 200
        const val PROXY_AUTH_REQUIRED: Int = 407

        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val HEAD_TERMINATOR = "\r\n\r\n"

        private val REASON_FOR_STATUS =
            mapOf(
                OK to "Connection established",
                PROXY_AUTH_REQUIRED to "Proxy Authentication Required",
            )

        private val DEFAULT_HEADERS_FOR_STATUS =
            mapOf(
                PROXY_AUTH_REQUIRED to listOf("Proxy-Authenticate: Basic realm=\"wallhub-test\""),
            )
    }
}
