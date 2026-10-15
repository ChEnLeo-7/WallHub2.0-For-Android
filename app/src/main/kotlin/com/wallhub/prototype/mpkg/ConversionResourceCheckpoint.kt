package com.wallhub.prototype.mpkg

import java.io.File

/** Optional durable seam for reusing individually verified conversion products. */
interface ConversionResourceCheckpoint {
    /** Returns a previously verified product, or null when any request attribute no longer matches. */
    fun reuse(request: ConversionCheckpointRequest): File?

    /** Atomically records a product only after the converter has successfully reinspected it. */
    fun record(request: ConversionCheckpointRequest, verifiedOutput: File): File
}

data class ConversionCheckpointRequest(
    val path: String,
    val sourceOffset: Long,
    val sourceLength: Long,
    val sourceSha256: String,
    val strategy: ResourceStrategy,
)
