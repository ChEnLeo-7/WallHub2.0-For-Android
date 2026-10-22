package com.wallhub.android.feature.votes

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Raw help-site flags, not an inferred current vote or app-specific membership. */
data class VoteRawRow(val fileId: Long, val votedUp: Boolean, val voteCancelled: Boolean)

data class VotesReadPage(val accountId: String, val rows: List<VoteRawRow>, val hasNext: Boolean)

enum class VotesReaderError {
    ACCOUNT_UNVERIFIED, ACCOUNT_MISMATCH, INVALID_PAGE, LIMIT_EXCEEDED,
    DUPLICATE_ROWS, NO_PROGRESS, REPEATED_CURSOR, UNTRUSTED_PAGE, NETWORK, TLS, BUSY,
}

internal class VotesReadException(val reason: VotesReaderError) : Exception(reason.name)

internal fun failVotes(reason: VotesReaderError): Nothing = throw VotesReadException(reason)

internal object VotesPageParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_PAGE_ROWS = 200
    const val MAX_CURSOR_CHARS = 8192

    /** Input is a minimized JSON projection of one trusted top-level document, never HTML. */
    fun parse(json: String, expectedSteamId: String): ParsedVotesPage {
        if (json.length > MAX_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            failVotes(VotesReaderError.LIMIT_EXCEEDED)
        }
        try {
            val root = JSONObject(json)
            if (root.has("error")) {
                val reason = runCatching { VotesReaderError.valueOf(root.getString("error")) }
                    .getOrDefault(VotesReaderError.INVALID_PAGE)
                failVotes(reason)
            }
            if (!expectedSteamId.matches(Regex("[1-9][0-9]{16}")) || expectedSteamId.toLongOrNull() == null) {
                failVotes(VotesReaderError.ACCOUNT_UNVERIFIED)
            }
            val accounts = root.getJSONArray("accounts")
            if (accounts.length() == 0 || accounts.length() > 8) failVotes(VotesReaderError.ACCOUNT_UNVERIFIED)
            val ids = (0 until accounts.length()).map { accounts.getString(it) }
            if (ids.any { !it.matches(Regex("[1-9][0-9]{16}")) }) failVotes(VotesReaderError.ACCOUNT_UNVERIFIED)
            if (ids.any { it != expectedSteamId }) failVotes(VotesReaderError.ACCOUNT_MISMATCH)
            val source = root.getJSONArray("rows")
            if (source.length() > MAX_PAGE_ROWS) failVotes(VotesReaderError.LIMIT_EXCEEDED)
            val rows = (0 until source.length()).map { index ->
                val row = source.getJSONArray(index)
                if (row.length() != 3) failVotes(VotesReaderError.INVALID_PAGE)
                val id = strictString(row, 0)
                if (!id.matches(Regex("[1-9][0-9]{0,18}"))) failVotes(VotesReaderError.INVALID_PAGE)
                val number = id.toLongOrNull()?.takeIf { it > 0 } ?: failVotes(VotesReaderError.INVALID_PAGE)
                VoteRawRow(number, flag(strictString(row, 1)), flag(strictString(row, 2)))
            }
            if (rows.map { it.fileId }.toSet().size != rows.size) failVotes(VotesReaderError.DUPLICATE_ROWS)
            val cursor = if (root.isNull("cursor")) {
                null
            } else {
                root.get("cursor") as? String ?: failVotes(VotesReaderError.INVALID_PAGE)
            }
            if (cursor != null && cursor.length > MAX_CURSOR_CHARS) failVotes(VotesReaderError.LIMIT_EXCEEDED)
            return ParsedVotesPage(expectedSteamId, rows, cursor?.takeIf { it.isNotEmpty() })
        } catch (failure: VotesReadException) {
            throw failure
        } catch (_: Exception) {
            failVotes(VotesReaderError.INVALID_PAGE)
        }
    }

    private fun strictString(array: JSONArray, index: Int): String =
        array.get(index) as? String ?: failVotes(VotesReaderError.INVALID_PAGE)

    private fun flag(value: String): Boolean = when (value) {
        "0" -> false
        "1" -> true
        else -> failVotes(VotesReaderError.INVALID_PAGE)
    }
}

internal class ParsedVotesPage(val accountId: String, val rows: List<VoteRawRow>, val cursor: String?)

/** Memory-only state, discarded with the reader/account. No automatic pagination or durable cursors. */
internal class VotesPagingGate(private val expectedSteamId: String) {
    private val seenIds = mutableSetOf<Long>()
    private val seenCursors = mutableSetOf<String>()
    private var pages = 0
    var cursor: String? = null
        private set
    val started: Boolean get() = pages > 0

    fun accept(json: String): VotesReadPage {
        if (pages >= 100) failVotes(VotesReaderError.LIMIT_EXCEEDED)
        val page = VotesPageParser.parse(json, expectedSteamId)
        if (seenIds.size + page.rows.size > 10000) failVotes(VotesReaderError.LIMIT_EXCEEDED)
        if (page.rows.isNotEmpty() && page.rows.all { it.fileId in seenIds }) failVotes(VotesReaderError.NO_PROGRESS)
        if (page.rows.any { it.fileId in seenIds }) failVotes(VotesReaderError.DUPLICATE_ROWS)
        val next = page.cursor.takeIf { page.rows.isNotEmpty() }
        if (next != null && next in seenCursors) failVotes(VotesReaderError.REPEATED_CURSOR)
        pages++
        seenIds += page.rows.map { it.fileId }
        next?.let(seenCursors::add)
        cursor = next
        return VotesReadPage(page.accountId, page.rows, next != null)
    }
}

internal object VotesWebPolicy {
    const val START_URL = "https://help.steampowered.com/zh-cn/accountdata/MyUGCVotes"
    private val navigationHosts = setOf(
        "help.steampowered.com", "login.steampowered.com", "store.steampowered.com", "steamcommunity.com",
    )
    private val assetHosts = navigationHosts + setOf(
        "api.steampowered.com", "community.fastly.steamstatic.com", "store.fastly.steamstatic.com",
        "shared.fastly.steamstatic.com", "community.akamai.steamstatic.com", "store.akamai.steamstatic.com",
        "shared.akamai.steamstatic.com", "cdn.fastly.steamstatic.com",
    )

    fun allowed(url: String, navigation: Boolean): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.host in (if (navigation) navigationHosts else assetHosts)
    }.getOrDefault(false)

    fun isVotesDocument(url: String): Boolean = allowed(url, true) && runCatching {
        val uri = URI(url)
        uri.host == "help.steampowered.com" &&
            uri.path.matches(Regex("/[a-z]{2}(?:-[a-z]{2,4})?/accountdata/MyUGCVotes/?")) &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
}
