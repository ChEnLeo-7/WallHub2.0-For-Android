package com.wallhub.android.di

import android.content.Context
import android.util.Log
import com.wallhub.android.BuildConfig
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.data.steam.AndroidSteamContentSdkSessionMessages
import com.wallhub.android.data.steam.EncryptedSteamCredentialStore
import com.wallhub.android.data.steam.KSteamSessionRepository
import com.wallhub.android.data.steam.SteamContentSdkEngine
import com.wallhub.android.data.steam.SteamDeviceSeedStore
import com.wallhub.android.data.steam.SteamContentSdkEnginePlatform
import com.wallhub.android.data.steam.SteamContentSdkPlatform
import com.wallhub.android.data.steam.SteamContentSdkRouteFactory
import com.wallhub.android.data.steam.SteamContentSdkSessionBackend
import com.wallhub.android.data.steam.SteamContentSdkSessionMessages
import com.wallhub.android.data.steam.SteamSessionBackend
import com.wallhub.android.data.steam.SteamSessionBackendRouter
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The SDK session backend needs its own platform engine.
 *
 * [SteamContentSdkEngine] owns one session at a time and `open` replaces it, and the download path already
 * holds the application-wide instance. A separate instance for the session domain keeps enabling
 * `wallhub.useSdkSession` and `wallhub.useSdkDepotCore` together from having one consumer close the other's
 * session.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
internal annotation class SteamSessionSdkEngine

/** Marks the SDK session repository so the router can receive it alongside the kSteam one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
internal annotation class SteamSessionSdkBackend

/**
 * Wires the session backend decision point.
 *
 * The SDK backend is injected as a [Lazy], so with `wallhub.useSdkSession` off (the default) the graph
 * serves [KSteamSessionRepository] exactly as before and never builds the SDK backend or its engine. With
 * the flag on, the SDK backend and nothing else answers the session contract.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object SteamSessionBackendModule {
    @Provides
    @Singleton
    @SteamSessionSdkEngine
    internal fun provideSteamSessionSdkEngine(
        @ApplicationContext context: Context,
        deviceSeed: SteamDeviceSeedStore,
    ): SteamContentSdkEngine = SteamContentSdkEngine(context, deviceSeed)

    @Provides
    @Singleton
    internal fun provideSteamContentSdkPlatform(
        @SteamSessionSdkEngine engine: SteamContentSdkEngine,
        routes: SteamContentSdkRouteFactory,
    ): SteamContentSdkPlatform = SteamContentSdkEnginePlatform(engine, routes)

    @Provides
    @Singleton
    internal fun provideSteamSessionCredentialStore(
        @ApplicationContext context: Context,
    ): EncryptedSteamCredentialStore = EncryptedSteamCredentialStore(context)

    @Provides
    @Singleton
    internal fun provideSteamContentSdkSessionMessages(
        @ApplicationContext context: Context,
    ): SteamContentSdkSessionMessages = AndroidSteamContentSdkSessionMessages(context)

    @Provides
    @Singleton
    @SteamSessionSdkBackend
    internal fun provideSteamSessionSdkBackend(
        platform: SteamContentSdkPlatform,
        credentialStore: EncryptedSteamCredentialStore,
        messages: SteamContentSdkSessionMessages,
        diagnostics: DiagnosticRepository,
    ): SteamSessionRepository =
        SteamContentSdkSessionBackend(
            platform = platform,
            credentialStore = credentialStore,
            messages = messages,
            diagnostics = diagnostics,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    @Provides
    @Singleton
    internal fun provideSteamSessionRepository(
        kSteam: KSteamSessionRepository,
        @SteamSessionSdkBackend sdk: Lazy<SteamSessionRepository>,
    ): SteamSessionRepository {
        val router = SteamSessionBackendRouter(kSteam, sdk::get, BuildConfig.USE_SDK_SESSION)
        Log.i(
            "WallHubSteamSession",
            "session backend selected=" +
                if (router.backend == SteamSessionBackend.STEAM_CONTENT_SDK) "steam-content-sdk" else "ksteam",
        )
        return router
    }
}
