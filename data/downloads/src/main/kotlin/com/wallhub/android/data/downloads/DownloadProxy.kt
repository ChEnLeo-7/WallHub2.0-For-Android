package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import okhttp3.OkHttpClient

internal fun OkHttpClient.Builder.applyDownloadProxy(proxyUrl: String): OkHttpClient.Builder = apply {
    proxyUrl.takeIf(String::isNotBlank)?.let { proxy(parseDownloadProxy(it)) }
}

internal fun parseDownloadProxy(raw: String): Proxy {
    require(isSupportedDownloadProxyUrl(raw)) { "下载代理地址无效" }
    val uri = runCatching { URI(raw.trim()) }.getOrElse {
        throw IllegalArgumentException("下载代理地址无效")
    }
    val host = uri.host?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("下载代理缺少主机名")
    val type = when (uri.scheme?.lowercase()) {
        "http", "https" -> Proxy.Type.HTTP
        "socks", "socks5" -> Proxy.Type.SOCKS
        else -> throw IllegalArgumentException("下载代理仅支持 HTTP(S) 或 SOCKS5")
    }
    val port = if (uri.port > 0) uri.port else if (type == Proxy.Type.SOCKS) 1080 else 8080
    return Proxy(type, InetSocketAddress(host, port))
}
