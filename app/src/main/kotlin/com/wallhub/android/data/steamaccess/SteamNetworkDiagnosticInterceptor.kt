package com.wallhub.android.data.steamaccess

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

internal class SteamNetworkDiagnosticInterceptor(
    private val mode: String,
    private val enhancedConnectionEnabled: () -> Boolean,
    private val record: (message: String, attributes: Map<String, String>, warning: Boolean) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val operation = steamNetworkOperation(request.url.host, request.url.encodedPath)
        val requestId = requestIds.incrementAndGet().toString(36)
        val startedAt = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            val warning = !response.isSuccessful
            if (warning || operation != GENERIC_STEAM_HTTP_OPERATION && operation != WORKSHOP_IMAGE_OPERATION) {
                record(
                    "Steam HTTP request completed",
                    diagnosticAttributes(
                        requestId = requestId,
                        operation = operation,
                        host = request.url.host,
                        method = request.method,
                        encodedPath = request.url.encodedPath,
                        elapsedMs = elapsedMs(startedAt),
                    ) + mapOf(
                        "outcome" to if (warning) "http_error" else "success",
                        "httpStatus" to response.code.toString(),
                    ),
                    warning,
                )
            }
            response
        } catch (error: Throwable) {
            if (!chain.call().isCanceled()) {
                record(
                    "Steam HTTP request failed",
                    diagnosticAttributes(
                        requestId = requestId,
                        operation = operation,
                        host = request.url.host,
                        method = request.method,
                        encodedPath = request.url.encodedPath,
                        elapsedMs = elapsedMs(startedAt),
                    ) + mapOf(
                        "outcome" to "transport_failure",
                        "failureChain" to diagnosticFailureChain(error),
                    ),
                    true,
                )
            }
            throw error
        }
    }

    private fun diagnosticAttributes(
        requestId: String,
        operation: String,
        host: String,
        method: String,
        encodedPath: String,
        elapsedMs: Long,
    ): Map<String, String> =
        mapOf(
            "requestId" to requestId,
            "operation" to operation,
            "host" to host,
            "method" to method,
            "clientMode" to mode,
            "enhancedConnection" to enhancedConnectionEnabled().toString(),
            "elapsedMs" to elapsedMs.toString(),
        ) + listOfNotNull(
            steamNetworkResourceId(host, encodedPath)?.let { "workshopId" to it },
        ).toMap()

    private companion object {
        val requestIds = AtomicLong()
    }
}

internal fun steamNetworkOperation(host: String, encodedPath: String): String =
    when {
        host == "api.steampowered.com" &&
            encodedPath == "/ISteamRemoteStorage/GetPublishedFileDetails/v1/" -> "public_details"
        host == "api.steampowered.com" &&
            encodedPath == "/ISteamDirectory/GetCMListForConnect/v1/" -> "cm_directory"
        host == "steamcommunity.com" &&
            encodedPath.startsWith("/comment/PublishedFile_Public/render/") -> "community_comments"
        host == "steamcommunity.com" && encodedPath == "/sharedfiles/filedetails/" -> "community_detail"
        host == "steamcommunity.com" && encodedPath.startsWith("/workshop/browse") -> "community_browse"
        host == "images.steamusercontent.com" && encodedPath.startsWith("/ugc/") -> WORKSHOP_IMAGE_OPERATION
        else -> GENERIC_STEAM_HTTP_OPERATION
    }

internal fun steamNetworkResourceId(host: String, encodedPath: String): String? {
    if (host != "steamcommunity.com" || !encodedPath.startsWith(COMMENT_PATH_PREFIX)) return null
    val segments = encodedPath.removePrefix(COMMENT_PATH_PREFIX).trimEnd('/').split('/')
    if (segments.size != 2 || segments.any { segment -> segment != "-1" && !segment.all(Char::isDigit) }) return null
    return if (segments[1] == "-1") segments[0] else segments[1]
}

internal fun diagnosticFailureChain(error: Throwable): String =
    generateSequence(error) { current -> current.cause }
        .take(MAX_DIAGNOSTIC_CAUSES)
        .map { current -> current.javaClass.simpleName.ifBlank { "Throwable" } }
        .joinToString("<-")

private fun elapsedMs(startedAtNanos: Long): Long =
    ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

private const val COMMENT_PATH_PREFIX = "/comment/PublishedFile_Public/render/"
private const val MAX_DIAGNOSTIC_CAUSES = 8
private const val GENERIC_STEAM_HTTP_OPERATION = "steam_http"
private const val WORKSHOP_IMAGE_OPERATION = "workshop_image"
