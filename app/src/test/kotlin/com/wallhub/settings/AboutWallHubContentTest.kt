package com.wallhub.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AboutWallHubContentTest {
    @Test
    fun `all people use direct immutable GitHub avatar authorities`() {
        val people = WALLHUB_DEVELOPERS + WALLHUB_CONTRIBUTORS

        assertEquals(4, people.size)
        people.forEach { person ->
            assertTrue(person.avatarUrl.startsWith("https://avatars.githubusercontent.com/u/"))
            assertTrue(person.avatarUrl.contains("?v=4&s=160"))
            assertFalse(person.avatarUrl.contains("github.com/${person.githubAccount}"))
        }
    }
}
