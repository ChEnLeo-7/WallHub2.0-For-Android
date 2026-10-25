package com.wallhub.android.data.steamaccess

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** One pre-response retry for these explicitly anonymous reads, never a general method-based retry. */
internal class SteamAnonymousReadRetry(
    private val enabled: () -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (error: IOException) {
            if (!enabled() || !mayRetryAnonymousSteamRead(request, error, chain.call().isCanceled())) throw error
            // The bridge publishes failed-address feedback before closing the client. Reconnecting
            // therefore selects remaining candidates. Both attempts share this Call's deadline.
            return chain.proceed(request)
        }
    }
}

internal fun mayRetryAnonymousSteamRead(request: Request, error: IOException, canceled: Boolean): Boolean {
    if (canceled || request.url.scheme != "https" || request.url.port != 443) return false
    if (request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) return false
    if (request.headers.names().any {
            it.equals("Authorization", true) || it.equals("Cookie", true) ||
                it.equals("Proxy-Authorization", true) || it.equals("x-webapi-key", true) ||
                it.equals("Expect", true) || it.equals("Transfer-Encoding", true) ||
                it.equals("Upgrade", true)
        }
    ) return false
    if (generateSequence<Throwable>(error) { it.cause }.take(16).any {
            it is SSLException || it is CertificateException
        }
    ) return false
    if (request.body?.isOneShot() == true || request.body?.isDuplex() == true) return false
    return when (request.url.host) {
        "api.steampowered.com" ->
            when {
                request.method == "GET" &&
                    request.url.encodedPath == STEAM_DIRECTORY_PATH ->
                    request.url.encodedQuery == STEAM_DIRECTORY_QUERY
                request.method == "POST" &&
                    request.url.encodedPath == STEAM_DETAILS_PATH &&
                    request.url.query == null -> {
                    val body = request.body as? FormBody ?: return false
                    (0 until body.size).all { index ->
                        body.name(index) in setOf("itemcount", "includetags", "short_description") ||
                            body.name(index).matches(Regex("publishedfileids\\[[0-9]+]"))
                    }
                }
                else -> false
            }
        "steamcommunity.com" -> request.method == "GET" && request.body == null &&
            request.url.encodedPath in setOf("/sharedfiles/filedetails/", "/workshop/browse/") &&
            request.url.queryParameterNames.all { it in ANONYMOUS_COMMUNITY_PARAMETERS }
        "images.steamusercontent.com" ->
            request.method == "GET" &&
                request.body == null &&
                WORKSHOP_IMAGE_PATH.matches(request.url.encodedPath) &&
                validWorkshopImageQuery(
                    List(request.url.querySize) { index ->
                        request.url.queryParameterName(index).lowercase() to
                            request.url.queryParameterValue(index).orEmpty()
                    },
                )
        else -> false
    }
}

internal fun isAnonymousSteamDirectoryRequest(
    connectAuthority: String,
    requestHead: String,
): Boolean =
    connectAuthority.equals("api.steampowered.com", ignoreCase = true) &&
        requestHead.startsWith("GET $STEAM_DIRECTORY_PATH?", ignoreCase = false) &&
        mayUseSteamWebAlias(connectAuthority, requestHead)

internal fun isAnonymousSteamDetailsRequest(
    connectAuthority: String,
    requestHead: String,
): Boolean =
    connectAuthority.equals("api.steampowered.com", ignoreCase = true) &&
        requestHead.startsWith("POST $STEAM_DETAILS_PATH HTTP/1.1\r\n", ignoreCase = false) &&
        mayUseSteamWebAlias(connectAuthority, requestHead)

internal fun isAnonymousWorkshopImageRequest(
    connectAuthority: String,
    requestHead: String,
): Boolean =
    connectAuthority.equals("images.steamusercontent.com", ignoreCase = true) &&
        requestHead.startsWith("GET /ugc/", ignoreCase = false) &&
        mayUseSteamWebAlias(connectAuthority, requestHead)

internal fun usesFastSteamWebFailover(
    connectAuthority: String,
    requestHead: String,
): Boolean =
    isAnonymousSteamDirectoryRequest(connectAuthority, requestHead) ||
        isAnonymousWorkshopImageRequest(connectAuthority, requestHead)

