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
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.min
import org.json.JSONObject

private const val TEX_FORMAT_RGBA8888 = 0
private const val TEX_FORMAT_ETC2_RGBA8_LZ4 = 5
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

internal enum class TexRuntimeDesktopFamily {
    STANDARD_BLOCK_COMPRESSED_ANIMATED,
    CONDITIONAL_VARIANT_EXTENDED,
}

private data class TexMipmapMetadata(
    val width: Int,
    val height: Int,
    val compressed: Boolean,
    val decompressedSize: Int,
    val storedSize: Int,
    val payloadStart: Long,
)

private data class TexEntryMetadata(
    val format: Int,
    val flags: Int,
    val unknown: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageFormat: Int,
    val isMp4: Boolean,
    val container: String,
    val mipmapCount: Int,
    val baseWidth: Int,
    val baseHeight: Int,
    val baseCompressed: Boolean,
    val baseDecompressedSize: Int,
    val baseStoredSize: Int,
    val payloadStart: Long,
    val payloadLength: Long,
    val mipmaps: List<TexMipmapMetadata>,
    val animationTailStart: Long,
    val animationTailLength: Long,
    val hasVerifiedAnimationTail: Boolean,
)

internal enum class TexEntryRoute {
    CONVERT_IN_MEMORY,
    CONVERT_IMAGE_TO_ETC2,
    CONVERT_ANIMATED_IMAGE_TO_RGBA,
    RETAIN_VERIFIED_MOBILE,
    RETAIN_VERIFIED_ANIMATED_MOBILE,
    RETAIN_VERIFIED_VIDEO,
    RETAIN_VERIFIED_RUNTIME_DESKTOP,
    UNSUPPORTED_RESOURCE_BUDGET,
    UNSUPPORTED_VIDEO,
    UNSUPPORTED,
}

internal data class TexEntryDecision(
    val route: TexEntryRoute,
    val decodedRgbaBytes: Long? = null,
    val rgbaConversionFitsMemoryBudget: Boolean = false,
    val animated: Boolean = false,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val boundedStaticPngOrJpeg: Boolean = false,
    val verifiedLosslessRgba: Boolean = false,
    val runtimeDesktopFamily: TexRuntimeDesktopFamily? = null,
)

/**
 * A compatibility path for WallHub's mobile MPKG texture profile.
 * It converts common scene TEX formats to RGBA8888 + LZ4 and oversized image-backed
 * textures to ETC2 RGBA8 + LZ4.
 */
