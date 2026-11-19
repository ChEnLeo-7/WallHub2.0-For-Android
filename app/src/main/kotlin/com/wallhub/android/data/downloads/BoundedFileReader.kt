package com.wallhub.android.data.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/**
 * Reads a response body into memory without trusting its length: the stream stops being consumed as soon as
 * the next read would pass [maxBytes], rather than after a size that arrived in a header.
 */
internal fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "Invalid response byte limit: $maxBytes" }
    val output = ByteArrayOutputStream(min(8192, maxBytes))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(total <= maxBytes - read) { "Steam manifest response exceeds $maxBytes bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

fun readBoundedUtf8File(
    file: File,
    maxBytes: Long,
    label: String = file.name,
): String {
    require(maxBytes in 0..Int.MAX_VALUE.toLong()) { "Invalid byte limit for $label: $maxBytes" }
    require(file.isFile) { "$label is missing: $file" }
    val length = file.length()
    require(length <= maxBytes) { "$label exceeds the $maxBytes-byte read limit: $length bytes" }
    return file.inputStream().buffered().use { input ->
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            require(read > 0) { "$label changed or ended while being read" }
            offset += read
        }
        require(input.read() < 0) { "$label grew beyond the $maxBytes-byte read limit while being read" }
        bytes.toString(Charsets.UTF_8)
    }
}

fun readProjectJson(file: File): String =
    readBoundedUtf8File(file, MAX_PROJECT_JSON_BYTES.toLong(), "project.json").removePrefix("\uFEFF")
