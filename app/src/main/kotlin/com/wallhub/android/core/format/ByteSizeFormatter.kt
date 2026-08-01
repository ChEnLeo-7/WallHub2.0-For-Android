package com.wallhub.android.core.format

import java.util.Locale

fun formatByteSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val megabytes = safeBytes / BYTES_PER_MEGABYTE
    return if (megabytes > MEGABYTES_PER_GIGABYTE) {
        String.format(Locale.getDefault(), "%.1f GB", safeBytes / BYTES_PER_GIGABYTE)
    } else {
        String.format(Locale.getDefault(), "%.1f MB", megabytes)
    }
}

private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
private const val MEGABYTES_PER_GIGABYTE = 1024.0
private const val BYTES_PER_GIGABYTE = BYTES_PER_MEGABYTE * MEGABYTES_PER_GIGABYTE
