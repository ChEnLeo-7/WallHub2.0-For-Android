package com.wallhub.prototype.mpkg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import net.jpountz.lz4.LZ4Factory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TEX_FORMAT_RGBA8888 = 0
private const val TEX_FORMAT_DXT5_LZ4 = 5
private const val TEX_FORMAT_DXT5 = 4
private const val TEX_FORMAT_DXT3 = 6
private const val TEX_FORMAT_DXT1 = 7
private const val TEX_FORMAT_RG88 = 8
private const val TEX_FORMAT_R8 = 9
private const val TEX_FLAG_GIF = 4
private const val TEX_FLAG_VIDEO = 32

data class TexConversionResult(
    val bytes: ByteArray,
    val converted: Boolean,
    val reason: String,
)

data class TexFileConversionResult(
    val converted: Boolean,
    val reason: String,
    val canRetainOriginal: Boolean = false,
)

private data class MobileRgbaTexture(
    val flags: Int,
    val width: Int,
    val height: Int,
    val unknown: Int,
    val rgba: ByteArray,
)

private data class PreparedTexConversion(
    val texture: MobileRgbaTexture?,
    val reason: String,
)

private data class TexFile(
    val format: Int,
    val flags: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val unknown: Int,
    val imageFormat: Int,
    val isMp4: Boolean,
    val mipmaps: List<TexMipmap>,
    val tailData: ByteArray,
)

private data class TexMipmap(
    val width: Int,
    val height: Int,
    val data: ByteArray,
)

private data class TexEntryMetadata(
    val flags: Int,
    val unknown: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageFormat: Int,
    val isMp4: Boolean,
    val baseWidth: Int,
    val baseHeight: Int,
    val baseCompressed: Boolean,
    val pngStart: Long,
    val pngLength: Long,
)

/**
 * A pure Kotlin compatibility path for WallHub's lossless MPKG profile.
 * It converts common scene TEX formats to RGBA8888 + LZ4. ETC2 remains an
 * explicitly separate NDK validation item because Android has no public ETC2 encoder API.
 */
object TexMobileConverter {
    fun convertOrKeep(source: ByteArray): TexConversionResult {
        return runCatching {
            val prepared = prepare(source)
            val texture =
                prepared.texture
                    ?: return TexConversionResult(source, false, prepared.reason)
            TexConversionResult(
                bytes =
                    writeMobileRgba(
                        flags = texture.flags,
                        width = texture.width,
                        height = texture.height,
                        unknown = texture.unknown,
                        rgba = texture.rgba,
                    ),
                converted = true,
                reason = prepared.reason,
            )
        }.getOrElse { error ->
            TexConversionResult(source, false, error.message ?: error.javaClass.simpleName)
        }
    }

