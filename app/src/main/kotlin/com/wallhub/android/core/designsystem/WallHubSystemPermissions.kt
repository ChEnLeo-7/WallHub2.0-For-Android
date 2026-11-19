package com.wallhub.android.core.designsystem

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Returns whether pre-Android 10 public download exports still need storage permission. */
fun Context.requiresLegacyPublicDownloadPermission(): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

/** Android 11+ "All files access": the broad file permission granted from the system settings page. */
fun Context.hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

/**
 * Whether WallHub may write its own files in public Download/WallHub directly.
 *
 * Android 10 has neither All files access nor a usable write permission; there the MediaStore path owns
 * the public folder instead, so this only answers for the direct File route.
 */
fun Context.canWritePublicDownloadsDirectly(): Boolean =
    hasAllFilesAccess() ||
        (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        )

/** Settings page that hosts the All files access toggle, or null when the platform has no such page. */
fun Context.allFilesAccessSettingsIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
    } else {
        null
    }