object TexMobileConverter {
    internal fun convertOrKeep(source: ByteArray): TexConversionResult {
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

    internal fun convertToFile(
        source: ByteArray,
        outputFile: File,
        checkCancellation: () -> Unit = {},
    ): TexFileConversionResult {
        return runCatching {
            checkCancellation()
            val prepared = prepare(source, checkCancellation)
            val texture =
                prepared.texture
                    ?: return TexFileConversionResult(
                        converted = false,
                        reason = prepared.reason,
                        canRetainOriginal = isVerifiedMobileCompatible(source),
                    ).also { outputFile.delete() }
            writeMobileRgba(
                outputFile = outputFile,
                flags = texture.flags,
                width = texture.width,
                height = texture.height,
                unknown = texture.unknown,
                rgba = texture.rgba,
            )
            checkCancellation()
            TexFileConversionResult(true, prepared.reason)
        }.getOrElse { error ->
            outputFile.delete()
            if (error is CancellationException || error is IOException) throw error
            TexFileConversionResult(
                converted = false,
                reason = error.message ?: error.javaClass.simpleName,
                canRetainOriginal = isVerifiedMobileCompatible(source),
            )
        }
    }

    internal fun convertAnimatedImageToRgbaFile(
        sourceFile: File,
        offset: Long,
        length: Long,
        outputFile: File,
        maxDecodedRgbaBytes: Long = STANDARD_MAX_RGBA_BYTES,
        checkCancellation: () -> Unit = {},
        decodeImage: (File, Int, Int, () -> Unit) -> ByteArray = ::decodeImageFileToRgba,
    ): TexFileConversionResult =
        runCatching {
            checkCancellation()
            val metadata = readTexEntryMetadata(sourceFile, offset, length)
            require(metadata.flags and TEX_FLAG_GIF != 0 && !metadata.isMp4 && metadata.flags and TEX_FLAG_VIDEO == 0) {
                "TEX is not an image-backed animation"
            }
            require(metadata.hasVerifiedAnimationTail) { "Animated TEX does not carry a verified TEXS0003 descriptor" }
            require(metadata.imageFormat >= 0 && !metadata.baseCompressed) { "Animated TEX payload is not an uncompressed image" }
            require(metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight) {
                "TEX base mipmap does not match logical image dimensions"
            }
            val rgbaBytes = checkedPayloadSizeLong(metadata.imageWidth, metadata.imageHeight, 4)
            require(rgbaBytes <= maxDecodedRgbaBytes) { "Animated TEX exceeds the configured RGBA conversion limit" }
            require(metadata.payloadLength in 1..MAX_EMBEDDED_IMAGE_BYTES) { "Animated TEX image payload exceeds the bounded size" }
            val descriptor = readFileRange(sourceFile, metadata.animationTailStart, metadata.animationTailLength, checkCancellation)
            val tempImage = File(outputFile.parentFile, "${outputFile.name}.image")
            try {
                val imageStart = locateEmbeddedImage(sourceFile, metadata.payloadStart, metadata.payloadLength)
                val imageLength = Math.subtractExact(Math.addExact(metadata.payloadStart, metadata.payloadLength), imageStart)
                require(imageLength in 1..MAX_EMBEDDED_IMAGE_BYTES) { "Animated TEX image payload exceeds the bounded size" }
                copyImagePayload(sourceFile, imageStart, imageLength, tempImage, checkCancellation)
                val rgba = decodeImage(tempImage, metadata.imageWidth, metadata.imageHeight, checkCancellation)
                checkCancellation()
                require(rgba.size.toLong() == rgbaBytes) { "Decoded animated TEX payload size does not match metadata" }
                writeMobileRgbaWithAnimation(
                    outputFile = outputFile,
                    flags = metadata.flags,
                    width = metadata.imageWidth,
                    height = metadata.imageHeight,
                    unknown = metadata.unknown,
                    rgba = rgba,
                    descriptor = descriptor,
                    checkCancellation = checkCancellation,
                )
                checkCancellation()
                val outputMetadata = readTexEntryMetadata(outputFile, 0L, outputFile.length())
                require(isVerifiedAnimatedMobileCompatible(outputMetadata)) { "Converted animated TEX failed mobile verification" }
                val outputDescriptor =
                    readFileRange(
                        outputFile,
                        outputMetadata.animationTailStart,
                        outputMetadata.animationTailLength,
                        checkCancellation,
                    )
                require(outputDescriptor.contentEquals(descriptor)) { "Converted animated TEX descriptor changed" }
                TexFileConversionResult(true, "RGBA8888 + LZ4 HC3 animated")
            } finally {
                tempImage.delete()
            }
        }.getOrElse { error ->
            outputFile.delete()
            if (error is CancellationException || error is IOException) throw error
            TexFileConversionResult(false, error.message ?: error.javaClass.simpleName)
        }

    internal fun convertImageToRgbaFile(
        sourceFile: File,
        offset: Long,
        length: Long,
        outputFile: File,
        maxDecodedRgbaBytes: Long,
        textureReduction: Int = 1,
        checkCancellation: () -> Unit = {},
        decodeImage: (File, Int, Int, () -> Unit) -> ByteArray = ::decodeImageFileToRgba,
    ): TexFileConversionResult =
        runCatching {
            require(maxDecodedRgbaBytes in 1..MAX_ALLOWED_RGBA_BYTES) { "Invalid static image RGBA limit" }
            checkCancellation()
            val metadata = readTexEntryMetadata(sourceFile, offset, length)
            require(metadata.flags and (TEX_FLAG_GIF or TEX_FLAG_VIDEO) == 0 && !metadata.isMp4) {
                "TEX is not a static image-backed texture"
            }
            require(metadata.imageFormat >= 0 && !metadata.baseCompressed) { "TEX payload is not an uncompressed image" }
            require(metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight) {
                "TEX base mipmap does not match image dimensions"
            }
            require(metadata.animationTailLength == 0L) { "Static image-backed TEX has trailing data" }
            val rgbaBytes = checkedPayloadSizeLong(metadata.imageWidth, metadata.imageHeight, 4)
            require(rgbaBytes <= maxDecodedRgbaBytes) { "TEX exceeds preset decoded RGBA limit" }
            require(metadata.payloadLength in 1..MAX_EMBEDDED_IMAGE_BYTES) { "TEX image payload exceeds the bounded size" }
            val tempImage = File(outputFile.parentFile, "${outputFile.name}.image")
            try {
                val imageStart = locateEmbeddedImage(sourceFile, metadata.payloadStart, metadata.payloadLength)
                val imageLength = Math.subtractExact(Math.addExact(metadata.payloadStart, metadata.payloadLength), imageStart)
                require(imageLength in 1..MAX_EMBEDDED_IMAGE_BYTES) { "TEX image payload exceeds the bounded size" }
                copyImagePayload(sourceFile, imageStart, imageLength, tempImage, checkCancellation)
                val rgba = decodeImage(tempImage, metadata.imageWidth, metadata.imageHeight, checkCancellation)
                checkCancellation()
                require(rgba.size.toLong() == rgbaBytes) { "Decoded TEX payload size does not match metadata" }
                val targetWidth = (metadata.imageWidth / textureReduction).coerceAtLeast(1)
                val targetHeight = (metadata.imageHeight / textureReduction).coerceAtLeast(1)
                val outputRgba =
                    if (textureReduction == 1) {
                        rgba
                    } else {
                        resizeRgbaAreaAverage(rgba, metadata.imageWidth, metadata.imageHeight, targetWidth, targetHeight)
                    }
                writeReducedMobileRgba(
                    outputFile = outputFile,
                    flags = metadata.flags,
                    imageWidth = metadata.imageWidth,
                    imageHeight = metadata.imageHeight,
                    storedWidth = targetWidth,
                    storedHeight = targetHeight,
                    unknown = metadata.unknown,
                    rgba = outputRgba,
                )
                checkCancellation()
                require(inspectEntryRoute(outputFile, 0L, outputFile.length()) == TexEntryRoute.RETAIN_VERIFIED_MOBILE) {
                    "Converted static TEX failed mobile verification"
                }
                TexFileConversionResult(true, "RGBA8888 + LZ4 HC3 static image")
            } finally {
                tempImage.delete()
            }
        }.getOrElse { error ->
            outputFile.delete()
            if (error is CancellationException || error is IOException) throw error
            TexFileConversionResult(false, error.message ?: error.javaClass.simpleName)
        }

    fun convertToEtc2File(
        sourceFile: File,
        offset: Long,
        length: Long,
        outputFile: File,
        checkCancellation: () -> Unit = {},
    ): TexFileConversionResult =
        runCatching {
            checkCancellation()
            val metadata = readTexEntryMetadata(sourceFile, offset, length)
            require(metadata.imageFormat >= 0) { "TEX payload is not image-backed" }
            require(!metadata.isMp4 && metadata.flags and TEX_FLAG_VIDEO == 0) { ANIMATED_TEXTURE_REASON }
            if (metadata.flags and TEX_FLAG_GIF != 0) {
                require(metadata.hasVerifiedAnimationTail) { "Animated TEX does not carry a verified TEXS0003 descriptor" }
            }
            require(!metadata.baseCompressed) { "Compressed TEX payload is unsupported for ETC2 conversion" }
            require(metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight) {
                "TEX base mipmap does not match image dimensions"
            }
            // Workshop artwork is not block aligned - a 3862-wide background is ordinary - but the block
            // encoder is. The encode pads to the next block and replicates the last row and column, which
            // the official client renders as the same image, so the alignment is not a reason to skip it.
            val etc2Width = etc2BlockAligned(metadata.imageWidth)
            val etc2Height = etc2BlockAligned(metadata.imageHeight)
            require(etc2Width.toLong() * etc2Height <= MAX_ETC2_PIXELS) {
                "TEX exceeds ETC2 conversion pixel limit"
            }
            val tempImage = File(outputFile.parentFile, "${outputFile.name}.image")
            try {
                val imageStart = locateEmbeddedImage(sourceFile, metadata.payloadStart, metadata.payloadLength)
                val imageLength = metadata.payloadStart + metadata.payloadLength - imageStart
                copyImagePayload(sourceFile, imageStart, imageLength, tempImage, checkCancellation)
                writeEtc2(
                    tempImage,
                    metadata.flags,
                    metadata.unknown,
                    metadata.imageWidth,
                    metadata.imageHeight,
                    outputFile,
                    checkCancellation,
                    sourceFile = sourceFile,
                    animationTailStart = metadata.animationTailStart,
                    animationTailLength = metadata.animationTailLength,
                )
                TexFileConversionResult(true, "ETC2 RGBA8 + LZ4 HC3")
            } finally {
                tempImage.delete()
            }
        }.getOrElse { error ->
            outputFile.delete()
            if (error is CancellationException || error is IOException) throw error
            TexFileConversionResult(false, error.message ?: error.javaClass.simpleName)
        }

    internal fun downsampleToEtc2File(
        sourceFile: File,
        offset: Long,
        length: Long,
        resourcePath: String,
        textureReduction: Int,
        outputFile: File,
        checkCancellation: () -> Unit = {},
        encodeDownsampledEtc2: (File, Int, Int, Int, Int, () -> Unit) -> ByteArray = ::encodeDownsampledEtc2,
    ): TexFileConversionResult =
        runCatching {
            checkCancellation()
            val metadata = readTexEntryMetadata(sourceFile, offset, length)
            require(textureReduction in setOf(2, 4)) { "Official texture reduction must be 2 or 4" }
            require(metadata.flags and (TEX_FLAG_GIF or TEX_FLAG_VIDEO) == 0 && !metadata.isMp4) {
                "Official texture reduction requires a static TEX"
            }
            require(metadata.container in setOf("TEXB0003", "TEXB0004")) { "TEX metadata is unsafe for texture reduction" }
            require(metadata.imageFormat in STATIC_PNG_JPEG_FORMATS && !metadata.baseCompressed) {
                "Official texture reduction requires an uncompressed PNG or JPEG payload"
            }
            require(metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight) {
                "TEX base mipmap does not match image dimensions"
            }
            require(metadata.animationTailLength == 0L) { "Static image-backed TEX has trailing data" }
            require(metadata.payloadLength in 1..MAX_EMBEDDED_IMAGE_BYTES) { "TEX image payload exceeds the bounded size" }
            require(metadata.imageWidth.toLong() * metadata.imageHeight <= MAX_IMAGE_DECODE_PIXELS) {
                "TEX image dimensions exceed global decode safety"
            }
            require(resourcePath.startsWith("materials/", ignoreCase = true)) { "Texture path is outside materials" }
            val targetWidth = (metadata.imageWidth / textureReduction).coerceAtLeast(1)
            val targetHeight = (metadata.imageHeight / textureReduction).coerceAtLeast(1)
            val targetMipIndex = if (textureReduction == 2) 1 else 2
            val reusableMip =
                metadata.mipmaps.getOrNull(targetMipIndex)?.takeIf { mip ->
                    !mip.compressed && mip.width == targetWidth && mip.height == targetHeight
                }
            val selectedPayloadStart = reusableMip?.payloadStart ?: metadata.payloadStart
            val selectedPayloadLength = reusableMip?.storedSize?.toLong() ?: metadata.payloadLength
            val selectedWidth = reusableMip?.width ?: metadata.imageWidth
            val selectedHeight = reusableMip?.height ?: metadata.imageHeight
            val tempImage = File(outputFile.parentFile, "${outputFile.name}.image")
            try {
                val imageStart = locateEmbeddedImage(sourceFile, selectedPayloadStart, selectedPayloadLength)
                val imageLength = Math.subtractExact(Math.addExact(selectedPayloadStart, selectedPayloadLength), imageStart)
                copyImagePayload(sourceFile, imageStart, imageLength, tempImage, checkCancellation)
                checkCancellation()
                val etc2 = encodeDownsampledEtc2(
                    tempImage,
                    selectedWidth,
                    selectedHeight,
                    targetWidth,
                    targetHeight,
                    checkCancellation,
                )
                val physicalWidth = etc2BlockAligned(targetWidth)
                val physicalHeight = etc2BlockAligned(targetHeight)
                require(etc2.size == checkedPayloadSize(physicalWidth, physicalHeight, 1)) { "Unexpected ETC2 payload size" }
                writeEtc2Payload(
                    outputFile = outputFile,
                    flags = metadata.flags,
                    unknown = metadata.unknown,
                    imageWidth = metadata.imageWidth,
                    imageHeight = metadata.imageHeight,
                    textureReduction = textureReduction,
                    storedWidth = physicalWidth,
                    storedHeight = physicalHeight,
                    etc2 = etc2,
                )
                checkCancellation()
                require(inspectEntryRoute(outputFile, 0L, outputFile.length()) == TexEntryRoute.RETAIN_VERIFIED_MOBILE) {
                    "Downsampled TEX failed mobile verification"
                }
                TexFileConversionResult(true, "Compatibility downsample to ETC2 RGBA8 + LZ4 HC3")
            } finally {
                tempImage.delete()
            }
        }.getOrElse { error ->
            outputFile.delete()
            if (error is CancellationException || error is IOException) throw error
            TexFileConversionResult(false, error.message ?: error.javaClass.simpleName)
        }

    private fun resizeRgbaAreaAverage(
        rgba: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        val output = ByteArray(checkedPayloadSize(targetWidth, targetHeight, 4))
        for (y in 0 until targetHeight) {
            val sourceTop = y * sourceHeight / targetHeight
            val sourceBottom = ((y + 1L) * sourceHeight / targetHeight).toInt().coerceAtLeast(sourceTop + 1)
            for (x in 0 until targetWidth) {
                val sourceLeft = x * sourceWidth / targetWidth
                val sourceRight = ((x + 1L) * sourceWidth / targetWidth).toInt().coerceAtLeast(sourceLeft + 1)
                val count = (sourceRight - sourceLeft) * (sourceBottom - sourceTop)
                val destination = (y * targetWidth + x) * 4
                for (channel in 0 until 4) {
                    var sum = 0
                    for (sourceY in sourceTop until sourceBottom) {
                        for (sourceX in sourceLeft until sourceRight) {
                            sum += rgba[(sourceY * sourceWidth + sourceX) * 4 + channel].toInt() and 0xff
                        }
                    }
                    output[destination + channel] = ((sum + count / 2) / count).toByte()
                }
            }
        }
        return output
    }

    internal fun compatibilityTargetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid TEX dimensions" }
        if (width <= COMPATIBILITY_MAX_DIMENSION && height <= COMPATIBILITY_MAX_DIMENSION) return width to height
        return if (width >= height) {
            COMPATIBILITY_MAX_DIMENSION to
                ((height.toLong() * COMPATIBILITY_MAX_DIMENSION + width / 2L) / width).coerceAtLeast(1L).toInt()
        } else {
            ((width.toLong() * COMPATIBILITY_MAX_DIMENSION + height / 2L) / height).coerceAtLeast(1L).toInt() to
                COMPATIBILITY_MAX_DIMENSION
        }
    }

