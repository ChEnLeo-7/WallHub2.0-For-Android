package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.util.zip.Adler32
import `in`.dragonbra.javasteam.util.Adler32 as SteamAdler32

internal class SteamVideoStreamCache(
    rootDirectory: File,
    namespace: String,
    private val limitBytes: Long,
    maximumEncryptedChunkBytes: Long = 0L,
    minimumStagingCapacityBytes: Long = 0L,
    stagingEnabled: Boolean = true,
) {
    private val stagingCapacityBytes =
        if (stagingEnabled) {
            streamStagingCapacityBytes(limitBytes, maximumEncryptedChunkBytes, minimumStagingCapacityBytes)
        } else {
            0L
        }
    val prefetchCapacityBytes: Long =
        (limitBytes - stagingCapacityBytes).coerceAtLeast(1L)
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
        state.protectedPathsByOwner[ownerId] = offsets.mapTo(mutableSetOf()) { chunkFile(it).absolutePath }
    }

    fun close() {
        evictionListener = null
        state.protectedPathsByOwner.remove(ownerId)
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
        check(SteamAdler32.calculate(data) == expectedChecksum) { "Steam video chunk checksum mismatch" }
        commitVerified(chunkOffset, expectedChecksum, data)
    }

    /**
     * Spools an encrypted response to disk so a completed network request never has
     * to occupy a decode slot or retain its byte array before the next request starts.
     */
    suspend fun reserveEncrypted(length: Int): SteamEncryptedChunkReservation {
        require(length >= 0) { "Steam encrypted chunk reservation must not be negative" }
        if (length == 0) return SteamEncryptedChunkReservation(0) {}
        require(length.toLong() <= stagingCapacityBytes) {
            "Steam encrypted chunk exceeds staging capacity: $length > $stagingCapacityBytes"
        }
        prepareCache()
        val waiter =
            state.mutex.withLock {
                if (state.stagedWaiters.isEmpty() && canReserveStagedBytes(length.toLong(), stagingCapacityBytes)) {
                    state.stagedBytes += length
                    null
                } else {
                    StagedWaiter(
                        bytes = length.toLong(),
                        capacityBytes = stagingCapacityBytes,
                        signal = CompletableDeferred(),
                    ).also(state.stagedWaiters::addLast)
                }
            }
        if (waiter != null) {
            try {
                waiter.signal.await()
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    val signals = mutableListOf<CompletableDeferred<Unit>>()
                    val removed =
                        state.mutex.withLock {
                            state.stagedWaiters.remove(waiter).also { didRemove ->
                                if (didRemove) dispatchStagedWaitersLocked(signals)
                            }
                        }
                    signals.forEach { signal -> signal.complete(Unit) }
                    if (!removed) releaseStagedBytes(waiter.bytes)
                }
                throw error
            }
        }
        val reservation = SteamEncryptedChunkReservation(length) { bytes -> releaseStagedBytes(bytes.toLong()) }
        try {
            evictOverflow()
            return reservation
        } catch (error: Throwable) {
            withContext(NonCancellable) { reservation.release() }
            throw error
        }
    }

    suspend fun stageEncrypted(
        reservation: SteamEncryptedChunkReservation,
        requestId: Long,
        chunkOffset: Long,
        data: ByteArray,
    ): SteamEncryptedChunkSpool {
        check(reservation.length == data.size) { "Steam encrypted chunk reservation length mismatch" }
        prepareCache()
        val file = File(cacheDirectory, "$SPOOL_PREFIX$requestId-$chunkOffset$SPOOL_SUFFIX")
        state.activeTemporaryFiles[file.absolutePath] = data.size.toLong()
        try {
            file.outputStream().use { output ->
                output.write(data)
                output.flush()
            }
            return SteamEncryptedChunkSpool(file, data.size, reservation) {
                state.activeTemporaryFiles.remove(file.absolutePath)
            }
        } catch (error: Throwable) {
            file.delete()
            state.activeTemporaryFiles.remove(file.absolutePath)
            withContext(NonCancellable) { reservation.release() }
            throw error
        }
    }

    /** Creates an empty, reserved spool that the HTTP response can stream into directly. */
    suspend fun createEncryptedSpool(
        reservation: SteamEncryptedChunkReservation,
        requestId: Long,
        chunkOffset: Long,
    ): SteamEncryptedChunkSpool {
        prepareCache()
        val file = File(cacheDirectory, "$SPOOL_PREFIX$requestId-$chunkOffset$SPOOL_SUFFIX")
        state.activeTemporaryFiles[file.absolutePath] = reservation.length.toLong()
        return try {
            if (file.exists()) check(file.delete()) { "Failed to reset Steam encrypted chunk spool" }
            SteamEncryptedChunkSpool(file, reservation.length, reservation) {
                state.activeTemporaryFiles.remove(file.absolutePath)
            }
        } catch (error: Throwable) {
            state.activeTemporaryFiles.remove(file.absolutePath)
            withContext(NonCancellable) { reservation.release() }
            throw error
        }
    }

    fun readStaged(spool: SteamEncryptedChunkSpool): ByteArray {
        check(spool.file.isFile && spool.file.length() == spool.length.toLong()) {
            "Steam encrypted chunk spool is incomplete"
        }
        return spool.file.readBytes()
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
        val evictionVictims =
            chunkLock(file).withLock {
                if (isValid(file, data.size, expectedChecksum)) return@withLock emptyList()
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
                        selectEvictionVictimsLocked(limitBytes)
                    }
                } finally {
                    partial.delete()
                    state.activeTemporaryFiles.remove(partial.absolutePath)
                }
            }
        deleteEvictionVictims(evictionVictims)
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

    private fun selectEvictionVictimsLocked(maximumBytes: Long): List<EvictionVictim> {
        if (state.totalBytes + state.stagedBytes <= maximumBytes) return emptyList()

        // Evict only the current overflow. The former 80% low-water sweep deleted
        // roughly one hundred 1 MiB chunks while holding the metadata lock and
        // periodically stopped the entire network/decode pipeline for many seconds.
        val victims = mutableListOf<EvictionVictim>()
        val protectedEntries = mutableListOf<AccessEntry>()
        while (state.totalBytes + state.stagedBytes > maximumBytes && state.accessQueue.isNotEmpty()) {
            val entry = state.accessQueue.remove()
            val file = entry.file
            if (state.currentEntries[file.absolutePath]?.sequence != entry.sequence) continue
            if (!file.isFile || file.length() != entry.length) {
                state.currentEntries.remove(file.absolutePath)
                state.totalBytes = (state.totalBytes - entry.length).coerceAtLeast(0L)
                continue
            }
            if (isProtected(file)) {
                protectedEntries += entry
                continue
            }
            verifiedFiles.remove(file.absolutePath)
            verifiedChecksums.remove(file.absolutePath)
            state.currentEntries.remove(file.absolutePath)
            state.totalBytes = (state.totalBytes - entry.length).coerceAtLeast(0L)
            state.evictionClaims[file.absolutePath] = entry.sequence
            victims += EvictionVictim(file, entry.length, entry.sequence)
        }
        state.accessQueue.addAll(protectedEntries)
        return victims
    }

    private suspend fun deleteEvictionVictims(victims: List<EvictionVictim>) {
        if (victims.isEmpty()) return
        victims.forEach { victim ->
            var evicted = false
            chunkLock(victim.file).withLock {
                val canDelete =
                    state.mutex.withLock {
                        val claimed = state.evictionClaims[victim.file.absolutePath] == victim.sequence
                        if (!claimed) {
                            false
                        } else if (isProtected(victim.file) || victim.file.absolutePath in state.currentEntries) {
                            state.evictionClaims.remove(victim.file.absolutePath)
                            val current = state.currentEntries[victim.file.absolutePath]
                            if (current != null) {
                                state.totalBytes += current.length
                            } else if (victim.file.isFile) {
                                state.totalBytes += victim.file.length()
                                addAccessEntry(victim.file, victim.file.lastModified())
                            }
                            false
                        } else {
                            true
                        }
                    }
                if (canDelete) {
                    evicted = !victim.file.isFile || victim.file.delete()
                    if (evicted) markerFile(victim.file).delete()
                    state.mutex.withLock {
                        state.evictionClaims.remove(victim.file.absolutePath)
                        if (!evicted && victim.file.isFile && victim.file.absolutePath !in state.currentEntries) {
                            state.totalBytes += victim.file.length()
                            addAccessEntry(victim.file, victim.file.lastModified())
                        }
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
        val victims =
            state.mutex.withLock {
                ensureCacheDirectory()
                initializeState()
                selectEvictionVictimsLocked(limitBytes)
            }
        deleteEvictionVictims(victims)
    }

    private suspend fun evictOverflow() {
        val victims = state.mutex.withLock { selectEvictionVictimsLocked(limitBytes) }
        deleteEvictionVictims(victims)
    }

    private fun initializeState() {
        if (!state.initialized) {
            rootDirectory.mkdirs()
            rootDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.name.endsWith(CHUNK_SUFFIX)) {
                    state.totalBytes += file.length()
                    addAccessEntry(file, file.lastModified())
                }
                if (file.name.endsWith(PARTIAL_SUFFIX)) file.delete()
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
        val previous = state.currentEntries[file.absolutePath]
        if (previous != null && timestamp - previous.lastModified < TOUCH_INTERVAL_MS) return
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
        val trackedLength = state.currentEntries.remove(file.absolutePath)?.length ?: 0L
        if (!file.isFile) {
            state.totalBytes = (state.totalBytes - trackedLength).coerceAtLeast(0L)
            return
        }
        val length = file.length()
        if (file.delete()) state.totalBytes = (state.totalBytes - length).coerceAtLeast(0L)
        markerFile(file).delete()
        verifiedFiles.remove(file.absolutePath)
        verifiedChecksums.remove(file.absolutePath)
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
        try {
            partial.writeText("$length:$checksum", Charsets.US_ASCII)
            moveReplacing(partial, marker)
        } finally {
            partial.delete()
        }
    }

    private fun readVerificationMarker(file: File): CacheVerification? =
        runCatching {
            val parts = markerFile(file).readText(Charsets.US_ASCII).trim().split(':')
            CacheVerification(parts[0].toInt(), parts[1].toInt())
        }.getOrNull()

    private fun chunkLock(file: File): Mutex = state.chunkLocks.computeIfAbsent(file.absolutePath) { Mutex() }

    private fun isProtected(file: File): Boolean =
        state.protectedPathsByOwner.values.any { protectedPaths -> file.absolutePath in protectedPaths }

    private fun notifyEvicted(file: File) {
        state.evictionObservers.values.forEach { observer -> runCatching { observer(file) } }
    }

    private suspend fun releaseStagedBytes(bytes: Long) {
        val signals = mutableListOf<CompletableDeferred<Unit>>()
        state.mutex.withLock {
            state.stagedBytes = (state.stagedBytes - bytes).coerceAtLeast(0L)
            dispatchStagedWaitersLocked(signals)
        }
        signals.forEach { signal -> signal.complete(Unit) }
    }

    private fun dispatchStagedWaitersLocked(signals: MutableList<CompletableDeferred<Unit>>) {
        while (state.stagedWaiters.isNotEmpty()) {
            val waiter = state.stagedWaiters.first()
            if (!canReserveStagedBytes(waiter.bytes, waiter.capacityBytes)) break
            state.stagedWaiters.removeFirst()
            state.stagedBytes += waiter.bytes
            signals += waiter.signal
        }
    }

    private fun canReserveStagedBytes(bytes: Long, capacityBytes: Long): Boolean =
        state.stagedBytes + bytes <= capacityBytes

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

    private data class StagedWaiter(
        val bytes: Long,
        val capacityBytes: Long,
        val signal: CompletableDeferred<Unit>,
    )

    private class RootState(
        val mutex: Mutex = Mutex(),
        val accessQueue: PriorityQueue<AccessEntry> = PriorityQueue(compareBy(AccessEntry::lastModified)),
        val currentEntries: MutableMap<String, AccessEntry> = mutableMapOf(),
        val evictionClaims: MutableMap<String, Long> = mutableMapOf(),
        val chunkLocks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap(),
        val protectedPathsByOwner: ConcurrentHashMap<String, Set<String>> = ConcurrentHashMap(),
        val evictionObservers: ConcurrentHashMap<String, (File) -> Unit> = ConcurrentHashMap(),
        val activeTemporaryFiles: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        val stagedWaiters: ArrayDeque<StagedWaiter> = ArrayDeque(),
        var initialized: Boolean = false,
        var totalBytes: Long = 0L,
        var stagedBytes: Long = 0L,
        var accessSequence: Long = 0L,
    )

    internal companion object {
        private const val CHUNK_SUFFIX = ".chunk"
        private const val PARTIAL_SUFFIX = ".part"
        private const val SPOOL_PREFIX = "encrypted-"
        private const val SPOOL_SUFFIX = ".spool.part"
        private const val MARKER_SUFFIX = ".verified"
        // Keep one eviction-band of headroom for older videos and chunk-boundary
        // overshoot. With the default 512 MiB cache this leaves 409.6 MiB, enough
        // for the playback window of a high-bitrate source without restoring the old 256 MiB cap.
        private const val CHECKSUM_BUFFER_SIZE = 64 * 1024
        private const val MIN_QUEUE_COMPACT_SIZE = 64
        private const val TOUCH_INTERVAL_MS = 2_000L
        private const val STAGING_RESERVE_PERCENT = 20L
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
            val files = root.walkTopDown().filter(File::isFile).toList()
            files.forEach { file ->
                val path = file.absolutePath
                if (state.protectedPathsByOwner.values.any { path in it } || state.activeTemporaryFiles.containsKey(path)) {
                    return@forEach
                }
                val lock = state.chunkLocks.computeIfAbsent(path) { Mutex() }
                var deleted = false
                var length = 0L
                lock.withLock {
                    if (state.protectedPathsByOwner.values.any { path in it } || state.activeTemporaryFiles.containsKey(path)) {
                        return@withLock
                    }
                    length = file.length()
                    deleted = !file.isFile || file.delete()
                    if (deleted) {
                        state.mutex.withLock {
                            state.currentEntries.remove(path)?.let { entry ->
                                state.totalBytes = (state.totalBytes - entry.length).coerceAtLeast(0L)
                            }
                            state.evictionClaims.remove(path)
                        }
                    }
                }
                if (deleted) {
                    clearedBytes += length
                    state.evictionObservers.values.forEach { observer -> runCatching { observer(file) } }
                }
            }
            root.walkBottomUp().filter { it.isDirectory && it != root }.forEach(File::delete)
            return clearedBytes
        }

        private fun streamStagingCapacityBytes(
            limitBytes: Long,
            maximumEncryptedChunkBytes: Long,
            minimumStagingCapacityBytes: Long,
        ): Long {
            return maxOf(
                limitBytes * STAGING_RESERVE_PERCENT / 100L,
                maximumEncryptedChunkBytes.coerceAtLeast(0L),
                minimumStagingCapacityBytes.coerceAtLeast(0L),
            )
                .coerceIn(1L, limitBytes.coerceAtLeast(1L))
        }
    }
}

internal class SteamEncryptedChunkReservation(
    val length: Int,
    private val onRelease: suspend (Int) -> Unit,
) {
    private val released = AtomicBoolean(false)

    suspend fun release() {
        if (released.compareAndSet(false, true)) onRelease(length)
    }
}

internal class SteamEncryptedChunkSpool(
    val file: File,
    val length: Int,
    private val reservation: SteamEncryptedChunkReservation,
    private val onDeleted: () -> Unit,
) {
    suspend fun delete() {
        try {
            file.delete()
        } finally {
            onDeleted()
            reservation.release()
        }
    }
}
