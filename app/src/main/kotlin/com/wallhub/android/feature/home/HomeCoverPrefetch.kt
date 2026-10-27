package com.wallhub.android.feature.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.request.CachePolicy
import coil.request.SuccessResult
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.wallhub.android.core.model.WorkshopSummary
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class HomeCoverPrefetchBudget {
    DISABLED,
    METERED,
    UNMETERED,
}

private data class HomeCoverViewport(
    val firstIndex: Int,
    val firstOffset: Int,
    val lastIndex: Int,
    val itemCount: Int,
    val columns: Int,
    val listMode: Boolean,
    val budget: HomeCoverPrefetchBudget,
)

internal data class HomeCoverPrefetchCandidate(
    val itemId: Long,
    val previewUrl: String,
)

internal fun normalizeHomeCoverPreviewUrl(previewUrl: String?): String? =
    previewUrl?.trim()?.takeIf(String::isNotEmpty)

internal fun selectHomeCoverPrefetchCandidates(
    items: List<WorkshopSummary>,
    lastVisibleIndex: Int,
    columns: Int,
    listMode: Boolean,
    movingForward: Boolean,
    budget: HomeCoverPrefetchBudget,
): List<HomeCoverPrefetchCandidate> {
    if (!movingForward || budget == HomeCoverPrefetchBudget.DISABLED || items.isEmpty()) return emptyList()
    val start = (lastVisibleIndex + 1).coerceIn(0, items.size)
    val effectiveColumns = columns.coerceAtLeast(1)
    val slotLimit =
        when (budget) {
            HomeCoverPrefetchBudget.DISABLED -> 0
            HomeCoverPrefetchBudget.METERED -> if (listMode) 1 else effectiveColumns
            HomeCoverPrefetchBudget.UNMETERED -> if (listMode) 3 else effectiveColumns * 2
        }.coerceAtMost(MAX_HOME_COVER_PREFETCH_ITEMS)
    if (slotLimit == 0 || start == items.size) return emptyList()
    return items
        .subList(start, minOf(items.size, start + slotLimit))
        .mapNotNull { item ->
            normalizeHomeCoverPreviewUrl(item.previewUrl)
                ?.let { HomeCoverPrefetchCandidate(item.id, it) }
        }.distinctBy(HomeCoverPrefetchCandidate::previewUrl)
}

internal fun homeWorkshopCoverRequest(
    context: Context,
    previewUrl: String,
    coverSizePx: Int,
): ImageRequest =
    ImageRequest
        .Builder(context.applicationContext)
        .data(previewUrl)
        .size(coverSizePx.coerceAtLeast(1), coverSizePx.coerceAtLeast(1))
        .precision(Precision.INEXACT)
        .scale(Scale.FILL)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .build()

@Composable
internal fun HomeCoverPrefetchEffect(
    items: List<WorkshopSummary>,
    gridState: LazyGridState,
    columns: Int,
    listMode: Boolean,
    budget: HomeCoverPrefetchBudget,
    coverSizePx: Int,
    batchKey: Any,
) {
    val context = LocalContext.current.applicationContext
    val imageLoader = LocalImageLoader.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentItems by rememberUpdatedState(items)
    val currentColumns by rememberUpdatedState(columns)
    val currentListMode by rememberUpdatedState(listMode)
    val currentBudget by rememberUpdatedState(budget)
    val currentCoverSizePx by rememberUpdatedState(coverSizePx)
    LaunchedEffect(gridState, lifecycleOwner, imageLoader, batchKey, coverSizePx) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var furthestVisibleIndex = -1
            var observedItemCount = 0
            val prefetchedUrls = mutableSetOf<String>()
            snapshotFlow {
                val visible = gridState.layoutInfo.visibleItemsInfo
                HomeCoverViewport(
                    firstIndex = visible.firstOrNull()?.index ?: -1,
                    firstOffset = gridState.firstVisibleItemScrollOffset,
                    lastIndex = visible.lastOrNull()?.index ?: -1,
                    itemCount = currentItems.size,
                    columns = currentColumns,
                    listMode = currentListMode,
                    budget = currentBudget,
                )
            }.distinctUntilChanged()
                .collectLatest { viewport ->
                    val lastVisibleIndex = viewport.lastIndex
                    val movingForward = lastVisibleIndex > furthestVisibleIndex
                    val itemsGrew = viewport.itemCount > observedItemCount
                    observedItemCount = viewport.itemCount
                    if (movingForward) furthestVisibleIndex = lastVisibleIndex
                    if (
                        (!movingForward && (!itemsGrew || lastVisibleIndex < furthestVisibleIndex)) ||
                        lastVisibleIndex < 0
                    ) {
                        return@collectLatest
                    }
                    delay(HOME_COVER_PREFETCH_DEBOUNCE_MS)
                    val candidates =
                        selectHomeCoverPrefetchCandidates(
                            items = currentItems,
                            lastVisibleIndex = lastVisibleIndex,
                            columns = viewport.columns,
                            listMode = viewport.listMode,
                            movingForward = true,
                            budget = viewport.budget,
                        ).filterNot { candidate -> candidate.previewUrl in prefetchedUrls }
                    val result =
                        prefetchHomeWorkshopCovers(
                            imageLoader = imageLoader,
                            context = context,
                            candidates = candidates,
                            coverSizePx = currentCoverSizePx,
                            onCompleted = { previewUrl -> prefetchedUrls += previewUrl },
                        )
                    if (candidates.isNotEmpty()) {
                        Log.i(
                            HOME_COVER_PREFETCH_TAG,
                            "batch candidates=${candidates.size} memory=${result.memory} disk=${result.disk} " +
                                "network=${result.network} failed=${result.failed} " +
                                "budget=${viewport.budget.name.lowercase()}",
                        )
                    }
                }
        }
    }
}

