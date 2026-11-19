package com.wallhub.android.data.downloads

import java.io.InputStream

/** Steam depot chunk checksums seed s1 with 0 (SteamKit2 DepotChunk.AdlerHash), not RFC 1950's 1. */
internal fun steamAdler32(data: ByteArray): Int {
    val base = 65_521
    var s1 = 0
    var s2 = 0
    for (byte in data) {
        s1 = (s1 + (byte.toInt() and 0xff)) % base
        s2 = (s2 + s1) % base
    }
    return (s2 shl 16) or s1
}

/** Streaming variant of [steamAdler32] for files too large to buffer in memory. */
internal fun steamAdler32(input: InputStream): Int {
    val base = 65_521
    var s1 = 0
    var s2 = 0
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        for (index in 0 until read) {
            s1 = (s1 + (buffer[index].toInt() and 0xff)) % base
            s2 = (s2 + s1) % base
        }
    }
    return (s2 shl 16) or s1
}
