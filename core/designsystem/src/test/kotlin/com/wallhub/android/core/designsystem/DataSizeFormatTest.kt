package com.wallhub.android.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DataSizeFormatTest {
    @Test
    fun `formats values up to 1024 MB as megabytes`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("1024.0 MB", formatMegabytes(1024L * 1024L * 1024L))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `formats values above 1024 MB as gigabytes`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("1.0 GB", formatMegabytes(1024L * 1024L * 1024L + 1L))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
