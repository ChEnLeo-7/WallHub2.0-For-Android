package com.wallhub.android.data.steam

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The trust bundle is the SDK's whole view of what the platform trusts, so its shape is pinned here.
 *
 * These tests pass arbitrary byte arrays and never a certificate: the helper must not care what a DER blob
 * means, only that each one comes out as one PEM block in the order it was given.
 */
class SteamContentSdkTrustBundleTest {
    @Test
    fun twoDerBlobsProduceTwoPemBlocksInInputOrder() {
        val first = byteArrayOf(0x30, 0x01, 0x00)
        val second = byteArrayOf(0x7F, 0x10, 0x20, 0x30, 0x40)

        val pem = trustBundlePem(listOf(first, second)).toString(Charsets.US_ASCII)

        assertEquals(2, pem.lineSequence().count { it == "-----BEGIN CERTIFICATE-----" })
        assertEquals(2, pem.lineSequence().count { it == "-----END CERTIFICATE-----" })
        val bodies =
            pem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        assertEquals(2, bodies.size, "one body line per small blob")
        assertContentEquals(first, Base64.getDecoder().decode(bodies[0]))
        assertContentEquals(second, Base64.getDecoder().decode(bodies[1]))
    }

    @Test
    fun aPemBlockStartsWithBeginAndEndsWithEnd() {
        val pem = trustBundlePem(listOf(byteArrayOf(1, 2, 3))).toString(Charsets.US_ASCII)
        val lines = pem.lineSequence().filter { it.isNotBlank() }.toList()

        assertEquals("-----BEGIN CERTIFICATE-----", lines.first())
        assertEquals("-----END CERTIFICATE-----", lines.last())
    }

    @Test
    fun aLongEncodingIsWrappedButStillDecodes() {
        val certificate = ByteArray(100) { index -> (index % 251).toByte() }

        val pem = trustBundlePem(listOf(certificate)).toString(Charsets.US_ASCII)
        val lines = pem.lineSequence().filter { it.isNotBlank() }.toList()

        assertEquals("-----BEGIN CERTIFICATE-----", lines.first())
        assertEquals("-----END CERTIFICATE-----", lines.last())
        assertTrue(lines.size > 3, "a 100 byte blob must wrap across more than one body line")
        assertTrue(lines.drop(1).dropLast(1).all { it.length <= 64 })
        val body = lines.drop(1).dropLast(1).joinToString("")
        assertContentEquals(certificate, Base64.getDecoder().decode(body))
    }

    @Test
    fun anEmptyStoreIsRejectedLoudly() {
        val failure = assertFailsWith<IllegalStateException> { trustBundlePem(emptyList()) }

        assertTrue(failure.message.orEmpty().contains("trust store is empty"))
    }
}
