package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Temporary live verification access; does not own or open a second account session. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LibrarySortProbeEntryPoint {
    fun sessions(): SteamContentSdkAuthenticatedSessionRegistry
    fun collections(): AccountWorkshopRepository
}
