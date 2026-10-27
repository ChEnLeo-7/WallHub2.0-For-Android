package com.wallhub.android.di

import android.content.Context
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.data.steam.AndroidSteamAccountAdmissionStore
import com.wallhub.android.data.steam.AndroidSteamContentSdkSessionMessages
import com.wallhub.android.data.steam.EncryptedSteamCredentialStore
import com.wallhub.android.data.steam.SteamContentSdkAuthenticatedSessionRegistry
import com.wallhub.android.data.steam.SteamContentSdkEngine
import com.wallhub.android.data.steam.SteamContentSdkEnginePlatform
import com.wallhub.android.data.steam.SteamContentSdkPlatform
import com.wallhub.android.data.steam.SteamContentSdkRouteFactory
import com.wallhub.android.data.steam.SteamContentSdkSessionBackend
import com.wallhub.android.data.steam.SteamContentSdkSessionMessages
import io.steamcontent.sdk.SessionMode
import com.wallhub.android.data.steam.SteamDeviceSeedStore
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

/** Qualifies the account-session engine from the anonymous public Workshop engine. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
internal annotation class SteamSessionSdkEngine

/**
 * Wires the session backend.
 *
 * The SDK backend is the only session implementation: kSteam's repository, its encrypted persistence
 * driver, and the router that chose between them are gone, so [SteamSessionRepository] resolves to this
 * backend and nothing selects a backend at build time any more.
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
    ): SteamContentSdkEngine = SteamContentSdkEngine(context, deviceSeed, SessionMode.ACCOUNT)

    @Provides
    @Singleton
    @SteamAnonymousWorkshopEngine
    internal fun provideAnonymousWorkshopEngine(
        @ApplicationContext context: Context,
        deviceSeed: SteamDeviceSeedStore,
    ): SteamContentSdkEngine = SteamContentSdkEngine(context, deviceSeed, SessionMode.ANONYMOUS)

    @Provides
    @Singleton
    internal fun provideSteamContentSdkPlatform(
        @SteamSessionSdkEngine engine: SteamContentSdkEngine,
        routes: SteamContentSdkRouteFactory,
        authenticatedSessions: SteamContentSdkAuthenticatedSessionRegistry,
    ): SteamContentSdkPlatform = SteamContentSdkEnginePlatform(engine, routes, authenticatedSessions)

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
    internal fun provideSteamSessionRepository(
        @ApplicationContext context: Context,
        platform: SteamContentSdkPlatform,
        credentialStore: EncryptedSteamCredentialStore,
        messages: SteamContentSdkSessionMessages,
        diagnostics: DiagnosticRepository,
        authenticatedSessions: SteamContentSdkAuthenticatedSessionRegistry,
    ): SteamSessionRepository =
        SteamContentSdkSessionBackend(
            platform = platform,
            credentialStore = credentialStore,
            messages = messages,
            diagnostics = diagnostics,
            authenticatedSessions = authenticatedSessions,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            admissionStore = AndroidSteamAccountAdmissionStore(context),
        )
}
