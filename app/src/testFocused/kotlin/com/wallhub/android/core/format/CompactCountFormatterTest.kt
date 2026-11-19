package com.wallhub.android.core.format

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class CompactCountFormatterTest {
    private val chinese = Locale.SIMPLIFIED_CHINESE
    private val english = Locale.US

    @Test
    fun nullCountRendersPlaceholder() {
        assertEquals("—", formatCompactCount(null, chinese))
    }

    @Test
    fun chineseUsesWanAndStripsTrailingZero() {
        assertEquals("999", formatCompactCount(999, chinese))
        assertEquals("1K", formatCompactCount(1_000, chinese))
        assertEquals("1 万", formatCompactCount(10_000, chinese))
        assertEquals("1.5 万", formatCompactCount(15_000, chinese))
    }

    @Test
    fun englishUsesMillionsAndStripsTrailingZero() {
        assertEquals("999", formatCompactCount(999, english))
        assertEquals("1.5K", formatCompactCount(1_500, english))
        assertEquals("1M", formatCompactCount(1_000_000, english))
        assertEquals("1.5M", formatCompactCount(1_500_000, english))
    }
}
