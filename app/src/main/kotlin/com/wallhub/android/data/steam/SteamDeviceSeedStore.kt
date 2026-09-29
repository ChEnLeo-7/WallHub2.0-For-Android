package com.wallhub.android.data.steam

import android.content.Context
import android.util.Log
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
    internal constructor(
        private val seedFile: File,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(File(context.filesDir, SEED_FILE_NAME))

        private val lock = Any()

        /**
         * The seed this process is using, kept so a write failure cannot change it.
         *
         * [seed] runs once per engine creation. Regenerating a seed that could not be persisted would present
         * a different `machine_id` to Steam Guard within one run of the app, so the value is remembered here
         * whether or not the write succeeded.
         */
        private var remembered: ByteArray? = null

        /**
         * Returns the install's seed, creating and persisting one on first use.
         *
         * A seed that could not be written is reported rather than swallowed: it is the one thing that makes
         * this install look like a new device to Steam Guard after a restart, and the previous code returned
         * the fresh seed in silence, so nothing could tell the two apart.
         */
        fun seed(): ByteArray =
            synchronized(lock) {
                remembered?.let { return it }
                val existing = runCatching { seedFile.readBytes() }.getOrNull()
                if (existing != null && existing.size == SEED_BYTES) {
                    remembered = existing
                    return existing
                }
                val created = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
                remembered = created
                runCatching {
                    seedFile.parentFile?.mkdirs()
                    seedFile.writeBytes(created)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "device seed could not be persisted; this install will look like a new device",
                        error,
                    )
                }
                created
            }

        private companion object {
            const val TAG = "WallHubSteamDeviceSeed"
            const val SEED_BYTES = 32
            const val SEED_FILE_NAME = "steam-content-sdk/device-seed"
        }
    }
