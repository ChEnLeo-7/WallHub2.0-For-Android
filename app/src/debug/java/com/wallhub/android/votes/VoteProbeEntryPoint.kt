package com.wallhub.android.votes

import com.wallhub.android.core.model.AccountWorkshopRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to the existing account owner; never exports credentials. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoteProbeEntryPoint {
    fun accountWorkshopRepository(): AccountWorkshopRepository
}
