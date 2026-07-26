package com.wallhub.android

import android.content.Context
import com.wallhub.android.core.database.AppPreferencesStore
import com.wallhub.android.core.database.FormalTaskDatabase
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.LocalWallpaperMetadataDao
import com.wallhub.android.data.downloads.ConversionWorkScheduler
import com.wallhub.android.data.downloads.DownloadWorkScheduler
import com.wallhub.android.data.downloads.WorkManagerConversionWorkScheduler
import com.wallhub.android.data.downloads.WorkManagerDownloadWorkScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAppPreferencesStore(
        @ApplicationContext context: Context,
    ): AppPreferencesStore = AppPreferencesStore(context)

    @Provides
    @Singleton
    fun provideFormalTaskDatabase(
        @ApplicationContext context: Context,
    ): FormalTaskDatabase = FormalTaskDatabase.get(context)

    @Provides
    fun provideFormalTaskRecordDao(
        database: FormalTaskDatabase,
    ): FormalTaskRecordDao = database.taskRecordDao()

    @Provides
    fun provideLocalWallpaperMetadataDao(
        database: FormalTaskDatabase,
    ): LocalWallpaperMetadataDao = database.localWallpaperMetadataDao()

    @Provides
    @Singleton
    fun provideDownloadWorkScheduler(
        @ApplicationContext context: Context,
    ): DownloadWorkScheduler = WorkManagerDownloadWorkScheduler(context)

    @Provides
    @Singleton
    fun provideConversionWorkScheduler(
        @ApplicationContext context: Context,
    ): ConversionWorkScheduler = WorkManagerConversionWorkScheduler(context)
}
