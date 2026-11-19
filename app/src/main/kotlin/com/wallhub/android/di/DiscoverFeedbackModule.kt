package com.wallhub.android

import com.wallhub.android.core.model.DiscoverFeedbackRepository
import com.wallhub.android.core.model.DiscoverSavedQueryRepository
import com.wallhub.android.data.discover.DataStoreDiscoverFeedbackRepository
import com.wallhub.android.feature.discover.DiscoverFeedCoordinator
import com.wallhub.android.feature.discover.DiscoverFeedLoader
import com.wallhub.android.feature.discover.DefaultDiscoverPageSource
import com.wallhub.android.feature.discover.DiscoverPageSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverFeedbackModule {
    @Binds
    @Singleton
    abstract fun bindDiscoverFeedLoader(coordinator: DiscoverFeedCoordinator): DiscoverFeedLoader

    @Binds
    @Singleton
    abstract fun bindDiscoverPageSource(source: DefaultDiscoverPageSource): DiscoverPageSource

    @Binds
    @Singleton
    abstract fun bindDiscoverFeedbackRepository(
        repository: DataStoreDiscoverFeedbackRepository,
    ): DiscoverFeedbackRepository

    @Binds
    @Singleton
    abstract fun bindDiscoverSavedQueryRepository(
        repository: DataStoreDiscoverFeedbackRepository,
    ): DiscoverSavedQueryRepository
}
