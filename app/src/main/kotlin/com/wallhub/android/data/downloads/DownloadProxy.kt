package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

internal fun OkHttpClient.Builder.applyDownloadProxy(proxyUrl: String): OkHttpClient.Builder =
    apply {
        proxyUrl.takeIf(String::isNotBlank)?.let { proxy(parseDownloadProxy(it)) }
    }

internal fun parseDownloadProxy(raw: String): Proxy {
    require(isSupportedDownloadProxyUrl(raw)) { "Invalid download proxy URL" }
    val uri =
        runCatching { URI(raw.trim()) }.getOrElse {
            throw IllegalArgumentException("Invalid download proxy URL")
        }
    val host =
        uri.host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Download proxy URL has no host")
    val type =
        when (uri.scheme?.lowercase()) {
            "http", "https" -> Proxy.Type.HTTP
            "socks", "socks5" -> Proxy.Type.SOCKS
            else -> throw IllegalArgumentException("Download proxy supports only HTTP(S) or SOCKS5")
        }
    val port =
        if (uri.port > 0) {
            uri.port
        } else if (type == Proxy.Type.SOCKS) {
            1080
        } else {
            8080
        }
    return Proxy(type, InetSocketAddress(host, port))
}
