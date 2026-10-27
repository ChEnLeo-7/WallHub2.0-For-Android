package com.wallhub.android.data.steam

import android.content.Context
import android.util.AtomicFile
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/** A durable account-page key. Session generation is intentionally absent. */
internal data class AccountWorkshopPageSnapshotKey(
    val accountId: Long,
    val query: AccountWorkshopQuery,
)

/** A persisted page plus the wall-clock interval in which its owner considers it fresh. */
internal data class AccountWorkshopPageSnapshot(
    val page: WorkshopPage,
    val storedAtMillis: Long,
    val freshUntilMillis: Long,
) {
    fun isFreshAt(nowMillis: Long): Boolean =
        nowMillis >= storedAtMillis && nowMillis < freshUntilMillis
}

/**
 * Account-isolated, query-exact durable storage for authenticated Workshop collection pages.
 *
 * Implementations treat every storage or decoding failure as a cache miss. The persisted value has no session
 * generation, refresh token, password, cookie, transport grant, or other credential field.
 */
internal interface AccountWorkshopPageSnapshotStore {
    fun read(key: AccountWorkshopPageSnapshotKey): AccountWorkshopPageSnapshot?

    fun write(
        key: AccountWorkshopPageSnapshotKey,
        snapshot: AccountWorkshopPageSnapshot,
    ): Boolean

    fun updateCollection(
        accountId: Long,
        collection: AccountWorkshopCollection,
        itemId: Long,
        present: Boolean,
        summary: WorkshopSummary?,
    ): Boolean

    fun removeAccount(accountId: Long): Boolean
}

internal data class AccountWorkshopPageSnapshotLimits(
    val maxFiles: Int = 48,
    val maxItemsPerPage: Int = MAX_ACCOUNT_WORKSHOP_PAGE_SIZE,
    val maxTagsPerItem: Int = 64,
    val maxQueryValues: Int = 64,
    val maxStringChars: Int = 4_096,
    val maxStringBytes: Int = 16 * 1_024,
    val maxFileBytes: Int = 256 * 1_024,
    val maxTotalBytes: Long = 4L * 1_024L * 1_024L,
    val maxFreshnessMillis: Long = 24L * 60L * 60L * 1_000L,
) {
    init {
        require(maxFiles > 0)
        require(maxItemsPerPage > 0)
        require(maxTagsPerItem > 0)
        require(maxQueryValues > 0)
        require(maxStringChars > 0)
        require(maxStringBytes > 0)
        require(maxFileBytes > 0)
        require(maxTotalBytes >= maxFileBytes)
        require(maxFreshnessMillis > 0L)
    }
}

/** Minimal file seam so the production store can be exercised with deterministic fake storage. */
internal interface AccountWorkshopSnapshotFiles {
    data class Entry(
        val name: String,
        val byteCount: Long,
        val updatedAtMillis: Long,
    )

    fun read(
        name: String,
        maxBytes: Int,
    ): ByteArray?

    fun write(
        name: String,
        bytes: ByteArray,
    ): Boolean

    fun entries(): List<Entry>

    fun delete(name: String): Boolean
}

