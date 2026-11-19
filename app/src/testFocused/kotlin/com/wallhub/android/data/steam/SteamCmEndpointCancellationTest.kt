package com.wallhub.android.data.steam

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SteamCmEndpointCancellationTest {
    @Test
    fun `cancelling directory await cancels the underlying okhttp call`() =
        runTest {
            val call = HangingCall()
            val waiting = launch { call.awaitResponse() }
            runCurrent()

            waiting.cancelAndJoin()

            assertTrue(call.cancelled)
        }
}

private class HangingCall : Call {
    var cancelled = false
    private var callback: Callback? = null

    override fun request(): Request = Request.Builder().url("https://api.steampowered.com/").build()
    override fun execute(): Response = error("the cancellable path must not use execute")
    override fun enqueue(responseCallback: Callback) { callback = responseCallback }
    override fun cancel() { cancelled = true }
    override fun isExecuted(): Boolean = callback != null
    override fun isCanceled(): Boolean = cancelled
    override fun timeout(): Timeout = Timeout.NONE
    override fun <T : Any> tag(type: KClass<T>): T? = null
    override fun <T> tag(type: Class<out T>): T? = null
    override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    override fun clone(): Call = HangingCall()
}
