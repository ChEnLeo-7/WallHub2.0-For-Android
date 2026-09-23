package com.wallhub.android.data.steam

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stable device identity seed the Steam Content SDK derives its `machine_id` from (ADR 0010).
 *
 * The platform owns device identity, so it owns exactly one seed per install: created on first use, kept in
 * the application's own files directory, and never leaving this process except as the derived `machine_id`
 * the SDK sends in a logon. A seed that changed between logons would look like a new device to Steam Guard,
 * which is why it is read back rather than regenerated.
 *
 * It is not a credential: it carries no account, token, or key, and it is not a secret the user owns.
 */
@Singleton
internal class SteamDeviceSeedStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val lock = Any()

        /** Returns the install's seed, creating and persisting one on first use. */
        fun seed(): ByteArray =
            synchronized(lock) {
                val file = File(context.filesDir, SEED_FILE_NAME)
                runCatching { file.readBytes() }
                    .getOrNull()
                    ?.takeIf { it.size == SEED_BYTES }
                    ?.let { return it }
                val created = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeBytes(created)
                }
                created
            }

        private companion object {
            const val SEED_BYTES = 32
            const val SEED_FILE_NAME = "steam-content-sdk/device-seed"
        }
    }