/** AtomicFile-backed storage rooted exclusively in Context.noBackupFilesDir. */
internal class AtomicAccountWorkshopSnapshotFiles(
    context: Context,
) : AccountWorkshopSnapshotFiles {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    override fun read(
        name: String,
        maxBytes: Int,
    ): ByteArray? =
        runCatching {
            val file = fileFor(name) ?: return null
            val atomicFile = AtomicFile(file)
            atomicFile.openRead().use { input ->
                val expectedLength = file.length()
                if (expectedLength < 0L || expectedLength > maxBytes.toLong()) return null
                val output = ByteArrayOutputStream(expectedLength.toInt().coerceAtLeast(32))
                val buffer = ByteArray(8 * 1_024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull()

    override fun write(
        name: String,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            val file = fileFor(name) ?: return false
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return false
            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
                true
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }.getOrDefault(false)

    override fun entries(): List<AccountWorkshopSnapshotFiles.Entry> =
        runCatching {
            directory
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isFile)
                .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
                .map {
                    AccountWorkshopSnapshotFiles.Entry(
                        name = it.name,
                        byteCount = it.length().coerceAtLeast(0L),
                        updatedAtMillis = it.lastModified().coerceAtLeast(0L),
                    )
                }.toList()
        }.getOrDefault(emptyList())

    override fun delete(name: String): Boolean =
        runCatching {
            val file = fileFor(name) ?: return false
            !file.exists() || file.delete()
        }.getOrDefault(false)

    private fun fileFor(name: String): File? {
        if (!SAFE_FILE_NAME.matches(name)) return null
        val file = File(directory, name)
        return file.takeIf { it.parentFile == directory }
    }

    private companion object {
        const val DIRECTORY_NAME = "steam-account-workshop-pages"
        const val FILE_PREFIX = "account-page-"
        const val FILE_SUFFIX = ".bin"
        val SAFE_FILE_NAME = Regex("^account-page-[a-f0-9]{64}-[a-f0-9]{64}\\.bin$")
    }
}

/**
 * Bounded persistent implementation. It owns only snapshots; callers retain network/session policy and decide
 * whether a decoded snapshot's freshness interval is acceptable for a read.
 */
internal class BoundedPersistentAccountWorkshopPageSnapshotStore(
    private val files: AccountWorkshopSnapshotFiles,
    private val limits: AccountWorkshopPageSnapshotLimits = AccountWorkshopPageSnapshotLimits(),
) : AccountWorkshopPageSnapshotStore {
    constructor(
        context: Context,
        limits: AccountWorkshopPageSnapshotLimits = AccountWorkshopPageSnapshotLimits(),
    ) : this(AtomicAccountWorkshopSnapshotFiles(context), limits)

    override fun read(key: AccountWorkshopPageSnapshotKey): AccountWorkshopPageSnapshot? =
        synchronized(STORE_LOCK) {
            if (!validKey(key)) return@synchronized null
            val name = runCatching { fileName(key) }.getOrNull() ?: return@synchronized null
            val bytes = runCatching { files.read(name, limits.maxFileBytes) }.getOrNull() ?: return@synchronized null
            val decoded = runCatching { decode(bytes, key) }.getOrNull()
            if (decoded == null) runCatching { files.delete(name) }
            decoded
        }

    override fun write(
        key: AccountWorkshopPageSnapshotKey,
        snapshot: AccountWorkshopPageSnapshot,
    ): Boolean =
        synchronized(STORE_LOCK) {
            if (!validKey(key) || snapshot.page.page != key.query.page || !validSnapshot(snapshot)) {
                return@synchronized false
            }
            val bytes = runCatching { encode(key, snapshot) }.getOrNull() ?: return@synchronized false
            if (bytes.size > limits.maxFileBytes || bytes.size.toLong() > limits.maxTotalBytes) {
                return@synchronized false
            }
            val name = fileName(key)
            if (!makeRoom(name, bytes.size.toLong())) return@synchronized false
            runCatching { files.write(name, bytes) }.getOrDefault(false)
        }

    override fun updateCollection(
        accountId: Long,
        collection: AccountWorkshopCollection,
        itemId: Long,
        present: Boolean,
        summary: WorkshopSummary?,
    ): Boolean =
        synchronized(STORE_LOCK) {
            if (accountId <= 0L || itemId <= 0L || (summary != null && summary.id != itemId)) return@synchronized false
            val entries = runCatching { files.entries() }.getOrElse { return@synchronized false }
                .filter { it.name.startsWith(accountFilePrefix(accountId)) }
            var success = true
            entries.forEach { entry ->
                val bytes = runCatching { files.read(entry.name, limits.maxFileBytes) }.getOrNull()
                val decoded = bytes?.let { runCatching { decodeAny(it) }.getOrNull() }
                if (decoded == null) {
                    success = runCatching { files.delete(entry.name) }.getOrDefault(false) && success
                    return@forEach
                }
                val (key, snapshot) = decoded
                if (key.accountId != accountId || key.query.collection != collection) return@forEach
                if (key.query.resolveTotalCount && key.query.pageSize == 1) {
                    success = runCatching { files.delete(entry.name) }.getOrDefault(false) && success
                    return@forEach
                }
                val existing = snapshot.page.items.indexOfFirst { it.id == itemId }
                val patchedItems =
                    when {
                        !present && existing >= 0 -> snapshot.page.items.filterNot { it.id == itemId }
                        present && existing >= 0 && summary != null ->
                            snapshot.page.items.toMutableList().also { it[existing] = summary }
                        present && existing < 0 && summary != null && key.query.page == 1 && key.query.matchesAccountCollectionItem(summary) ->
                            (listOf(summary) + snapshot.page.items).take(key.query.pageSize)
                        else -> snapshot.page.items
                    }
                val patched =
                    snapshot.copy(
                        page =
                            snapshot.page.copy(
                                items = patchedItems,
                                totalCount = null,
                                totalPages = null,
                            ),
                        // Keep the rows available but force stale-while-revalidate after membership changes.
                        freshUntilMillis = snapshot.storedAtMillis,
                    )
                val encoded = runCatching { encode(key, patched) }.getOrNull()
                success = encoded != null && encoded.size <= limits.maxFileBytes && files.write(entry.name, encoded) && success
            }
            success
        }

    override fun removeAccount(accountId: Long): Boolean =
        synchronized(STORE_LOCK) {
            if (accountId <= 0L) return@synchronized false
            val prefix = accountFilePrefix(accountId)
            val accountEntries = runCatching { files.entries() }.getOrElse { return@synchronized false }
                .filter { it.name.startsWith(prefix) }
            accountEntries.fold(true) { success, entry ->
                runCatching { files.delete(entry.name) }.getOrDefault(false) && success
            }
        }

    private fun makeRoom(
        targetName: String,
        targetBytes: Long,
    ): Boolean {
        val initial = runCatching { files.entries() }.getOrElse { return false }
        val retained = initial.filterNot { it.name == targetName }.toMutableList()
        var retainedBytes = boundedTotalBytes(retained)
        val oldestFirst =
            retained.sortedWith(
                compareBy(
                    AccountWorkshopSnapshotFiles.Entry::updatedAtMillis,
                    AccountWorkshopSnapshotFiles.Entry::name,
                ),
            )
        var index = 0
        while (
            retained.size + 1 > limits.maxFiles ||
                retainedBytes > limits.maxTotalBytes - targetBytes
        ) {
            val victim = oldestFirst.getOrNull(index++) ?: return false
            if (!runCatching { files.delete(victim.name) }.getOrDefault(false)) return false
            if (retained.remove(victim)) retainedBytes -= victim.byteCount.coerceAtLeast(0L)
        }
        val verified = runCatching { files.entries() }.getOrElse { return false }.filterNot { it.name == targetName }
        return verified.size + 1 <= limits.maxFiles &&
            boundedTotalBytes(verified) <= limits.maxTotalBytes - targetBytes
    }

    private fun boundedTotalBytes(entries: List<AccountWorkshopSnapshotFiles.Entry>): Long {
        var total = 0L
        entries.forEach { entry ->
            val byteCount = entry.byteCount.coerceAtLeast(0L)
            if (total > Long.MAX_VALUE - byteCount) return Long.MAX_VALUE
            total += byteCount
        }
        return total
    }

    private fun validKey(key: AccountWorkshopPageSnapshotKey): Boolean =
        key.accountId > 0L && validQuery(key.query)

    private fun validQuery(query: AccountWorkshopQuery): Boolean =
        query.page > 0 &&
            query.pageSize in 1..MAX_ACCOUNT_WORKSHOP_PAGE_SIZE &&
            validString(query.searchText) &&
            validEnumSet(query.types) &&
            validStringSet(query.tags) &&
            validEnumSet(query.ratings) &&
            validStringSet(query.genres) &&
            validStringSet(query.officialTags) &&
            validStringSet(query.excludedOfficialTags) &&
            validStringSet(query.resolutions)

    private fun validSnapshot(snapshot: AccountWorkshopPageSnapshot): Boolean {
        val page = snapshot.page
        val freshness = snapshot.freshUntilMillis - snapshot.storedAtMillis
        return snapshot.storedAtMillis >= 0L &&
            snapshot.freshUntilMillis >= snapshot.storedAtMillis &&
            freshness in 0L..limits.maxFreshnessMillis &&
            page.page > 0 &&
            page.items.size <= limits.maxItemsPerPage &&
            page.totalCount?.let { it >= 0 } != false &&
            page.totalPages?.let { it >= 0 } != false &&
            page.items.all(::validSummary)
    }

    private fun validSummary(summary: WorkshopSummary): Boolean =
        summary.id > 0L &&
            validString(summary.title) &&
            validString(summary.author) &&
            validNullableString(summary.creatorId) &&
            validNullableString(summary.authorAvatarUrl) &&
            validNullableString(summary.previewUrl) &&
            summary.tags.size <= limits.maxTagsPerItem &&
            summary.tags.all(::validString) &&
            validNullableCounter(summary.subscriptions) &&
            validNullableCounter(summary.favorites) &&
            validNullableCounter(summary.views) &&
            validNullableCounter(summary.fileSizeBytes)

    private fun validNullableCounter(value: Long?): Boolean = value == null || value >= 0L

    private fun validNullableString(value: String?): Boolean = value == null || validString(value)

    private fun validString(value: String): Boolean =
        value.length <= limits.maxStringChars && value.toByteArray(Charsets.UTF_8).size <= limits.maxStringBytes

    private fun validStringSet(values: Set<String>): Boolean =
        values.size <= limits.maxQueryValues && values.all(::validString)

    private fun <T : Enum<T>> validEnumSet(values: Set<T>): Boolean = values.size <= limits.maxQueryValues

    private fun fileName(key: AccountWorkshopPageSnapshotKey): String =
        "${accountFilePrefix(key.accountId)}${sha256(encodeQuery(key.query))}.bin"

    private fun accountFilePrefix(accountId: Long): String =
        "account-page-${sha256(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(accountId).array())}-"

    private fun encode(
        key: AccountWorkshopPageSnapshotKey,
        snapshot: AccountWorkshopPageSnapshot,
    ): ByteArray =
        boundedBytes {
            writeInt(FILE_MAGIC)
            writeInt(FILE_VERSION)
            writeLong(key.accountId)
            writeQuery(key.query)
            writeLong(snapshot.storedAtMillis)
            writeLong(snapshot.freshUntilMillis)
            writePage(snapshot.page)
        }

    private fun encodeQuery(query: AccountWorkshopQuery): ByteArray =
        boundedBytes { writeQuery(query) }

    private fun DataOutputStream.writeQuery(query: AccountWorkshopQuery) {
        writeEnum(query.collection)
        writeInt(query.page)
        writeInt(query.pageSize)
        writeString(query.searchText)
        writeBoolean(query.exactPhrase)
        writeBoolean(query.resolveTotalCount)
        writeNullableEnum(query.type)
        writeEnumSet(query.types)
        writeStringSet(query.tags)
        writeEnumSet(query.ratings)
        writeStringSet(query.genres)
        writeStringSet(query.officialTags)
        writeStringSet(query.excludedOfficialTags)
        writeStringSet(query.resolutions)
    }

    private fun DataOutputStream.writePage(page: WorkshopPage) {
        writeInt(page.page)
        writeBoolean(page.hasNextPage)
        writeNullableInt(page.totalCount)
        writeNullableInt(page.totalPages)
        writeInt(page.items.size)
        page.items.forEach { summary -> writeSummary(summary) }
    }

    private fun DataOutputStream.writeSummary(summary: WorkshopSummary) {
        writeLong(summary.id)
        writeString(summary.title)
        writeString(summary.author)
        writeNullableString(summary.creatorId)
        writeNullableString(summary.authorAvatarUrl)
        writeNullableString(summary.previewUrl)
        writeEnum(summary.type)
        writeInt(summary.tags.size)
        summary.tags.forEach { tag -> writeString(tag) }
        writeNullableLong(summary.subscriptions)
        writeNullableLong(summary.favorites)
        writeNullableLong(summary.views)
        writeNullableLong(summary.fileSizeBytes)
        writeEnum(summary.subscriptionState)
        writeEnum(summary.favoriteState)
        writeBoolean(summary.isTitlePlaceholder)
        writeEnum(summary.authorPlaceholder)
    }

    private fun decode(
        bytes: ByteArray,
        expectedKey: AccountWorkshopPageSnapshotKey,
    ): AccountWorkshopPageSnapshot {
        val (decodedKey, snapshot) = decodeAny(bytes)
        require(decodedKey == expectedKey)
        return snapshot
    }

    private fun decodeAny(bytes: ByteArray): Pair<AccountWorkshopPageSnapshotKey, AccountWorkshopPageSnapshot> {
        require(bytes.size <= limits.maxFileBytes)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == FILE_MAGIC)
        require(input.readInt() == FILE_VERSION)
        val decodedKey = AccountWorkshopPageSnapshotKey(input.readLong(), input.readQuery())
        require(validKey(decodedKey))
        val storedAtMillis = input.readLong()
        val freshUntilMillis = input.readLong()
        val snapshot =
            AccountWorkshopPageSnapshot(
                page = input.readPage(),
                storedAtMillis = storedAtMillis,
                freshUntilMillis = freshUntilMillis,
            )
        require(input.read() == -1)
        require(validSnapshot(snapshot))
        return decodedKey to snapshot
    }

    private fun DataInputStream.readQuery(): AccountWorkshopQuery =
        AccountWorkshopQuery(
            collection = readEnum<AccountWorkshopCollection>(),
            page = readInt(),
            pageSize = readInt(),
            searchText = readString(),
            exactPhrase = readBoolean(),
            resolveTotalCount = readBoolean(),
            type = readNullableEnum<WorkshopType>(),
            types = readEnumSet<WorkshopType>(),
            tags = readStringSet(),
            ratings = readEnumSet<WorkshopRating>(),
            genres = readStringSet(),
            officialTags = readStringSet(),
            excludedOfficialTags = readStringSet(),
            resolutions = readStringSet(),
        ).also { require(validQuery(it)) }

    private fun DataInputStream.readPage(): WorkshopPage {
        val pageNumber = readInt()
        val hasNextPage = readBoolean()
        val totalCount = readNullableInt()
        val totalPages = readNullableInt()
        val itemCount = readBoundedCount(limits.maxItemsPerPage)
        return WorkshopPage(
            items = List(itemCount) { readSummary() },
            page = pageNumber,
            hasNextPage = hasNextPage,
            totalCount = totalCount,
            totalPages = totalPages,
        )
    }

    private fun DataInputStream.readSummary(): WorkshopSummary {
        val id = readLong()
        val title = readString()
        val author = readString()
        val creatorId = readNullableString()
        val authorAvatarUrl = readNullableString()
        val previewUrl = readNullableString()
        val type = readEnum<WorkshopType>()
        val tags = List(readBoundedCount(limits.maxTagsPerItem)) { readString() }
        return WorkshopSummary(
            id = id,
            title = title,
            author = author,
            creatorId = creatorId,
            authorAvatarUrl = authorAvatarUrl,
            previewUrl = previewUrl,
            type = type,
            tags = tags,
            subscriptions = readNullableLong(),
            favorites = readNullableLong(),
            views = readNullableLong(),
            fileSizeBytes = readNullableLong(),
            subscriptionState = readEnum<SubscriptionState>(),
            favoriteState = readEnum<FavoriteState>(),
            isTitlePlaceholder = readBoolean(),
            authorPlaceholder = readEnum<WorkshopAuthorPlaceholder>(),
        ).also { require(validSummary(it)) }
    }

    private fun boundedBytes(block: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(BoundedOutputStream(buffer, limits.maxFileBytes)).use { output -> output.block() }
        return buffer.toByteArray()
    }

    private fun DataOutputStream.writeString(value: String) {
        require(validString(value))
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readString(): String {
        val byteCount = readBoundedCount(limits.maxStringBytes)
        val bytes = ByteArray(byteCount)
        readFully(bytes)
        val value =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        require(value.length <= limits.maxStringChars)
        return value
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeStringSet(values: Set<String>) {
        require(validStringSet(values))
        val ordered = values.sorted()
        writeInt(ordered.size)
        ordered.forEach { value -> writeString(value) }
    }

    private fun DataInputStream.readStringSet(): Set<String> {
        val values = List(readBoundedCount(limits.maxQueryValues)) { readString() }
        require(values.distinct().size == values.size)
        return values.toSet()
    }

    private fun <T : Enum<T>> DataOutputStream.writeEnum(value: T) = writeString(value.name)

    private fun <T : Enum<T>> DataOutputStream.writeNullableEnum(value: T?) {
        writeBoolean(value != null)
        if (value != null) writeEnum(value)
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val name = readString()
        return enumValues<T>().firstOrNull { it.name == name } ?: throw IOException("Unknown enum value")
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) readEnum<T>() else null

    private fun <T : Enum<T>> DataOutputStream.writeEnumSet(values: Set<T>) {
        require(validEnumSet(values))
        val ordered = values.sortedBy { it.name }
        writeInt(ordered.size)
        ordered.forEach { value -> writeEnum(value) }
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnumSet(): Set<T> {
        val values = List(readBoundedCount(limits.maxQueryValues)) { readEnum<T>() }
        require(values.distinct().size == values.size)
        return values.toSet()
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? =
        if (readBoolean()) readInt() else null

    private fun DataInputStream.readBoundedCount(maximum: Int): Int {
        val value = readInt()
        if (value !in 0..maximum) throw EOFException("Invalid bounded count")
        return value
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private companion object {
        val STORE_LOCK = Any()
        const val FILE_MAGIC = 0x57485053
        const val FILE_VERSION = 1
    }
}

private class BoundedOutputStream(
    output: OutputStream,
    private val maxBytes: Int,
) : FilterOutputStream(output) {
    private var written = 0

    override fun write(value: Int) {
        ensureCapacity(1)
        out.write(value)
        written += 1
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        ensureCapacity(length)
        out.write(buffer, offset, length)
        written += length
    }

    private fun ensureCapacity(additionalBytes: Int) {
        if (additionalBytes < 0 || written > maxBytes - additionalBytes) {
            throw IOException("Account Workshop snapshot exceeds byte limit")
        }
    }
}
