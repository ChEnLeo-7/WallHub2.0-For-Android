package com.wallhub.android.data.diagnostics

import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `redacts secrets in messages and attributes before persistence`() {
        val sanitized = DiagnosticSanitizer.sanitize(
            DiagnosticEvent(
                source = "steam",
                level = DiagnosticLevel.ERROR,
                message = "token=abc123 Authorization: Bearer private-value sessionid=session-secret",
                attributes = mapOf(
                    "password" to "not-for-disk",
                    "refresh-token" to "also-not-for-disk",
                    "steamLoginSecure" to "account-token",
                    "clientsessionid" to "client-secret",
                    "route" to "cm.example.test",
                ),
            ),
        )

        assertFalse(sanitized.message.contains("abc123"))
        assertFalse(sanitized.message.contains("private-value"))
        assertFalse(sanitized.message.contains("session-secret"))
        assertEquals("[REDACTED]", sanitized.attributes.getValue("password"))
        assertEquals("[REDACTED]", sanitized.attributes.getValue("refresh-token"))
        assertEquals("[REDACTED]", sanitized.attributes.getValue("steamLoginSecure"))
        assertEquals("[REDACTED]", sanitized.attributes.getValue("clientsessionid"))
        assertEquals("cm.example.test", sanitized.attributes.getValue("route"))
    }
}
