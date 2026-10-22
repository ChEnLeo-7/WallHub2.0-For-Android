package com.wallhub.android.data.downloads

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalWallpaperDeleteStrategyTest {
    @Test
    fun `SAF documents use document deletion without calling unsupported content deletion`() {
        var documentCalled = false
        var contentCalled = false

        val deleted =
            deleteLocalWallpaperUri(
                kind = LocalWallpaperDeleteKind.DOCUMENT,
                deleteFile = { error("file deletion must not run") },
                deleteDocument = { documentCalled = true; true },
                deleteContent = {
                    contentCalled = true
                    throw UnsupportedOperationException("Delete not supported")
                },
            )

        assertTrue(deleted)
        assertTrue(documentCalled)
        assertFalse(contentCalled)
    }

    @Test
    fun `MediaStore resources use content deletion`() {
        var contentCalled = false
        val deleted =
            deleteLocalWallpaperUri(
                kind = LocalWallpaperDeleteKind.CONTENT,
                deleteFile = { error("file deletion must not run") },
                deleteDocument = { error("document deletion must not run") },
                deleteContent = { contentCalled = true; true },
            )
        assertTrue(deleted)
        assertTrue(contentCalled)
    }

    @Test
    fun `selected deletion failure remains visible to repository caller`() {
        assertFailsWith<SecurityException> {
            deleteLocalWallpaperUri(
                kind = LocalWallpaperDeleteKind.DOCUMENT,
                deleteFile = { false },
                deleteDocument = { throw SecurityException("expired") },
                deleteContent = { false },
            )
        }
    }
}
