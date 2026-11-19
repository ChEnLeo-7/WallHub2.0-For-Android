package com.wallhub.android.data.steam

import android.content.Context

/** A local safety interval, not a Steam-specified retry duration. */
internal const val ACCOUNT_RATE_LIMIT_COOLDOWN_MS = 60 * 60 * 1_000L

internal interface SteamAccountAdmissionStore {
    fun readLimited(): Boolean
    fun writeLimited(limited: Boolean)
}

internal class AndroidSteamAccountAdmissionStore(context: Context) : SteamAccountAdmissionStore {
    private val preferences = context.getSharedPreferences("wallhub_account_admission", Context.MODE_PRIVATE)

    override fun readLimited(): Boolean = preferences.getBoolean("rate_limited", false)

    override fun writeLimited(limited: Boolean) {
        check(preferences.edit().putBoolean("rate_limited", limited).commit())
    }
}
