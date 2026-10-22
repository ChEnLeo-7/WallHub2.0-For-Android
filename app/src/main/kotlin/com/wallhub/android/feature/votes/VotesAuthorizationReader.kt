package com.wallhub.android.feature.votes

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

/** Host supplies localized text; no resource or navigation ownership in this optional adapter. */
data class VotesReaderLabels(val authorization: String, val sync: String, val dismiss: String)

/**
 * User-visible Steam login and explicit first-page sync. Increasing [requestCounter] after onPage
 * requests one next page; it is never an automatic scan. Keep this composable mounted with
 * [visible] false while displaying results, so WebView's memory-only paging context survives.
 * Identity is checked on EVERY read against the caller's current CM account, never a typed claim.
 * Removing this reader cancels reads and destroys the WebView, not its Steam-owned cookie store.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VotesAuthorizationReader(
    expectedSteamId: String,
    requestCounter: Int,
    labels: VotesReaderLabels,
    onPage: (VotesReadPage) -> Unit,
    onError: (VotesReaderError) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val currentPage by rememberUpdatedState(onPage)
    val currentError by rememberUpdatedState(onError)
    val scope = remember(expectedSteamId) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val gate = remember(expectedSteamId) { VotesPagingGate(expectedSteamId) }
    var busy by remember(expectedSteamId) { mutableStateOf(false) }
    var ready by remember(expectedSteamId) { mutableStateOf(false) }
    val epoch = remember(expectedSteamId) { longArrayOf(0L) }
    val webView = remember(expectedSteamId) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ -> currentError(VotesReaderError.UNTRUSTED_PAGE) }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !VotesWebPolicy.allowed(request.url.toString(), navigation = true)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !VotesWebPolicy.allowed(url, navigation = true)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (VotesWebPolicy.allowed(request.url.toString(), navigation = request.isForMainFrame)) return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    epoch[0]++
                    ready = false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    ready = VotesWebPolicy.isVotesDocument(url)
                    // Cookie values never leave WebView; persist login across process restarts.
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    epoch[0]++
                    ready = false
                    view.stopLoading()
                    currentError(VotesReaderError.TLS)
                }
            }
            loadUrl(VotesWebPolicy.START_URL)
        }
    }

    suspend fun readPage() {
        if (busy) { currentError(VotesReaderError.BUSY); return }
        if (!ready || !VotesWebPolicy.isVotesDocument(webView.url.orEmpty())) {
            currentError(VotesReaderError.UNTRUSTED_PAGE)
            return
        }
        if (gate.started && gate.cursor == null) return
        busy = true
        val generation = epoch[0]
        val resultKey = "__wallhubVotes_" + UUID.randomUUID().toString().replace("-", "")
        try {
            webView.evaluate(VotesReaderScript.start(resultKey, gate.cursor, expectedSteamId))
            val json = withTimeoutOrNull(25000) {
                var result: String? = null
                while (result == null) {
                    if (epoch[0] != generation || !VotesWebPolicy.isVotesDocument(webView.url.orEmpty())) {
                        failVotes(VotesReaderError.UNTRUSTED_PAGE)
                    }
                    val encoded = webView.evaluate(VotesReaderScript.poll(resultKey))
                    if (encoded.length > VotesPageParser.MAX_BYTES * 2) failVotes(VotesReaderError.LIMIT_EXCEEDED)
                    result = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                    if (result == null) delay(100)
                }
                result
            } ?: failVotes(VotesReaderError.NETWORK)
            if (epoch[0] != generation || !VotesWebPolicy.isVotesDocument(webView.url.orEmpty())) {
                failVotes(VotesReaderError.UNTRUSTED_PAGE)
            }
            val page = gate.accept(json)
            CookieManager.getInstance().flush()
            currentPage(page)
        } catch (failure: VotesReadException) {
            currentError(failure.reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentError(VotesReaderError.INVALID_PAGE)
        } finally {
            busy = false
        }
    }

    var consumedRequest by remember(expectedSteamId) { mutableStateOf(requestCounter) }
    LaunchedEffect(requestCounter, expectedSteamId) {
        if (requestCounter != consumedRequest) {
            consumedRequest = requestCounter
            // Counter increments before explicit first sync do not authorize a read.
            if (gate.started) readPage()
        }
    }
    DisposableEffect(webView) {
        onDispose {
            scope.cancel()
            epoch[0]++
            webView.stopLoading()
            CookieManager.getInstance().flush()
            webView.destroy()
        }
    }
    Column(modifier = modifier) {
        if (visible) {
            Text(labels.authorization)
            Row {
                Button(onClick = { scope.launch { readPage() } }, enabled = ready && !busy && !gate.started) {
                    Text(labels.sync)
                }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text(labels.dismiss) }
            }
        }
        AndroidView(
            factory = { webView },
            modifier = if (visible) Modifier.weight(1f).fillMaxSize() else Modifier,
            update = { it.visibility = if (visible) View.VISIBLE else View.GONE },
        )
    }
}

private suspend fun WebView.evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result ?: "null") }
}
