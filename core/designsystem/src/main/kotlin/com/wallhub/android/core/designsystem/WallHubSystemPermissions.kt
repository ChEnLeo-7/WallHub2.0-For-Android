package com.wallhub.android.core.designsystem

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Returns whether pre-Android 10 public download exports still need storage permission. */
fun Context.requiresLegacyPublicDownloadPermission(): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
