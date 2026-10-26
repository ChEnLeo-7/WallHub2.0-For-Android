package com.wallhub.android.data.discover

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiscoverCuratorNoSniTransportTest {
    @Test
    fun `target policy allows only the two fixed HTTPS API authorities`() {
        assertEquals(
            CuratorTarget("www.wallpaperengineapi.com", "/api/explore/v1"),
            requireCuratorTarget("https://www.wallpaperengineapi.com/api/explore/v1"),
        )
        assertEquals(
            CuratorTarget("wallpaperengineapi.com", "/api/explore/v1"),
            requireCuratorTarget("https://wallpaperengineapi.com/api/explore/v1"),
        )

        listOf(
            "http://www.wallpaperengineapi.com/api/explore/v1",
            "https://www.wallpaperengineapi.com:444/api/explore/v1",
            "https://www.wallpaperengineapi.com/api/explore/v2",
            "https://www.wallpaperengineapi.com/api/explore/v1?next=https://example.com",
            "https://example.com/api/explore/v1",
            "https://user@www.wallpaperengineapi.com/api/explore/v1",
        ).forEach { url -> assertFailsWith<IllegalArgumentException>(url) { requireCuratorTarget(url) } }
    }

    @Test
    fun `hidden TLS parameters remove SNI and automatic endpoint identification`() {
        val parameters =
            SSLParameters().apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName("www.wallpaperengineapi.com"))
            }

        hiddenSniParameters(parameters)

        assertEquals(null, parameters.endpointIdentificationAlgorithm)
        assertTrue(parameters.serverNames.isEmpty())
    }

    @Test
    fun `DoH parser keeps unique public IPv4 addresses only`() {
        val addresses =
            parsePublicIpv4Answers(
                """{"Status":0,"Answer":[
                    {"type":1,"data":"212.95.50.195"},
                    {"type":1,"data":"212.95.50.195"},
                    {"type":1,"data":"198.18.0.1"},
                    {"type":1,"data":"192.168.1.1"},
                    {"type":1,"data":"203.0.113.4"},
                    {"type":28,"data":"2001:db8::1"},
                    {"type":1,"data":"999.1.1.1"}
                ]}""",
            )

        assertEquals(listOf("212.95.50.195"), addresses.map { it.hostAddress })
        assertTrue(parsePublicIpv4Answers("""{"Status":2}""").isEmpty())
        assertTrue(parsePublicIpv4Answers("not-json").isEmpty())
    }

    @Test
    fun `HTTP parser accepts bounded JSON 200 response`() {
        val body = """{"response":{"items":[]}}"""
        val response =
            parseHttpResponse(
                response(
                    "HTTP/1.1 200 OK",
                    listOf(
                        "Content-Type: application/json; charset=utf-8",
                        "Content-Length: ${body.toByteArray().size}",
                        "ETag: \"revision\"",
                        "Last-Modified: Mon, 26 Oct 2026 17:25:10 GMT",
                    ),
                    body,
                ),
            )

        assertEquals(200, response.statusCode)
        assertEquals(body, response.body)
        assertEquals("\"revision\"", response.etag)
        assertEquals("Mon, 26 Oct 2026 17:25:10 GMT", response.lastModified)
    }

    @Test
    fun `HTTP parser accepts bodyless 304 response`() {
        val response =
            parseHttpResponse(
                response(
                    "HTTP/1.1 304 Not Modified",
                    listOf("ETag: \"revision\"", "Content-Length: 0"),
                ),
            )

        assertEquals(304, response.statusCode)
        assertEquals("", response.body)
    }

    @Test
    fun `HTTP parser rejects ambiguous or unbounded responses`() {
        val body = "{}"
        val invalidResponses =
            listOf(
                response(
                    "HTTP/1.1 200 OK",
                    listOf("Content-Type: application/json", "Transfer-Encoding: chunked"),
                    "2\r\n{}\r\n0\r\n\r\n",
                ),
                response(
                    "HTTP/1.1 200 OK",
                    listOf("Content-Type: text/html", "Content-Length: ${body.length}"),
                    body,
                ),
                response(
                    "HTTP/1.1 200 OK",
                    listOf(
                        "Content-Type: application/json",
                        "Content-Length: ${body.length}",
                        "Content-Length: ${body.length}",
                    ),
                    body,
                ),
                response(
                    "HTTP/1.1 200 OK",
                    listOf("Content-Type: application/json", "Content-Length: 524289"),
                ),
                response(
                    "HTTP/1.1 200 OK",
                    listOf("Content-Type: application/json", "Content-Length: 4"),
                    body,
                ),
                response(
                    "HTTP/1.1 304 Not Modified",
                    listOf("Content-Length: 1"),
                    "x",
                ),
                response(
                    "HTTP/1.1 302 Found",
                    listOf("Location: https://example.com", "Content-Length: 0"),
                ),
                response(
                    "HTTP/1.1 200 OK",
                    listOf("Content-Type: application/json", "Content-Encoding: gzip", "Content-Length: 0"),
                ),
                ByteArrayInputStream(
                    "HTTP/1.1 200 OK\nContent-Type: application/json\nContent-Length: 0\n\n"
                        .toByteArray(Charsets.ISO_8859_1),
                ),
                ByteArrayInputStream(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xff.toByte()),
                ),
            )

        invalidResponses.forEach { input -> assertFailsWith<IOException> { parseHttpResponse(input) } }
    }

    private fun response(
        statusLine: String,
        headers: List<String>,
        body: String = "",
    ): ByteArrayInputStream =
        ByteArrayInputStream(
            buildString {
                append(statusLine).append("\r\n")
                headers.forEach { header -> append(header).append("\r\n") }
                append("\r\n")
                append(body)
            }.toByteArray(Charsets.ISO_8859_1),
        )
}
