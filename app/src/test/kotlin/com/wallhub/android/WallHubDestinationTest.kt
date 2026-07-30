package com.wallhub.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallHubDestinationTest {
    @Test
    fun `author destination normalizes a Steam creator identifier`() {
        assertEquals(
            AuthorSearchDestination(authorSearchCreator = "76561198000000000"),
            "https://steamcommunity.com/profiles/76561198000000000".authorSearchDestinationOrNull(),
        )
    }

    @Test
    fun `author destination rejects a value without an identifier`() {
        assertNull("not-a-creator".authorSearchDestinationOrNull())
    }

    @Test
    fun `navigation arguments retain SavedStateHandle property names`() {
        assertEquals("authorSearchCreator", AuthorSearchDestination.serializer().descriptor.getElementName(0))
        assertEquals("workshopId", WorkshopDetailDestination.serializer().descriptor.getElementName(0))
        assertEquals("taskId", LocalVideoPlayerDestination.serializer().descriptor.getElementName(0))
    }
}
