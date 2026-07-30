package com.wallhub.android.data.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class SteamWorkshopThumbnailCacheTest {
    @Test
    fun `parses valid https preview urls by workshop id`() {
        val previews =
            parsePreviewUrls(
                """
                {
                  "response": {
                    "publishedfiledetails": [
                      {
                        "publishedfileid": "123456",
                        "preview_url": "https://steamuserimages-a.akamaihd.net/example.jpg"
                      },
                      {
                        "publishedfileid": 789012,
                        "preview_url": "http://example.invalid/insecure.jpg"
                      },
                      {
                        "publishedfileid": "invalid",
                        "preview_url": "https://example.invalid/no-id.jpg"
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            mapOf(123456L to "https://steamuserimages-a.akamaihd.net/example.jpg"),
            previews,
        )
    }

    @Test
    fun `returns empty map when Steam details are absent`() {
        assertEquals(emptyMap(), parsePreviewUrls("{\"response\":{}}"))
    }
}
