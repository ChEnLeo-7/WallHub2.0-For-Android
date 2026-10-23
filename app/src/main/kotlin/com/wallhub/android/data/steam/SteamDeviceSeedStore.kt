package com.wallhub.android.data.steam

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

        /** The seed this process may use after its exact bytes are durable at [seedFile]. */
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
                persistAtomically(created)
                remembered = created
                created
            }

        @Throws(IOException::class)
        private fun persistAtomically(seed: ByteArray) {
            val parent = seedFile.parentFile ?: throw IOException("Device seed has no parent directory")
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Cannot create device seed directory")
            }
            val temp = File(parent, "${seedFile.name}.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(seed)
                    output.fd.sync()
                }
                Files.move(
                    temp.toPath(),
                    seedFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                if (!seedFile.isFile || !seed.contentEquals(seedFile.readBytes())) {
                    throw IOException("Persisted device seed verification failed")
                }
            } catch (error: IOException) {
                temp.delete()
                throw error
            } catch (error: RuntimeException) {
                temp.delete()
                throw IOException("Cannot persist device seed", error)
            }
        }

        private companion object {
            const val SEED_BYTES = 32
            const val SEED_FILE_NAME = "steam-content-sdk/device-seed"
        }
    }
