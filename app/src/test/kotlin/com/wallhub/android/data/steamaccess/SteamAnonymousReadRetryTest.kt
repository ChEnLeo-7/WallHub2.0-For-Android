package com.wallhub.android.data.steamaccess

import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamAnonymousReadRetryTest {
    private fun details() = Request.Builder()
        .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
        .post(FormBody.Builder().add("itemcount", "1").add("publishedfileids[0]", "123").build())
        .build()

    @Test
    fun `only explicitly anonymous read requests qualify`() {
        val error = IOException("EOF")
        assertTrue(mayRetryAnonymousSteamRead(details(), error, false))
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
    fun `cancellation and TLS failures cannot trigger replay`() {
        assertFalse(mayRetryAnonymousSteamRead(details(), IOException("cancel"), true))
        assertFalse(mayRetryAnonymousSteamRead(details(), SSLHandshakeException("certificate"), false))
        assertFalse(mayRetryAnonymousSteamRead(details(), IOException(SSLHandshakeException("certificate")), false))
    }
}
