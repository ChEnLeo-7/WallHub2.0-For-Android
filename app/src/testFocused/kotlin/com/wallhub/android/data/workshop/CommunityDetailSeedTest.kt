package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunityDetailSeedTest {
    @Test
    fun `cold startup has no seed until primed`() = runTest {
        val repository = repository()

        assertNull(repository.peekDetailSeed(WORKSHOP_ID))

        val detail = detail()
        repository.primeDetailSeed(detail)

        assertEquals(detail, repository.peekDetailSeed(WORKSHOP_ID))
    }

    @Test
    fun `priming preserves richer existing counts and metadata`() = runTest {
        val repository = repository()
        val richer = detail(
            subscriptions = 18_321L,
            favorites = 4_567L,
            views = 98_765L,
            fileSizeBytes = 321_000_000L,
            updatedAt = 1_712_345_678L,
        )
        val sparse = detail(
            subscriptions = null,
            favorites = null,
            views = null,
            fileSizeBytes = null,
            updatedAt = null,
        )

        repository.primeDetailSeed(richer)
        repository.primeDetailSeed(sparse)

        val seed = repository.peekDetailSeed(WORKSHOP_ID)
        requireNotNull(seed)
        assertEquals(18_321L, seed.summary.subscriptions)
        assertEquals(4_567L, seed.summary.favorites)
        assertEquals(98_765L, seed.summary.views)
        assertEquals(321_000_000L, seed.summary.fileSizeBytes)
        assertEquals(1_712_345_678L, seed.summary.updatedAt)
        assertEquals(18_321L, seed.subscriptions)
        assertEquals(321_000_000L, seed.fileSizeBytes)
        assertEquals(1_712_345_678L, seed.updatedAt)
    }

    private fun repository(): CommunityWorkshopRepository {
        val preferences = MutableStateFlow(AppPreferences(steamWorkshopDataSource = SteamWorkshopDataSource.CM_WEBSOCKET))
        val settingsRepository = Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPreferences" -> preferences
                "toString" -> "SeedTestSettingsRepository"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> error("Unexpected SettingsRepository call: ${method.name}")
            }
        } as SettingsRepository
        val unifiedWorkshopRepository = Proxy.newProxyInstance(
            SteamUnifiedWorkshopRepository::class.java.classLoader,
            arrayOf(SteamUnifiedWorkshopRepository::class.java),
        ) { _, method, _ -> error("Unexpected unified Workshop repository call: ${method.name}") }
            as SteamUnifiedWorkshopRepository

        return CommunityWorkshopRepository(settingsRepository, unifiedWorkshopRepository, OkHttpClient())
    }

    private fun detail(
        subscriptions: Long? = null,
        favorites: Long? = null,
        views: Long? = null,
        fileSizeBytes: Long? = null,
        updatedAt: Long? = null,
    ) = WorkshopDetail(
        summary = WorkshopSummary(
            id = WORKSHOP_ID,
            title = "Workshop item",
            author = "Creator",
            subscriptions = subscriptions,
            favorites = favorites,
            views = views,
            fileSizeBytes = fileSizeBytes,
            updatedAt = updatedAt,
            subscriptionState = SubscriptionState.NOT_SUBSCRIBED,
            favoriteState = FavoriteState.NOT_FAVORITED,
        ),
        subscriptions = subscriptions,
        fileSizeBytes = fileSizeBytes,
        updatedAt = updatedAt,
    )

    private companion object {
        const val WORKSHOP_ID = 42L
    }
}
