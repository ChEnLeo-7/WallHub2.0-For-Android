package com.wallhub.android.data.steam

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The device identity seed, and the one property that matters: the value a logon presents must not change
 * underneath the account.
 *
 * [SteamDeviceSeedStore.seed] runs once per engine creation, so a seed that could not be written must still be
 * the same seed on the next call - otherwise one app run presents several `machine_id`s to Steam Guard, which
 * is exactly what makes it treat a known device as a new one.
 */
class SteamDeviceSeedStoreTest {
    @Test
    fun `an existing seed is read back unchanged`() {
        val file = seedFile()
        val existing = ByteArray(32) { index -> index.toByte() }
        file.parentFile?.mkdirs()
        file.writeBytes(existing)

        assertContentEquals(existing, SteamDeviceSeedStore(file).seed())
    }

    @Test
    fun `a seed of the wrong size is replaced`() {
        val file = seedFile()
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(8))

        val created = SteamDeviceSeedStore(file).seed()

        assertEquals(32, created.size)
        assertContentEquals(created, file.readBytes())
    }

    @Test
    fun `a seed that cannot be written is still stable within the process`() {
        // The seed's parent path is a file, so the directory cannot be created and the write cannot succeed:
        // the case a full or read-only filesystem produces.
        val blocker = Files.createTempDirectory("wallhub-seed-test").toFile().resolve("blocked")
        blocker.writeText("not a directory")
        val file = blocker.resolve("device-seed")
        val store = SteamDeviceSeedStore(file)

        val first = store.seed()
        val second = store.seed()

        assertFalse(file.exists())
        assertContentEquals(first, second)
    }

    @Test
    fun `two stores over one file agree on the seed`() {
        val file = seedFile()

        val first = SteamDeviceSeedStore(file).seed()
        val second = SteamDeviceSeedStore(file).seed()

        assertContentEquals(first, second)
    }

    @Test
    fun `a generated seed is not a constant`() {
        val first = SteamDeviceSeedStore(seedFile()).seed()
        val second = SteamDeviceSeedStore(seedFile()).seed()

        assertFalse(MessageDigest.isEqual(first, second))
        assertTrue(first.any { it.toInt() != 0 })
    }

    private fun seedFile() =
        Files.createTempDirectory("wallhub-seed-test")
            .toFile()
            .resolve("steam-content-sdk/device-seed")
}