internal data class HomeCoverPrefetchResult(
    val memory: Int,
    val disk: Int,
    val network: Int,
    val failed: Int,
)

internal suspend fun prefetchHomeWorkshopCovers(
    imageLoader: ImageLoader,
    context: Context,
    candidates: List<HomeCoverPrefetchCandidate>,
    coverSizePx: Int,
    onCompleted: (String) -> Unit = {},
): HomeCoverPrefetchResult {
    val permits = Semaphore(HOME_COVER_PREFETCH_CONCURRENCY)
    val memory = AtomicInteger(0)
    val disk = AtomicInteger(0)
    val network = AtomicInteger(0)
    val failed = AtomicInteger(0)
    coroutineScope {
        candidates.forEach { candidate ->
            launch {
                permits.withPermit {
                    when (val result = imageLoader.execute(homeWorkshopCoverRequest(context, candidate.previewUrl, coverSizePx))) {
                        is SuccessResult ->
                            when (result.dataSource) {
                                DataSource.MEMORY, DataSource.MEMORY_CACHE -> memory.incrementAndGet()
                                DataSource.DISK -> disk.incrementAndGet()
                                DataSource.NETWORK -> network.incrementAndGet()
                            }
                        else -> failed.incrementAndGet()
                    }
                    onCompleted(candidate.previewUrl)
                }
            }
        }
    }
    return HomeCoverPrefetchResult(memory.get(), disk.get(), network.get(), failed.get())
}

@Composable
internal fun rememberHomeCoverPrefetchBudget(context: Context): State<HomeCoverPrefetchBudget> {
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    val state = remember(connectivity) { mutableStateOf(connectivity.homeCoverPrefetchBudget()) }
    DisposableEffect(connectivity) {
        if (connectivity == null) return@DisposableEffect onDispose {}
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    state.value = connectivity.homeCoverPrefetchBudget()
                }

                override fun onLost(network: Network) {
                    state.value = connectivity.homeCoverPrefetchBudget()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    state.value = connectivity.homeCoverPrefetchBudget()
                }

            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    state.value = connectivity.homeCoverPrefetchBudget()
                }
            }
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        state.value = connectivity.homeCoverPrefetchBudget()
        onDispose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return state
}

private fun ConnectivityManager?.homeCoverPrefetchBudget(): HomeCoverPrefetchBudget {
    this ?: return HomeCoverPrefetchBudget.DISABLED
    if (restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
        return HomeCoverPrefetchBudget.DISABLED
    }
    val network = activeNetwork ?: return HomeCoverPrefetchBudget.DISABLED
    val capabilities = getNetworkCapabilities(network) ?: return HomeCoverPrefetchBudget.DISABLED
    if (
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    ) {
        return HomeCoverPrefetchBudget.DISABLED
    }
    return if (isActiveNetworkMetered) HomeCoverPrefetchBudget.METERED else HomeCoverPrefetchBudget.UNMETERED
}

private const val HOME_COVER_PREFETCH_TAG = "WallHubCoverPrefetch"
internal const val MAX_HOME_COVER_PREFETCH_ITEMS = 8
internal const val HOME_COVER_PREFETCH_CONCURRENCY = 2
internal const val HOME_COVER_PREFETCH_DEBOUNCE_MS = 120L
