package com.wallhub.android.feature.votes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VotesPageParserTest {
    private val account = "76561198000000001"

    @Test
    fun `flags remain raw and no vote classification or timestamp is inferred`() {
        val page = VotesPageParser.parse(page(listOf(row("1959836574", "1", "0"), row("3050040845", "1", "1"))), account)
        assertEquals(listOf(1959836574L, 3050040845L), page.rows.map { it.fileId })
        assertEquals(VoteRawRow(1959836574L, true, false), page.rows[0])
        assertEquals(VoteRawRow(3050040845L, true, true), page.rows[1])
    }

    @Test
    fun `IDs are canonical positive checked Long decimals`() {
        for (id in listOf("0", "-1", "+1", "01", "1e4", "1.0", "9223372036854775808", "18446744073709551615")) {
            rejects(VotesReaderError.INVALID_PAGE) { VotesPageParser.parse(page(listOf(row(id))), account) }
        }
        assertEquals(Long.MAX_VALUE, VotesPageParser.parse(page(listOf(row(Long.MAX_VALUE.toString()))), account).rows.single().fileId)
    }

    @Test
    fun `flags accept only sanitized literal zero and one strings`() {
        for (flag in listOf("", "yes", "true", "2", " 1", "01")) {
            rejects(VotesReaderError.INVALID_PAGE) { VotesPageParser.parse(page(listOf(row("123", flag))), account) }
        }
        rejects(VotesReaderError.INVALID_PAGE) {
            VotesPageParser.parse(page(listOf(JSONArray().put("123").put(1).put("0"))), account)
        }
    }

    @Test
    fun `duplicates and oversized pages fail without silently dropping rows`() {
        rejects(VotesReaderError.DUPLICATE_ROWS) {
            VotesPageParser.parse(page(listOf(row("123"), row("123"))), account)
        }
        rejects(VotesReaderError.LIMIT_EXCEEDED) {
            VotesPageParser.parse(page((1..201).map { row(it.toString()) }), account)
        }
        rejects(VotesReaderError.LIMIT_EXCEEDED) {
            VotesPageParser.parse(" ".repeat(VotesPageParser.MAX_BYTES + 1), account)
        }
        rejects(VotesReaderError.LIMIT_EXCEEDED) {
            VotesPageParser.parse(page(emptyList(), "x".repeat(8193)), account)
        }
    }

    @Test
    fun `missing identity and conflicting account proof fail closed`() {
        rejects(VotesReaderError.ACCOUNT_UNVERIFIED) {
            VotesPageParser.parse(page(emptyList(), accounts = emptyList()), account)
        }
        rejects(VotesReaderError.ACCOUNT_MISMATCH) {
            VotesPageParser.parse(page(emptyList(), accounts = listOf(account, "76561198000000002")), account)
        }
        rejects(VotesReaderError.ACCOUNT_UNVERIFIED) {
            VotesPageParser.parse(page(emptyList()), "not a CM SteamID")
        }
    }

    @Test
    fun `cursor is opaque exact and missing cursor or empty rows terminate`() {
        val cursor = " a+/=&% 游标 "
        val gate = VotesPagingGate(account)
        assertTrue(gate.accept(page(listOf(row("1")), cursor)).hasNext)
        assertEquals(cursor, gate.cursor)
        assertFalse(gate.accept(page(listOf(row("2")))).hasNext)
        assertEquals(null, gate.cursor)
        val emptyGate = VotesPagingGate(account)
        assertFalse(emptyGate.accept(page(emptyList(), cursor)).hasNext)
        assertEquals(null, emptyGate.cursor)
    }

    @Test
    fun `repeat cursor no progress and overlap are distinct and do not commit failed page`() {
        val gate = VotesPagingGate(account)
        gate.accept(page(listOf(row("1")), "first"))
        rejects(VotesReaderError.REPEATED_CURSOR) { gate.accept(page(listOf(row("2")), "first")) }
        rejects(VotesReaderError.NO_PROGRESS) { gate.accept(page(listOf(row("1")), "second")) }
        rejects(VotesReaderError.DUPLICATE_ROWS) { gate.accept(page(listOf(row("1"), row("2")), "second")) }
        assertEquals("first", gate.cursor)
        assertTrue(gate.accept(page(listOf(row("2")), "second")).hasNext)
    }

    @Test
    fun `page and aggregate row caps are enforced`() {
        val gate = VotesPagingGate(account)
        repeat(100) { n -> gate.accept(page(listOf(row((n + 1).toString())), "cursor-$n")) }
        rejects(VotesReaderError.LIMIT_EXCEEDED) { gate.accept(page(listOf(row("101")))) }
        val rowGate = VotesPagingGate(account)
        repeat(50) { n ->
            rowGate.accept(page((1..200).map { row((n * 200 + it).toString()) }, "cursor-$n"))
        }
        rejects(VotesReaderError.LIMIT_EXCEEDED) { rowGate.accept(page(listOf(row("10001")))) }
    }

    @Test
    fun `exact HTTPS official navigation only and extraction only on votes document`() {
        assertTrue(VotesWebPolicy.allowed(VotesWebPolicy.START_URL, true))
        assertTrue(VotesWebPolicy.allowed("https://login.steampowered.com/jwt/ajaxrefresh", true))
        for (url in listOf(
            "http://help.steampowered.com/", "https://help.steampowered.com.evil.test/",
            "https://help.steampowered.com@evil.test/", "https://evil.test@help.steampowered.com/",
            "https://help.steampowered.com:444/", "intent://login", "file:///etc/passwd", "javascript:alert(1)",
        )) assertFalse(url, VotesWebPolicy.allowed(url, true))
        assertTrue(VotesWebPolicy.isVotesDocument(VotesWebPolicy.START_URL))
        assertFalse(VotesWebPolicy.isVotesDocument("https://help.steampowered.com/zh-cn/login/"))
        assertFalse(VotesWebPolicy.isVotesDocument(VotesWebPolicy.START_URL + "?spoof=1"))
        assertFalse(VotesWebPolicy.isVotesDocument("https://steamcommunity.com/zh-cn/accountdata/MyUGCVotes"))
    }

    private fun row(id: String, up: String = "1", cancelled: String = "0") =
        JSONArray().put(id).put(up).put(cancelled)

    private fun page(rows: List<JSONArray>, cursor: String? = null, accounts: List<String> = listOf(account)): String =
        JSONObject().put("accounts", JSONArray(accounts)).put("rows", JSONArray(rows))
            .put("cursor", cursor ?: JSONObject.NULL).toString()

    private fun rejects(reason: VotesReaderError, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $reason")
        } catch (failure: VotesReadException) {
            assertEquals(reason, failure.reason)
        }
    }
}
