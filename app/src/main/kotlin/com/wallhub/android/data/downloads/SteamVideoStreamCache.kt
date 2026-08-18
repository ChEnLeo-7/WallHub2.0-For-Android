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
import `in`.dragonbra.javasteam.util.Adler32 as SteamAdler32

internal class SteamVideoStreamCache(
    rootDirectory: File,
    namespace: String,
    private val limitBytes: Long,
) {
    val prefetchCapacityBytes: Long =
        (limitBytes * CACHE_PREFETCH_PERCENT / 100L)
            .coerceIn(
                minimumValue = minOf(limitBytes, STREAM_MIN_CACHE_LIMIT_BYTES),
                maximumValue = STEAM_STREAM_MAX_AHEAD_BYTES,
            )
    private val rootDirectory = rootDirectory.canonicalFile
    private val cacheDirectory = File(this.rootDirectory, namespace)
    private val state = stateFor(this.rootDirectory)
    private val verifiedFiles = mutableSetOf<String>()
    private val chunkLocks = ConcurrentHashMap<String, Mutex>()

    init {
        cacheDirectory.mkdirs()
        check(cacheDirectory.isDirectory) { "Failed to create streaming cache directory" }
    }

    suspend fun readSlice(
        chunkOffset: Long,
        chunkLength: Int,
        expectedChecksum: Int,
        offset: Int,
        length: Int,
    ): ByteArray? {
        state.mutex.withLock {
            ensureCacheDirectory()
            initializeState()
        }
        val file = chunkFile(chunkOffset)
        return chunkLock(file).withLock {
            val valid = state.mutex.withLock { isValid(file, chunkLength, expectedChecksum) }
            if (!valid) return@withLock null
            require(offset >= 0 && length >= 0 && offset <= chunkLength - length) { "Invalid cached chunk slice" }
            val result = ByteArray(length)
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset.toLong())
                input.readFully(result)
            }
            state.mutex.withLock { touch(file) }
            result
        }
    }

    suspend fun commit(
        chunkOffset: Long,
        expectedChecksum: Int,
        data: ByteArray,
    ) {
        check(SteamAdler32.calculate(data) == expectedChecksum) { "Steam video chunk checksum mismatch" }
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
        state.mutex.withLock {
            ensureCacheDirectory()
            initializeState()
        }
        val file = chunkFile(chunkOffset)
        chunkLock(file).withLock {
            if (state.mutex.withLock { isValid(file, data.size, expectedChecksum) }) return@withLock
            val partial = File(cacheDirectory, "${file.name}.${UUID.randomUUID()}.part")
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
                    verifiedFiles += file.absolutePath
                    evictIfNeeded()
                }
            } finally {
                partial.delete()
            }
        }
    }

    private fun isValid(
        file: File,
        expectedLength: Int,
        expectedChecksum: Int,
    ): Boolean {
        if (!file.isFile || file.length() != expectedLength.toLong()) {
            verifiedFiles.remove(file.absolutePath)
            deleteTracked(file)
            return false
        }
        if (file.absolutePath in verifiedFiles) return true
        val valid = calculateChecksum(file) == expectedChecksum
        if (valid) {
            verifiedFiles += file.absolutePath
        } else {
            deleteTracked(file)
        }
        return valid
    }

    private fun evictIfNeeded() {
        if (state.totalBytes <= limitBytes) return

        val lowWaterBytes = (limitBytes * CACHE_LOW_WATER_PERCENT / 100L).coerceAtLeast(0L)
        while (state.totalBytes > lowWaterBytes && state.accessQueue.isNotEmpty()) {
            val entry = state.accessQueue.remove()
            val file = entry.file
            if (state.currentEntries[file.absolutePath]?.sequence != entry.sequence) continue
            if (!file.isFile || file.length() != entry.length) {
                state.currentEntries.remove(file.absolutePath)
                continue
            }
            verifiedFiles.remove(file.absolutePath)
            deleteTracked(file)
        }
    }

    private fun initializeState() {
        if (state.initialized) return
        rootDirectory.mkdirs()
        rootDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.name.endsWith(CHUNK_SUFFIX)) {
                state.totalBytes += file.length()
                addAccessEntry(file, file.lastModified())
            } else if (file.name.endsWith(PARTIAL_SUFFIX)) {
                file.delete()
            }
        }
        state.initialized = true
        evictIfNeeded()
    }

    private fun ensureCacheDirectory() {
        cacheDirectory.mkdirs()
        check(cacheDirectory.isDirectory) { "Failed to create streaming cache directory" }
    }

    private fun touch(file: File) {
        val timestamp = System.currentTimeMillis()
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
        state.currentEntries.remove(file.absolutePath)
        if (!file.isFile) return
        val length = file.length()
        if (file.delete()) state.totalBytes = (state.totalBytes - length).coerceAtLeast(0L)
    }

    private fun chunkFile(chunkOffset: Long): File = File(cacheDirectory, "$chunkOffset$CHUNK_SUFFIX")

    private fun chunkLock(file: File): Mutex = chunkLocks.computeIfAbsent(file.absolutePath) { Mutex() }

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

    private class RootState(
        val mutex: Mutex = Mutex(),
        val accessQueue: PriorityQueue<AccessEntry> = PriorityQueue(compareBy(AccessEntry::lastModified)),
        val currentEntries: MutableMap<String, AccessEntry> = mutableMapOf(),
        var initialized: Boolean = false,
        var totalBytes: Long = 0L,
        var accessSequence: Long = 0L,
    )

    internal companion object {
        private const val CHUNK_SUFFIX = ".chunk"
        private const val PARTIAL_SUFFIX = ".part"
        private const val CACHE_LOW_WATER_PERCENT = 80L
        private const val CACHE_PREFETCH_PERCENT = 60L
        private const val CHECKSUM_BUFFER_SIZE = 64 * 1024
        private const val MIN_QUEUE_COMPACT_SIZE = 64
        private val statesLock = Any()
        private val states = mutableMapOf<String, RootState>()

        private fun stateFor(rootDirectory: File): RootState =
            synchronized(statesLock) {
                states.getOrPut(rootDirectory.absolutePath) { RootState() }
            }

        internal suspend fun clearRoot(rootDirectory: File): Long {
            val root = rootDirectory.canonicalFile
            val state = stateFor(root)
            return state.mutex.withLock {
                var clearedBytes = 0L
                if (root.isDirectory) {
                    root.walkBottomUp().forEach { entry ->
                        if (entry == root) return@forEach
                        if (entry.isFile) {
                            val length = entry.length()
                            if (entry.delete()) clearedBytes += length
                        } else {
                            entry.delete()
                        }
                    }
                }
                state.accessQueue.clear()
                state.currentEntries.clear()
                state.totalBytes = 0L
                state.accessSequence = 0L
                state.initialized = true
                clearedBytes
            }
        }
    }
}