    fun convertToFile(
        source: ByteArray,
        outputFile: File,
    ): TexFileConversionResult {
        return runCatching {
            val prepared = prepare(source)
            val texture =
                prepared.texture
                    ?: return TexFileConversionResult(
                        converted = false,
                        reason = prepared.reason,
                        canRetainOriginal = true,
                    ).also { outputFile.delete() }
            writeMobileRgba(
                outputFile = outputFile,
                flags = texture.flags,
                width = texture.width,
                height = texture.height,
                unknown = texture.unknown,
                rgba = texture.rgba,
            )
            TexFileConversionResult(true, prepared.reason)
    }.getOrElse { error ->
        outputFile.delete()
        TexFileConversionResult(
            converted = false,
            reason = error.message ?: error.javaClass.simpleName,
            canRetainOriginal = hasValidTexEnvelope(source),
        )
    }

    fun convertToDxt5File(
        sourceFile: File,
        offset: Long,
        length: Long,
        outputFile: File,
    ): TexFileConversionResult =
        runCatching {
            val metadata = readTexEntryMetadata(sourceFile, offset, length)
            require(metadata.imageFormat >= 0) { "TEX payload is not image-backed" }
            require(!metadata.isMp4 && metadata.flags and TEX_FLAG_GIF == 0 && metadata.flags and TEX_FLAG_VIDEO == 0) {
                ANIMATED_TEXTURE_REASON
            }
            require(!metadata.baseCompressed) { "Compressed TEX payload is unsupported for DXT5 conversion" }
            require(metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight) {
                "TEX base mipmap does not match image dimensions"
            }
            require(metadata.imageWidth % 4 == 0 && metadata.imageHeight % 4 == 0) {
                "TEX dimensions are not aligned to DXT5 block edges"
            }
            require(metadata.imageWidth.toLong() * metadata.imageHeight <= MAX_DXT5_PIXELS) {
                "TEX exceeds DXT5 conversion pixel limit"
            }
            val tempPng = File(outputFile.parentFile, "${outputFile.name}.png")
            try {
                copyPngPayload(sourceFile, metadata.pngStart, metadata.pngLength, tempPng)
                writeDxt5(tempPng, metadata.flags, metadata.unknown, metadata.imageWidth, metadata.imageHeight, outputFile)
                TexFileConversionResult(true, "DXT5 + LZ4 HC3")
            } finally {
                tempPng.delete()
            }
        }.getOrElse { error ->
            outputFile.delete()
            TexFileConversionResult(false, error.message ?: error.javaClass.simpleName)
        }

    internal fun hasValidTexEnvelope(source: ByteArray): Boolean =
        runCatching {
            val reader = ByteCursor(source)
            require(reader.readCString() == "TEXV0005")
            require(reader.readCString() == "TEXI0001")
            reader.readIntLe()
            reader.readIntLe()
            repeat(4) { require(reader.readIntLe() > 0) }
            reader.readIntLe()
            val container = reader.readCString()
            require(reader.readIntLe() == 1)
            when (container) {
                "TEXB0003" -> reader.readIntLe()
                "TEXB0004" -> {
                    reader.readIntLe()
                    reader.readIntLe()
                }
                "TEXB0001", "TEXB0002" -> Unit
                else -> error("Unsupported TEX container")
            }
            val mipmapCount = reader.readIntLe()
            require(mipmapCount in 0..64)
            repeat(mipmapCount) {
                require(reader.readIntLe() > 0)
                require(reader.readIntLe() > 0)
                val storedSize =
                    if (container == "TEXB0001") {
                        reader.readIntLe()
                    } else {
                        val compressed = reader.readIntLe()
                        val decompressedSize = reader.readIntLe()
                        val size = reader.readIntLe()
                        require(compressed in 0..1)
                        require(decompressedSize in 0..MAX_RETAINED_TEX_DECOMPRESSED_BYTES)
                        size
                    }
                require(storedSize >= 0)
                reader.skip(storedSize)
            }
            require(reader.remainingBytes().isEmpty())
            true
        }.getOrDefault(false)

    internal fun hasValidTexEnvelope(
        sourceFile: File,
        offset: Long,
        length: Long,
    ): Boolean =
        runCatching {
            require(offset >= 0L && length >= 0L && length <= sourceFile.length() && offset <= sourceFile.length() - length)
            RandomAccessFile(sourceFile, "r").use { input ->
                val end = offset + length
                input.seek(offset)
                require(input.readBoundedCString(end) == "TEXV0005")
                require(input.readBoundedCString(end) == "TEXI0001")
                input.readBoundedIntLe(end)
                input.readBoundedIntLe(end)
                repeat(4) { require(input.readBoundedIntLe(end) > 0) }
                input.readBoundedIntLe(end)
                val container = input.readBoundedCString(end)
                require(input.readBoundedIntLe(end) == 1)
                when (container) {
                    "TEXB0003" -> input.readBoundedIntLe(end)
                    "TEXB0004" -> {
                        input.readBoundedIntLe(end)
                        input.readBoundedIntLe(end)
                    }
                    "TEXB0001", "TEXB0002" -> Unit
                    else -> error("Unsupported TEX container")
                }
                val mipmapCount = input.readBoundedIntLe(end)
                require(mipmapCount in 0..64)
                repeat(mipmapCount) {
                    require(input.readBoundedIntLe(end) > 0)
                    require(input.readBoundedIntLe(end) > 0)
                    val storedSize =
                        if (container == "TEXB0001") {
                            input.readBoundedIntLe(end)
                        } else {
                            val compressed = input.readBoundedIntLe(end)
                            val decompressedSize = input.readBoundedIntLe(end)
                            val size = input.readBoundedIntLe(end)
                            require(compressed in 0..1)
                            require(decompressedSize in 0..MAX_RETAINED_TEX_DECOMPRESSED_BYTES)
                            size
                        }
                    require(storedSize >= 0)
                    require(input.filePointer <= end - storedSize.toLong())
                    input.seek(input.filePointer + storedSize)
                }
                require(input.filePointer == end)
            }
            true
        }.getOrDefault(false)

    fun writeMobileRgba(
        flags: Int,
        width: Int,
        height: Int,
        unknown: Int = 0,
        rgba: ByteArray,
    ): ByteArray {
        require(width > 0 && height > 0) { "Invalid TEX dimensions" }
        val expectedSize = checkedPayloadSize(width, height, 4)
        require(rgba.size == expectedSize) { "RGBA payload size does not match TEX dimensions" }
        val (compressed, compressedLength) = compress(rgba)
        val output = ByteArray(MOBILE_TEX_HEADER_SIZE + compressedLength)
        mobileRgbaHeader(flags, width, height, unknown, rgba.size, compressedLength).copyInto(output)
        compressed.copyInto(output, destinationOffset = MOBILE_TEX_HEADER_SIZE, endIndex = compressedLength)
        return output
    }

    private fun writeMobileRgba(
        outputFile: File,
        flags: Int,
        width: Int,
        height: Int,
        unknown: Int,
        rgba: ByteArray,
    ) {
        require(width > 0 && height > 0) { "Invalid TEX dimensions" }
        val expectedSize = checkedPayloadSize(width, height, 4)
        require(rgba.size == expectedSize) { "RGBA payload size does not match TEX dimensions" }
        val (compressed, compressedLength) = compress(rgba)
        outputFile.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Unable to create TEX output directory" }
        }
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            output.write(mobileRgbaHeader(flags, width, height, unknown, rgba.size, compressedLength))
            output.write(compressed, 0, compressedLength)
        }
    }

    private fun prepare(source: ByteArray): PreparedTexConversion {
        val tex = parse(source)
        if (tex.mipmaps.isEmpty()) return PreparedTexConversion(null, "empty texture")
        if (tex.flags and TEX_FLAG_GIF != 0 || tex.flags and TEX_FLAG_VIDEO != 0 || tex.isMp4) {
            return PreparedTexConversion(null, ANIMATED_TEXTURE_REASON)
        }
        if (tex.tailData.isNotEmpty()) return PreparedTexConversion(null, "trailing texture data")

        val mipmap =
            tex.mipmaps.singleOrNull { mipmap ->
                mipmap.width == tex.textureWidth && mipmap.height == tex.textureHeight
            } ?: return PreparedTexConversion(null, "base texture mipmap is missing or ambiguous")
        val rgba =
            when {
                tex.imageFormat >= 0 -> imagePayloadToRgba(mipmap.data, tex.imageWidth, tex.imageHeight)
                tex.format == TEX_FORMAT_RGBA8888 ->
                    cropRgba(
                        mipmap.data,
                        mipmap.width,
                        mipmap.height,
                        tex.imageWidth,
                        tex.imageHeight,
                    )
                tex.format in setOf(TEX_FORMAT_DXT1, TEX_FORMAT_DXT3, TEX_FORMAT_DXT5) ->
                    cropRgba(
                        decodeDxt(mipmap.width, mipmap.height, mipmap.data, tex.format),
                        mipmap.width,
                        mipmap.height,
                        tex.imageWidth,
                        tex.imageHeight,
                    )
                tex.format == TEX_FORMAT_R8 ->
                    convertR8(
                        mipmap.data,
                        mipmap.width,
                        mipmap.height,
                        tex.imageWidth,
                        tex.imageHeight,
                    )
                tex.format == TEX_FORMAT_RG88 ->
                    convertRg88(
                        mipmap.data,
                        mipmap.width,
                        mipmap.height,
                        tex.imageWidth,
                        tex.imageHeight,
                    )
                else -> return PreparedTexConversion(null, "unsupported TEX format ${tex.format}")
            }
        return PreparedTexConversion(
            texture = MobileRgbaTexture(tex.flags, tex.imageWidth, tex.imageHeight, tex.unknown, rgba),
            reason = "RGBA8888 + LZ4 HC3",
        )
    }

    private fun compress(rgba: ByteArray): Pair<ByteArray, Int> {
        val compressor = LZ4Factory.fastestJavaInstance().highCompressor(LZ4_COMPRESSION_LEVEL)
        val compressed = ByteArray(compressor.maxCompressedLength(rgba.size))
        val length = compressor.compress(rgba, 0, rgba.size, compressed, 0, compressed.size)
        return compressed to length
    }

    private fun mobileRgbaHeader(
        flags: Int,
        width: Int,
        height: Int,
        unknown: Int,
        decompressedLength: Int,
        compressedLength: Int,
    ): ByteArray {
        val output = ByteArray(MOBILE_TEX_HEADER_SIZE)
        var offset = 0
        offset = output.writeCString(offset, "TEXV0005")
        offset = output.writeCString(offset, "TEXI0001")
        listOf(TEX_FORMAT_RGBA8888, flags, width, height, width, height, unknown).forEach { value ->
            offset = output.writeIntLe(offset, value)
        }
        offset = output.writeCString(offset, "TEXB0004")
        listOf(1, -1, 0, 1, width, height, 1, decompressedLength, compressedLength).forEach { value ->
            offset = output.writeIntLe(offset, value)
        }
        require(offset == MOBILE_TEX_HEADER_SIZE)
        return output
    }

    private fun readTexEntryMetadata(
        sourceFile: File,
        offset: Long,
        length: Long,
    ): TexEntryMetadata =
        RandomAccessFile(sourceFile, "r").use { input ->
            require(offset >= 0L && length >= 0L && length <= sourceFile.length() && offset <= sourceFile.length() - length)
            val end = offset + length
            input.seek(offset)
            require(input.readBoundedCString(end) == "TEXV0005") { "Invalid TEX version" }
            require(input.readBoundedCString(end) == "TEXI0001") { "Invalid TEX metadata" }
            input.readBoundedIntLe(end)
            val flags = input.readBoundedIntLe(end)
            val textureWidth = input.readBoundedIntLe(end)
            val textureHeight = input.readBoundedIntLe(end)
            val imageWidth = input.readBoundedIntLe(end)
            val imageHeight = input.readBoundedIntLe(end)
            require(textureWidth > 0 && textureHeight > 0 && imageWidth > 0 && imageHeight > 0) {
                "Invalid TEX dimensions"
            }
            val unknown = input.readBoundedIntLe(end)
            val container = input.readBoundedCString(end)
            require(input.readBoundedIntLe(end) == 1) { "Unsupported TEX image count" }
            var imageFormat = -1
            var isMp4 = false
            when (container) {
                "TEXB0003" -> imageFormat = input.readBoundedIntLe(end)
                "TEXB0004" -> {
                    imageFormat = input.readBoundedIntLe(end)
                    isMp4 = input.readBoundedIntLe(end) != 0
                }
                "TEXB0001", "TEXB0002" -> Unit
                else -> error("Unsupported TEX container: $container")
            }
            val mipCount = input.readBoundedIntLe(end)
            require(mipCount in 1..64) { "Invalid TEX mipmap count" }
            var baseWidth = 0
            var baseHeight = 0
            var baseCompressed = false
            var payloadStart = 0L
            var payloadLength = 0L
            repeat(mipCount) { index ->
                val width = input.readBoundedIntLe(end)
                val height = input.readBoundedIntLe(end)
                require(width > 0 && height > 0) { "Invalid TEX mipmap dimensions" }
                var compressed = false
                val storedSize =
                    if (container == "TEXB0001") {
                        input.readBoundedIntLe(end)
                    } else {
                        compressed = input.readBoundedIntLe(end) == 1
                        input.readBoundedIntLe(end)
                        input.readBoundedIntLe(end)
                    }
                require(storedSize >= 0) { "Invalid TEX payload size" }
                require(input.filePointer <= end - storedSize.toLong()) { "Invalid TEX payload size" }
                if (index == 0) {
                    baseWidth = width
                    baseHeight = height
                    baseCompressed = compressed
                    payloadStart = input.filePointer
                    payloadLength = storedSize.toLong()
                }
                input.seek(input.filePointer + storedSize)
            }
            require(input.filePointer == end) { "TEX entry has trailing data" }
            val pngStart = locatePngSignature(input, payloadStart, payloadLength)
            TexEntryMetadata(
                flags = flags,
                unknown = unknown,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                imageFormat = imageFormat,
                isMp4 = isMp4,
                baseWidth = baseWidth,
                baseHeight = baseHeight,
                baseCompressed = baseCompressed,
                pngStart = pngStart,
                pngLength = payloadStart + payloadLength - pngStart,
            )
        }

    private fun locatePngSignature(
        input: RandomAccessFile,
        payloadStart: Long,
        payloadLength: Long,
    ): Long {
        val scanLength = min(payloadLength, PNG_SIGNATURE_SCAN_BYTES.toLong()).toInt()
        require(scanLength >= PNG_SIGNATURE.size) { "TEX payload is too small for a PNG image" }
        input.seek(payloadStart)
        val buffer = ByteArray(scanLength)
        input.readFully(buffer)
        for (index in 0..buffer.size - PNG_SIGNATURE.size) {
            var matched = true
            for (byteIndex in PNG_SIGNATURE.indices) {
                if (buffer[index + byteIndex] != PNG_SIGNATURE[byteIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return payloadStart + index
        }
        error("TEX payload is not a PNG image")
    }

    private fun copyPngPayload(
        sourceFile: File,
        pngStart: Long,
        pngLength: Long,
        outputFile: File,
    ) {
        RandomAccessFile(sourceFile, "r").use { input ->
            input.seek(pngStart)
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(PNG_COPY_BUFFER_BYTES)
                var remaining = pngLength
                while (remaining > 0) {
                    val chunk = min(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, chunk)
                    output.write(buffer, 0, chunk)
                    remaining -= chunk
                }
            }
        }
    }

    private fun writeDxt5(
        pngFile: File,
        flags: Int,
        unknown: Int,
        width: Int,
        height: Int,
        outputFile: File,
    ) {
        val dxt = encodeDxt5(pngFile, width, height)
        val (compressed, compressedLength) = compress(dxt)
        outputFile.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Unable to create TEX output directory" }
        }
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            output.write(dxt5Header(flags, width, height, unknown, dxt.size, compressedLength))
            output.write(compressed, 0, compressedLength)
        }
    }

    private fun encodeDxt5(
        pngFile: File,
        width: Int,
        height: Int,
    ): ByteArray {
        val dxt = ByteArray(checkedPayloadSize(width, height, 1))
        FileInputStream(pngFile).use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream.fd, false)
                ?: error("Unsupported PNG payload in TEX entry")
            try {
                require(decoder.width == width && decoder.height == height) {
                    "PNG payload dimensions do not match TEX metadata"
                }
                val stripHeight = min(DXT5_STRIP_HEIGHT, height)
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var stripTop = 0
                var dxtOffset = 0
                while (stripTop < height) {
                    val rows = min(stripHeight, height - stripTop)
                    require(rows % 4 == 0) { "TEX height is not aligned to DXT5 block edges" }
                    val bitmap =
                        decoder.decodeRegion(Rect(0, stripTop, width, stripTop + rows), options)
                            ?: error("Failed to decode TEX PNG payload")
                    try {
                        require(bitmap.width == width && bitmap.height == rows) {
                            "PNG payload dimensions do not match TEX metadata"
                        }
                        val pixels = IntArray(checkedPayloadSize(width, rows, 1))
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, rows)
                        encodeDxt5Strip(pixels, width, rows, dxt, dxtOffset)
                    } finally {
                        bitmap.recycle()
                    }
                    stripTop += rows
                    dxtOffset += width * rows
                }
            } finally {
                decoder.recycle()
            }
        }
        return dxt
    }

    private fun encodeDxt5Strip(
        pixels: IntArray,
        width: Int,
        height: Int,
        dxt: ByteArray,
        dxtOffset: Int,
    ) {
        var blockOffset = dxtOffset
        for (blockTop in 0 until height step 4) {
            for (blockLeft in 0 until width step 4) {
                encodeDxt5Block(pixels, width, blockTop, blockLeft, dxt, blockOffset)
                blockOffset += DXT5_BLOCK_BYTES
            }
        }
    }

    private fun encodeDxt5Block(
        pixels: IntArray,
        width: Int,
        blockTop: Int,
        blockLeft: Int,
        dxt: ByteArray,
        offset: Int,
    ) {
        val colors = IntArray(DXT5_BLOCK_PIXELS)
        var index = 0
        repeat(4) { row ->
            repeat(4) { column ->
                colors[index++] = pixels[(blockTop + row) * width + blockLeft + column]
            }
        }
        encodeDxt5AlphaBlock(colors, dxt, offset)
        encodeDxt5ColorBlock(colors, dxt, offset + DXT5_ALPHA_BLOCK_BYTES)
    }

    private fun encodeDxt5AlphaBlock(
        colors: IntArray,
        dxt: ByteArray,
        offset: Int,
    ) {
        val alphas = IntArray(DXT5_BLOCK_PIXELS) { index -> (colors[index] ushr 24) and 0xff }
        val alphaMax = alphas.max()
        val alphaMin = alphas.min()
        val palette = IntArray(8)
        palette[0] = alphaMax
        palette[1] = alphaMin
        if (alphaMax > alphaMin) {
            (1..6).forEach { index -> palette[index + 1] = ((7 - index) * alphaMax + index * alphaMin) / 7 }
        } else {
            (1..4).forEach { index -> palette[index + 1] = ((5 - index) * alphaMax + index * alphaMin) / 5 }
            palette[6] = 0
            palette[7] = 255
        }
        var bits = 0L
        alphas.forEachIndexed { index, alpha ->
            var bestIndex = 0
            var bestDistance = Int.MAX_VALUE
            repeat(8) { paletteIndex ->
                val distance = abs(alpha - palette[paletteIndex])
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = paletteIndex
                }
            }
            bits = bits or (bestIndex.toLong() shl (index * 3))
        }
        dxt[offset] = alphaMax.toByte()
        dxt[offset + 1] = alphaMin.toByte()
        repeat(6) { byteIndex ->
            dxt[offset + 2 + byteIndex] = ((bits ushr (byteIndex * 8)) and 0xffL).toByte()
        }
    }

    private fun encodeDxt5ColorBlock(
        colors: IntArray,
        dxt: ByteArray,
        offset: Int,
    ) {
        var redMin = 255
        var redMax = 0
        var greenMin = 255
        var greenMax = 0
        var blueMin = 255
        var blueMax = 0
        colors.forEach { color ->
            val red = (color ushr 16) and 0xff
            val green = (color ushr 8) and 0xff
            val blue = color and 0xff
            redMin = min(redMin, red)
            redMax = max(redMax, red)
            greenMin = min(greenMin, green)
            greenMax = max(greenMax, green)
            blueMin = min(blueMin, blue)
            blueMax = max(blueMax, blue)
        }
        val packedMax = packRgb565(redMax, greenMax, blueMax)
        val packedMin = packRgb565(redMin, greenMin, blueMin)
        val high = maxOf(packedMax, packedMin)
        val low = minOf(packedMax, packedMin)
        val r0 = (rgb565(high, 255) ushr 16) and 0xff
        val g0 = (rgb565(high, 255) ushr 8) and 0xff
        val b0 = rgb565(high, 255) and 0xff
        val r1 = (rgb565(low, 255) ushr 16) and 0xff
        val g1 = (rgb565(low, 255) ushr 8) and 0xff
        val b1 = rgb565(low, 255) and 0xff
        val palette =
            intArrayOf(
                rgb565(high, 255),
                rgb565(low, 255),
                argb(255, (2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3),
                argb(255, (r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3),
            )
        var bits = 0
        colors.forEachIndexed { index, color ->
            val rgb = color and 0xffffff
            var bestIndex = 0
            var bestDistance = Long.MAX_VALUE
            repeat(4) { paletteIndex ->
                val candidate = palette[paletteIndex] and 0xffffff
                val red = ((rgb ushr 16) and 0xff) - ((candidate ushr 16) and 0xff)
                val green = ((rgb ushr 8) and 0xff) - ((candidate ushr 8) and 0xff)
                val blue = (rgb and 0xff) - (candidate and 0xff)
                val distance = red.toLong() * red + green.toLong() * green + blue.toLong() * blue
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = paletteIndex
                }
            }
            bits = bits or (bestIndex shl (index * 2))
        }
        dxt[offset] = high.toByte()
        dxt[offset + 1] = (high ushr 8).toByte()
        dxt[offset + 2] = low.toByte()
        dxt[offset + 3] = (low ushr 8).toByte()
        dxt[offset + 4] = bits.toByte()
        dxt[offset + 5] = (bits ushr 8).toByte()
        dxt[offset + 6] = (bits ushr 16).toByte()
        dxt[offset + 7] = (bits ushr 24).toByte()
    }

    private fun packRgb565(
        red: Int,
        green: Int,
        blue: Int,
    ): Int = ((red ushr 3) shl 11) or ((green ushr 2) shl 5) or (blue ushr 3)

    private fun dxt5Header(
        flags: Int,
        width: Int,
        height: Int,
        unknown: Int,
        decompressedLength: Int,
        compressedLength: Int,
    ): ByteArray {
        val output = ByteArray(MOBILE_TEX_HEADER_SIZE)
        var offset = 0
        offset = output.writeCString(offset, "TEXV0005")
        offset = output.writeCString(offset, "TEXI0001")
        listOf(TEX_FORMAT_DXT5_LZ4, flags, width, height, width, height, unknown).forEach { value ->
            offset = output.writeIntLe(offset, value)
        }
        offset = output.writeCString(offset, "TEXB0004")
        listOf(1, -1, 0, 1, width, height, 1, decompressedLength, compressedLength).forEach { value ->
            offset = output.writeIntLe(offset, value)
        }
        require(offset == MOBILE_TEX_HEADER_SIZE)
        return output
    }

    private fun parse(source: ByteArray): TexFile {
        val reader = ByteCursor(source)
        require(reader.readCString() == "TEXV0005") { "Invalid TEX version" }
        require(reader.readCString() == "TEXI0001") { "Invalid TEX metadata" }
        val format = reader.readIntLe()
        val flags = reader.readIntLe()
        val textureWidth = reader.readIntLe()
        val textureHeight = reader.readIntLe()
        val imageWidth = reader.readIntLe()
        val imageHeight = reader.readIntLe()
        require(textureWidth > 0 && textureHeight > 0 && imageWidth > 0 && imageHeight > 0) {
            "Invalid TEX dimensions"
        }
        require(checkedPayloadSize(imageWidth, imageHeight, 4) <= mobileRgbaLimit()) {
            "TEX texture exceeds mobile conversion memory limit"
        }
        require(checkedPayloadSize(textureWidth, textureHeight, 4) <= mobileRgbaLimit()) {
            "TEX base mipmap exceeds mobile conversion memory limit"
        }
        val unknown = reader.readIntLe()
        val container = reader.readCString()
        val imageCount = reader.readIntLe()
        require(imageCount == 1) { "Unsupported TEX image count: $imageCount" }
        var imageFormat = -1
        var isMp4 = false
        when (container) {
            "TEXB0003" -> imageFormat = reader.readIntLe()
            "TEXB0004" -> {
                imageFormat = reader.readIntLe()
                isMp4 = reader.readIntLe() != 0
            }
            "TEXB0001", "TEXB0002" -> Unit
            else -> error("Unsupported TEX container: $container")
        }
        val mipmapCount = reader.readIntLe()
        require(mipmapCount in 0..64) { "Invalid TEX mipmap count" }
        val mipmaps =
            List(mipmapCount) {
                val width = reader.readIntLe()
                val height = reader.readIntLe()
                require(width > 0 && height > 0) { "Invalid TEX mipmap dimensions" }
                val isBaseMipmap = width == textureWidth && height == textureHeight
                val expectedPayloadSize = expectedPayloadSize(format, imageFormat, width, height)
                if (container == "TEXB0001") {
                    val size = reader.readIntLe()
                    expectedPayloadSize?.let { expected ->
                        require(size == expected) { "TEX payload size does not match mipmap dimensions" }
                    }
                    TexMipmap(
                        width,
                        height,
                        if (isBaseMipmap) reader.readBytes(size) else byteArrayOf().also { reader.skip(size) },
                    )
                } else {
                    val compressed = reader.readIntLe() == 1
                    val decompressedSize = reader.readIntLe()
                    val storedSize = reader.readIntLe()
                    require(storedSize >= 0) { "Invalid TEX payload size" }
                    if (compressed) {
                        require(decompressedSize > 0) { "Invalid LZ4 TEX payload size" }
                        require(decompressedSize <= MAX_DECOMPRESSED_TEX_BYTES) {
                            "TEX payload exceeds mobile conversion memory limit"
                        }
                        expectedPayloadSize?.let { expected ->
                            require(decompressedSize == expected) {
                                "TEX payload size does not match mipmap dimensions"
                            }
                        }
                    } else {
                        expectedPayloadSize?.let { expected ->
                            require(storedSize == expected) {
                                "TEX payload size does not match mipmap dimensions"
                            }
                        }
                    }
                    if (!isBaseMipmap) {
                        reader.skip(storedSize)
                        TexMipmap(width, height, byteArrayOf())
                    } else {
                        val stored = reader.readBytes(storedSize)
                        val payload =
                            if (compressed) {
                                val decoded = ByteArray(decompressedSize)
                                val read =
                                    LZ4Factory
                                        .fastestJavaInstance()
                                        .safeDecompressor()
                                        .decompress(stored, 0, stored.size, decoded, 0, decoded.size)
                                require(read == decoded.size) { "Incomplete LZ4 TEX payload" }
                                decoded
                            } else {
                                stored
                            }
                        TexMipmap(width, height, payload)
                    }
                }
            }
        return TexFile(
            format = format,
            flags = flags,
            textureWidth = textureWidth,
            textureHeight = textureHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            unknown = unknown,
            imageFormat = imageFormat,
            isMp4 = isMp4,
            mipmaps = mipmaps,
            tailData = reader.remainingBytes(),
        )
    }

    private fun imagePayloadToRgba(
        payload: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        val bitmap =
            BitmapFactory.decodeByteArray(payload, 0, payload.size)
                ?: error("Unsupported image-backed TEX payload")
        require(bitmap.width == targetWidth && bitmap.height == targetHeight) {
            "Image-backed TEX dimensions do not match metadata"
        }
        try {
            val pixels = IntArray(checkedPayloadSize(targetWidth, targetHeight, 1))
            bitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            return ByteArray(pixels.size * 4).also { rgba ->
                pixels.forEachIndexed { index, color ->
                    val position = index * 4
                    rgba[position] = ((color ushr 16) and 0xff).toByte()
                    rgba[position + 1] = ((color ushr 8) and 0xff).toByte()
                    rgba[position + 2] = (color and 0xff).toByte()
                    rgba[position + 3] = ((color ushr 24) and 0xff).toByte()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun cropRgba(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        require(source.size == checkedPayloadSize(sourceWidth, sourceHeight, 4)) {
            "RGBA TEX payload size does not match mipmap dimensions"
        }
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            return source
        }
        val output = ByteArray(checkedPayloadSize(targetWidth, targetHeight, 4))
        val rowBytes = min(sourceWidth, targetWidth) * 4
        repeat(min(sourceHeight, targetHeight)) { row ->
            source.copyInto(
                output,
                destinationOffset = row * targetWidth * 4,
                startIndex = row * sourceWidth * 4,
                endIndex = row * sourceWidth * 4 + rowBytes,
            )
        }
        return output
    }

    private fun convertR8(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        require(source.size == checkedPayloadSize(sourceWidth, sourceHeight, 1)) {
            "R8 TEX payload size does not match mipmap dimensions"
        }
        val output = ByteArray(checkedPayloadSize(sourceWidth, sourceHeight, 4))
        repeat(sourceWidth * sourceHeight) { index ->
            val position = index * 4
            output[position] = source[index]
            output[position + 1] = source[index]
            output[position + 2] = source[index]
            output[position + 3] = 0xff.toByte()
        }
        return cropRgba(output, sourceWidth, sourceHeight, targetWidth, targetHeight)
    }

    private fun convertRg88(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        require(source.size == checkedPayloadSize(sourceWidth, sourceHeight, 2)) {
            "RG88 TEX payload size does not match mipmap dimensions"
        }
        val output = ByteArray(checkedPayloadSize(sourceWidth, sourceHeight, 4))
        repeat(sourceWidth * sourceHeight) { index ->
            val position = index * 4
            val r = source[index * 2]
            val g = source[index * 2 + 1]
            output[position] = g
            output[position + 1] = g
            output[position + 2] = g
            output[position + 3] = r
        }
        return cropRgba(output, sourceWidth, sourceHeight, targetWidth, targetHeight)
    }

    private fun decodeDxt(
        width: Int,
        height: Int,
        source: ByteArray,
        format: Int,
    ): ByteArray {
        val blockSize = if (format == TEX_FORMAT_DXT1) 8 else 16
        val expectedSize = ((width + 3L) / 4L) * ((height + 3L) / 4L) * blockSize
        require(expectedSize <= Int.MAX_VALUE && source.size == expectedSize.toInt()) {
            "DXT TEX payload size does not match mipmap dimensions"
        }
        val output = ByteArray(checkedPayloadSize(width, height, 4))
        var offset = 0
        for (blockY in 0 until height step 4) {
            for (blockX in 0 until width step 4) {
                val colorOffset = if (format == TEX_FORMAT_DXT1) offset else offset + 8
                val colors = decodeDxtColors(source, colorOffset, format == TEX_FORMAT_DXT1)
                val alpha =
                    when (format) {
                        TEX_FORMAT_DXT3 -> decodeDxt3Alpha(source, offset)
                        TEX_FORMAT_DXT5 -> decodeDxt5Alpha(source, offset)
                        else -> IntArray(16) { index -> (colors[index] ushr 24) and 0xff }
                    }
                repeat(4) { pixelY ->
                    repeat(4) { pixelX ->
                        val x = blockX + pixelX
                        val y = blockY + pixelY
                        if (x >= width || y >= height) return@repeat
                        val color = colors[pixelY * 4 + pixelX]
                        val destination = (y * width + x) * 4
                        output[destination] = ((color ushr 16) and 0xff).toByte()
                        output[destination + 1] = ((color ushr 8) and 0xff).toByte()
                        output[destination + 2] = (color and 0xff).toByte()
                        output[destination + 3] = alpha[pixelY * 4 + pixelX].toByte()
                    }
                }
                offset += blockSize
            }
        }
        return output
    }

    private fun checkedPayloadSize(
        width: Int,
        height: Int,
        bytesPerPixel: Int,
    ): Int {
        val size = width.toLong() * height.toLong() * bytesPerPixel
        require(size in 1..Int.MAX_VALUE.toLong()) { "TEX payload dimensions are too large" }
        return size.toInt()
    }

    private fun expectedPayloadSize(
        format: Int,
        imageFormat: Int,
        width: Int,
        height: Int,
    ): Int? {
        if (imageFormat >= 0) return null
        return when (format) {
            TEX_FORMAT_RGBA8888 -> checkedPayloadSize(width, height, 4)
            TEX_FORMAT_RG88 -> checkedPayloadSize(width, height, 2)
            TEX_FORMAT_R8 -> checkedPayloadSize(width, height, 1)
            TEX_FORMAT_DXT1 -> checkedDxtPayloadSize(width, height, 8)
            TEX_FORMAT_DXT3, TEX_FORMAT_DXT5 -> checkedDxtPayloadSize(width, height, 16)
            else -> null
        }
    }

    private fun checkedDxtPayloadSize(
        width: Int,
        height: Int,
        blockSize: Int,
    ): Int {
        val size = ((width + 3L) / 4L) * ((height + 3L) / 4L) * blockSize
        require(size in 1..Int.MAX_VALUE.toLong()) { "TEX payload dimensions are too large" }
        return size.toInt()
    }

    private fun ByteArray.writeCString(
        offset: Int,
        value: String,
    ): Int {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(this, destinationOffset = offset)
        this[offset + bytes.size] = 0
        return offset + bytes.size + 1
    }

    private fun ByteArray.writeIntLe(
        offset: Int,
        value: Int,
    ): Int {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
        return offset + 4
    }

    private fun decodeDxtColors(
        source: ByteArray,
        offset: Int,
        isDxt1: Boolean,
    ): IntArray {
        val color0 = readU16Le(source, offset)
        val color1 = readU16Le(source, offset + 2)
        val palette = IntArray(4)
        palette[0] = rgb565(color0, 255)
        palette[1] = rgb565(color1, 255)
        val r0 = (palette[0] ushr 16) and 0xff
        val g0 = (palette[0] ushr 8) and 0xff
        val b0 = palette[0] and 0xff
        val r1 = (palette[1] ushr 16) and 0xff
        val g1 = (palette[1] ushr 8) and 0xff
        val b1 = palette[1] and 0xff
        if (isDxt1 && color0 <= color1) {
            palette[2] = argb(255, (r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2)
            palette[3] = argb(0, 0, 0, 0)
        } else {
            palette[2] = argb(255, (2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3)
            palette[3] = argb(255, (r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3)
        }
        val indices = readU32Le(source, offset + 4)
        return IntArray(16) { index -> palette[((indices ushr (index * 2)) and 0x3).toInt()] }
    }

    private fun decodeDxt3Alpha(
        source: ByteArray,
        offset: Int,
    ): IntArray =
        IntArray(16) { index ->
            val value = source[offset + index / 2].toInt() and 0xff
            val nibble = if (index % 2 == 0) value and 0x0f else value ushr 4
            (nibble shl 4) or nibble
        }

    private fun decodeDxt5Alpha(
        source: ByteArray,
        offset: Int,
    ): IntArray {
        val first = source[offset].toInt() and 0xff
        val second = source[offset + 1].toInt() and 0xff
        val palette = IntArray(8)
        palette[0] = first
        palette[1] = second
        if (first > second) {
            (1..6).forEach { index -> palette[index + 1] = ((7 - index) * first + index * second) / 7 }
        } else {
            (1..4).forEach { index -> palette[index + 1] = ((5 - index) * first + index * second) / 5 }
            palette[6] = 0
            palette[7] = 255
        }
        var bits = 0L
        repeat(6) { index -> bits = bits or ((source[offset + 2 + index].toLong() and 0xffL) shl (index * 8)) }
        return IntArray(16) { index -> palette[((bits ushr (index * 3)) and 0x7L).toInt()] }
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Le(
        source: ByteArray,
        offset: Int,
    ): Long =
        (source[offset].toLong() and 0xffL) or
            ((source[offset + 1].toLong() and 0xffL) shl 8) or
            ((source[offset + 2].toLong() and 0xffL) shl 16) or
            ((source[offset + 3].toLong() and 0xffL) shl 24)

    private fun rgb565(
        value: Int,
        alpha: Int,
    ): Int {
        val red = ((value ushr 11) and 0x1f) * 255 / 31
        val green = ((value ushr 5) and 0x3f) * 255 / 63
        val blue = (value and 0x1f) * 255 / 31
        return argb(alpha, red, green, blue)
    }

    private fun argb(
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
    ): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun RandomAccessFile.readBoundedIntLe(end: Long): Int {
    require(end >= Int.SIZE_BYTES && filePointer <= end - Int.SIZE_BYTES)
    return Integer.reverseBytes(readInt())
}

private fun RandomAccessFile.readBoundedCString(end: Long): String {
    val bytes = ByteArray(64)
    var length = 0
    while (filePointer < end) {
        val value = read()
        require(value >= 0)
        if (value == 0) return bytes.copyOf(length).toString(Charsets.US_ASCII)
        require(length < bytes.size)
        bytes[length++] = value.toByte()
    }
    error("Unterminated C string")
}

private const val MOBILE_TEX_HEADER_SIZE = 91
private const val ANIMATED_TEXTURE_REASON = "animated texture"
private const val LZ4_COMPRESSION_LEVEL = 3
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private const val PNG_SIGNATURE_SCAN_BYTES = 1024
private const val PNG_COPY_BUFFER_BYTES = 128 * 1024
private const val DXT5_STRIP_HEIGHT = 256
private const val DXT5_BLOCK_BYTES = 16
private const val DXT5_ALPHA_BLOCK_BYTES = 8
private const val DXT5_BLOCK_PIXELS = 16
private const val MAX_DXT5_PIXELS = 48L * 1024 * 1024
private fun mobileRgbaLimit(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
    (maxHeapBytes / TEX_RGBA_HEAP_DIVISOR)
        .coerceIn(MIN_MOBILE_RGBA_BYTES.toLong(), MAX_MOBILE_RGBA_BYTES.toLong())
        .toInt()

private const val TEX_RGBA_HEAP_DIVISOR = 24L
private const val MIN_MOBILE_RGBA_BYTES = 2 * 1024 * 1024
private const val MAX_MOBILE_RGBA_BYTES = 8 * 1024 * 1024
private val MAX_DECOMPRESSED_TEX_BYTES: Int
    get() = mobileRgbaLimit()
private const val MAX_RETAINED_TEX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
