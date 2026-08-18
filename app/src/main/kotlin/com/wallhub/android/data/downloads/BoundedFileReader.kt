package com.wallhub.android.data.downloads

import java.io.File

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