internal fun mayUseSteamWebAlias(
    connectAuthority: String,
    requestHead: String,
): Boolean {
    if (requestHead.length > MAX_ALIAS_REQUEST_HEAD_CHARS || !requestHead.endsWith("\r\n\r\n")) return false
    val lines = requestHead.removeSuffix("\r\n\r\n").split("\r\n")
    val requestLine = lines.firstOrNull()?.split(' ') ?: return false
    if (
        requestLine.size != 3 ||
        requestLine[0] !in setOf("GET", "POST") ||
        requestLine[2] != "HTTP/1.1"
    ) return false
    val target = requestLine[1]
    if (!target.startsWith('/') || target.startsWith("//") || "://" in target) return false

    val headers = lines.drop(1).mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return false
        line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
    }
    val hosts = headers.filter { (name, _) -> name == "host" }.map(Pair<String, String>::second)
    if (hosts.size != 1) return false
    val expectedHost = connectAuthority.lowercase().trimEnd('.')
    val actualHost = hosts.single().lowercase().removeSuffix(":443").trimEnd('.')
    if (actualHost != expectedHost || SteamAccessRoutes.webUpstreams(expectedHost).size < 2) return false
    if (headers.any { (name, _) -> name in ALIAS_SECRET_HEADERS }) return false
    val connectionTokens =
        headers
            .filter { (name, _) -> name == "connection" }
            .flatMap { (_, value) -> value.split(',').map(String::trim) }
    if (connectionTokens.none { token -> token.equals("close", ignoreCase = true) }) return false
    if (headers.any { (name, _) -> name in ALIAS_UNSUPPORTED_REQUEST_HEADERS }) return false
    val path = target.substringBefore('?')
    val queryParameters = aliasQueryParameters(target) ?: return false
    val queryNames = queryParameters.mapTo(linkedSetOf()) { (name, _) -> name }
    return when (expectedHost) {
        "steamcommunity.com", "www.steamcommunity.com" ->
            when {
                requestLine[0] == "GET" && path in ANONYMOUS_COMMUNITY_PATHS ->
                    queryNames.all { name -> name in ANONYMOUS_COMMUNITY_PARAMETERS }
                requestLine[0] == "POST" && ANONYMOUS_COMMUNITY_COMMENT_PATH.matches(path) -> {
                    val contentType = headers.singleOrNull { (name, _) -> name == "content-type" }?.second.orEmpty()
                    val contentLength =
                        headers.singleOrNull { (name, _) -> name == "content-length" }
                            ?.second
                            ?.toLongOrNull()
                    queryNames.isEmpty() &&
                        contentType.substringBefore(';').trim().equals(
                            "application/x-www-form-urlencoded",
                            ignoreCase = true,
                        ) &&
                        contentLength != null && contentLength in 1..MAX_ALIAS_FORM_BYTES
                }
                else -> false
            }
        "images.steamusercontent.com" ->
            requestLine[0] == "GET" &&
                WORKSHOP_IMAGE_PATH.matches(path) &&
                validWorkshopImageQuery(queryParameters)
        "api.steampowered.com", "community.steam-api.com" ->
            when {
                requestLine[0] == "GET" && path == STEAM_DIRECTORY_PATH ->
                    expectedHost == "api.steampowered.com" &&
                        target == "$STEAM_DIRECTORY_PATH?$STEAM_DIRECTORY_QUERY"
                requestLine[0] == "GET" && path == "/ISteamWebAPIUtil/GetSupportedAPIList/v1/" ->
                    queryNames.all { name -> name == "format" }
                requestLine[0] == "POST" && path == STEAM_DETAILS_PATH -> {
                    val contentType = headers.singleOrNull { (name, _) -> name == "content-type" }?.second.orEmpty()
                    val contentLength =
                        headers.singleOrNull { (name, _) -> name == "content-length" }
                            ?.second
                            ?.toLongOrNull()
                    queryNames.isEmpty() &&
                        contentType.substringBefore(';').trim().equals(
                            "application/x-www-form-urlencoded",
                            ignoreCase = true,
                        ) &&
                        contentLength != null && contentLength in 1..MAX_ALIAS_FORM_BYTES
                }
                else -> false
            }
        else -> false
    }
}

private fun aliasQueryParameters(target: String): List<Pair<String, String>>? {
    val query = target.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return emptyList()
    return runCatching {
        query.split('&').map { parameter ->
            val rawName = parameter.substringBefore('=')
            require(rawName.isNotEmpty())
            val rawValue = parameter.substringAfter('=', missingDelimiterValue = "")
            java.net.URLDecoder.decode(rawName, Charsets.UTF_8.name()).lowercase() to
                java.net.URLDecoder.decode(rawValue, Charsets.UTF_8.name())
        }
    }.getOrNull()
}

private fun validWorkshopImageQuery(parameters: List<Pair<String, String>>): Boolean {
    if (parameters.map { (name, _) -> name }.distinct().size != parameters.size) return false
    return parameters.all { (name, value) ->
        when (name) {
            "imw", "imh" -> value.toIntOrNull()?.let { it in 1..MAX_WORKSHOP_IMAGE_DIMENSION } == true
            "ima" -> value == "fit"
            "letterbox" -> value == "false" || value == "true"
            else -> false
        }
    }
}

internal const val STEAM_DIRECTORY_PATH = "/ISteamDirectory/GetCMListForConnect/v1/"
internal const val STEAM_DETAILS_PATH = "/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
internal const val STEAM_DIRECTORY_QUERY = "format=json&cellid=0"
private const val MAX_ALIAS_REQUEST_HEAD_CHARS = 16 * 1024
private const val MAX_ALIAS_FORM_BYTES = 8L * 1024L
private const val MAX_WORKSHOP_IMAGE_DIMENSION = 5_000
private val ALIAS_SECRET_HEADERS = setOf("authorization", "cookie", "proxy-authorization", "x-webapi-key")
private val ALIAS_UNSUPPORTED_REQUEST_HEADERS = setOf("expect", "transfer-encoding", "upgrade")
private val ANONYMOUS_COMMUNITY_PATHS = setOf("/sharedfiles/filedetails/", "/workshop/browse/")
private val ANONYMOUS_COMMUNITY_COMMENT_PATH =
    Regex("^/comment/PublishedFile_Public/render/[0-9]+/[0-9]+/$")
private val WORKSHOP_IMAGE_PATH = Regex("^/ugc/[0-9]+/[0-9A-Fa-f]+/$")

private val ANONYMOUS_COMMUNITY_PARAMETERS = setOf(
    "id", "appid", "searchtext", "browsesort", "section", "actualsort", "p", "numperpage",
    "days", "requiredtags[]", "excludedtags[]", "l", "insideModal", "childpublishedfileid",
)
