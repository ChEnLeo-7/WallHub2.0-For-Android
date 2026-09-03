package com.wallhub.android.data.downloads

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.zip.Adler32

internal class SteamVideoStreamCache(
    rootDirectory: File,
    namespace: String,
    private val limitBytes: Long,
) {
    init {
        require(limitBytes > 0L) { "Streaming cache limit must be positive" }
    }

    val prefetchCapacityBytes: Long = limitBytes
    private val rootDirectory = rootDirectory.canonicalFile
    private val cacheDirectory = File(this.rootDirectory, namespace)
    private val state = stateFor(this.rootDirectory)
    private val ownerId = UUID.randomUUID().toString()
    private val verifiedFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val verifiedChecksums = ConcurrentHashMap<String, CacheVerification>()
    // RootState is shared by all namespaces, while verification markers belong to
    // this cache instance. Track the per-instance marker load separately.
    private var instanceInitialized = false

    @Volatile
    private var evictionListener: ((Long) -> Unit)? = null

    init {
        cacheDirectory.mkdirs()
        check(cacheDirectory.isDirectory) { "Failed to create streaming cache directory" }
        state.evictionObservers[ownerId] = { file ->
            if (file.parentFile?.absolutePath == cacheDirectory.absolutePath && file.name.endsWith(CHUNK_SUFFIX)) {
                verifiedFiles.remove(file.absolutePath)
                verifiedChecksums.remove(file.absolutePath)
                file.name.removeSuffix(CHUNK_SUFFIX).toLongOrNull()?.let { offset ->
                    evictionListener?.invoke(offset)
                }
            }
        }
    }

    fun setEvictionListener(listener: (Long) -> Unit) {
        evictionListener = listener
    }

    fun protectChunkOffsets(offsets: Collection<Long>) {
        val protectedPaths = offsets.mapTo(mutableSetOf()) { chunkFile(it).absolutePath }
        synchronized(state.protectionLock) {
            state.protectedPathsByOwner[ownerId] = protectedPaths
        }
    }

    fun close() {
        evictionListener = null
        synchronized(state.protectionLock) {
            state.protectedPathsByOwner.remove(ownerId)
        }
        state.evictionObservers.remove(ownerId)
    }

    suspend fun readSlice(
        chunkOffset: Long,
        chunkLength: Int,
        expectedChecksum: Int,
        offset: Int,
        length: Int,
    ): ByteArray? {
        val result = ByteArray(length)
        return if (
            readSliceInto(
                chunkOffset = chunkOffset,
                chunkLength = chunkLength,
                expectedChecksum = expectedChecksum,
                sourceOffset = offset,
                destination = result,
                destinationOffset = 0,
                length = length,
            )
        ) {
            result
        } else {
            null
        }
    }

    suspend fun readSliceInto(
        chunkOffset: Long,
        chunkLength: Int,
        expectedChecksum: Int,
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Boolean {
        ensureCacheReady()
        val file = chunkFile(chunkOffset)
        return chunkLock(file).withLock {
            val valid = isValid(file, chunkLength, expectedChecksum)
            if (!valid) return@withLock false
            require(sourceOffset >= 0 && length >= 0 && sourceOffset <= chunkLength - length) {
                "Invalid cached chunk slice"
            }
            require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
                "Invalid cached chunk destination"
            }
            if (length > 0) {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(sourceOffset.toLong())
                    input.readFully(destination, destinationOffset, length)
                }
            }
            state.mutex.withLock { touch(file) }
            true
        }
    }

    suspend fun commit(
        chunkOffset: Long,
        expectedChecksum: Int,
        data: ByteArray,
    ) {
        check(steamAdler32(data) == expectedChecksum) { "Steam video chunk checksum mismatch" }
        commitVerified(chunkOffset, expectedChecksum, data)
    }

    /**
     * Commits a payload already authenticated by DepotChunk.process().
     */
    suspend fun commitVerified(
        chunkOffset: Long,
        expectedChecksum: Int,
        data: ByteArray,
    ) {
        prepareCache()
        val file = chunkFile(chunkOffset)
        val path = file.absolutePath
        state.mutex.withLock {
            state.activeChunkWrites[path] = (state.activeChunkWrites[path] ?: 0) + 1
            if (state.evictionClaims.remove(path) != null) {
                state.currentEntries[path]?.let { state.accessQueue += it }
            }
        }
        try {
            chunkLock(file).withLock {
                if (isValid(file, data.size, expectedChecksum)) return@withLock
                val partial = File(cacheDirectory, "${file.name}.${UUID.randomUUID()}.part")
                state.activeTemporaryFiles[partial.absolutePath] = data.size.toLong()
                try {
                    // The expensive write is intentionally outside the root metadata lock so
                    // foreground reads and other completed chunks are not serialized behind it.
                    partial.outputStream().use { output ->
                        output.write(data)
                        output.flush()
                    }
                    state.mutex.withLock {
                        val replacedLength = file.takeIf(File::isFile)?.length() ?: 0L
                        moveReplacing(partial, file)
                        state.totalBytes += file.length() - replacedLength
                        touch(file)
                        writeVerificationMarker(file, data.size, expectedChecksum)
                        verifiedFiles += file.absolutePath
                        verifiedChecksums[file.absolutePath] = CacheVerification(data.size, expectedChecksum)
                    }
                } finally {
                    partial.delete()
                    state.activeTemporaryFiles.remove(partial.absolutePath)
                }
            }
            evictOverflow(excludedPaths = setOf(path))
        } finally {
            state.mutex.withLock {
                val remaining = (state.activeChunkWrites[path] ?: 1) - 1
                if (remaining > 0) state.activeChunkWrites[path] = remaining else state.activeChunkWrites.remove(path)
            }
        }
    }

    private suspend fun isValid(
        file: File,
        expectedLength: Int,
        expectedChecksum: Int,
    ): Boolean {
        val expectedLengthLong = expectedLength.toLong()
        if (!file.isFile || file.length() != expectedLengthLong) {
            state.mutex.withLock {
                verifiedFiles.remove(file.absolutePath)
                verifiedChecksums.remove(file.absolutePath)
                deleteTracked(file)
            }
            return false
        }
        if (state.mutex.withLock { file.absolutePath in verifiedFiles }) return true
        if (verifiedChecksums[file.absolutePath] == CacheVerification(expectedLength, expectedChecksum)) {
            verifiedFiles += file.absolutePath
            return true
        }
        val observedLastModified = file.lastModified()
        val valid = calculateChecksum(file) == expectedChecksum
        return state.mutex.withLock {
            if (!file.isFile || file.length() != expectedLengthLong || file.lastModified() != observedLastModified) {
                false
            } else if (valid) {
                verifiedFiles += file.absolutePath
                verifiedChecksums[file.absolutePath] = CacheVerification(expectedLength, expectedChecksum)
                writeVerificationMarker(file, expectedLength, expectedChecksum)
                true
            } else {
                verifiedChecksums.remove(file.absolutePath)
                deleteTracked(file)
                false
            }
        }
    }

    private fun selectEvictionVictimsLocked(
        maximumBytes: Long,
        excludedPaths: Set<String> = emptySet(),
    ): List<EvictionVictim> {
        if (state.totalBytes <= maximumBytes) return emptyList()

        // Evict only the current overflow. The former 80% low-water sweep deleted
        // roughly one hundred 1 MiB chunks while holding the metadata lock and
        // periodically stopped the entire network/decode pipeline for many seconds.
        val victims = mutableListOf<EvictionVictim>()
        val protectedEntries = mutableListOf<AccessEntry>()
        var projectedBytes = state.totalBytes
        while (projectedBytes > maximumBytes && state.accessQueue.isNotEmpty()) {
            val entry = state.accessQueue.remove()
            val file = entry.file
            if (state.currentEntries[file.absolutePath]?.sequence != entry.sequence) continue
            if (
                file.absolutePath in excludedPaths ||
                state.activeChunkWrites.containsKey(file.absolutePath) ||
                isProtected(file)
            ) {
                protectedEntries += entry
                continue
            }
            state.evictionClaims[file.absolutePath] = entry.sequence
            victims += EvictionVictim(file, entry.length, entry.sequence)
            projectedBytes = (projectedBytes - entry.length).coerceAtLeast(0L)
        }
        state.accessQueue.addAll(protectedEntries)
        return victims
    }

    private suspend fun deleteEvictionVictims(victims: List<EvictionVictim>) {
        if (victims.isEmpty()) return
        victims.forEach { victim ->
            var evicted = false
            chunkLock(victim.file).withLock {
                state.mutex.withLock {
                    synchronized(state.protectionLock) {
                        val path = victim.file.absolutePath
                        val current = state.currentEntries[path]
                        val claimValid =
                            state.evictionClaims[path] == victim.sequence &&
                                current?.sequence == victim.sequence
                        if (
                            claimValid &&
                            current != null &&
                            !state.activeChunkWrites.containsKey(path) &&
                            !isProtectedPathLocked(path)
                        ) {
                            evicted = !victim.file.isFile || victim.file.delete()
                            if (evicted) {
                                state.currentEntries.remove(path)
                                state.totalBytes = (state.totalBytes - current.length).coerceAtLeast(0L)
                                verifiedFiles.remove(path)
                                verifiedChecksums.remove(path)
                                markerFile(victim.file).delete()
                            } else {
                                val actualLength = victim.file.length()
                                state.totalBytes += actualLength - current.length
                                addAccessEntry(victim.file, victim.file.lastModified())
                            }
                        } else if (current != null) {
                            state.accessQueue += current
                        }
                        state.evictionClaims.remove(path)
                    }
                }
            }
            if (evicted) notifyEvicted(victim.file)
        }
    }

    private suspend fun ensureCacheReady() {
        state.mutex.withLock {
            ensureCacheDirectory()
            initializeState()
        }
    }

    private suspend fun prepareCache() {
        ensureCacheReady()
        evictOverflow()
    }

    private suspend fun evictOverflow(excludedPaths: Set<String> = emptySet()) {
        state.evictionMutex.withLock {
            val victims = state.mutex.withLock { selectEvictionVictimsLocked(limitBytes, excludedPaths) }
            deleteEvictionVictims(victims)
        }
    }

    private fun initializeState() {
        if (!state.initialized) {
            rootDirectory.mkdirs()
            rootDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.name.endsWith(CHUNK_SUFFIX)) {
                    state.totalBytes += file.length()
                    addAccessEntry(file, file.lastModified())
                }
                if (file.name.endsWith(PARTIAL_SUFFIX)) {
                    file.delete()
                    state.activeTemporaryFiles.remove(file.absolutePath)
                }
            }
            state.initialized = true
        }
        if (!instanceInitialized) {
            // A later stream instance must load markers for its own namespace even
            // when the shared root state was initialized by an earlier instance.
            cacheDirectory.walkTopDown().filter { it.isFile && it.name.endsWith(CHUNK_SUFFIX) }.forEach { file ->
                readVerificationMarker(file)?.let { verification ->
                    verifiedChecksums[file.absolutePath] = verification
                }
            }
            instanceInitialized = true
        }
    }

    private fun ensureCacheDirectory() {
        cacheDirectory.mkdirs()
        check(cacheDirectory.isDirectory) { "Failed to create streaming cache directory" }
    }

    private fun touch(file: File) {
        val timestamp = System.currentTimeMillis()
        val path = file.absolutePath
        val previous = state.currentEntries[path]
        val cancelledClaim = state.evictionClaims.remove(path) != null
        if (!cancelledClaim && previous != null && timestamp - previous.lastModified < TOUCH_INTERVAL_MS) return
        file.setLastModified(timestamp)
        addAccessEntry(file, file.lastModified())
        val compactThreshold = (state.currentEntries.size * 2).coerceAtLeast(MIN_QUEUE_COMPACT_SIZE)
        if (state.accessQueue.size > compactThreshold) {
            state.accessQueue.clear()
            state.accessQueue.addAll(state.currentEntries.values)
        }
    }

    private fun addAccessEntry(
        file: File,
        lastModified: Long,
    ) {
        val entry = AccessEntry(file, lastModified, file.length(), ++state.accessSequence)
        state.currentEntries[file.absolutePath] = entry
        state.accessQueue += entry
    }

    private fun deleteTracked(file: File) {
        val path = file.absolutePath
        val trackedLength = state.currentEntries.remove(path)?.length ?: 0L
        state.totalBytes = (state.totalBytes - trackedLength).coerceAtLeast(0L)
        val deleted = !file.isFile || file.delete()
        markerFile(file).delete()
        verifiedFiles.remove(path)
        verifiedChecksums.remove(path)
        state.evictionClaims.remove(path)
        if (!deleted && file.isFile) {
            state.totalBytes += file.length()
            addAccessEntry(file, file.lastModified())
        }
    }

    private fun chunkFile(chunkOffset: Long): File = File(cacheDirectory, "$chunkOffset$CHUNK_SUFFIX")

    private fun markerFile(chunk: File): File = File(chunk.parentFile, "${chunk.name}$MARKER_SUFFIX")

    private fun writeVerificationMarker(
        file: File,
        length: Int,
        checksum: Int,
    ) {
        val marker = markerFile(file)
        val partial = File(cacheDirectory, "${marker.name}.${UUID.randomUUID()}$PARTIAL_SUFFIX")
        state.activeTemporaryFiles[partial.absolutePath] = 0L
        try {
            partial.writeText("$length:$checksum", Charsets.US_ASCII)
            moveReplacing(partial, marker)
        } finally {
            partial.delete()
            state.activeTemporaryFiles.remove(partial.absolutePath)
        }
    }

    private fun readVerificationMarker(file: File): CacheVerification? =
        runCatching {
            val parts = markerFile(file).readText(Charsets.US_ASCII).trim().split(':')
            CacheVerification(parts[0].toInt(), parts[1].toInt())
        }.getOrNull()

    private fun chunkLock(file: File): Mutex {
        val index = (file.absolutePath.hashCode() and Int.MAX_VALUE) % CHUNK_LOCK_STRIPES
        return state.chunkLocks[index]
    }

    private fun isProtected(file: File): Boolean =
        synchronized(state.protectionLock) {
            isProtectedPathLocked(file.absolutePath)
        }

    private fun isProtectedPathLocked(path: String): Boolean =
        state.protectedPathsByOwner.values.any { protectedPaths -> path in protectedPaths }

    private fun notifyEvicted(file: File) {
        state.evictionObservers.values.forEach { observer -> runCatching { observer(file) } }
    }

    private fun calculateChecksum(file: File): Int {
        val checksum = Adler32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(CHECKSUM_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) checksum.update(buffer, 0, read)
            }
        }
        return checksum.value.toInt()
    }

    private fun moveReplacing(
        source: File,
        destination: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class AccessEntry(
        val file: File,
        val lastModified: Long,
        val length: Long,
        val sequence: Long,
    )

    private data class CacheVerification(
        val length: Int,
        val checksum: Int,
    )

    private data class EvictionVictim(
        val file: File,
        val length: Long,
        val sequence: Long,
    )

    private class RootState(
        val mutex: Mutex = Mutex(),
        val evictionMutex: Mutex = Mutex(),
        val protectionLock: Any = Any(),
        val accessQueue: PriorityQueue<AccessEntry> = PriorityQueue(compareBy(AccessEntry::lastModified)),
        val currentEntries: MutableMap<String, AccessEntry> = mutableMapOf(),
        val evictionClaims: MutableMap<String, Long> = mutableMapOf(),
        val chunkLocks: Array<Mutex> = Array(CHUNK_LOCK_STRIPES) { Mutex() },
        val protectedPathsByOwner: MutableMap<String, Set<String>> = mutableMapOf(),
        val evictionObservers: ConcurrentHashMap<String, (File) -> Unit> = ConcurrentHashMap(),
        val activeTemporaryFiles: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        val activeChunkWrites: MutableMap<String, Int> = mutableMapOf(),
        var initialized: Boolean = false,
        var totalBytes: Long = 0L,
        var accessSequence: Long = 0L,
    )

    internal companion object {
        private const val CHUNK_SUFFIX = ".chunk"
        private const val PARTIAL_SUFFIX = ".part"
        private const val MARKER_SUFFIX = ".verified"
        // Keep one eviction-band of headroom for older videos and chunk-boundary
        // overshoot. With the default 512 MiB cache this leaves 409.6 MiB, enough
        // for the playback window of a high-bitrate source without restoring the old 256 MiB cap.
        private const val CHECKSUM_BUFFER_SIZE = 64 * 1024
        private const val MIN_QUEUE_COMPACT_SIZE = 64
        private const val TOUCH_INTERVAL_MS = 2_000L
        private const val CHUNK_LOCK_STRIPES = 64
        private val statesLock = Any()
        private val states = mutableMapOf<String, RootState>()

        private fun stateFor(rootDirectory: File): RootState =
            synchronized(statesLock) {
                states.getOrPut(rootDirectory.absolutePath) { RootState() }
            }

        internal suspend fun clearRoot(rootDirectory: File): Long {
            val root = rootDirectory.canonicalFile
            val state = stateFor(root)
            if (!root.isDirectory) return 0L
            var clearedBytes = 0L
            val evictedFiles = mutableListOf<File>()
            state.evictionMutex.withLock {
                val files = root.walkTopDown().filter(File::isFile).toList()
                files.forEach { file ->
                    val path = file.absolutePath
                    val protectedPath =
                        if (file.name.endsWith("$CHUNK_SUFFIX$MARKER_SUFFIX")) {
                            path.removeSuffix(MARKER_SUFFIX)
                        } else {
                            path
                        }
                    val lockIndex = (protectedPath.hashCode() and Int.MAX_VALUE) % CHUNK_LOCK_STRIPES
                    state.chunkLocks[lockIndex].withLock {
                        state.mutex.withLock {
                            synchronized(state.protectionLock) {
                                val protected =
                                    state.protectedPathsByOwner.values.any { protectedPath in it } ||
                                        state.activeChunkWrites.containsKey(protectedPath) ||
                                        state.activeTemporaryFiles.containsKey(path)
                                if (!protected) {
                                    val length = if (file.name.endsWith(MARKER_SUFFIX)) 0L else file.length()
                                    val deleted = !file.isFile || file.delete()
                                    if (deleted) {
                                        state.currentEntries.remove(path)?.let { entry ->
                                            state.totalBytes = (state.totalBytes - entry.length).coerceAtLeast(0L)
                                        }
                                        state.evictionClaims.remove(path)
                                        clearedBytes += length
                                        evictedFiles += file
                                    }
                                }
                            }
                        }
                    }
                }
                root.walkBottomUp().filter { it.isDirectory && it != root }.forEach(File::delete)
            }
            evictedFiles.forEach { file ->
                state.evictionObservers.values.forEach { observer -> runCatching { observer(file) } }
            }
            return clearedBytes
        }

    }
}
