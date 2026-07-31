package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.networking.steam3.Connection
import `in`.dragonbra.javasteam.networking.steam3.NetMsgEventArgs
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JavaSteam 1.8.0 creates a private Ktor CIO client for CM WebSockets, bypassing the
 * configured OkHttp DNS, proxy and TLS policy. This transport keeps every Steam request
 * on the same application-owned network stack.
 */
internal class OkHttpSteamWebSocketConnection(
    baseClient: OkHttpClient,
    private val onFailure: (InetSocketAddress?, Throwable) -> Unit = { _, _ -> },
) : Connection() {
    private val client =
        baseClient
            .newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(STEAM_WEB_SOCKET_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()
    private val connectionClosed = AtomicBoolean(true)
    private val failureReported = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var endpoint: InetSocketAddress? = null

    override fun connect(
        endPoint: InetSocketAddress,
        timeout: Int,
    ) {
        check(connectionClosed.compareAndSet(true, false)) { "Steam WebSocket is already active" }
        failureReported.set(false)
        endpoint = endPoint
        val request = Request.Builder().url(steamWebSocketUrl(endPoint)).build()
        val connectionClient =
            client
                .newBuilder()
                .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .build()
        socket = connectionClient.newWebSocket(request, listener)
    }

    override fun disconnect(userInitiated: Boolean) {
        val activeSocket = socket
        if (!userInitiated && activeSocket != null) {
            reportFailure(IOException("Steam CM WebSocket disconnected by the Steam client state machine"))
        }
        signalDisconnected(userInitiated)
        if (activeSocket?.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON) == false) {
            activeSocket.cancel()
        }
    }

    override fun send(data: ByteArray) {
        if (socket?.send(data.toByteString()) != true) {
            reportFailure(IOException("Steam CM WebSocket rejected an outgoing ${data.size}-byte binary message"))
            signalDisconnected(userInitiated = false)
        }
    }

    override fun getLocalIP(): InetAddress = InetAddress.getLocalHost()

    override fun getCurrentEndPoint(): InetSocketAddress? = endpoint

    override fun getProtocolTypes(): ProtocolTypes = ProtocolTypes.WEB_SOCKET

    private fun signalDisconnected(userInitiated: Boolean) {
        if (connectionClosed.compareAndSet(false, true)) {
            socket = null
            onDisconnected(userInitiated)
        }
    }

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                socket = webSocket
                if (connectionClosed.get()) {
                    webSocket.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
                } else {
                    onConnected()
                }
            }

            override fun onMessage(
                webSocket: WebSocket,
                bytes: okio.ByteString,
            ) {
                if (!connectionClosed.get()) {
                    onNetMsgReceived(NetMsgEventArgs(bytes.toByteArray(), endpoint))
                }
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                if (!connectionClosed.get()) {
                    reportFailure(IOException("Steam CM WebSocket closed with code $code: $reason"))
                }
                webSocket.close(code, reason)
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                signalDisconnected(userInitiated = false)
            }

            override fun onFailure(
                webSocket: WebSocket,
                error: Throwable,
                response: Response?,
            ) {
                reportFailure(error)
                signalDisconnected(userInitiated = false)
            }
        }

    private fun reportFailure(error: Throwable) {
        if (failureReported.compareAndSet(false, true)) {
            onFailure(endpoint, error)
        }
    }

    private companion object {
        const val STEAM_WEB_SOCKET_PING_INTERVAL_MS = 15_000L
        const val NORMAL_CLOSURE_CODE = 1_000
        const val NORMAL_CLOSURE_REASON = "WallHub Steam session closed"
    }
}

internal fun steamWebSocketUrl(endpoint: InetSocketAddress): HttpUrl =
    HttpUrl
        .Builder()
        .scheme("https")
        .host(endpoint.hostString)
        .port(endpoint.port)
        .addPathSegment("cmsocket")
        .addPathSegment("")
        .build()
