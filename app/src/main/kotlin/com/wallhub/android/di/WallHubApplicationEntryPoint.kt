package com.wallhub.android

import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.WorkshopRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Application-scoped dependencies used by device-level integration tests. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WallHubApplicationEntryPoint {
    fun workshopRepository(): WorkshopRepository

    fun workshopDownloads(): WorkshopDownloads

    fun steamAccessRepository(): SteamAccessRepository
}
