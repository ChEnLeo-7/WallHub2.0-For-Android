package com.wallhub.android.data.steamaccess

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Returns only input-side I/O failure; EOF and destination cancellation are not route failures. */
internal fun forwardSteamBytes(
    input: InputStream,
    output: OutputStream,
    onBytes: (ByteArray, Int) -> Unit = { _, _ -> },
    onEof: () -> IOException? = { null },
): IOException? {
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val count = try { input.read(buffer) } catch (error: IOException) { return error }
        if (count < 0) return onEof()
        if (count == 0) continue
        try {
            output.write(buffer, 0, count)
            // copyTo already writes each chunk. Explicit flushing avoids depending on a TLS
            // provider's buffering; it is not a claim that Conscrypt caused the observed stall.
            output.flush()
        } catch (_: IOException) { return null }
        onBytes(buffer, count)
    }
}

/** Observe only the initial HTTP/1.1 status line; never retain headers, bodies or credentials. */
internal class SteamResponseStatusObserver(private val onResponse: () -> Unit) {
    private val line = StringBuilder()
    private var done = false
    @Volatile var receivedResponse = false
        private set
    fun accept(bytes: ByteArray, count: Int) {
        if (done) return
        for (index in 0 until count) {
            val char = (bytes[index].toInt() and 0xff).toChar()
            if (char == '\n') {
                done = true
                if (line.toString().matches(Regex("HTTP/1\\.1 [1-5][0-9]{2} [^\\r\\n]*\\r"))) {
                    receivedResponse = true
                    onResponse()
                }
                line.clear()
                return
            }
            if (line.length >= 1024) { done = true; line.clear(); return }
            line.append(char)
        }
    }
}
