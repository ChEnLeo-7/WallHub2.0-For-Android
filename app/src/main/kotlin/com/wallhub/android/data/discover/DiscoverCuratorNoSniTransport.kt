package com.wallhub.android.data.discover

import android.util.Log
import com.wallhub.android.data.downloads.readBytesBounded
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class CuratorTransportResponse(
    val statusCode: Int,
    val body: String,
    val etag: String?,
    val lastModified: String?,
)

private class CuratorAuthenticatedResponseException(cause: Throwable) : IOException(
    "Authenticated Curator response failed",
    cause,
)

@Singleton
internal class DiscoverCuratorNoSniTransport internal constructor(
    private val resolverClient: OkHttpClient,
    private val socketFactory: SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier,
) {
    @Inject
    constructor() : this(
        resolverClient =
            OkHttpClient
                .Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        socketFactory =
            SSLContext
                .getInstance("TLS")
                .apply { init(null, null, SecureRandom()) }
                .socketFactory,
        hostnameVerifier = OkHttpClient().hostnameVerifier,
    )

    @Volatile
    private var lastSuccessfulAddress: Inet4Address? = null

    suspend fun get(
        url: String,
        etag: String?,
        lastModified: String?,
    ): CuratorTransportResponse =
        withContext(Dispatchers.IO) {
            val target = requireCuratorTarget(url)
            val startedAt = System.nanoTime()
            val cached = lastSuccessfulAddress
            if (cached != null) {
                runCatching { execute(target, cached, etag, lastModified) }
                    .onSuccess { response ->
                        logSuccess(target.host, response.statusCode, startedAt)
                        return@withContext response
                    }.onFailure { error ->
                        currentCoroutineContext().ensureActive()
                        if (error is CuratorAuthenticatedResponseException) throw error
                    }
                lastSuccessfulAddress = null
            }

            var lastFailure: Throwable? = null
            resolve(target.host).forEach { address ->
                runCatching { execute(target, address, etag, lastModified) }
                    .onSuccess { response ->
                        lastSuccessfulAddress = address
                        logSuccess(target.host, response.statusCode, startedAt)
                        return@withContext response
                    }.onFailure { error ->
                        currentCoroutineContext().ensureActive()
                        if (error is CuratorAuthenticatedResponseException) throw error
                        lastFailure = error
                    }
            }
            throw IOException("No authenticated no-SNI Curator route for ${target.host}", lastFailure)
        }

    private fun resolve(host: String): List<Inet4Address> {
        val url =
            okhttp3.HttpUrl
                .Builder()
                .scheme("https")
                .host(DOH_HOST)
                .addPathSegment("resolve")
                .addQueryParameter("name", host)
                .addQueryParameter("type", "A")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .get()
                .build()
        return resolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Curator DNS failed: HTTP ${response.code}")
            val payload =
                response.body.byteStream().use { input ->
                    input.readBytesBounded(MAX_DOH_RESPONSE_BYTES).decodeUtf8Strict()
                }
            parsePublicIpv4Answers(payload)
                .take(MAX_CANDIDATES)
                .ifEmpty { throw IOException("Curator DNS returned no public IPv4 address") }
        }
    }

    private suspend fun execute(
        target: CuratorTarget,
        address: Inet4Address,
        etag: String?,
        lastModified: String?,
    ): CuratorTransportResponse {
        val rawSocket = Socket()
        val activeSocket = AtomicReference<Socket>(rawSocket)
        val cancellationHandle =
            currentCoroutineContext().job.invokeOnCompletion { runCatching { activeSocket.get().close() } }
        try {
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(address, HTTPS_PORT), CONNECT_TIMEOUT_MS)
            val sslSocket =
                socketFactory.createSocket(rawSocket, address.hostAddress, HTTPS_PORT, true) as SSLSocket
            activeSocket.set(sslSocket)
            sslSocket.use { secured ->
                secured.enabledProtocols =
                    secured.supportedProtocols
                        .filter { protocol -> protocol == "TLSv1.3" || protocol == "TLSv1.2" }
                        .toTypedArray()
                secured.sslParameters =
                    hiddenSniParameters(secured.sslParameters)
                secured.startHandshake()
                if (!hostnameVerifier.verify(target.host, secured.session)) {
                    throw SSLPeerUnverifiedException("Certificate does not match ${target.host}")
                }
                secured.soTimeout = READ_TIMEOUT_MS
                val requestHead =
                    buildString {
                        append("GET ${target.path} HTTP/1.1\r\n")
                        append("Host: ${target.host}\r\n")
                        append("Accept: application/json\r\n")
                        append("Accept-Encoding: identity\r\n")
                        append("User-Agent: $USER_AGENT\r\n")
                        etag.safeValidator()?.let { append("If-None-Match: $it\r\n") }
                        lastModified.safeValidator()?.let { append("If-Modified-Since: $it\r\n") }
                        append("Connection: close\r\n\r\n")
                    }
                secured.outputStream.write(requestHead.toByteArray(Charsets.ISO_8859_1))
                secured.outputStream.flush()
                return try {
                    parseHttpResponse(secured.inputStream)
                } catch (error: Exception) {
                    throw CuratorAuthenticatedResponseException(error)
                }
            }
        } catch (error: Throwable) {
            runCatching { activeSocket.get().close() }
            throw error
        } finally {
            cancellationHandle.dispose()
        }
    }

    private fun logSuccess(
        host: String,
        statusCode: Int,
        startedAtNanos: Long,
    ) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        Log.i(LOG_TAG, "route=no_sni host=$host status=$statusCode durationMs=$elapsedMs")
    }

    internal companion object {
        private const val DOH_HOST = "doh.pub"
        private const val HTTPS_PORT = 443
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_DOH_RESPONSE_BYTES = 64 * 1024
        private const val MAX_CANDIDATES = 2
        private const val USER_AGENT = "WallHub-Android/Discover"
        private const val LOG_TAG = "DiscoverCuratorTransport"
    }
}