    private fun isSafeColorTexturePath(path: String): Boolean {
        val tokens = path.substringAfterLast('/').substringBeforeLast('.').lowercase().split(Regex("[^a-z0-9]+"))
        return tokens.none { it in UNSAFE_TEXTURE_SEMANTIC_TOKENS } && tokens.any { it in SAFE_COLOR_TEXTURE_TOKENS }
    }

    internal fun classifyRuntimeDesktopFamily(source: ByteArray): TexRuntimeDesktopFamily? =
        runCatching { classifyRuntimeDesktopFamily(ByteCursor(source)) }.getOrNull()

    private fun classifyRuntimeDesktopFamily(
        sourceFile: File,
        offset: Long,
        length: Long,
    ): TexRuntimeDesktopFamily? {
        if (length !in 1..MAX_RUNTIME_DESKTOP_TEX_BYTES) return null
        return runCatching {
            require(offset >= 0L && offset <= sourceFile.length() - length)
            val bytes = RandomAccessFile(sourceFile, "r").use { input ->
                input.seek(offset)
                ByteArray(length.toInt()).also(input::readFully)
            }
            classifyRuntimeDesktopFamily(ByteCursor(bytes))
        }.getOrNull()
    }

    private fun classifyRuntimeDesktopFamily(reader: ByteCursor): TexRuntimeDesktopFamily {
        require(reader.readCString() == "TEXV0005")
        require(reader.readCString() == "TEXI0001")
        val format = reader.readIntLe()
        val flags = reader.readIntLe()
        val textureWidth = reader.readIntLe()
        val textureHeight = reader.readIntLe()
        val imageWidth = reader.readIntLe()
        val imageHeight = reader.readIntLe()
        reader.readIntLe()
        require(format in RUNTIME_DESKTOP_DXT_FORMATS)
        require(textureWidth > 0 && textureHeight > 0 && imageWidth > 0 && imageHeight > 0)
        val container = reader.readCString()
        require(container in RUNTIME_DESKTOP_CONTAINERS)
        require(reader.readIntLe() == 1)
        var hasCondition = false
        var declaredVariantCount = 0
        when (container) {
            "TEXB0003" -> require(reader.readIntLe() == -1)
            "TEXB0004" -> {
                require(reader.readIntLe() == -1)
                val extensionMarker = reader.readIntLe()
                require(extensionMarker in 0..1)
                hasCondition = extensionMarker == 1
                if (hasCondition) {
                    val conditionCount = reader.readIntLe()
                    declaredVariantCount = reader.readIntLe()
                    val selectedCondition = reader.readIntLe()
                    require(conditionCount in 1..MAX_RUNTIME_VARIANTS)
                    require(declaredVariantCount in 1..MAX_RUNTIME_VARIANTS)
                    require(selectedCondition in 0 until conditionCount)
                }
            }
        }
        if (hasCondition) {
            val metadata = reader.readCString(MAX_RUNTIME_CONDITION_JSON_BYTES)
            requireValidRuntimeCondition(metadata)
        }
        val mipCount = reader.readIntLe()
        require(mipCount in 1..MAX_RUNTIME_DESKTOP_MIPS)
        var expectedWidth = textureWidth
        var expectedHeight = textureHeight
        repeat(mipCount) {
            val width = reader.readIntLe()
            val height = reader.readIntLe()
            require(width == expectedWidth && height == expectedHeight)
            val storedSize =
                if (container == "TEXB0001") {
                    reader.readIntLe()
                } else {
                    val compressed = reader.readIntLe()
                    val decompressedSize = reader.readIntLe()
                    val size = reader.readIntLe()
                    require(compressed in 0..1)
                    val expected = dxtPayloadSize(format, width, height)
                    require(expected <= MAX_RUNTIME_DECODED_MIP_BYTES)
                    require(decompressedSize == expected)
                    if (compressed == 0) {
                        require(size == expected)
                        reader.skip(size)
                    } else {
                        require(size in 1..expected)
                        val stored = reader.readBytes(size)
                        val decoded = ByteArray(expected)
                        val decodedBytes =
                            LZ4Factory.fastestJavaInstance().safeDecompressor()
                                .decompress(stored, 0, stored.size, decoded, 0, decoded.size)
                        require(decodedBytes == expected)
                    }
                    0
                }
            if (container == "TEXB0001") {
                val expected = dxtPayloadSize(format, width, height)
                require(expected <= MAX_RUNTIME_DECODED_MIP_BYTES && storedSize == expected)
                reader.skip(storedSize)
            }
            expectedWidth = maxOf(1, expectedWidth / 2)
            expectedHeight = maxOf(1, expectedHeight / 2)
        }
        return if (hasCondition) {
            require(flags and TEX_FLAG_VIDEO == 0)
            requireValidRuntimeVariantTable(reader, imageWidth, imageHeight, declaredVariantCount)
            TexRuntimeDesktopFamily.CONDITIONAL_VARIANT_EXTENDED
        } else {
            require(flags and TEX_FLAG_GIF != 0 && flags and TEX_FLAG_VIDEO == 0)
            require(reader.readCString() == ANIMATION_DESCRIPTOR_MAGIC)
            val frameCount = reader.readIntLe()
            val frameWidth = reader.readIntLe()
            val frameHeight = reader.readIntLe()
            require(frameCount in 1..MAX_ANIMATION_FRAMES && frameWidth > 0 && frameHeight > 0)
            require(imageWidth % frameWidth == 0 && imageHeight % frameHeight == 0)
            require(Math.multiplyExact(imageWidth / frameWidth, imageHeight / frameHeight) == frameCount)
            reader.skip(Math.multiplyExact(frameCount, ANIMATION_FRAME_DESCRIPTOR_BYTES.toInt()))
            require(reader.remainingBytes().isEmpty())
            TexRuntimeDesktopFamily.STANDARD_BLOCK_COMPRESSED_ANIMATED
        }
    }

