package com.wallhub.android.data.steamaccess

import okhttp3.FormBody
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SteamAnonymousReadRetryTest {
    private fun details() = Request.Builder()
        .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
        .post(FormBody.Builder().add("itemcount", "1").add("publishedfileids[0]", "123").build())
        .build()

    private fun directory() = Request.Builder()
        .url("https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?format=json&cellid=0")
        .build()

    @Test
    fun `only explicitly anonymous read requests qualify`() {
        val error = IOException("EOF")
        assertTrue(mayRetryAnonymousSteamRead(details(), error, false))
        assertTrue(mayRetryAnonymousSteamRead(directory(), error, false))
        assertFalse(mayRetryAnonymousSteamRead(directory().newBuilder().header("Cookie", "secret").build(), error, false))
        assertFalse(
            mayRetryAnonymousSteamRead(
                directory().newBuilder()
                    .url("https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?format=json&cellid=0&key=secret")
                    .build(),
                error,
                false,
            ),
        )
        assertTrue(mayRetryAnonymousSteamRead(Request.Builder()
            .url("https://steamcommunity.com/sharedfiles/filedetails/?id=123").build(), error, false))
        assertFalse(mayRetryAnonymousSteamRead(details().newBuilder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/SubscribePublishedFile/v1/").build(), error, false))
        assertFalse(mayRetryAnonymousSteamRead(details().newBuilder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/?key=secret").build(), error, false))
        assertFalse(mayRetryAnonymousSteamRead(details().newBuilder()
            .post(FormBody.Builder().add("key", "secret").build()).build(), error, false))
        listOf("Authorization", "Cookie", "x-webapi-key").forEach { header ->
            assertFalse(mayRetryAnonymousSteamRead(details().newBuilder().header(header, "secret").build(), error, false))
        }
        listOf("key", "access_token", "token", "sessionid", "unknown").forEach { parameter ->
            assertFalse(mayRetryAnonymousSteamRead(Request.Builder()
                .url("https://steamcommunity.com/workshop/browse/?$parameter=secret").build(), error, false))
        }
    }

    @Test
    fun `interceptor retries once using the same call and request`() {
        var attempts = 0
        var firstCall: okhttp3.Call? = null
        val request = details()
        val client = OkHttpClient.Builder()
            .addInterceptor(SteamAnonymousReadRetry { true })
            .addInterceptor { chain ->
                attempts++
                assertSame(request, chain.request())
                if (attempts == 1) {
                    firstCall = chain.call()
                    throw IOException("upstream EOF")
                }
                assertSame(firstCall, chain.call())
                response(chain.request(), 200)
            }
            .build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(2, attempts)
    }

    @Test
    fun `Directory response failure switches route once inside the same call`() {
        var attempts = 0
        var firstCall: okhttp3.Call? = null
        val request = directory()
        val client = OkHttpClient.Builder()
            .addInterceptor(SteamAnonymousReadRetry { true })
            .addInterceptor { chain ->
                attempts++
                if (attempts == 1) {
                    firstCall = chain.call()
                    throw IOException("Directory first-response timeout")
                }
                assertSame(firstCall, chain.call())
                response(chain.request(), 200)
            }
            .build()

        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(2, attempts)
    }

    @Test
    fun `interceptor never attempts a third request`() {
        var attempts = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(SteamAnonymousReadRetry { true })
            .addInterceptor {
                attempts++
                throw IOException("upstream EOF")
            }
            .build()
        assertFailsWith<IOException> { client.newCall(details()).execute() }
        assertEquals(2, attempts)
    }

    @Test
    fun `interceptor returns HTTP rate limit without replay`() {
        var attempts = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(SteamAnonymousReadRetry { true })
            .addInterceptor { chain ->
                attempts++
                response(chain.request(), 429)
            }
            .build()
        client.newCall(details()).execute().use { assertEquals(429, it.code) }
        assertEquals(1, attempts)
    }

    @Test
    fun `interceptor does not replay cancellation TLS mutation or disabled enhancement`() {
        for (scenario in listOf("cancel", "certificate", "mutation", "disabled")) {
            var attempts = 0
            val request = if (scenario == "mutation") {
                details().newBuilder()
                    .url("https://api.steampowered.com/ISteamRemoteStorage/SubscribePublishedFile/v1/")
                    .tag(String::class.java, "explicit mutation")
                    .build()
            } else {
                details()
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(SteamAnonymousReadRetry { scenario != "disabled" })
                .addInterceptor { chain ->
                    attempts++
                    if (scenario == "cancel") chain.call().cancel()
                    if (scenario == "certificate") throw SSLHandshakeException("certificate")
                    throw IOException("upstream EOF")
                }
                .build()
            assertFailsWith<IOException> { client.newCall(request).execute() }
            assertEquals(1, attempts, scenario)
        }
    }

    private fun response(request: Request, code: Int): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("synthetic")
        .body("".toResponseBody())
        .build()

    @Test
    fun `cancellation and TLS failures cannot trigger replay`() {
        assertFalse(mayRetryAnonymousSteamRead(details(), IOException("cancel"), true))
        assertFalse(mayRetryAnonymousSteamRead(details(), SSLHandshakeException("certificate"), false))
        assertFalse(mayRetryAnonymousSteamRead(details(), IOException(SSLHandshakeException("certificate")), false))
    }
}
