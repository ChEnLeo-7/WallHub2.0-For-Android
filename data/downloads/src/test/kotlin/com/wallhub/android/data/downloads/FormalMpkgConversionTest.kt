package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.FilePayload
import com.wallhub.prototype.mpkg.MpkgInputEntry
import com.wallhub.prototype.mpkg.MpkgInspector
import com.wallhub.prototype.mpkg.MpkgWriter
import com.wallhub.prototype.mpkg.PkgEntry
import com.wallhub.prototype.mpkg.PkgTestWriter
import com.wallhub.prototype.mpkg.SCENE_MPKG_MAGIC
import com.wallhub.prototype.mpkg.ShaderCompatibility
import com.wallhub.prototype.mpkg.TexMobileConverter
import com.wallhub.prototype.mpkg.VIDEO_MPKG_MAGIC
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class FormalMpkgConversionTest {
    @Test
    fun `video workshop converts to inspectable MPKG`() {
        val root = Files.createTempDirectory("wallhub-video-mpkg-test").toFile()
        try {
            File(root, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "media").mkdirs()
            File(root, "media/demo.mp4").writeBytes(ByteArray(512) { it.toByte() })
            File(root, "project.json").writeText(
                "{\"type\":\"video\",\"file\":\"media/demo.mp4\",\"title\":\"Demo\"}",
            )
            val output = File(root, "output.mpkg")

            WorkshopConverter.convert(root, output, "video")

            assertTrue(output.isFile)
            assertEquals(VIDEO_MPKG_MAGIC, MpkgInspector.inspect(output).magic)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scene workshop converts the first mipmap instead of retaining desktop TEX`() {
        val root = Files.createTempDirectory("wallhub-scene-mipmap-test").toFile()
        try {
            File(root, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "project.json").writeText("{\"type\":\"scene\",\"title\":\"Scene\"}")
            val sourceTex = multiMipmapRgbaTex()
            val textureConversion = TexMobileConverter.convertOrKeep(sourceTex)
            assertTrue(textureConversion.converted, textureConversion.reason)
            PkgTestWriter.write(
                File(root, "scene.pkg"),
                listOf(PkgEntry("materials/main.tex", sourceTex)),
            )
            val output = File(root, "output.mpkg")

            val report = WorkshopConverter.convert(root, output, "scene")

            assertTrue(output.isFile)
            assertEquals(SCENE_MPKG_MAGIC, MpkgInspector.inspect(output).magic)
            assertEquals(1, report.convertedTextures)
            assertEquals(0, report.copiedTextures)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shader compatibility ports Web numeric and blur fixes`() {
        val source = """
            uniform sampler2D g_Texture0; // {"hidden":true}
            #include "common_blur.h"
            const int sampleCount = 3;
            void main() {
                vec4 sample = vec4(1, 0, 0, 1);
                float mask = 1;
                v_TexCoord.z = 0;
                vec3 normal = sample.rgb * 2 - 1;
                for (int i = 0; i < sampleCount; ++i) {}
            }
        """.trimIndent()

        val rewritten = ShaderCompatibility.rewrite(source)

        assertTrue(rewritten.contains("uniform sampler2D g_Texture0;\n#include \"common_blur.h\""))
        assertFalse(rewritten.contains("uniform sampler2D g_Texture0; // {\"hidden\":true}"))
        assertTrue(rewritten.contains("const float sampleCount = 3.0;"))
        assertTrue(rewritten.contains("vec4 _sample = vec4(1.0, 0.0, 0.0, 1.0);"))
        assertTrue(rewritten.contains("float mask = 1;"))
        assertTrue(rewritten.contains("v_TexCoord.z = 0.0;"))
        assertTrue(rewritten.contains("vec3 normal = _sample.rgb * 2 - 1;"))
        assertTrue(rewritten.contains("for (float i = 0.0; i < sampleCount; ++i) {}"))
    }

    @Test
    fun `shader compatibility preserves integer semantics`() {
        val source = """
            int layer = 1;
            ivec2 offset = ivec2(1, 0);
            float exponent = 1e+0;
            vec4 chooseLayer(int index) { return index == 1 ? vec4(1) : vec4(0); }
        """.trimIndent()

        assertEquals(source, ShaderCompatibility.rewrite(source))
    }

    @Test
    fun `mobile TEX writer emits complete LZ4 payload metadata`() {
        val rgba = byteArrayOf(
            1, 2, 3, -1,
            4, 5, 6, -1,
            7, 8, 9, -1,
            10, 11, 12, -1,
        )

        val tex = TexMobileConverter.writeMobileRgba(
            flags = 0,
            width = 2,
            height = 2,
            rgba = rgba,
        )

        StrictMobileTexReader(tex).assertRgba(width = 2, height = 2, expected = rgba)
    }

    @Test
    fun `scene conversion rejects unsupported desktop texture`() {
        val root = Files.createTempDirectory("wallhub-scene-unsupported-tex-test").toFile()
        try {
            File(root, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "project.json").writeText("{\"type\":\"scene\",\"title\":\"Scene\"}")
            PkgTestWriter.write(
                File(root, "scene.pkg"),
                listOf(PkgEntry("materials/unsupported.tex", byteArrayOf(1, 2, 3))),
            )

            val error = assertFailsWith<IllegalStateException> {
                WorkshopConverter.convert(root, File(root, "output.mpkg"), "scene")
            }

            assertTrue(error.message.orEmpty().contains("materials/unsupported.tex"))
            assertFalse(File(root, "output.mpkg").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed MPKG write preserves existing output`() {
        val root = Files.createTempDirectory("wallhub-atomic-mpkg-test").toFile()
        try {
            val source = File(root, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val payload = FilePayload(source)
            source.writeBytes(byteArrayOf(1, 2))
            val output = File(root, "output.mpkg").apply { writeBytes(byteArrayOf(9, 8, 7)) }

            assertFailsWith<IllegalArgumentException> {
                MpkgWriter.write(
                    entries = listOf(MpkgInputEntry("source.bin", payload)),
                    outputFile = output,
                    magic = SCENE_MPKG_MAGIC,
                )
            }

            assertTrue(output.readBytes().contentEquals(byteArrayOf(9, 8, 7)))
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun multiMipmapRgbaTex(): ByteArray = ByteArrayOutputStream().use { output ->
        output.writeCString("TEXV0005")
        output.writeCString("TEXI0001")
        output.writeIntLe(0)
        output.writeIntLe(0)
        output.writeIntLe(2)
        output.writeIntLe(2)
        output.writeIntLe(2)
        output.writeIntLe(2)
        output.writeIntLe(0)
        output.writeCString("TEXB0004")
        output.writeIntLe(1)
        output.writeIntLe(-1)
        output.writeIntLe(0)
        output.writeIntLe(2)
        writeUncompressedMipmap(
            output = output,
            width = 2,
            height = 2,
            bytes = byteArrayOf(
                1, 2, 3, -1,
                4, 5, 6, -1,
                7, 8, 9, -1,
                10, 11, 12, -1,
            ),
        )
        writeUncompressedMipmap(
            output = output,
            width = 1,
            height = 1,
            bytes = byteArrayOf(13, 14, 15, -1),
        )
        output.toByteArray()
    }

    private fun writeUncompressedMipmap(
        output: ByteArrayOutputStream,
        width: Int,
        height: Int,
        bytes: ByteArray,
    ) {
        output.writeIntLe(width)
        output.writeIntLe(height)
        output.writeIntLe(0)
        output.writeIntLe(bytes.size)
        output.writeIntLe(bytes.size)
        output.write(bytes)
    }

    private fun ByteArrayOutputStream.writeCString(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
        write(0)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private class StrictMobileTexReader(private val source: ByteArray) {
        private var offset = 0

        fun assertRgba(width: Int, height: Int, expected: ByteArray) {
            assertEquals("TEXV0005", readCString())
            assertEquals("TEXI0001", readCString())
            assertEquals(0, readIntLe())
            assertEquals(0, readIntLe())
            assertEquals(width, readIntLe())
            assertEquals(height, readIntLe())
            assertEquals(width, readIntLe())
            assertEquals(height, readIntLe())
            assertEquals(0, readIntLe())
            assertEquals("TEXB0004", readCString())
            assertEquals(1, readIntLe())
            assertEquals(-1, readIntLe())
            assertEquals(0, readIntLe())
            assertEquals(1, readIntLe())
            assertEquals(width, readIntLe())
            assertEquals(height, readIntLe())
            assertEquals(1, readIntLe())
            assertEquals(expected.size, readIntLe())
            val compressedSize = readIntLe()
            val compressed = source.copyOfRange(offset, offset + compressedSize)
            offset += compressedSize
            val decoded = ByteArray(expected.size)
            val decodedSize = net.jpountz.lz4.LZ4Factory.fastestJavaInstance().safeDecompressor()
                .decompress(compressed, 0, compressed.size, decoded, 0, decoded.size)
            assertEquals(decoded.size, decodedSize)
            assertTrue(expected.contentEquals(decoded))
            assertEquals(source.size, offset)
        }

        private fun readCString(): String {
            var end = offset
            while (end < source.size && source[end] != 0.toByte()) end += 1
            require(end >= offset)
            return source.copyOfRange(offset, end).toString(Charsets.US_ASCII).also {
                offset = end + 1
            }
        }

        private fun readIntLe(): Int {
            require(offset + 4 <= source.size)
            return ((source[offset].toInt() and 0xff) or
                ((source[offset + 1].toInt() and 0xff) shl 8) or
                ((source[offset + 2].toInt() and 0xff) shl 16) or
                ((source[offset + 3].toInt() and 0xff) shl 24)).also {
                offset += 4
            }
        }
    }
}
