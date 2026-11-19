package com.wallhub.prototype.mpkg

import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertContentEquals

class RgbaLz4BenchmarkTest {
    @Test
    fun `compare HC3 and fast raw block candidates on texture-like RGBA`() {
        val rgba = textureLikeRgba(8 * 1024 * 1024)
        RgbaLz4Mode.entries.forEach { mode -> repeat(3) { RgbaLz4Codec.compress(rgba, mode) } }

        RgbaLz4Mode.entries.forEach { mode ->
            val times = ArrayList<Long>()
            var compressedBytes = 0
            repeat(10) {
                lateinit var block: CompressedLz4Block
                times += measureNanoTime { block = RgbaLz4Codec.compress(rgba, mode) }
                compressedBytes = block.length
                assertContentEquals(rgba, decompressLz4(block, rgba.size))
            }
            println(
                "LZ4_BENCH mode=${mode.stableId} medianMs=${times.sorted()[times.size / 2] / 1_000_000.0} " +
                    "compressedBytes=$compressedBytes inputBytes=${rgba.size}",
            )
        }
    }

    private fun textureLikeRgba(size: Int): ByteArray {
        val noise = Random(7)
        return ByteArray(size) { index ->
            val pixel = index / 4
            when (index % 4) {
                0 -> (pixel % 256).toByte()
                1 -> ((pixel / 256) % 256).toByte()
                2 -> if (pixel % 97 == 0) noise.nextInt(256).toByte() else 96.toByte()
                else -> 0xff.toByte()
            }
        }
    }

    private fun decompressLz4(block: CompressedLz4Block, size: Int): ByteArray =
        ByteArray(size).also { output ->
            net.jpountz.lz4.LZ4Factory.fastestJavaInstance().safeDecompressor()
                .decompress(block.bytes, 0, block.length, output, 0, output.size)
        }
}
