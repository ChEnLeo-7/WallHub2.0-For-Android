package com.wallhub.android.feature.local

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalWallpaperFileProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority: String get() = "${context.packageName}.files"
    private val downloads: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    @Test
    fun defaultWallpaperAndNestedFilesCanBeShared() {
        for (relative in listOf("example.mpkg", "nested/壁纸 file.mpkg")) {
            val uri = FileProvider.getUriForFile(context, authority, File(downloads, "WallHub/$relative"))
            assertEquals("content", uri.scheme)
            assertEquals(authority, uri.authority)
            assertEquals("/wallhub_wallpapers/$relative", uri.path)
        }
    }

    @Test
    fun filesOutsideWallpaperDirectoryCannotBeShared() {
        for (relative in listOf("private.mpkg", "WallHub-other/private.mpkg", "WallHub/../private.mpkg")) {
            val failure = runCatching {
                FileProvider.getUriForFile(context, authority, File(downloads, relative))
            }.exceptionOrNull()
            assertTrue("Unexpectedly shared $relative", failure is IllegalArgumentException)
        }
    }

    @Test
    fun updateCacheRemainsShareable() {
        val uri = FileProvider.getUriForFile(context, authority, File(context.cacheDir, "app-updates/update.apk"))
        assertTrue(uri.path.orEmpty().startsWith("/wallhub_updates/"))
    }
}