    private fun requireValidRuntimeCondition(json: String) {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_RUNTIME_CONDITION_JSON_BYTES)
        val root = JSONObject(json)
        require(root.keys().asSequence().toSet() == setOf("condition"))
        val condition = root.getJSONObject("condition")
        require(condition.keys().asSequence().toSet() == setOf("condition", "name"))
        require(condition.getString("condition").length in 1..MAX_RUNTIME_CONDITION_VALUE_CHARS)
        require(condition.getString("name").length in 1..MAX_RUNTIME_CONDITION_VALUE_CHARS)
    }

    private fun requireValidRuntimeVariantTable(
        reader: ByteCursor,
        imageWidth: Int,
        imageHeight: Int,
        declaredVariantCount: Int,
    ) {
        val count = reader.readIntLe()
        require(count == declaredVariantCount)
        repeat(count) {
            repeat(3) { require(reader.readIntLe() >= 0) }
            val x = reader.readIntLe()
            val y = reader.readIntLe()
            val width = reader.readIntLe()
            val height = reader.readIntLe()
            require(x >= 0 && y >= 0 && width > 0 && height > 0)
            require(x.toLong() + width <= imageWidth && y.toLong() + height <= imageHeight)
            reader.readIntLe()
            val payloadLength = reader.readIntLe()
            require(payloadLength in 1..MAX_RUNTIME_VARIANT_PAYLOAD_BYTES)
            reader.skip(payloadLength)
        }
        require(reader.remainingBytes().isEmpty())
    }

    private fun dxtPayloadSize(format: Int, width: Int, height: Int): Int {
        val bytesPerBlock = if (format == TEX_FORMAT_DXT1) 8 else 16
        return Math.toIntExact(Math.multiplyExact(((width + 3L) / 4L) * ((height + 3L) / 4L), bytesPerBlock.toLong()))
    }

    internal fun inspectEntryRoute(
        sourceFile: File,
        offset: Long,
        length: Long,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
    ): TexEntryRoute = inspectEntryDecision(sourceFile, offset, length, maxHeapBytes).route

    internal fun inspectEntryDecision(
        sourceFile: File,
        offset: Long,
        length: Long,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
    ): TexEntryDecision =
        runCatching {
            val runtimeDesktopFamily = classifyRuntimeDesktopFamily(sourceFile, offset, length)
            if (runtimeDesktopFamily != null) {
                return@runCatching TexEntryDecision(
                    route = TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP,
                    animated = runtimeDesktopFamily == TexRuntimeDesktopFamily.STANDARD_BLOCK_COMPRESSED_ANIMATED,
                    runtimeDesktopFamily = runtimeDesktopFamily,
                )
            }
            val metadata = readTexEntryMetadata(sourceFile, offset, length)
            val animated = metadata.flags and TEX_FLAG_GIF != 0
            val video = metadata.isMp4 || metadata.flags and TEX_FLAG_VIDEO != 0
            val route =
                when {
                    video ->
                        if (isVerifiedVideoTex(sourceFile, metadata)) {
                            TexEntryRoute.RETAIN_VERIFIED_VIDEO
                        } else {
                            TexEntryRoute.UNSUPPORTED_VIDEO
                        }
                    isVerifiedAnimatedMobileCompatible(metadata) -> TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE
                    isVerifiedMobileCompatible(metadata) -> TexEntryRoute.RETAIN_VERIFIED_MOBILE
                    animated && isVerifiedAnimatedImageConversionSource(metadata) ->
                        if (animatedImageConversionFitsBudget(metadata, length, maxHeapBytes)) {
                            TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA
                        } else {
                            TexEntryRoute.UNSUPPORTED_RESOURCE_BUDGET
                        }
                    animated && metadata.hasVerifiedAnimationTail && metadata.imageFormat >= 0 && !metadata.baseCompressed &&
                        metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight ->
                        TexEntryRoute.CONVERT_IMAGE_TO_ETC2
                    metadata.imageFormat >= 0 && !metadata.baseCompressed &&
                        metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight &&
                        metadata.animationTailLength == 0L -> TexEntryRoute.CONVERT_IMAGE_TO_ETC2
                    animated -> TexEntryRoute.UNSUPPORTED
                    metadata.format in CONVERTIBLE_STATIC_FORMATS ->
                        if (fitsStaticConversionBudget(metadata, length, maxHeapBytes)) {
                            TexEntryRoute.CONVERT_IN_MEMORY
                        } else {
                            TexEntryRoute.UNSUPPORTED_RESOURCE_BUDGET
                        }
                    else -> TexEntryRoute.UNSUPPORTED
                }
            val decodedRgbaBytes =
                if (route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2) {
                    checkedPayloadSizeLong(metadata.imageWidth, metadata.imageHeight, 4)
                } else {
                    null
                }
            TexEntryDecision(
                route = route,
                decodedRgbaBytes = decodedRgbaBytes,
                rgbaConversionFitsMemoryBudget =
                    route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2 &&
                        animatedImageConversionFitsBudget(metadata, length, maxHeapBytes),
                animated = animated,
                imageWidth = metadata.imageWidth,
                imageHeight = metadata.imageHeight,
                boundedStaticPngOrJpeg =
                    route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2 &&
                        !animated &&
                        !video &&
                        metadata.flags == 0 &&
                        metadata.container == "TEXB0004" &&
                        metadata.mipmapCount == 1 &&
                        metadata.imageFormat in STATIC_PNG_JPEG_FORMATS &&
                        !metadata.baseCompressed &&
                        metadata.animationTailLength == 0L &&
                        metadata.payloadLength in 1..MAX_EMBEDDED_IMAGE_BYTES &&
                        metadata.imageWidth.toLong() * metadata.imageHeight <= MAX_IMAGE_DECODE_PIXELS &&
                        hasPngOrJpegPayload(sourceFile, metadata),
                verifiedLosslessRgba =
                    route in setOf(TexEntryRoute.RETAIN_VERIFIED_MOBILE, TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE) &&
                        metadata.format == TEX_FORMAT_RGBA8888,
            )
        }.getOrDefault(TexEntryDecision(TexEntryRoute.UNSUPPORTED))

    private fun isVerifiedMobileCompatible(source: ByteArray): Boolean =
        runCatching {
            val tex = parse(source)
            require(tex.flags and TEX_FLAG_GIF == 0 && tex.tailData.isEmpty() && tex.mipmaps.size == 1)
            val base = tex.mipmaps.single()
            require(tex.imageFormat == -1 && !tex.isMp4)
            require(tex.format == TEX_FORMAT_RGBA8888 || tex.format == TEX_FORMAT_ETC2_RGBA8_LZ4)
            if (tex.format == TEX_FORMAT_RGBA8888) {
                require(
                    setOf(1, 2, 4).any { reduction ->
                        base.width == (tex.imageWidth / reduction).coerceAtLeast(1) &&
                            base.height == (tex.imageHeight / reduction).coerceAtLeast(1) &&
                            tex.textureWidth == tex.imageWidth && tex.textureHeight == tex.imageHeight
                    },
                )
                require(base.data.size == checkedPayloadSize(base.width, base.height, 4))
            } else {
                require(
                    setOf(1, 2, 4).any { reduction ->
                        val reducedWidth = (tex.imageWidth / reduction).coerceAtLeast(1)
                        val reducedHeight = (tex.imageHeight / reduction).coerceAtLeast(1)
                        val storedWidth = etc2BlockAligned(reducedWidth)
                        val storedHeight = etc2BlockAligned(reducedHeight)
                        base.width == storedWidth && base.height == storedHeight &&
                            tex.textureWidth == tex.imageWidth + (storedWidth - reducedWidth) * reduction &&
                            tex.textureHeight == tex.imageHeight + (storedHeight - reducedHeight) * reduction
                    },
                )
            }
            true
        }.getOrDefault(false)

    private fun isVerifiedMobileCompatible(metadata: TexEntryMetadata): Boolean {
        if (
            metadata.flags and TEX_FLAG_GIF != 0 || metadata.animationTailLength != 0L ||
            metadata.container != "TEXB0004" || metadata.mipmapCount != 1 || metadata.imageFormat != -1 || metadata.isMp4
        ) {
            return false
        }
        if (!metadata.baseCompressed) return false
        val expected =
            when (metadata.format) {
                TEX_FORMAT_RGBA8888 -> {
                    if (metadata.textureWidth != metadata.imageWidth || metadata.textureHeight != metadata.imageHeight) return false
                    if (
                        setOf(1, 2, 4).none { reduction ->
                            metadata.baseWidth == (metadata.imageWidth / reduction).coerceAtLeast(1) &&
                                metadata.baseHeight == (metadata.imageHeight / reduction).coerceAtLeast(1)
                        }
                    ) return false
                    checkedPayloadSize(metadata.baseWidth, metadata.baseHeight, 4)
                }
                TEX_FORMAT_ETC2_RGBA8_LZ4 -> {
                    val reduction =
                        setOf(1, 2, 4).firstOrNull { candidate ->
                            val reducedWidth = (metadata.imageWidth / candidate).coerceAtLeast(1)
                            val reducedHeight = (metadata.imageHeight / candidate).coerceAtLeast(1)
                            val storedWidth = etc2BlockAligned(reducedWidth)
                            val storedHeight = etc2BlockAligned(reducedHeight)
                            metadata.baseWidth == storedWidth &&
                                metadata.baseHeight == storedHeight &&
                                metadata.textureWidth == metadata.imageWidth + (storedWidth - reducedWidth) * candidate &&
                                metadata.textureHeight == metadata.imageHeight + (storedHeight - reducedHeight) * candidate
                        } ?: return false
                    require(reduction in setOf(1, 2, 4))
                    checkedPayloadSize(metadata.baseWidth, metadata.baseHeight, 1)
                }
                else -> return false
            }
        return metadata.baseDecompressedSize == expected && metadata.baseStoredSize > 0
    }

    private fun isVerifiedAnimatedMobileCompatible(metadata: TexEntryMetadata): Boolean {
        if (
            metadata.flags and TEX_FLAG_GIF == 0 || metadata.flags and TEX_FLAG_VIDEO != 0 ||
            !metadata.hasVerifiedAnimationTail || metadata.container != "TEXB0004" || metadata.mipmapCount != 1 ||
            metadata.imageFormat != -1 || metadata.isMp4 ||
            metadata.format !in setOf(TEX_FORMAT_RGBA8888, TEX_FORMAT_ETC2_RGBA8_LZ4)
        ) {
            return false
        }
        if (
            !metadata.baseCompressed || metadata.baseWidth != metadata.imageWidth || metadata.baseHeight != metadata.imageHeight ||
            metadata.textureWidth != metadata.imageWidth || metadata.textureHeight != metadata.imageHeight
        ) {
            return false
        }
        val expected =
            when (metadata.format) {
                TEX_FORMAT_RGBA8888 -> checkedPayloadSize(metadata.imageWidth, metadata.imageHeight, 4)
                TEX_FORMAT_ETC2_RGBA8_LZ4 -> checkedPayloadSize(metadata.imageWidth, metadata.imageHeight, 1)
                else -> return false
            }
        return metadata.baseDecompressedSize == expected && metadata.baseStoredSize > 0
    }

    private fun isVerifiedAnimatedImageConversionSource(metadata: TexEntryMetadata): Boolean =
        runCatching {
            require(metadata.flags and TEX_FLAG_GIF != 0 && metadata.flags and TEX_FLAG_VIDEO == 0 && !metadata.isMp4)
            require(metadata.hasVerifiedAnimationTail && metadata.imageFormat >= 0 && !metadata.baseCompressed)
            require(metadata.baseWidth == metadata.imageWidth && metadata.baseHeight == metadata.imageHeight)
            require(
                metadata.imageWidth != etc2BlockAligned(metadata.imageWidth) ||
                    metadata.imageHeight != etc2BlockAligned(metadata.imageHeight),
            )
            require(metadata.payloadLength in 1..MAX_EMBEDDED_IMAGE_BYTES)
            require(checkedPayloadSizeLong(metadata.imageWidth, metadata.imageHeight, 4) <= STANDARD_MAX_RGBA_BYTES)
            true
        }.getOrDefault(false)

    private fun isVerifiedVideoTex(
        sourceFile: File,
        metadata: TexEntryMetadata,
    ): Boolean {
        if (
            metadata.container != "TEXB0004" || metadata.mipmapCount != 1 || metadata.imageFormat != -1 ||
            metadata.baseCompressed || metadata.baseStoredSize <= 0 || metadata.payloadLength != metadata.baseStoredSize.toLong() ||
            metadata.baseWidth != metadata.imageWidth || metadata.baseHeight != metadata.imageHeight ||
            metadata.animationTailLength != 0L
        ) {
            return false
        }
        return RandomAccessFile(sourceFile, "r").use { input ->
            val start = metadata.payloadStart
            val end = Math.addExact(start, metadata.payloadLength)
            var position = start
            var first = true
            var hasMovie = false
            var hasMediaData = false
            var recognizedBrand = false
            while (position < end) {
                if (end - position < ISO_BMFF_BOX_HEADER_BYTES) return@use false
                input.seek(position)
                val header = ByteArray(ISO_BMFF_EXTENDED_HEADER_BYTES)
                input.readFully(header, 0, ISO_BMFF_BOX_HEADER_BYTES)
                val size32 = readUIntBe(header, 0)
                val type = header.copyOfRange(4, 8).toString(Charsets.US_ASCII)
                val headerBytes: Int
                val boxBytes: Long
                when (size32) {
                    0L -> {
                        headerBytes = ISO_BMFF_BOX_HEADER_BYTES
                        boxBytes = end - position
                    }
                    1L -> {
                        if (end - position < ISO_BMFF_EXTENDED_HEADER_BYTES) return@use false
                        input.readFully(header, ISO_BMFF_BOX_HEADER_BYTES, Long.SIZE_BYTES)
                        headerBytes = ISO_BMFF_EXTENDED_HEADER_BYTES
                        boxBytes = readUInt64Be(header, ISO_BMFF_BOX_HEADER_BYTES)
                    }
                    else -> {
                        headerBytes = ISO_BMFF_BOX_HEADER_BYTES
                        boxBytes = size32
                    }
                }
                if (boxBytes < headerBytes || boxBytes > end - position) return@use false
                if (first) {
                    if (type != "ftyp" || boxBytes !in MIN_ISO_BMFF_FTYP_BYTES..MAX_ISO_BMFF_FTYP_BYTES || boxBytes % 4L != 0L) {
                        return@use false
                    }
                    val brandBytes = ByteArray((boxBytes - headerBytes).toInt())
                    input.readFully(brandBytes)
                    if (brandBytes.size < 8 || !brandBytes.copyOfRange(0, 4).all(::isIsoBrandByte)) return@use false
                    val brands = buildList {
                        add(brandBytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                        for (offset in 8 until brandBytes.size step 4) {
                            if (offset + 4 > brandBytes.size || !brandBytes.copyOfRange(offset, offset + 4).all(::isIsoBrandByte)) {
                                return@use false
                            }
                            add(brandBytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII))
                        }
                    }
                    recognizedBrand = brands.any { it in MP4_VIDEO_BRANDS }
                } else {
                    hasMovie = hasMovie || type == "moov"
                    hasMediaData = hasMediaData || type == "mdat"
                }
                position = Math.addExact(position, boxBytes)
                first = false
            }
            position == end && recognizedBrand && hasMovie && hasMediaData
        }
    }

    private fun animatedImageConversionFitsBudget(
        metadata: TexEntryMetadata,
        entryLength: Long,
        maxHeapBytes: Long,
    ): Boolean =
        runCatching {
            require(entryLength > 0L && maxHeapBytes > 0L)
            val rgbaBytes = checkedPayloadSizeLong(metadata.imageWidth, metadata.imageHeight, 4)
            require(rgbaBytes <= STANDARD_MAX_RGBA_BYTES)
            // Bitmap pixels, getPixels IntArray, RGBA ByteArray, LZ4 destination and compressor workspace
            // can coexist until the output write completes. Include the source entry because the converter's
            // caller may still own its package/index buffers while this operation runs.
            val estimatedPeak =
                checkedSum(
                    entryLength,
                    rgbaBytes,
                    rgbaBytes,
                    rgbaBytes,
                    lz4MaximumOutputBytes(rgbaBytes),
                    LZ4_HC_WORKSPACE_ALLOWANCE_BYTES,
                )
            estimatedPeak <= Math.multiplyExact(maxHeapBytes, STATIC_CONVERSION_HEAP_NUMERATOR) / STATIC_CONVERSION_HEAP_DENOMINATOR
        }.getOrDefault(false)

    private fun fitsStaticConversionBudget(
        metadata: TexEntryMetadata,
        entryLength: Long,
        maxHeapBytes: Long,
    ): Boolean =
        staticConversionFitsBudget(
            format = metadata.format,
            textureWidth = metadata.baseWidth,
            textureHeight = metadata.baseHeight,
            imageWidth = metadata.imageWidth,
            imageHeight = metadata.imageHeight,
            entryBytes = entryLength,
            storedBytes = metadata.baseStoredSize.toLong(),
            decompressedBytes = metadata.baseDecompressedSize.toLong().takeIf { metadata.baseCompressed } ?: metadata.baseStoredSize.toLong(),
            maxHeapBytes = maxHeapBytes,
        )

    internal fun staticConversionFitsBudget(
        format: Int,
        textureWidth: Int,
        textureHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        entryBytes: Long,
        storedBytes: Long,
        decompressedBytes: Long,
        maxHeapBytes: Long,
    ): Boolean =
        runCatching {
            require(format in CONVERTIBLE_STATIC_FORMATS)
            require(entryBytes > 0L && storedBytes > 0L && decompressedBytes > 0L && maxHeapBytes > 0L)
            val textureRgba = checkedPayloadSizeLong(textureWidth, textureHeight, 4)
            val imageRgba = checkedPayloadSizeLong(imageWidth, imageHeight, 4)
            require(imageRgba <= STANDARD_MAX_RGBA_BYTES && decompressedBytes <= MAX_STATIC_DECOMPRESSED_BYTES)
            val croppedRgba = if (textureWidth == imageWidth && textureHeight == imageHeight) 0L else imageRgba
            val decodedBytes = if (format == TEX_FORMAT_RGBA8888) 0L else textureRgba
            val parsePeak = checkedSum(entryBytes, storedBytes, decompressedBytes)
            val decodePeak = checkedSum(entryBytes, decompressedBytes, decodedBytes, croppedRgba)
            // ART is not required to collect the decompressed input before LZ4 HC allocates its destination
            // and tables, so include both plus a fixed compressor allowance in the last phase.
            val compressionPeak =
                checkedSum(
                    entryBytes,
                    decompressedBytes,
                    imageRgba,
                    lz4MaximumOutputBytes(imageRgba),
                    LZ4_HC_WORKSPACE_ALLOWANCE_BYTES,
                )
            val estimatedPeak = maxOf(parsePeak, decodePeak, compressionPeak)
            estimatedPeak <= Math.multiplyExact(maxHeapBytes, STATIC_CONVERSION_HEAP_NUMERATOR) / STATIC_CONVERSION_HEAP_DENOMINATOR
        }.getOrDefault(false)

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

    private fun writeReducedMobileRgba(
        outputFile: File,
        flags: Int,
        imageWidth: Int,
        imageHeight: Int,
        storedWidth: Int,
        storedHeight: Int,
        unknown: Int,
        rgba: ByteArray,
    ) {
        require(rgba.size == checkedPayloadSize(storedWidth, storedHeight, 4)) { "RGBA payload size does not match stored mip dimensions" }
        val (compressed, compressedLength) = compress(rgba)
        outputFile.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Unable to create TEX output directory" }
        }
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            output.write(
                mobileRgbaHeader(
                    flags = flags,
                    width = imageWidth,
                    height = imageHeight,
                    mipWidth = storedWidth,
                    mipHeight = storedHeight,
                    unknown = unknown,
                    decompressedLength = rgba.size,
                    compressedLength = compressedLength,
                ),
            )
            output.write(compressed, 0, compressedLength)
        }
    }

    private fun writeMobileRgbaWithAnimation(
        outputFile: File,
        flags: Int,
        width: Int,
        height: Int,
        unknown: Int,
        rgba: ByteArray,
        descriptor: ByteArray,
        checkCancellation: () -> Unit,
    ) {
        require(descriptor.size.toLong() in 1..MAX_ANIMATION_DESCRIPTOR_BYTES) { "Invalid TEX animation descriptor size" }
        val expectedSize = checkedPayloadSize(width, height, 4)
        require(rgba.size == expectedSize) { "RGBA payload size does not match TEX dimensions" }
        checkCancellation()
        val (compressed, compressedLength) = compress(rgba)
        outputFile.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Unable to create TEX output directory" }
        }
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            output.write(mobileRgbaHeader(flags, width, height, unknown, rgba.size, compressedLength))
            output.write(compressed, 0, compressedLength)
            checkCancellation()
            output.write(descriptor)
        }
    }

    private fun decodeImageFileToRgba(
        imageFile: File,
        width: Int,
        height: Int,
        checkCancellation: () -> Unit,
    ): ByteArray {
        require(imageFile.length() in 1..MAX_EMBEDDED_IMAGE_BYTES) { "Animated TEX image payload exceeds the bounded size" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth == width && bounds.outHeight == height) {
            "Embedded image dimensions do not match TEX metadata"
        }
        require(checkedPayloadSizeLong(width, height, 4) <= STANDARD_MAX_RGBA_BYTES) {
            "Animated TEX exceeds the 256 MiB RGBA conversion limit"
        }
        checkCancellation()
        val bitmap =
            BitmapFactory.decodeFile(
                imageFile.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: error("Unsupported embedded image in TEX entry")
        try {
            require(bitmap.width == width && bitmap.height == height) {
                "Embedded image dimensions do not match TEX metadata"
            }
            val pixels = IntArray(checkedPayloadSize(width, height, 1))
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val rgba = ByteArray(checkedPayloadSize(width, height, 4))
            pixels.forEachIndexed { index, color ->
                if (index % width == 0) checkCancellation()
                val position = Math.multiplyExact(index, 4)
                rgba[position] = ((color ushr 16) and 0xff).toByte()
                rgba[position + 1] = ((color ushr 8) and 0xff).toByte()
                rgba[position + 2] = (color and 0xff).toByte()
                rgba[position + 3] = ((color ushr 24) and 0xff).toByte()
            }
            return rgba
        } finally {
            bitmap.recycle()
        }
    }

    private fun prepare(
        source: ByteArray,
        checkCancellation: () -> Unit = {},
    ): PreparedTexConversion {
        checkCancellation()
        val tex = parse(source)
        checkCancellation()
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
        mipWidth: Int = width,
        mipHeight: Int = height,
    ): ByteArray {
        val output = ByteArray(MOBILE_TEX_HEADER_SIZE)
        var offset = 0
        offset = output.writeCString(offset, "TEXV0005")
        offset = output.writeCString(offset, "TEXI0001")
        listOf(TEX_FORMAT_RGBA8888, flags, width, height, width, height, unknown).forEach { value ->
            offset = output.writeIntLe(offset, value)
        }
        offset = output.writeCString(offset, "TEXB0004")
        listOf(1, -1, 0, 1, mipWidth, mipHeight, 1, decompressedLength, compressedLength).forEach { value ->
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
            val format = input.readBoundedIntLe(end)
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
            var baseDecompressedSize = 0
            var baseStoredSize = 0
            var payloadStart = 0L
            var payloadLength = 0L
            val mipmaps = ArrayList<TexMipmapMetadata>(mipCount)
            repeat(mipCount) { index ->
                val width = input.readBoundedIntLe(end)
                val height = input.readBoundedIntLe(end)
                require(width > 0 && height > 0) { "Invalid TEX mipmap dimensions" }
                var compressed = false
                var decompressedSize = 0
                val storedSize =
                    if (container == "TEXB0001") {
                        input.readBoundedIntLe(end)
                    } else {
                        val compressionMarker = input.readBoundedIntLe(end)
                        require(compressionMarker in 0..1) { "Invalid TEX compression marker" }
                        compressed = compressionMarker == 1
                        decompressedSize = input.readBoundedIntLe(end)
                        input.readBoundedIntLe(end)
                    }
                require(storedSize >= 0) { "Invalid TEX payload size" }
                require(input.filePointer <= end - storedSize.toLong()) { "Invalid TEX payload size" }
                mipmaps +=
                    TexMipmapMetadata(
                        width = width,
                        height = height,
                        compressed = compressed,
                        decompressedSize = decompressedSize,
                        storedSize = storedSize,
                        payloadStart = input.filePointer,
                    )
                if (index == 0) {
                    baseWidth = width
                    baseHeight = height
                    baseCompressed = compressed
                    baseDecompressedSize = decompressedSize
                    baseStoredSize = storedSize
                    payloadStart = input.filePointer
                    payloadLength = storedSize.toLong()
                }
                input.seek(input.filePointer + storedSize)
            }
            val animationTailStart = input.filePointer
            val animationTailLength = end - animationTailStart
            val hasVerifiedAnimationTail =
                if (animationTailLength == 0L) {
                    false
                } else {
                    require(flags and TEX_FLAG_GIF != 0) { "TEX entry has unexpected trailing data" }
                    require(animationTailLength <= MAX_ANIMATION_DESCRIPTOR_BYTES) {
                        "TEX animation descriptor exceeds the bounded size"
                    }
                    input.seek(animationTailStart)
                    require(input.readBoundedCString(end) == ANIMATION_DESCRIPTOR_MAGIC) {
                        "Unsupported TEX animation descriptor"
                    }
                    val frameCount = input.readBoundedIntLe(end)
                    val frameWidth = input.readBoundedIntLe(end)
                    val frameHeight = input.readBoundedIntLe(end)
                    require(frameCount in 1..MAX_ANIMATION_FRAMES && frameWidth > 0 && frameHeight > 0) {
                        "Invalid TEX animation descriptor"
                    }
                    val frameBytes = frameCount.toLong() * ANIMATION_FRAME_DESCRIPTOR_BYTES
                    require(input.filePointer <= end && end - input.filePointer == frameBytes) {
                        "Invalid TEX animation descriptor length"
                    }
                    input.seek(end)
                    true
                }
            TexEntryMetadata(
                format = format,
                flags = flags,
                unknown = unknown,
                textureWidth = textureWidth,
                textureHeight = textureHeight,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                imageFormat = imageFormat,
                isMp4 = isMp4,
                container = container,
                mipmapCount = mipCount,
                baseWidth = baseWidth,
                baseHeight = baseHeight,
                baseCompressed = baseCompressed,
                baseDecompressedSize = baseDecompressedSize,
                baseStoredSize = baseStoredSize,
                payloadStart = payloadStart,
                payloadLength = payloadLength,
                mipmaps = mipmaps,
                animationTailStart = animationTailStart,
                animationTailLength = animationTailLength,
                hasVerifiedAnimationTail = hasVerifiedAnimationTail,
            )
        }

    private fun hasPngOrJpegPayload(sourceFile: File, metadata: TexEntryMetadata): Boolean =
        runCatching {
            val imageStart = locateEmbeddedImage(sourceFile, metadata.payloadStart, metadata.payloadLength)
            val headerLength = min(MAX_IMAGE_HEADER_BYTES.toLong(), metadata.payloadStart + metadata.payloadLength - imageStart).toInt()
            require(headerLength >= PNG_IHDR_BYTES)
            val header = RandomAccessFile(sourceFile, "r").use { input ->
                input.seek(imageStart)
                ByteArray(headerLength).also(input::readFully)
            }
            embeddedImageDimensions(header) == (metadata.imageWidth to metadata.imageHeight)
        }.getOrDefault(false)

    private fun embeddedImageDimensions(header: ByteArray): Pair<Int, Int>? {
        if (header.size >= PNG_IHDR_BYTES && header.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            if (!header.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII))) return null
            return readPositiveIntBe(header, 16) to readPositiveIntBe(header, 20)
        }
        if (header.size >= JPEG_SIGNATURE.size && header.copyOfRange(0, JPEG_SIGNATURE.size).contentEquals(JPEG_SIGNATURE)) {
            var position = 2
            while (position + 8 < header.size) {
                if (header[position].toInt() and 0xff != 0xff) return null
                val marker = header[position + 1].toInt() and 0xff
                position += 2
                if (marker == 0xd8 || marker == 0xd9) continue
                if (position + 2 > header.size) return null
                val segmentLength = ((header[position].toInt() and 0xff) shl 8) or (header[position + 1].toInt() and 0xff)
                if (segmentLength < 2 || position + segmentLength > header.size) return null
                if (marker in JPEG_START_OF_FRAME_MARKERS && segmentLength >= 7) {
                    val height = ((header[position + 3].toInt() and 0xff) shl 8) or (header[position + 4].toInt() and 0xff)
                    val width = ((header[position + 5].toInt() and 0xff) shl 8) or (header[position + 6].toInt() and 0xff)
                    return if (width > 0 && height > 0) width to height else null
                }
                position += segmentLength
            }
        }
        return null
    }

    private fun readPositiveIntBe(bytes: ByteArray, offset: Int): Int {
        val value =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        require(value > 0) { "Invalid embedded image dimensions" }
        return value
    }

    private fun locateEmbeddedImage(
        sourceFile: File,
        payloadStart: Long,
        payloadLength: Long,
    ): Long = RandomAccessFile(sourceFile, "r").use { input -> locateEmbeddedImage(input, payloadStart, payloadLength) }

    private fun locateEmbeddedImage(
        input: RandomAccessFile,
        payloadStart: Long,
        payloadLength: Long,
    ): Long {
        val scanLength = min(payloadLength, IMAGE_SIGNATURE_SCAN_BYTES.toLong()).toInt()
        require(scanLength >= MIN_IMAGE_SIGNATURE_BYTES) { "TEX payload is too small for an embedded image" }
        input.seek(payloadStart)
        val buffer = ByteArray(scanLength)
        input.readFully(buffer)
        val offset = embeddedImageOffset(buffer)
        require(offset >= 0) { "TEX payload is not a PNG or JPEG image" }
        return payloadStart + offset
    }

    /**
     * The offset of the first PNG or JPEG signature in [payload], or -1 when it carries neither.
     *
     * Desktop Workshop packs embed either; the pixel format of the container does not say which, so the
     * payload itself has to be read. The base mipmap holds the whole embedded file.
     */
    internal fun embeddedImageOffset(payload: ByteArray): Int {
        val png = indexOfSignature(payload, PNG_SIGNATURE)
        val jpeg = indexOfSignature(payload, JPEG_SIGNATURE)
        return listOf(png, jpeg).filter { it >= 0 }.minOrNull() ?: -1
    }

    private fun indexOfSignature(
        payload: ByteArray,
        signature: ByteArray,
    ): Int {
        for (index in 0..payload.size - signature.size) {
            if (signature.indices.all { payload[index + it] == signature[it] }) return index
        }
        return -1
    }

    private fun copyImagePayload(
        sourceFile: File,
        imageStart: Long,
        imageLength: Long,
        outputFile: File,
        checkCancellation: () -> Unit,
    ) {
        RandomAccessFile(sourceFile, "r").use { input ->
            input.seek(imageStart)
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(IMAGE_COPY_BUFFER_BYTES)
                var remaining = imageLength
                while (remaining > 0) {
                    checkCancellation()
                    val chunk = min(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, chunk)
                    output.write(buffer, 0, chunk)
                    remaining -= chunk
                }
            }
        }
    }

    private fun readFileRange(
        sourceFile: File,
        offset: Long,
        length: Long,
        checkCancellation: () -> Unit,
    ): ByteArray {
        require(length in 0..MAX_ANIMATION_DESCRIPTOR_BYTES && length <= Int.MAX_VALUE.toLong()) {
            "Invalid TEX animation descriptor range"
        }
        require(offset >= 0L && offset <= sourceFile.length() - length) { "Invalid TEX animation descriptor range" }
        checkCancellation()
        return RandomAccessFile(sourceFile, "r").use { input ->
            input.seek(offset)
            ByteArray(length.toInt()).also(input::readFully)
        }
    }

    private fun copyFileRange(
        sourceFile: File,
        offset: Long,
        length: Long,
        output: OutputStream,
        checkCancellation: () -> Unit,
    ) {
        require(offset >= 0L && length >= 0L && offset <= sourceFile.length() - length) {
            "Invalid TEX animation descriptor range"
        }
        RandomAccessFile(sourceFile, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(IMAGE_COPY_BUFFER_BYTES)
            var remaining = length
            while (remaining > 0L) {
                checkCancellation()
                val count = min(buffer.size.toLong(), remaining).toInt()
                input.readFully(buffer, 0, count)
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun encodeDownsampledEtc2(
        imageFile: File,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        checkCancellation: () -> Unit,
    ): ByteArray {
        val physicalWidth = etc2BlockAligned(targetWidth)
        val physicalHeight = etc2BlockAligned(targetHeight)
        val output = ByteArray(checkedPayloadSize(physicalWidth, physicalHeight, 1))
        FileInputStream(imageFile).use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream.fd, false)
                ?: error("Unsupported embedded image in TEX entry")
            try {
                require(decoder.width == sourceWidth && decoder.height == sourceHeight) {
                    "Embedded image dimensions do not match TEX metadata"
                }
                var targetTop = 0
                var outputOffset = 0
                while (targetTop < physicalHeight) {
                    checkCancellation()
                    val rows = min(ETC2_STRIP_HEIGHT, physicalHeight - targetTop)
                    val logicalRows = min(rows, targetHeight - min(targetTop, targetHeight - 1)).coerceAtLeast(1)
                    val sourceTop = (targetTop.toLong() * sourceHeight / targetHeight).toInt().coerceAtMost(sourceHeight - 1)
                    val sourceBottom =
                        (((targetTop + logicalRows).toLong() * sourceHeight + targetHeight - 1L) / targetHeight)
                            .toInt()
                            .coerceIn(sourceTop + 1, sourceHeight)
                    var sample = 1
                    while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sample
                    }
                    val decoded = decoder.decodeRegion(Rect(0, sourceTop, sourceWidth, sourceBottom), options)
                        ?: error("Failed to decode the embedded TEX image")
                    try {
                        checkCancellation()
                        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, logicalRows, true)
                        try {
                            val pixels = IntArray(checkedPayloadSize(targetWidth, logicalRows, 1))
                            scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, logicalRows)
                            val padded = IntArray(checkedPayloadSize(physicalWidth, rows, 1))
                            repeat(rows) { row ->
                                val sourceRow = min(row, logicalRows - 1) * targetWidth
                                val destinationRow = row * physicalWidth
                                pixels.copyInto(padded, destinationRow, sourceRow, sourceRow + targetWidth)
                                val edge = pixels[sourceRow + targetWidth - 1]
                                for (column in targetWidth until physicalWidth) padded[destinationRow + column] = edge
                            }
                            encodeEtc2Strip(padded, physicalWidth, rows, output, outputOffset)
                        } finally {
                            if (scaled !== decoded) scaled.recycle()
                        }
                    } finally {
                        decoded.recycle()
                    }
                    targetTop += rows
                    outputOffset += physicalWidth * rows
                }
            } finally {
                decoder.recycle()
            }
        }
        return output
    }

    private fun writeEtc2Payload(
        outputFile: File,
        flags: Int,
        unknown: Int,
        imageWidth: Int,
        imageHeight: Int,
        textureReduction: Int,
        storedWidth: Int,
        storedHeight: Int,
        etc2: ByteArray,
    ) {
        require(etc2.size == checkedPayloadSize(storedWidth, storedHeight, 1)) { "Unexpected ETC2 payload size" }
        val reducedWidth = (imageWidth / textureReduction).coerceAtLeast(1)
        val reducedHeight = (imageHeight / textureReduction).coerceAtLeast(1)
        val textureWidth = Math.addExact(imageWidth, Math.multiplyExact(storedWidth - reducedWidth, textureReduction))
        val textureHeight = Math.addExact(imageHeight, Math.multiplyExact(storedHeight - reducedHeight, textureReduction))
        val (compressed, compressedLength) = compress(etc2)
        outputFile.parentFile?.let { check(it.exists() || it.mkdirs()) { "Unable to create TEX output directory" } }
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            output.write(
                etc2Header(
                    flags = flags,
                    width = textureWidth,
                    height = textureHeight,
                    logicalWidth = imageWidth,
                    logicalHeight = imageHeight,
                    mipWidth = storedWidth,
                    mipHeight = storedHeight,
                    unknown = unknown,
                    decompressedLength = etc2.size,
                    compressedLength = compressedLength,
                ),
            )
            output.write(compressed, 0, compressedLength)
        }
    }

    private fun writeEtc2(
        imageFile: File,
        flags: Int,
        unknown: Int,
        width: Int,
        height: Int,
        outputFile: File,
        checkCancellation: () -> Unit,
        sourceFile: File,
        animationTailStart: Long,
        animationTailLength: Long,
    ) {
        val etc2 = encodeEtc2(imageFile, width, height, checkCancellation)
        val (compressed, compressedLength) = compress(etc2)
        outputFile.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Unable to create TEX output directory" }
        }
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            output.write(
                etc2Header(
                    flags = flags,
                    width = etc2BlockAligned(width),
                    height = etc2BlockAligned(height),
                    logicalWidth = width,
                    logicalHeight = height,
                    unknown = unknown,
                    decompressedLength = etc2.size,
                    compressedLength = compressedLength,
                ),
            )
            output.write(compressed, 0, compressedLength)
            if (animationTailLength > 0L) {
                copyFileRange(sourceFile, animationTailStart, animationTailLength, output, checkCancellation)
            }
        }
    }

    /**
     * The ETC2 block extent [value] pads to, four texels on each axis.
     *
     * An ETC2 block covers a fixed 4x4 texels, so a dimension that is not a multiple of four has to be
     * padded before it can be encoded.
     */
    internal fun etc2BlockAligned(value: Int): Int {
        require(value in 1..(Int.MAX_VALUE - ETC2_BLOCK_EDGE)) { "Invalid TEX dimension for ETC2: $value" }
        return (value + ETC2_BLOCK_EDGE - 1) / ETC2_BLOCK_EDGE * ETC2_BLOCK_EDGE
    }

    /**
     * Encodes [imageFile] as an ETC2 RGBA8 block grid, padding an unaligned edge by replicating its last
     * row and column, which the official client renders as the same image.
     */
    private fun encodeEtc2(
        imageFile: File,
        width: Int,
        height: Int,
        checkCancellation: () -> Unit,
    ): ByteArray {
        val alignedWidth = etc2BlockAligned(width)
        val alignedHeight = etc2BlockAligned(height)
        val etc2 = ByteArray(checkedPayloadSize(alignedWidth, alignedHeight, 1))
        FileInputStream(imageFile).use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream.fd, false)
                ?: error("Unsupported embedded image in TEX entry")
            try {
                require(decoder.width == width && decoder.height == height) {
                    "Embedded image dimensions do not match TEX metadata"
                }
                require(width.toLong() * min(ETC2_STRIP_HEIGHT, alignedHeight).toLong() * 8L <= MAX_ETC2_TILE_WORKING_BYTES) {
                    "Embedded image row is too wide for bounded ETC2 conversion"
                }
                val stripHeight = min(ETC2_STRIP_HEIGHT, alignedHeight)
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var stripTop = 0
                var etc2Offset = 0
                while (stripTop < alignedHeight) {
                    checkCancellation()
                    val rows = min(stripHeight, alignedHeight - stripTop)
                    require(rows % ETC2_BLOCK_EDGE == 0) { "ETC2 strip is not aligned to block edges" }
                    val sourceTop = min(stripTop, height - 1)
                    val sourceRows = (min(stripTop + rows, height) - sourceTop).coerceAtLeast(1)
                    val bitmap =
                        decoder.decodeRegion(Rect(0, sourceTop, width, sourceTop + sourceRows), options)
                            ?: error("Failed to decode the embedded TEX image")
                    try {
                        require(bitmap.width == width && bitmap.height == sourceRows) {
                            "Embedded image dimensions do not match TEX metadata"
                        }
                        val pixels = IntArray(checkedPayloadSize(width, sourceRows, 1))
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, sourceRows)
                        val padded = IntArray(checkedPayloadSize(alignedWidth, rows, 1))
                        repeat(rows) { row ->
                            val sourceRow = min(row, sourceRows - 1) * width
                            val destinationRow = row * alignedWidth
                            pixels.copyInto(padded, destinationRow, sourceRow, sourceRow + width)
                            val edge = pixels[sourceRow + width - 1]
                            for (column in width until alignedWidth) {
                                padded[destinationRow + column] = edge
                            }
                        }
                        encodeEtc2Strip(padded, alignedWidth, rows, etc2, etc2Offset)
                    } finally {
                        bitmap.recycle()
                    }
                    stripTop += rows
                    etc2Offset += alignedWidth * rows
                }
            } finally {
                decoder.recycle()
            }
        }
        return etc2
    }

    private fun encodeEtc2Strip(
        pixels: IntArray,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        val rgba = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { index, color ->
            val position = index * 4
            rgba[position] = ((color ushr 16) and 0xff).toByte()
            rgba[position + 1] = ((color ushr 8) and 0xff).toByte()
            rgba[position + 2] = (color and 0xff).toByte()
            rgba[position + 3] = ((color ushr 24) and 0xff).toByte()
        }
        val compressed = Etc2Compressor.compressRgba(rgba, width, height)
        require(compressed.size == width * height) { "Unexpected ETC2 payload size" }
        compressed.copyInto(output, outputOffset)
    }

    private fun etc2Header(
        flags: Int,
        width: Int,
        height: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        mipWidth: Int = width,
        mipHeight: Int = height,
        unknown: Int,
        decompressedLength: Int,
        compressedLength: Int,
    ): ByteArray {
        val output = ByteArray(MOBILE_TEX_HEADER_SIZE)
        var offset = 0
        offset = output.writeCString(offset, "TEXV0005")
        offset = output.writeCString(offset, "TEXI0001")
        listOf(TEX_FORMAT_ETC2_RGBA8_LZ4, flags, width, height, logicalWidth, logicalHeight, unknown).forEach { value ->
            offset = output.writeIntLe(offset, value)
        }
        offset = output.writeCString(offset, "TEXB0004")
        listOf(1, -1, 0, 1, mipWidth, mipHeight, 1, decompressedLength, compressedLength).forEach { value ->
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
        // Admission is decided from metadata before the full entry is read. Repeating the old single-buffer
        // 8 MiB test here rejected ordinary 4K DXT/R8 inputs without modelling the conversion's actual peak.
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
                    val compressionMarker = reader.readIntLe()
                    require(compressionMarker in 0..1) { "Invalid TEX compression marker" }
                    val compressed = compressionMarker == 1
                    val decompressedSize = reader.readIntLe()
                    val storedSize = reader.readIntLe()
                    require(storedSize >= 0) { "Invalid TEX payload size" }
                    if (compressed) {
                        require(decompressedSize > 0) { "Invalid LZ4 TEX payload size" }
                        require(decompressedSize.toLong() <= MAX_STATIC_DECOMPRESSED_BYTES) {
                            "TEX decompressed payload exceeds the static conversion limit"
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image-backed TEX payload" }
        require(bounds.outWidth == targetWidth && bounds.outHeight == targetHeight) {
            "Image-backed TEX dimensions do not match metadata"
        }
        require(checkedPayloadSize(bounds.outWidth, bounds.outHeight, 4).toLong() <= STANDARD_MAX_RGBA_BYTES) {
            "Image-backed TEX exceeds the static conversion limit"
        }
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
    ): Int = checkedPayloadSizeLong(width, height, bytesPerPixel).also {
        require(it <= Int.MAX_VALUE) { "TEX payload dimensions are too large" }
    }.toInt()

    private fun checkedPayloadSizeLong(
        width: Int,
        height: Int,
        bytesPerPixel: Int,
    ): Long {
        require(width > 0 && height > 0 && bytesPerPixel > 0) { "TEX payload dimensions are too large" }
        return Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), bytesPerPixel.toLong())
    }

    private fun checkedSum(vararg values: Long): Long = values.fold(0L, Math::addExact)

    private fun lz4MaximumOutputBytes(inputBytes: Long): Long =
        Math.addExact(Math.addExact(inputBytes, inputBytes / 255L), 16L)

    private fun readUIntBe(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun readUInt64Be(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        require(bytes[offset].toInt() and 0x80 == 0) { "ISO BMFF box is too large" }
        var value = 0L
        repeat(Long.SIZE_BYTES) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL) }
        return value
    }

    private fun isIsoBrandByte(value: Byte): Boolean {
        val unsigned = value.toInt() and 0xff
        return unsigned in 0x20..0x7e
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
private const val ANIMATION_DESCRIPTOR_MAGIC = "TEXS0003"
private const val ANIMATION_FRAME_DESCRIPTOR_BYTES = 32L
private const val MAX_ANIMATION_FRAMES = 16_384
private const val MAX_ANIMATION_DESCRIPTOR_BYTES = 1024L * 1024L
// Runtime-retained candidates are structurally inspected in memory. Keep this well below a typical
// Android heap; larger desktop TEX files must use a future streaming validator rather than risking OOM.
private const val MAX_RUNTIME_DESKTOP_TEX_BYTES = 32L * 1024L * 1024L
private const val MAX_RUNTIME_DECODED_MIP_BYTES = 16 * 1024 * 1024
private const val MAX_RUNTIME_DESKTOP_MIPS = 32
private const val MAX_RUNTIME_CONDITION_JSON_BYTES = 16 * 1024
private const val MAX_RUNTIME_CONDITION_VALUE_CHARS = 256
private const val MAX_RUNTIME_VARIANTS = 1024
private const val MAX_RUNTIME_VARIANT_PAYLOAD_BYTES = 64 * 1024 * 1024
private val RUNTIME_DESKTOP_DXT_FORMATS = setOf(TEX_FORMAT_DXT1, TEX_FORMAT_DXT3, TEX_FORMAT_DXT5)
private val RUNTIME_DESKTOP_CONTAINERS = setOf("TEXB0001", "TEXB0002", "TEXB0003", "TEXB0004")
private const val LZ4_COMPRESSION_LEVEL = 3
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private const val IMAGE_SIGNATURE_SCAN_BYTES = 1024
private const val MAX_IMAGE_HEADER_BYTES = 64 * 1024
private const val PNG_IHDR_BYTES = 24
private val JPEG_START_OF_FRAME_MARKERS = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
private const val MIN_IMAGE_SIGNATURE_BYTES = 3
private const val IMAGE_COPY_BUFFER_BYTES = 128 * 1024
private const val ETC2_STRIP_HEIGHT = 256
private const val ETC2_BLOCK_EDGE = 4
private const val COMPATIBILITY_MAX_DIMENSION = 2048
private const val MAX_IMAGE_DECODE_PIXELS = 48L * 1024 * 1024
private val STATIC_PNG_JPEG_FORMATS = setOf(2, 13)
private val SAFE_COLOR_TEXTURE_TOKENS = setOf("background", "wallpaper", "backdrop", "scene", "color", "albedo", "diffuse")
private val UNSAFE_TEXTURE_SEMANTIC_TOKENS = setOf("normal", "norm", "nrm", "mask", "roughness", "metallic", "height", "depth", "specular", "ao", "alpha")
private const val MAX_ETC2_PIXELS = 48L * 1024 * 1024
private const val MAX_ETC2_TILE_WORKING_BYTES = 64L * 1024 * 1024
private val CONVERTIBLE_STATIC_FORMATS = setOf(TEX_FORMAT_RGBA8888, TEX_FORMAT_DXT1, TEX_FORMAT_DXT3, TEX_FORMAT_DXT5, TEX_FORMAT_RG88, TEX_FORMAT_R8)
private const val STANDARD_MAX_RGBA_BYTES = 64L * 1024 * 1024
private const val MAX_ALLOWED_RGBA_BYTES = 256L * 1024 * 1024
private const val MAX_STATIC_DECOMPRESSED_BYTES = 64L * 1024 * 1024
// Encoded image bytes are copied between files in bounded buffers. Decoded memory is constrained
// independently by MAX_IMAGE_DECODE_PIXELS and the heap admission checks above.
private const val MAX_EMBEDDED_IMAGE_BYTES = 256L * 1024 * 1024
private const val LZ4_HC_WORKSPACE_ALLOWANCE_BYTES = 8L * 1024 * 1024
private const val STATIC_CONVERSION_HEAP_NUMERATOR = 5L
private const val STATIC_CONVERSION_HEAP_DENOMINATOR = 8L
private const val ISO_BMFF_BOX_HEADER_BYTES = 8
private const val ISO_BMFF_EXTENDED_HEADER_BYTES = 16
private const val MIN_ISO_BMFF_FTYP_BYTES = 16L
private const val MAX_ISO_BMFF_FTYP_BYTES = 4096L
private val MP4_VIDEO_BRANDS = setOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ")
private const val MAX_RETAINED_TEX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