internal data class CuratorTarget(
    val host: String,
    val path: String,
)

internal fun requireCuratorTarget(url: String): CuratorTarget {
    val parsed = runCatching { java.net.URI(url) }.getOrElse { throw IllegalArgumentException("Invalid Curator URL", it) }
    val host = parsed.host?.lowercase()?.trimEnd('.')
    require(parsed.scheme == "https") { "Curator URL must use HTTPS" }
    require(parsed.port == -1 || parsed.port == 443) { "Curator URL must use port 443" }
    require(host in CURATOR_HOSTS) { "Unsupported Curator host" }
    require(parsed.rawPath == CURATOR_PATH && parsed.rawQuery == null && parsed.rawFragment == null) {
        "Unsupported Curator path"
    }
    require(parsed.rawUserInfo == null) { "Curator URL must not contain user info" }
    return CuratorTarget(requireNotNull(host), CURATOR_PATH)
}

internal fun hiddenSniParameters(base: javax.net.ssl.SSLParameters): javax.net.ssl.SSLParameters =
    base.apply {
        endpointIdentificationAlgorithm = null
        serverNames = Collections.emptyList()
    }

internal fun parsePublicIpv4Answers(payload: String): List<Inet4Address> {
    val root = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
    if (root.optInt("Status", -1) != 0) return emptyList()
    val answers = root.optJSONArray("Answer") ?: return emptyList()
    return buildList {
        repeat(answers.length()) { index ->
            val answer = answers.optJSONObject(index) ?: return@repeat
            if (answer.optInt("type") != 1) return@repeat
            val value = answer.optString("data")
            if (!value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) return@repeat
            val octets = value.split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 } || !isPublicCuratorIpv4(octets)) return@repeat
            runCatching { InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as Inet4Address }
                .getOrNull()
                ?.let(::add)
        }
    }.distinctBy(InetAddress::getHostAddress)
}

private fun isPublicCuratorIpv4(bytes: List<Int>): Boolean =
    when {
        bytes[0] == 0 || bytes[0] == 10 || bytes[0] == 127 || bytes[0] >= 224 -> false
        bytes[0] == 100 && bytes[1] in 64..127 -> false
        bytes[0] == 169 && bytes[1] == 254 -> false
        bytes[0] == 172 && bytes[1] in 16..31 -> false
        bytes[0] == 192 && bytes[1] == 168 -> false
        bytes[0] == 192 && bytes[1] == 0 && bytes[2] in 0..2 -> false
        bytes[0] == 198 && bytes[1] in 18..19 -> false
        bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100 -> false
        bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113 -> false
        else -> true
    }

