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
                it.equals("x-webapi-key", true)
        }
    ) return false
    if (generateSequence<Throwable>(error) { it.cause }.take(16).any {
            it is SSLException || it is CertificateException
        }
    ) return false
    if (request.body?.isOneShot() == true || request.body?.isDuplex() == true) return false
    return when (request.url.host) {
        "api.steampowered.com" -> {
            if (request.method != "POST" || request.url.encodedPath != "/ISteamRemoteStorage/GetPublishedFileDetails/v1/" ||
                request.url.query != null
            ) return false
            val body = request.body as? FormBody ?: return false
            (0 until body.size).all { index ->
                body.name(index) in setOf("itemcount", "includetags", "short_description") ||
                    body.name(index).matches(Regex("publishedfileids\\[[0-9]+]"))
            }
        }
        "steamcommunity.com" -> request.method == "GET" && request.body == null &&
            request.url.encodedPath in setOf("/sharedfiles/filedetails/", "/workshop/browse/") &&
            request.url.queryParameterNames.all { it in ANONYMOUS_COMMUNITY_PARAMETERS }
        else -> false
    }
}

private val ANONYMOUS_COMMUNITY_PARAMETERS = setOf(
    "id", "appid", "searchtext", "browsesort", "section", "actualsort", "p", "numperpage",
    "days", "requiredtags[]", "excludedtags[]", "l", "insideModal", "childpublishedfileid",
)
