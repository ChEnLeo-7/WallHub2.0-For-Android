package com.wallhub.android.core.model

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

fun Context.requiresLegacyPublicDownloadPermission(): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
