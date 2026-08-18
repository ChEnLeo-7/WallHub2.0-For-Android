package com.wallhub.android.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFilterSelectionTest {
    private val allOptions = linkedSetOf("scene", "video", "web")

    @Test
    fun `empty selection presents every default option as checked`() {
        val selection = emptySet<String>()

        allOptions.forEach { option ->
            assertTrue(selection.isFilterOptionSelected(option, allOptions))
        }
    }

    @Test
    fun `toggling a checked option from select all removes only that option`() {
        val updated = emptySet<String>().toggleBounded("video", allOptions)

        assertEquals(setOf("scene", "web"), updated)
        assertFalse(updated.isFilterOptionSelected("video", allOptions))
        assertTrue(updated.isFilterOptionSelected("scene", allOptions))
    }

    @Test
    fun `toggling the final missing option restores the full selection`() {
        val partialSelection = setOf("scene", "web")

        assertEquals(allOptions, partialSelection.toggleBounded("video", allOptions))
    }
}