internal fun parseHttpResponse(responseStream: java.io.InputStream): CuratorTransportResponse {
    val input = responseStream.buffered()
    val statusLine = input.readBoundedAsciiLine(MAX_STATUS_LINE_BYTES)
    val match = STATUS_LINE.matchEntire(statusLine) ?: throw IOException("Malformed Curator HTTP status line")
    val statusCode = match.groupValues[1].toInt()
    val headers = linkedMapOf<String, String>()
    var headerBytes = statusLine.length + 2
    var headerCount = 0
    while (true) {
        val line = input.readBoundedAsciiLine(MAX_HEADER_LINE_BYTES)
        headerBytes += line.length + 2
        if (headerBytes > MAX_HEADER_BYTES) throw IOException("Curator HTTP headers exceed limit")
        if (line.isEmpty()) break
        headerCount += 1
        if (headerCount > MAX_HEADER_COUNT) throw IOException("Too many Curator HTTP headers")
        if (line.firstOrNull()?.isWhitespace() == true) throw IOException("Curator HTTP folded header is not allowed")
        val separator = line.indexOf(':')
        if (separator <= 0) throw IOException("Malformed Curator HTTP header")
        val name = line.substring(0, separator).trim().lowercase()
        val value = line.substring(separator + 1).trim()
        if (!name.matches(HTTP_HEADER_NAME)) throw IOException("Malformed Curator HTTP header name")
        if (value.any { character -> character.code < 0x20 && character != '\t' }) {
            throw IOException("Malformed Curator HTTP header value")
        }
        if (name in headers) throw IOException("Duplicate Curator HTTP header: $name")
        headers[name] = value
    }
    if (statusCode != 200 && statusCode != 304) throw IOException("Curator failed: HTTP $statusCode")
    if (headers["transfer-encoding"] != null) throw IOException("Curator transfer encoding is not supported")
    val contentEncoding = headers["content-encoding"]
    if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
        throw IOException("Curator content encoding is not supported")
    }
    if (statusCode == 304) {
        val declaredLength = headers["content-length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength != 0L) throw IOException("Curator 304 response contains a body")
        return CuratorTransportResponse(statusCode, "", headers["etag"], headers["last-modified"])
    }
    if (!headers["content-type"].orEmpty().lowercase().startsWith("application/json")) {
        throw IOException("Curator response is not JSON")
    }
    val declaredLength = headers["content-length"]?.toLongOrNull()
        ?: throw IOException("Curator response is missing Content-Length")
    if (declaredLength !in 0..MAX_MANIFEST_BYTES.toLong()) throw IOException("Curator response exceeds limit")
    val body = input.readExactly(declaredLength.toInt()).decodeUtf8Strict()
    return CuratorTransportResponse(statusCode, body, headers["etag"], headers["last-modified"])
}

private fun ByteArray.decodeUtf8Strict(): String =
    Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()

private fun java.io.BufferedInputStream.readBoundedAsciiLine(limit: Int): String {
    val bytes = ArrayList<Byte>(minOf(limit, 128))
    while (bytes.size <= limit) {
        val value = read()
        if (value < 0) throw IOException("Unexpected EOF in Curator HTTP headers")
        if (value == '\n'.code) {
            if (bytes.lastOrNull() != '\r'.code.toByte()) throw IOException("Curator HTTP line must end with CRLF")
            bytes.removeAt(bytes.lastIndex)
            return bytes.toByteArray().toString(Charsets.ISO_8859_1)
        }
        bytes += value.toByte()
    }
    throw IOException("Curator HTTP line exceeds limit")
}

private fun java.io.BufferedInputStream.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) throw IOException("Unexpected EOF in Curator response body")
        if (count > 0) offset += count
    }
    return result
}

private fun String?.safeValidator(): String? =
    this?.takeIf { value ->
        value.length <= MAX_VALIDATOR_LENGTH && value.none { character -> character.code < 0x20 || character.code == 0x7f }
    }

private val CURATOR_HOSTS = setOf("www.wallpaperengineapi.com", "wallpaperengineapi.com")
private const val CURATOR_PATH = "/api/explore/v1"
private const val MAX_MANIFEST_BYTES = 512 * 1024
private const val MAX_VALIDATOR_LENGTH = 1_024
private const val MAX_STATUS_LINE_BYTES = 1_024
private const val MAX_HEADER_LINE_BYTES = 4_096
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_HEADER_COUNT = 64
private val STATUS_LINE = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: [^\\r\\n]*)?")
private val HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9a-z-]+")
