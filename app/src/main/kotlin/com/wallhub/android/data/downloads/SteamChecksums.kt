package com.wallhub.android.data.downloads

import java.util.zip.Adler32

/** Standard Adler-32 over the payload, matching Steam depot chunk checksums. */
internal fun steamAdler32(data: ByteArray): Int = Adler32().apply { update(data) }.value.toInt()
